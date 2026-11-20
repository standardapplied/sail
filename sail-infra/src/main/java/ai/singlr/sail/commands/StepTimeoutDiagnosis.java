/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.PrivateDirectory;
import ai.singlr.sail.engine.CommandTimedOut;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
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

  private final ShellExec probes;
  private final Path log;
  private final Duration bound;

  StepTimeoutDiagnosis(ShellExec probes, Path log, Duration bound) {
    this.probes = probes;
    this.log = log;
    this.bound = bound;
  }

  static StepTimeoutDiagnosis here() {
    return new StepTimeoutDiagnosis(new ShellExecutor(false), LOG, PROBE_BOUND);
  }

  private record Probe(String title, List<String> command) {}

  /** What one probe answered: its result, or why there is none. */
  private record Answer(ShellExec.Result result, String failure) {
    String text() {
      return result == null ? failure + "\n" : result.stdout() + result.stderr();
    }
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
    PROBES.forEach(probe -> answers.put(probe, ask(probe)));
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
      record(timedOut, answers);
      return sentence + "; the step's processes and this box's file locks are in " + log + ".";
    } catch (IOException e) {
      return sentence + "; this diagnosis could not be written to " + log + " (" + reason(e) + ").";
    }
  }

  /** What went wrong: the message sail wrote, or the exception itself when it names only a path. */
  private static String reason(IOException e) {
    return e.getClass() == IOException.class ? e.getMessage() : e.toString();
  }

  private Answer ask(Probe probe) {
    try {
      return new Answer(probes.exec(probe.command(), null, bound), null);
    } catch (TimeoutException e) {
      return new Answer(null, "did not answer within " + bound.toSeconds() + " s");
    } catch (IOException e) {
      return new Answer(null, "could not run: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Answer(null, "interrupted");
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

  private void record(CommandTimedOut timedOut, Map<Probe, Answer> answers) throws IOException {
    var entry = new StringBuilder();
    entry
        .append("== ")
        .append(DateTimeUtils.format(DateTimeUtils.now()))
        .append(' ')
        .append(timedOut.getMessage())
        .append('\n');
    entry.append("-- the step's processes at the timeout\n").append(timedOut.processes());
    answers.forEach(
        (probe, answer) ->
            entry
                .append("-- ")
                .append(probe.title())
                .append(": ")
                .append(String.join(" ", probe.command()))
                .append('\n')
                .append(answer.text()));
    requireOwnDirectory(log.getParent());
    try (var out =
        Files.newByteChannel(
            log,
            Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
      out.write(ByteBuffer.wrap(entry.toString().getBytes(StandardCharsets.UTF_8)));
    }
  }

  /**
   * The log's directory, made owner-only when it is not there, and refused unless it is a
   * directory, not a link, owned by the account writing it (root, for init; on a system without
   * {@code /proc} its owner is not checked): {@code /var/log} is writable by the {@code syslog}
   * group, which could otherwise plant a link that root writes through.
   */
  private static void requireOwnDirectory(Path dir) throws IOException {
    if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectory(
          dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    var attributes =
        Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()) {
      throw new IOException(dir + " is a symbolic link, not a directory");
    }
    if (!attributes.isDirectory()) {
      throw new IOException(dir + " is not a directory");
    }
    var self = PrivateDirectory.ProcessOwner.current();
    var owner = (int) Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    if (self.isPresent() && owner != self.get().uid()) {
      throw new IOException(
          dir + " is owned by " + attributes.owner().getName() + ", not by this account");
    }
  }
}
