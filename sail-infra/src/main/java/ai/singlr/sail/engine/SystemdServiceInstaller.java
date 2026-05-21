/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Installs and manages the {@code sail-api.service} systemd <em>user</em> unit so the API server
 * runs as a persistent daemon on the host.
 *
 * <p>The real unit file lives under sail's own state directory at {@code
 * ~/.sail/services/sail-api.service} so everything sail manages stays in one place. Systemd needs
 * the unit on its search path, so {@link #install()} writes the file under {@code ~/.sail/} and
 * creates a symlink at {@code ~/.config/systemd/user/sail-api.service} pointing at it. Uninstall
 * removes both.
 *
 * <p>All operations run as the current user — never via {@code sudo}. For the service to survive
 * logout the user needs {@code loginctl enable-linger} set; {@link #lingerStatus()} reports current
 * state and {@link #isLingerEnabled()} answers the yes/no question so callers can prompt for {@code
 * sudo loginctl enable-linger $USER} when needed.
 */
public final class SystemdServiceInstaller {

  /** Filename of the generated unit. */
  public static final String UNIT_FILENAME = "sail-api.service";

  /** Systemd unit name as understood by {@code systemctl --user}. */
  public static final String UNIT_NAME = "sail-api.service";

  private final ShellExec shell;
  private final Path serviceFilePath;
  private final Path systemdLinkPath;
  private final Path sailBinary;
  private final String bindAddress;
  private final int bindPort;
  private final String username;

  /**
   * @param shell the shell executor (real or scripted for tests)
   * @param userHome target user's home directory; sail-owned unit lives at {@code
   *     <home>/.sail/services/sail-api.service} with a systemd-discovery symlink at {@code
   *     <home>/.config/systemd/user/sail-api.service}
   * @param sailBinary absolute path to the {@code sail} binary for ExecStart
   * @param bindAddress bind address for {@code sail api --host}
   * @param bindPort bind port
   * @param username target username (used by {@code loginctl} queries)
   */
  public SystemdServiceInstaller(
      ShellExec shell,
      Path userHome,
      Path sailBinary,
      String bindAddress,
      int bindPort,
      String username) {
    this.shell = Objects.requireNonNull(shell, "shell");
    var home = Objects.requireNonNull(userHome, "userHome");
    this.serviceFilePath = home.resolve(".sail/services").resolve(UNIT_FILENAME);
    this.systemdLinkPath = home.resolve(".config/systemd/user").resolve(UNIT_FILENAME);
    this.sailBinary = Objects.requireNonNull(sailBinary, "sailBinary");
    if (bindAddress == null || bindAddress.isBlank()) {
      throw new IllegalArgumentException("bindAddress is required");
    }
    if (bindPort <= 0 || bindPort > 65535) {
      throw new IllegalArgumentException("bindPort must be 1..65535, got " + bindPort);
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username is required");
    }
    this.bindAddress = bindAddress;
    this.bindPort = bindPort;
    this.username = username;
  }

  /** Where the real, sail-owned unit file lives: {@code ~/.sail/services/sail-api.service}. */
  public Path serviceFilePath() {
    return serviceFilePath;
  }

  /**
   * Where the systemd-discovery symlink lives: {@code ~/.config/systemd/user/sail-api.service}.
   * systemd reads from here; the symlink targets {@link #serviceFilePath()}.
   */
  public Path systemdLinkPath() {
    return systemdLinkPath;
  }

  /** Whether both the sail-owned unit file and the systemd-discovery symlink exist on disk. */
  public boolean isInstalled() {
    return Files.exists(serviceFilePath) && Files.exists(systemdLinkPath);
  }

  /**
   * Returns the contents of the unit file that {@link #install()} writes. Pure function — no I/O.
   * Public for tests and {@code sail host service status --show-unit}.
   */
  public String renderUnit() {
    return """
        [Unit]
        Description=Sail API server
        Documentation=https://github.com/singlr-ai/sing
        After=network.target

        [Service]
        Type=simple
        ExecStart=%s api --host %s --port %d
        Restart=on-failure
        RestartSec=2
        LimitNOFILE=4096
        RuntimeDirectory=sail
        RuntimeDirectoryMode=0750

        [Install]
        WantedBy=default.target
        """
        .formatted(sailBinary, bindAddress, bindPort);
  }

  /**
   * Writes the sail-owned unit file under {@code ~/.sail/services/}, creates the systemd-discovery
   * symlink under {@code ~/.config/systemd/user/}, runs {@code daemon-reload}, then {@code enable
   * --now} so the service starts immediately and on every login. Idempotent: an existing symlink is
   * replaced.
   */
  public void install() throws IOException, InterruptedException, TimeoutException {
    Files.createDirectories(serviceFilePath.getParent());
    Files.writeString(serviceFilePath, renderUnit());
    Files.createDirectories(systemdLinkPath.getParent());
    Files.deleteIfExists(systemdLinkPath);
    Files.createSymbolicLink(systemdLinkPath, serviceFilePath);
    requireSuccess(
        shell.exec(List.of("systemctl", "--user", "daemon-reload")),
        "Failed to reload systemd user units");
    requireSuccess(
        shell.exec(List.of("systemctl", "--user", "enable", "--now", UNIT_NAME)),
        "Failed to enable+start " + UNIT_NAME);
  }

  /**
   * Stops and disables the service, removes the discovery symlink and the sail-owned unit file,
   * reloads systemd. Idempotent — missing pieces are not an error.
   */
  public void uninstall() throws IOException, InterruptedException, TimeoutException {
    shell.exec(List.of("systemctl", "--user", "disable", "--now", UNIT_NAME));
    Files.deleteIfExists(systemdLinkPath);
    Files.deleteIfExists(serviceFilePath);
    requireSuccess(
        shell.exec(List.of("systemctl", "--user", "daemon-reload")),
        "Failed to reload systemd user units");
  }

  /** Starts the service. */
  public void start() throws IOException, InterruptedException, TimeoutException {
    requireSuccess(
        shell.exec(List.of("systemctl", "--user", "start", UNIT_NAME)),
        "Failed to start " + UNIT_NAME);
  }

  /** Stops the service. */
  public void stop() throws IOException, InterruptedException, TimeoutException {
    requireSuccess(
        shell.exec(List.of("systemctl", "--user", "stop", UNIT_NAME)),
        "Failed to stop " + UNIT_NAME);
  }

  /** Restarts the service. */
  public void restart() throws IOException, InterruptedException, TimeoutException {
    requireSuccess(
        shell.exec(List.of("systemctl", "--user", "restart", UNIT_NAME)),
        "Failed to restart " + UNIT_NAME);
  }

  /** Parsed systemctl status: ActiveState + MainPID + LoadState. */
  public ServiceStatus status() throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            List.of(
                "systemctl",
                "--user",
                "show",
                UNIT_NAME,
                "--property=ActiveState,SubState,MainPID,LoadState"));
    var fields = parseShowOutput(result.stdout());
    return new ServiceStatus(
        fields.getOrDefault("LoadState", "unknown"),
        fields.getOrDefault("ActiveState", "unknown"),
        fields.getOrDefault("SubState", "unknown"),
        parsePid(fields.get("MainPID")));
  }

  /**
   * Returns the raw output of {@code journalctl --user -u sail-api.service --no-pager -n <lines>}.
   * Used by {@code sail host service logs}.
   */
  public String journal(int lines) throws IOException, InterruptedException, TimeoutException {
    if (lines <= 0) {
      throw new IllegalArgumentException("lines must be positive");
    }
    var result =
        shell.exec(
            List.of(
                "journalctl",
                "--user",
                "-u",
                UNIT_NAME,
                "--no-pager",
                "-n",
                Integer.toString(lines)));
    if (!result.ok()) {
      throw new IOException("Failed to read journal: " + result.stderr());
    }
    return result.stdout();
  }

  /** {@code loginctl show-user <user> --property=Linger} parsed value, or "unknown" on failure. */
  public String lingerStatus() throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(List.of("loginctl", "show-user", username, "--property=Linger", "--value"));
    if (!result.ok()) {
      return "unknown";
    }
    return result.stdout().strip();
  }

  /** True when {@link #lingerStatus()} reports {@code yes}. */
  public boolean isLingerEnabled() throws IOException, InterruptedException, TimeoutException {
    return "yes".equalsIgnoreCase(lingerStatus());
  }

  /**
   * The shell command an operator should run as root to enable linger, so {@link #lingerStatus()}
   * starts returning {@code yes} and the service survives logout.
   */
  public String enableLingerCommand() {
    return "sudo loginctl enable-linger " + username;
  }

  /** Parsed service status. */
  public record ServiceStatus(String loadState, String activeState, String subState, Integer pid) {
    /** True when the service is active and running. */
    public boolean running() {
      return "active".equals(activeState) && "running".equals(subState);
    }
  }

  private static Map<String, String> parseShowOutput(String output) {
    var map = new java.util.LinkedHashMap<String, String>();
    if (output == null) {
      return map;
    }
    for (var line : output.split("\n", -1)) {
      var eq = line.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      map.put(line.substring(0, eq), line.substring(eq + 1).strip());
    }
    return map;
  }

  private static Integer parsePid(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      var pid = Integer.parseInt(raw.strip());
      return pid > 0 ? pid : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static void requireSuccess(ShellExec.Result result, String message) throws IOException {
    if (!result.ok()) {
      throw new IOException(message + ": " + result.stderr());
    }
  }
}
