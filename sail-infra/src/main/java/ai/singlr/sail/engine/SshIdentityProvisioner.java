/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds the ordered, idempotent plan that turns a single-operator host into one that authenticates
 * FDEs by their SSH key: a locked-down {@code sail} system user (whose forced-command keys run the
 * gateway), a group-shared control-plane data directory, the database moved into it, and a systemd
 * drop-in pointing {@code sail-api} at it. The plan is pure data so it can be previewed verbatim
 * ({@code --dry-run} default) and unit-tested; it touches no global sshd config and never alters
 * the operator's existing root SSH — the per-key forced commands live only in the {@code sail}
 * user's own {@code authorized_keys}.
 *
 * <p>Every step is safe to re-run: user creation is guarded by {@code id}, directories use {@code
 * install -d}, the database move skips files already relocated, and {@code authorized_keys} is
 * never truncated if it already exists.
 */
public final class SshIdentityProvisioner {

  public static final String SAIL_USER = "sail";
  public static final String SAIL_HOME = "/home/sail";
  public static final String DEFAULT_DATA_DIR = "/var/lib/sail";
  public static final String DROP_IN =
      "/etc/systemd/system/sail-api.service.d/10-sail-data-dir.conf";

  private static final List<String> DB_FILES = List.of("sail.db", "sail.db-wal", "sail.db-shm");

  private SshIdentityProvisioner() {}

  /** A single provisioning action; either a shell command or a file write. */
  public sealed interface Step permits Run, WriteFile {
    String description();
  }

  public record Run(String description, List<String> command) implements Step {}

  public record WriteFile(String description, Path path, String content) implements Step {}

  /**
   * @param sourceDataDir where the database currently lives (the operator's {@code ~/.sail})
   * @param dataDir the shared system data directory to move it into
   */
  public static List<Step> plan(Path sourceDataDir, String dataDir) {
    var source = sourceDataDir.toString();
    return List.of(
        new Run(
            "Create the locked-down '" + SAIL_USER + "' system user",
            List.of(
                "sh",
                "-c",
                "id -u "
                    + SAIL_USER
                    + " >/dev/null 2>&1 || useradd --system --create-home --home-dir "
                    + SAIL_HOME
                    + " --shell /bin/bash "
                    + SAIL_USER)),
        new Run(
            "Restrict the sail home directory (sshd StrictModes)",
            List.of("chmod", "700", SAIL_HOME)),
        new Run(
            "Create the sail user's .ssh directory",
            List.of(
                "install",
                "-d",
                "-m",
                "700",
                "-o",
                SAIL_USER,
                "-g",
                SAIL_USER,
                SAIL_HOME + "/.ssh")),
        new Run(
            "Create authorized_keys (preserved if it already exists)",
            List.of(
                "sh",
                "-c",
                "test -f "
                    + SAIL_HOME
                    + "/.ssh/authorized_keys || install -m 600 -o "
                    + SAIL_USER
                    + " -g "
                    + SAIL_USER
                    + " /dev/null "
                    + SAIL_HOME
                    + "/.ssh/authorized_keys")),
        new Run(
            "Create the shared control-plane data directory (setgid, group " + SAIL_USER + ")",
            List.of("install", "-d", "-m", "2770", "-o", "root", "-g", SAIL_USER, dataDir)),
        new Run(
            "Stop sail-api before moving the database",
            List.of(
                "sh",
                "-c",
                "systemctl is-active --quiet sail-api && systemctl stop sail-api || true")),
        new Run(
            "Move the control-plane database into " + dataDir,
            List.of(
                "sh",
                "-c",
                "for f in "
                    + String.join(" ", DB_FILES)
                    + "; do [ -e \""
                    + source
                    + "/$f\" ] && [ ! -e \""
                    + dataDir
                    + "/$f\" ] && mv \""
                    + source
                    + "/$f\" \""
                    + dataDir
                    + "/$f\"; done; true")),
        new Run(
            "Set database ownership and permissions for group access",
            List.of(
                "sh",
                "-c",
                "cd "
                    + dataDir
                    + " && for f in "
                    + String.join(" ", DB_FILES)
                    + "; do [ -e \"$f\" ] && chgrp "
                    + SAIL_USER
                    + " \"$f\" && chmod 660 \"$f\"; done; true")),
        new Run(
            "Create the systemd drop-in directory",
            List.of("install", "-d", "-m", "755", Path.of(DROP_IN).getParent().toString())),
        new WriteFile(
            "Point sail-api at the shared data directory",
            Path.of(DROP_IN),
            "[Service]\nEnvironment=SAIL_DATA_DIR=" + dataDir + "\nUMask=0007\n"),
        new Run("Reload systemd units", List.of("systemctl", "daemon-reload")),
        new Run(
            "Start sail-api on the shared data directory",
            List.of("systemctl", "start", "sail-api")));
  }
}
