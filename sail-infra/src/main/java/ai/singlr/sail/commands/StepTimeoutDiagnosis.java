/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.CommandTimedOut;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.engine.TimeoutDiagnosis;
import ai.singlr.sail.engine.TimeoutDiagnosis.Answer;
import ai.singlr.sail.engine.TimeoutDiagnosis.Probe;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What init records when one of its host steps runs out of time: the step's processes as they were
 * at the timeout, and what the box itself was doing a moment later (who holds a file lock, the
 * account database's lock files, {@code systemd-userdbd}, and whether an account lookup answers),
 * appended to the init log, and summed up for the person in one sentence. Each probe has its own
 * bound, so a box that hangs a step cannot hang its diagnosis.
 */
final class StepTimeoutDiagnosis {

  static final Path LOG = Path.of("/var/log/sail/init.log");

  private static final Duration PROBE_BOUND = Duration.ofSeconds(5);
  private static final String USERDBD = "systemd-userdbd";
  private static final Probe LOCKS =
      new Probe(
          "file locks held on this box",
          List.of("lslocks", "--output", "COMMAND,PID,TYPE,MODE,PATH"));
  private static final Probe LOCK_FILES =
      new Probe(
          "the account database's lock files",
          List.of(
              "ls",
              "-l",
              "/etc/.pwd.lock",
              "/etc/passwd.lock",
              "/etc/shadow.lock",
              "/etc/group.lock",
              "/etc/gshadow.lock"));
  private static final Probe USERDBD_STATE =
      new Probe(USERDBD, List.of("systemctl", "is-active", USERDBD));
  private static final Probe LOOKUP =
      new Probe("an account lookup", List.of("getent", "passwd", SshIdentityProvisioner.SAIL_USER));
  private static final List<Probe> PROBES = List.of(LOCKS, LOCK_FILES, USERDBD_STATE, LOOKUP);
  private static final Map<Character, String> STATES =
      Map.of(
          'R', "running",
          'S', "sleeping",
          'D', "blocked uninterruptibly",
          'T', "stopped",
          't', "stopped by a debugger",
          'Z', "exited but not yet reaped");

  private final TimeoutDiagnosis probes;
  private final Path log;

  StepTimeoutDiagnosis(ShellExec probes, Path log, Duration bound) {
    this.probes = new TimeoutDiagnosis(probes, bound);
    this.log = log;
  }

  static StepTimeoutDiagnosis here() {
    return new StepTimeoutDiagnosis(new ShellExecutor(false), LOG, PROBE_BOUND);
  }

  /**
   * The sentence that sums up the timeout among {@code failure}'s causes, once its diagnosis is in
   * the init log; empty when {@code failure} is not a timeout.
   */
  Optional<String> of(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CommandTimedOut timedOut) {
        return Optional.of(diagnose(timedOut));
      }
    }
    return Optional.empty();
  }

  private String diagnose(CommandTimedOut timedOut) {
    var answers = new LinkedHashMap<Probe, Answer>();
    PROBES.forEach(probe -> answers.put(probe, probes.ask(probe)));
    var sentence =
        "At the timeout "
            + stepState(timedOut.processes())
            + "; just after it, "
            + String.join(" ", LOOKUP.command())
            + " "
            + lookup(answers.get(LOOKUP))
            + " and "
            + userdbd(answers.get(USERDBD_STATE));
    try {
      TimeoutDiagnosis.record(log, timedOut, answers);
      return sentence + "; the step's processes and this box's file locks are in " + log + ".";
    } catch (IOException e) {
      return sentence
          + "; this diagnosis could not be written to "
          + log
          + " ("
          + TimeoutDiagnosis.reason(e)
          + ").";
    }
  }

  /**
   * The deepest of the step's processes, what it was doing and the kernel function it waited in; or
   * that {@code ps} listed none of them (its header alone), or why there is no listing.
   */
  private static String stepState(String processes) {
    var header = processes.lines().findFirst().orElse("");
    if (!header.contains("PID")) {
      return "the step's processes could not be listed"
          + (header.isBlank() ? "" : " (" + header.strip() + ")");
    }
    var rows =
        processes
            .lines()
            .skip(1)
            .map(line -> line.strip().split("\\s+", 6))
            .filter(row -> row.length == 6 && row[0].chars().allMatch(Character::isDigit))
            .toList();
    var parents = rows.stream().map(row -> row[1]).collect(Collectors.toSet());
    return rows.stream()
        .filter(row -> !parents.contains(row[0]))
        .reduce((first, second) -> second)
        .map(
            leaf ->
                "the step was still running "
                    + Path.of(leaf[5].split(" ", 2)[0]).getFileName()
                    + " (pid "
                    + leaf[0]
                    + ", "
                    + STATES.getOrDefault(leaf[2].charAt(0), "in state " + leaf[2])
                    + ("-".equals(leaf[3]) ? "" : " in " + leaf[3])
                    + ")")
        .orElse("the step's processes had all ended before ps could list them");
  }

  private static String lookup(Answer answer) {
    if (answer.result() == null) {
      return answer.failure();
    }
    return answer.result().ok() || answer.result().exitCode() == 2
        ? "answered"
        : "failed (" + answer.result().reason() + ")";
  }

  private static String userdbd(Answer answer) {
    if (answer.result() == null) {
      return "systemctl " + answer.failure() + " about " + USERDBD;
    }
    var state = answer.result().stdout().strip();
    return USERDBD + " was " + (state.isEmpty() ? "of unknown state" : state);
  }
}
