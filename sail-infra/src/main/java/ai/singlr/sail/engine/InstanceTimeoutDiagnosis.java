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

/**
 * Why an {@code incus start} or {@code incus stop} of an instance ran out of time, read the moment
 * after ({@link TimeoutDiagnosis}): Incus's own log of the instance, cut to its last {@link
 * #LOG_LINES} lines, and, if the instance still answers a command, systemd's state in it and the
 * jobs it has queued. All of it goes to an owner-only log, {@code /var/log/sail/containers.log} for
 * root (the API's service, and the CLI under sudo) and {@code ~/.sail/logs/containers.log} for
 * anyone else, and one sentence of it to the error the person reads. Nothing it reads is given a
 * token or holds one: the commands are Incus's and systemd's own.
 */
final class InstanceTimeoutDiagnosis {

  /** How many of the last lines of Incus's log of the instance are kept. */
  static final int LOG_LINES = 60;

  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final String NAME = "containers.log";

  private final TimeoutDiagnosis probes;
  private final Path log;

  InstanceTimeoutDiagnosis(ShellExec shell, Path log, Duration bound) {
    this.probes = new TimeoutDiagnosis(shell, bound);
    this.log = log;
  }

  /** Diagnoses timeouts by asking {@code shell}, into the log of the account Sail runs as. */
  static InstanceTimeoutDiagnosis of(ShellExec shell) {
    var root = PrivateDirectory.ProcessOwner.current().map(owner -> owner.uid() == 0).orElse(false);
    return new InstanceTimeoutDiagnosis(
        shell,
        root ? Path.of("/var/log/sail", NAME) : SailPaths.sailDir().resolve("logs").resolve(NAME),
        BOUND);
  }

  /**
   * {@code timedOut}, an {@code incus start} or {@code incus stop} of {@code instance}, again with
   * the sentence that says why after its own, once the whole of it is in the log.
   */
  CommandTimedOut of(String instance, CommandTimedOut timedOut) {
    var answers = new LinkedHashMap<Probe, Answer>();
    var logProbe =
        new Probe("Incus's log of " + instance, List.of("incus", "info", "--show-log", instance));
    var incusLog = tail(probes.ask(logProbe));
    answers.put(logProbe, incusLog);
    var stateProbe =
        new Probe(
            "systemd's state in " + instance,
            List.of("incus", "exec", instance, "--", "systemctl", "is-system-running"));
    var state = probes.ask(stateProbe);
    answers.put(stateProbe, state);
    var jobs = (Answer) null;
    if (state.said()) {
      var jobsProbe =
          new Probe(
              "systemd's jobs in " + instance,
              List.of("incus", "exec", instance, "--", "systemctl", "list-jobs", "--no-pager"));
      jobs = probes.ask(jobsProbe);
      answers.put(jobsProbe, jobs);
    }
    var sentence =
        timedOut.getMessage()
            + ". Just after, "
            + systemd(instance, state, jobs)
            + "; "
            + lastLine(instance, incusLog);
    try {
      TimeoutDiagnosis.record(log, timedOut, answers);
      sentence += "; all of it is in " + log + ".";
    } catch (IOException e) {
      sentence +=
          "; this diagnosis could not be written to "
              + log
              + " ("
              + TimeoutDiagnosis.reason(e)
              + ").";
    }
    var said = new CommandTimedOut(sentence, timedOut.processes());
    said.initCause(timedOut);
    return said;
  }

  /** What systemd in the instance said of itself and its jobs, or that it no longer answered. */
  private static String systemd(String instance, Answer state, Answer jobs) {
    if (!state.said()) {
      return instance
          + " no longer answered a command ("
          + (state.result() == null ? state.failure() : state.result().reason())
          + ")";
    }
    var said = "systemd in " + instance + " said it is " + state.result().stdout().strip();
    if (jobs == null || !jobs.said()) {
      return said + ", and its jobs could not be read";
    }
    var queued = jobs.result().stdout().lines().skip(1).takeWhile(line -> !line.isBlank()).toList();
    if (queued.isEmpty()) {
      return said + ", with no job queued";
    }
    return said
        + ", with "
        + queued.size()
        + (queued.size() == 1 ? " job" : " jobs")
        + " queued, the first "
        + queued.getFirst().strip().replaceAll("\\s+", " ");
  }

  /** How Incus's log of the instance ends, or that it kept none. */
  private static String lastLine(String instance, Answer incusLog) {
    if (incusLog.result() == null || !incusLog.result().ok()) {
      return "Incus's log of "
          + instance
          + " could not be read ("
          + (incusLog.result() == null ? incusLog.failure() : incusLog.result().reason())
          + ")";
    }
    var lines = logLines(incusLog.result().stdout());
    return lines.isEmpty()
        ? "Incus kept no log of " + instance
        : "Incus's log of " + instance + " ends: " + lines.getLast().strip();
  }

  /** The lines under {@code Log:} in {@code incus info --show-log}, blank ones left out. */
  private static List<String> logLines(String info) {
    var all = info.lines().toList();
    var at = all.indexOf("Log:");
    return all.subList(at < 0 ? 0 : at + 1, all.size()).stream()
        .filter(line -> !line.isBlank())
        .toList();
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
