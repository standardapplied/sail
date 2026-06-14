/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SpecAuditEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Append-only audit log for a single spec, persisted at {@code <specsDir>/<id>/audit.jsonl} inside
 * the project container.
 *
 * <p>Writes use {@code printf '%s\n' "$1" >> "$2"} so each line is appended atomically — Linux
 * guarantees atomicity for writes smaller than {@code PIPE_BUF} (4096 bytes) when the file is
 * opened with {@code O_APPEND}, which {@code >>} does. Audit lines are tiny ({@literal ~}200
 * bytes), so concurrent appenders (sail orchestrator + agent hooks + watcher) never interleave.
 *
 * <p>Reads tolerate corruption: malformed JSON lines and unrecognized payloads are skipped rather
 * than aborting, so a single bad line doesn't make the rest unreadable.
 */
public final class SpecAudit {

  /** Filename for the per-spec audit log inside its spec directory. */
  public static final String AUDIT_FILENAME = "audit.jsonl";

  private final ShellExec shell;
  private final String containerName;
  private final String specsDir;

  public SpecAudit(ShellExec shell, String containerName, String specsDir) {
    this.shell = Objects.requireNonNull(shell, "shell");
    NameValidator.requireValidProjectName(containerName);
    if (Strings.isBlank(specsDir)) {
      throw new IllegalArgumentException("specsDir is required.");
    }
    this.containerName = containerName;
    this.specsDir = specsDir;
  }

  /** Returns the absolute path to a spec's audit log inside the container. */
  public String auditPath(String specId) {
    NameValidator.requireValidSpecId(specId);
    return specsDir + "/" + specId + "/" + AUDIT_FILENAME;
  }

  /** Appends one event to the spec's audit log. Creates the file on first write. */
  public void append(String specId, SpecAuditEvent event)
      throws IOException, InterruptedException, TimeoutException {
    Objects.requireNonNull(event, "event");
    var path = auditPath(specId);
    var line = event.toJsonLine();
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of("bash", "-c", "printf '%s\\n' \"$1\" >> \"$2\"", "bash", line, path));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException(
          "Failed to append audit event for spec '" + specId + "': " + result.stderr());
    }
  }

  /**
   * Reads all events in the spec's audit log, in file order (oldest first). Returns an empty list
   * when no audit file exists yet. Malformed lines are silently skipped.
   */
  public List<SpecAuditEvent> read(String specId)
      throws IOException, InterruptedException, TimeoutException {
    var path = auditPath(specId);
    var result = shell.exec(ContainerExec.asDevUser(containerName, List.of("cat", path)));
    if (!result.ok()) {
      if (isMissingFile(result)) {
        return List.of();
      }
      throw new IOException(
          "Failed to read audit log for spec '" + specId + "': " + result.stderr());
    }
    var events = new ArrayList<SpecAuditEvent>();
    for (var line : result.stdout().split("\n", -1)) {
      if (line.isBlank()) {
        continue;
      }
      try {
        events.add(SpecAuditEvent.fromJsonLine(line));
      } catch (Exception ignored) {
        // skip corrupted lines so one bad line doesn't poison the rest of the log
      }
    }
    return List.copyOf(events);
  }

  private static boolean isMissingFile(ShellExec.Result result) {
    return result.stderr() != null && result.stderr().contains("No such file");
  }
}
