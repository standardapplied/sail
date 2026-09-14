/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Tells a stop from a restart at {@code SIGTERM} time: systemd sends the same signal for both, but
 * the job it is running on the unit says which — {@code systemctl list-jobs} names it {@code stop}
 * or {@code restart}. The unit is read from this process's cgroup, which also says whether it is a
 * user or a system service. Anything unanswerable reads as a restart: handing sessions to the store
 * is the safe default, and a stop clears the store anyway. {@code systemctl} runs without the
 * host's {@code NOTIFY_SOCKET}, so its own notifications are not mistaken for the host's.
 */
final class SystemdStop {

  private static final Path CGROUP = Path.of("/proc/self/cgroup");

  private SystemdStop() {}

  static boolean stoppingForGood(ShellExec shell) {
    try {
      return stoppingForGood(shell, Files.readString(CGROUP));
    } catch (IOException unreadable) {
      return false;
    }
  }

  static boolean stoppingForGood(ShellExec shell, String cgroup) {
    var unit = unitOf(cgroup);
    if (unit == null) {
      return false;
    }
    var command =
        cgroup.contains("/user@")
            ? List.of(
                "env",
                "-u",
                "NOTIFY_SOCKET",
                "systemctl",
                "--user",
                "list-jobs",
                "--no-legend",
                "--plain")
            : List.of(
                "env", "-u", "NOTIFY_SOCKET", "systemctl", "list-jobs", "--no-legend", "--plain");
    try {
      var result = shell.exec(command);
      return result.ok() && "stop".equals(jobTypeOn(unit, result.stdout()));
    } catch (Exception unanswered) {
      return false;
    }
  }

  /** The {@code .service} this process runs in, per its unified-hierarchy cgroup line. */
  static String unitOf(String cgroup) {
    for (var line : cgroup.split("\n")) {
      var path = line.substring(line.lastIndexOf(':') + 1).strip();
      var leaf = path.substring(path.lastIndexOf('/') + 1);
      if (leaf.endsWith(".service")) {
        return leaf;
      }
    }
    return null;
  }

  /** The type of the job {@code list-jobs} shows on {@code unit}, or {@code null} for none. */
  static String jobTypeOn(String unit, String listing) {
    for (var line : Objects.toString(listing, "").split("\n")) {
      var fields = line.strip().split("\\s+");
      if (fields.length >= 3 && fields[1].equals(unit)) {
        return fields[2];
      }
    }
    return null;
  }
}
