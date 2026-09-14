/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.pty.PtySessionHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Installs {@code sail-pty-host.service}, the host-side pty session host daemon. Same two-mode
 * discipline as the API unit — user-level unit plus discovery symlink, or system-level under {@code
 * /etc/systemd/system} — with none of the API's bind surface: the host listens only on its unix
 * socket. Deliberately its own small installer; unifying the two service installers is a follow-up
 * refactor, not this brick's job.
 *
 * <p>The unit is what lets sessions outlive the host: {@code NotifyAccess=main} lets the host push
 * every pty master into systemd's file descriptor store, {@code FileDescriptorStoreMax} sizes the
 * store to the host's session cap, and {@code KillMode=process} makes a stop or restart signal only
 * the host — the children stay in the cgroup until their master closes. {@code Restart=on-failure}
 * brings a crashed host back with the store intact, so a crash preserves sessions too; a host that
 * exits 143 after handling {@code SIGTERM} (the JVM's way of saying so) is a clean stop, not a
 * failure.
 */
public final class PtyHostUnit {

  public static final String UNIT_NAME = "sail-pty-host.service";

  private final ShellExec shell;
  private final SystemdServiceInstaller.Mode mode;
  private final Path serviceFilePath;
  private final Path systemdLinkPath;
  private final Path sailBinary;

  public PtyHostUnit(
      ShellExec shell, SystemdServiceInstaller.Mode mode, Path userHome, Path sailBinary) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.mode = Objects.requireNonNull(mode, "mode");
    var home = Objects.requireNonNull(userHome, "userHome");
    if (mode == SystemdServiceInstaller.Mode.SYSTEM) {
      this.serviceFilePath = Path.of("/etc/systemd/system").resolve(UNIT_NAME);
      this.systemdLinkPath = null;
    } else {
      this.serviceFilePath = home.resolve(".sail/services").resolve(UNIT_NAME);
      this.systemdLinkPath = home.resolve(".config/systemd/user").resolve(UNIT_NAME);
    }
    this.sailBinary = Objects.requireNonNull(sailBinary, "sailBinary");
  }

  public Path serviceFilePath() {
    return serviceFilePath;
  }

  /** The unit file contents — pure function, public for tests and status display. */
  public String renderUnit() {
    var userClause = mode == SystemdServiceInstaller.Mode.SYSTEM ? "User=root" + "\n" : "";
    var wantedBy =
        mode == SystemdServiceInstaller.Mode.SYSTEM ? "multi-user.target" : "default.target";
    return """
        [Unit]
        Description=Sail pty session host
        Documentation=https://github.com/standardapplied/sail

        [Service]
        Type=simple
        %sExecStart=%s _pty-host
        Restart=on-failure
        RestartSec=2
        SuccessExitStatus=143
        NotifyAccess=main
        FileDescriptorStoreMax=%d
        KillMode=process
        TimeoutStopSec=15
        LimitNOFILE=4096

        [Install]
        WantedBy=%s
        """
        .formatted(userClause, sailBinary, PtySessionHost.Limits.DEFAULTS.sessions(), wantedBy);
  }

  /** Whether the unit file at {@code path} carries the descriptor store — an older one does not. */
  public static boolean predatesLiveHandoff(Path path) {
    try {
      return Files.isRegularFile(path)
          && !Files.readString(path).contains("FileDescriptorStoreMax=");
    } catch (IOException unreadable) {
      return false;
    }
  }

  /**
   * Writes the unit (and USER-mode symlink), reloads systemd, enables the service, and {@code
   * restart}s it. Restart, not {@code enable --now}: an upgrade rewrites the binary on disk but the
   * running host keeps executing the old process, so it must be restarted to pick up the new code —
   * {@code restart} also starts a stopped service, so it is correct on a first install too. The
   * restart is the live path: the running host hands its sessions to the store and the new binary
   * adopts them.
   */
  public void install() throws IOException, InterruptedException, TimeoutException {
    Files.createDirectories(serviceFilePath.getParent());
    Files.writeString(serviceFilePath, renderUnit());
    if (systemdLinkPath != null) {
      Files.createDirectories(systemdLinkPath.getParent());
      Files.deleteIfExists(systemdLinkPath);
      Files.createSymbolicLink(systemdLinkPath, serviceFilePath);
    }
    requireSuccess(shell.exec(systemctl("daemon-reload")), "Failed to reload systemd units");
    requireSuccess(shell.exec(systemctl("enable", UNIT_NAME)), "Failed to enable " + UNIT_NAME);
    requireSuccess(shell.exec(systemctl("restart", UNIT_NAME)), "Failed to (re)start " + UNIT_NAME);
  }

  /** Stops, disables, and removes the unit; missing pieces are not an error. */
  public void uninstall() throws IOException, InterruptedException, TimeoutException {
    shell.exec(systemctl("disable", "--now", UNIT_NAME));
    if (systemdLinkPath != null) {
      Files.deleteIfExists(systemdLinkPath);
    }
    Files.deleteIfExists(serviceFilePath);
    requireSuccess(shell.exec(systemctl("daemon-reload")), "Failed to reload systemd units");
  }

  private List<String> systemctl(String... args) {
    var command = new ArrayList<String>();
    command.add("systemctl");
    if (mode == SystemdServiceInstaller.Mode.USER) {
      command.add("--user");
    }
    command.addAll(List.of(args));
    return command;
  }

  private static void requireSuccess(ShellExec.Result result, String message) throws IOException {
    if (!result.ok()) {
      throw new IOException(message + ": " + result.stderr());
    }
  }
}
