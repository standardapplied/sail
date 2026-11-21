/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.PrivateDirectory;
import ai.singlr.sail.engine.TimeoutDiagnosis.Answer;
import ai.singlr.sail.engine.TimeoutDiagnosis.Probe;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Why an {@code incus start} or {@code incus stop} of an instance ran out of time, read the moment
 * after ({@link TimeoutDiagnosis}), all of it within one {@link #BUDGET}, since it runs under the
 * workspace's lock: Incus's own log of the instance, cut to its last {@link #LOG_LINES} lines,
 * asked beside, and, if the instance still answers a command, systemd's state in it and the jobs it
 * has queued. All of it goes to an owner-only log, {@code /var/log/sail/containers.log} for root
 * (the API's service, and the CLI under sudo) and {@code ~/.sail/logs/containers.log} for anyone
 * else. The error the person reads carries only systemd's state, how many jobs were queued and the
 * log's path: that sentence can reach a node's other members (a workspace's word of why the pass
 * did not reach it), so Incus's lines and the units' names stay in the file. Nothing it reads is
 * given a token or holds one.
 */
final class InstanceTimeoutDiagnosis {

  /** How many of the last lines of Incus's log of the instance are kept. */
  static final int LOG_LINES = 60;

  /** How long the whole diagnosis may take. */
  static final Duration BUDGET = Duration.ofSeconds(10);

  private static final String NAME = "containers.log";

  /**
   * The states {@code systemctl is-system-running} names: the instance's own command answers, and
   * whoever runs in it can replace it, so nothing else it says is repeated.
   */
  private static final Set<String> STATES =
      Set.of(
          "initializing",
          "starting",
          "running",
          "degraded",
          "maintenance",
          "stopping",
          "offline",
          "unknown");

  private final TimeoutDiagnosis probes;
  private final Supplier<Path> log;
  private final Duration budget;
  private final LongSupplier nanos;

  InstanceTimeoutDiagnosis(ShellExec shell, Path log, Duration budget) {
    this(shell, log, budget, System::nanoTime);
  }

  InstanceTimeoutDiagnosis(ShellExec shell, Path log, Duration budget, LongSupplier nanos) {
    this(shell, () -> log, budget, nanos);
  }

  private InstanceTimeoutDiagnosis(
      ShellExec shell, Supplier<Path> log, Duration budget, LongSupplier nanos) {
    this.probes = new TimeoutDiagnosis(shell, budget);
    this.log = log;
    this.budget = budget;
    this.nanos = nanos;
  }

  /**
   * Diagnoses timeouts by asking {@code shell}, into the log of the account Sail runs as, found
   * only when a timeout happens.
   */
  static InstanceTimeoutDiagnosis of(ShellExec shell) {
    return new InstanceTimeoutDiagnosis(
        shell, InstanceTimeoutDiagnosis::logHere, BUDGET, System::nanoTime);
  }

  private static Path logHere() {
    var root = PrivateDirectory.ProcessOwner.current().map(owner -> owner.uid() == 0).orElse(false);
    return root
        ? Path.of("/var/log/sail", NAME)
        : SailPaths.sailDir().resolve("logs").resolve(NAME);
  }

  /**
   * {@code timedOut}, an {@code incus start} or {@code incus stop} of {@code instance}, again with
   * the sentence that says why after its own, once the whole of it is in the log.
   */
  CommandTimedOut of(String instance, CommandTimedOut timedOut) throws InterruptedException {
    var deadline = nanos.getAsLong() + budget.toNanos();
    var logProbe =
        new Probe("Incus's log of " + instance, List.of("incus", "info", "--show-log", instance));
    var incusLog = new AtomicReference<Answer>();
    var beside =
        Thread.ofVirtual().start(() -> incusLog.set(tail(probes.ask(logProbe, left(deadline)))));
    var stateProbe =
        new Probe(
            "systemd's state in " + instance,
            List.of("incus", "exec", instance, "--", "systemctl", "is-system-running"));
    var state = probes.ask(stateProbe, left(deadline));
    var jobsProbe =
        new Probe(
            "systemd's jobs in " + instance,
            List.of("incus", "exec", instance, "--", "systemctl", "list-jobs", "--no-pager"));
    var jobs = state.said() ? probes.ask(jobsProbe, left(deadline)) : null;
    if (!beside.join(left(deadline))) {
      beside.interrupt();
    }
    var answers = new LinkedHashMap<Probe, Answer>();
    answers.put(
        logProbe,
        Objects.requireNonNullElse(
            incusLog.get(),
            new Answer(
                null, "did not answer within the diagnosis's " + budget.toSeconds() + " s")));
    answers.put(stateProbe, state);
    if (jobs != null) {
      answers.put(jobsProbe, jobs);
    }
    var at = log.get();
    var sentence = timedOut.getMessage() + ". Just after, " + systemd(instance, state, jobs);
    try {
      TimeoutDiagnosis.record(at, timedOut, answers);
      sentence += "; Incus's log of " + instance + " and the rest are in " + at + ".";
    } catch (IOException e) {
      sentence +=
          "; this diagnosis could not be written to "
              + at
              + " ("
              + TimeoutDiagnosis.reason(e)
              + ").";
    }
    var said = new CommandTimedOut(sentence, timedOut.processes());
    said.initCause(timedOut);
    return said;
  }

  /** What is left of the diagnosis's budget at {@code deadline}, never less than nothing. */
  private Duration left(long deadline) {
    return Duration.ofNanos(Math.max(0, deadline - nanos.getAsLong()));
  }

  /**
   * What systemd in the instance said of itself and how many jobs, or that it no longer answered.
   */
  private static String systemd(String instance, Answer state, Answer jobs) {
    if (!state.said()) {
      return instance + " no longer answered a command";
    }
    var stated = state.result().stdout().strip();
    var said =
        "systemd in "
            + instance
            + (STATES.contains(stated) ? " said it is " + stated : " gave an unknown state");
    if (jobs == null || !jobs.said()) {
      return said + ", and its jobs could not be read";
    }
    var queued = jobs.result().stdout().lines().skip(1).takeWhile(line -> !line.isBlank()).count();
    return said
        + (queued == 0
            ? ", with no job queued"
            : ", with " + queued + (queued == 1 ? " job" : " jobs") + " queued");
  }

  /** {@code answer} with Incus's log cut to its last {@link #LOG_LINES} lines. */
  private static Answer tail(Answer answer) {
    if (answer.result() == null) {
      return answer;
    }
    var lines = answer.result().stdout().lines().toList();
    var kept = lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size());
    return new Answer(
        new ShellExec.Result(
            answer.result().exitCode(),
            String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"),
            answer.result().stderr()),
        null);
  }
}
