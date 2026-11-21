/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.PrivateDirectory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * The one way Sail says why a command it ran ran out of time: it asks the box a few questions just
 * after, each with its own bound so a box that hung the command cannot hang its diagnosis, and
 * appends the command, its processes and every answer to an owner-only log, which only a directory
 * this account owns and no one else may write is trusted to hold. Its callers choose the questions
 * and sum the answers up in one sentence for the person.
 */
public final class TimeoutDiagnosis {

  /** One question asked of the box, and the heading its answer is recorded under. */
  public record Probe(String title, List<String> command) {}

  /** What one probe answered: its result, or why there is none. */
  public record Answer(ShellExec.Result result, String failure) {

    /** Its words as the log records them. */
    public String text() {
      return result == null ? failure + "\n" : result.stdout() + result.stderr();
    }

    /** Whether it printed anything on its standard output. */
    public boolean said() {
      return result != null && !result.stdout().isBlank();
    }
  }

  private final ShellExec shell;
  private final Duration bound;

  public TimeoutDiagnosis(ShellExec shell, Duration bound) {
    this.shell = shell;
    this.bound = bound;
  }

  /** {@code probe}'s answer, within its bound. */
  public Answer ask(Probe probe) {
    try {
      return new Answer(shell.exec(probe.command(), null, bound), null);
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
   * Appends to {@code log} the command that timed out, as {@code timedOut} says it, its processes
   * when they were listed, and each of {@code answers} under its probe's heading.
   */
  public static void record(Path log, CommandTimedOut timedOut, Map<Probe, Answer> answers)
      throws IOException {
    var entry = new StringBuilder();
    entry
        .append("== ")
        .append(DateTimeUtils.format(DateTimeUtils.now()))
        .append(' ')
        .append(timedOut.getMessage())
        .append('\n');
    if (!timedOut.processes().isEmpty()) {
      entry.append("-- the step's processes at the timeout\n").append(timedOut.processes());
    }
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
      Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
      out.write(ByteBuffer.wrap(entry.toString().getBytes(StandardCharsets.UTF_8)));
    }
  }

  /**
   * What went wrong writing a log: the message Sail wrote, or the exception when it names a path.
   */
  public static String reason(IOException e) {
    return e.getClass() == IOException.class ? e.getMessage() : e.toString();
  }

  /**
   * The log's directory, made owner-only when it is not there, and refused unless it is a
   * directory, not a link, owned by the account writing it (on a system without {@code /proc} its
   * owner is not checked), that neither its group nor anyone else may write: {@code /var/log} is
   * writable by the {@code syslog} group, which could otherwise plant a link that root writes
   * through, and a directory others may write lets them swap the log between its check and its
   * open.
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
    var mode = attributes.permissions();
    if (mode.contains(PosixFilePermission.GROUP_WRITE)
        || mode.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException(dir + " may be written by others than its owner");
    }
  }
}
