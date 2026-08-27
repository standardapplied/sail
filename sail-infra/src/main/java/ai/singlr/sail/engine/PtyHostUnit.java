/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

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
        LimitNOFILE=4096

        [Install]
        WantedBy=%s
        """
        .formatted(userClause, sailBinary, wantedBy);
  }

  /**
   * Writes the unit (and USER-mode symlink), reloads systemd, enables the service, and {@code
   * restart}s it. Restart, not {@code enable --now}: an upgrade rewrites the binary on disk but the
   * running host keeps executing the old process, so it must be restarted to pick up the new code —
   * {@code restart} also starts a stopped service, so it is correct on a first install too. (Live
   * sessions do not survive a host restart regardless — there is no rehydration — so this loses
   * nothing an upgrade wasn't already going to lose.)
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
