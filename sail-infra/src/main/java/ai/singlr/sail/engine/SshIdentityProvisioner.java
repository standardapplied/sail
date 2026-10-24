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
 * drop-in pointing {@code sail-api} and the pty session host at it, each restarted onto it: a
 * service left on the directory it resolved before the move opens an empty database. The plan is
 * pure data so it can be previewed verbatim ({@code --dry-run} default) and unit-tested; it touches
 * no global sshd config and never alters the operator's existing root SSH — the per-key forced
 * commands live only in the {@code sail} user's own {@code authorized_keys}.
 *
 * <p>Every step is safe to re-run: user creation is guarded by {@code id}, directories use {@code
 * install -d}, the database move skips files already relocated, and {@code authorized_keys} is
 * never truncated if it already exists.
 */
public final class SshIdentityProvisioner {

  public static final String SAIL_USER = "sail";
  public static final String SAIL_HOME = "/home/sail";
  public static final String DEFAULT_DATA_DIR = "/var/lib/sail";
  public static final String DROP_IN = dropIn("sail-api");
  public static final String PTY_HOST_DROP_IN = dropIn("sail-pty-host");

  private static final List<String> DB_FILES = List.of("sail.db", "sail.db-wal", "sail.db-shm");

  private SshIdentityProvisioner() {}

  /** A single provisioning action; either a shell command or a file write. */
  public sealed interface Step permits Run, WriteFile {
    String description();
  }

  public record Run(String description, List<String> command) implements Step {}

  public record WriteFile(HostDropIn file) implements Step {
    @Override
    public String description() {
      return file.name();
    }
  }

  private static String dropIn(String service) {
    return "/etc/systemd/system/" + service + ".service.d/10-sail-data-dir.conf";
  }

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
            "Move host.yaml into " + dataDir + " (readable by gateway commands)",
            List.of(
                "sh",
                "-c",
                "[ -e \""
                    + source
                    + "/host.yaml\" ] && [ ! -e \""
                    + dataDir
                    + "/host.yaml\" ] && mv \""
                    + source
                    + "/host.yaml\" \""
                    + dataDir
                    + "/host.yaml\"; true")),
        new Run(
            "Set host.yaml group access",
            List.of(
                "sh",
                "-c",
                "[ -e \""
                    + dataDir
                    + "/host.yaml\" ] && chgrp "
                    + SAIL_USER
                    + " \""
                    + dataDir
                    + "/host.yaml\" && chmod 640 \""
                    + dataDir
                    + "/host.yaml\"; true")),
        pointAt("sail-api", DROP_IN, dataDir),
        pointAt("the pty session host", PTY_HOST_DROP_IN, dataDir),
        new Run("Reload systemd units", List.of("systemctl", "daemon-reload")),
        new Run(
            "Start sail-api on the shared data directory",
            List.of("systemctl", "start", "sail-api")),
        new Run(
            "Restart the pty session host on the shared data directory",
            List.of("systemctl", "restart", PtyHostUnit.UNIT_NAME)));
  }

  /**
   * The pty session host's drop-in holding {@code content}, the bytes sail-api's drop-in holds, so
   * a box whose sail-api was pointed at a data directory before the pty host followed it there is
   * converged by {@code migrate}.
   */
  public static HostDropIn ptyHostDataDir(String content) {
    return dataDirDropIn("the pty session host", PTY_HOST_DROP_IN, content);
  }

  private static WriteFile pointAt(String service, String path, String dataDir) {
    return new WriteFile(
        dataDirDropIn(
            service, path, "[Service]\nEnvironment=SAIL_DATA_DIR=" + dataDir + "\nUMask=0007\n"));
  }

  private static HostDropIn dataDirDropIn(String service, String path, String content) {
    return new HostDropIn("Point " + service + " at the shared data directory", path, content, "");
  }
}
