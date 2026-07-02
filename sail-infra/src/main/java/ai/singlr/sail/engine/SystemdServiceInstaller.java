/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Installs and manages the {@code sail-api.service} systemd unit so the API server runs as a
 * persistent daemon on the host. Two modes:
 *
 * <ul>
 *   <li>{@link Mode#USER} — the engineer runs sail as a non-root user. Unit goes to {@code
 *       ~/.sail/services/sail-api.service} (sail-owned, single state directory) with a discovery
 *       symlink at {@code ~/.config/systemd/user/sail-api.service}. All systemctl invocations use
 *       {@code --user}; the service needs {@code loginctl enable-linger} to survive logout.
 *   <li>{@link Mode#SYSTEM} — the engineer runs sail as root (typical for single-operator bare
 *       metal). Unit goes to {@code /etc/systemd/system/sail-api.service} with {@code User=root}
 *       and {@code WantedBy=multi-user.target}. No symlink, no linger; systemd starts the unit at
 *       boot.
 * </ul>
 *
 * <p>The mode is picked once at construction. {@link HostServiceInstallers#create} selects {@code
 * SYSTEM} when the invoking process is root and {@code USER} otherwise, so every {@code sail host
 * service ...} command lands in the right place automatically.
 */
public final class SystemdServiceInstaller {

  /** Filename of the generated unit. */
  public static final String UNIT_FILENAME = "sail-api.service";

  /** Systemd unit name as understood by {@code systemctl}. */
  public static final String UNIT_NAME = "sail-api.service";

  /** Install mode — user-level vs system-level systemd. */
  public enum Mode {
    /** Per-user systemd ({@code systemctl --user}); unit symlinked into the user's home. */
    USER,
    /** System-wide systemd ({@code systemctl}); unit lives under {@code /etc/systemd/system}. */
    SYSTEM
  }

  private final ShellExec shell;
  private final Mode mode;
  private final Path serviceFilePath;
  private final Path systemdLinkPath;
  private final Path sailBinary;
  private final String bindAddress;
  private final int bindPort;
  private final String username;

  /**
   * @param shell the shell executor (real or scripted for tests)
   * @param mode install scope — USER (per-user systemd) or SYSTEM ({@code /etc/systemd/system})
   * @param userHome target user's home directory; only used in {@link Mode#USER} for the unit and
   *     discovery-symlink paths
   * @param sailBinary absolute path to the {@code sail} binary for {@code ExecStart}
   * @param bindAddress bind address for {@code sail server start --host}
   * @param bindPort bind port
   * @param username target username; in {@link Mode#USER} used for {@code loginctl} queries, in
   *     {@link Mode#SYSTEM} written into the unit's {@code User=} field
   */
  public SystemdServiceInstaller(
      ShellExec shell,
      Mode mode,
      Path userHome,
      Path sailBinary,
      String bindAddress,
      int bindPort,
      String username) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.mode = Objects.requireNonNull(mode, "mode");
    var home = Objects.requireNonNull(userHome, "userHome");
    if (mode == Mode.SYSTEM) {
      this.serviceFilePath = Path.of("/etc/systemd/system").resolve(UNIT_FILENAME);
      this.systemdLinkPath = null;
    } else {
      this.serviceFilePath = home.resolve(".sail/services").resolve(UNIT_FILENAME);
      this.systemdLinkPath = home.resolve(".config/systemd/user").resolve(UNIT_FILENAME);
    }
    this.sailBinary = Objects.requireNonNull(sailBinary, "sailBinary");
    if (Strings.isBlank(bindAddress)) {
      throw new IllegalArgumentException("bindAddress is required");
    }
    if (bindPort <= 0 || bindPort > 65535) {
      throw new IllegalArgumentException("bindPort must be 1..65535, got " + bindPort);
    }
    if (Strings.isBlank(username)) {
      throw new IllegalArgumentException("username is required");
    }
    this.bindAddress = bindAddress;
    this.bindPort = bindPort;
    this.username = username;
  }

  /** The mode this installer was constructed with. */
  public Mode mode() {
    return mode;
  }

  /**
   * Where the unit file is written:
   *
   * <ul>
   *   <li>{@link Mode#USER}: {@code ~/.sail/services/sail-api.service}
   *   <li>{@link Mode#SYSTEM}: {@code /etc/systemd/system/sail-api.service}
   * </ul>
   */
  public Path serviceFilePath() {
    return serviceFilePath;
  }

  /**
   * In {@link Mode#USER}, the systemd-discovery symlink at {@code
   * ~/.config/systemd/user/sail-api.service} pointing at {@link #serviceFilePath()}. {@code null}
   * in {@link Mode#SYSTEM} (the unit is already on systemd's system search path).
   */
  public Path systemdLinkPath() {
    return systemdLinkPath;
  }

  /** Whether the unit file (and, in {@link Mode#USER}, the discovery symlink) exists on disk. */
  public boolean isInstalled() {
    if (!Files.exists(serviceFilePath)) {
      return false;
    }
    return systemdLinkPath == null || Files.exists(systemdLinkPath);
  }

  /**
   * Returns the contents of the unit file that {@link #install()} writes. Pure function — no I/O.
   * Public for tests and {@code sail host service status --show-unit}.
   */
  public String renderUnit() {
    var userClause = mode == Mode.SYSTEM ? "User=root\n" : "";
    var wantedBy = mode == Mode.SYSTEM ? "multi-user.target" : "default.target";
    var remoteFlag = BindPolicy.isLoopback(bindAddress) ? "" : " --allow-remote";
    return """
        [Unit]
        Description=Sail API server
        Documentation=https://github.com/standardapplied/sail
        After=network.target

        [Service]
        Type=simple
        %sExecStart=%s server start --host %s --port %d%s
        Restart=on-failure
        RestartSec=2
        LimitNOFILE=4096

        [Install]
        WantedBy=%s
        """
        .formatted(userClause, sailBinary, bindAddress, bindPort, remoteFlag, wantedBy);
  }

  /**
   * Writes the unit file, creates the discovery symlink (USER mode only), runs {@code
   * daemon-reload}, then {@code enable --now} so the service starts immediately and on every boot.
   * Idempotent: an existing symlink is replaced; an existing unit file is overwritten in place.
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
    requireSuccess(
        shell.exec(systemctl("enable", "--now", UNIT_NAME)), "Failed to enable+start " + UNIT_NAME);
  }

  /**
   * Re-renders the unit file from the current template and rewrites it iff the on-disk content
   * differs. Runs {@code daemon-reload} when a rewrite happens so the new content takes effect.
   * Does NOT enable or start the service — the caller is responsible for that (the upgrade flow,
   * for example, restarts an already-running service after reconciliation).
   *
   * <p>This is the seam that prevents binary-only upgrades from leaving stale unit content on disk
   * — the bug class that held 0.12.5 / 0.12.6 in production stuck on a 0.12.0-era {@code
   * RuntimeDirectoryMode}.
   *
   * @return {@code true} if the on-disk unit was rewritten; {@code false} if it already matched.
   */
  public boolean reconcile() throws IOException, InterruptedException, TimeoutException {
    var expected = renderUnit();
    String onDisk;
    try {
      onDisk = Files.readString(serviceFilePath);
    } catch (NoSuchFileException nsf) {
      install();
      return true;
    }
    if (expected.equals(onDisk)) {
      return false;
    }
    Files.writeString(serviceFilePath, expected);
    requireSuccess(shell.exec(systemctl("daemon-reload")), "Failed to reload systemd units");
    return true;
  }

  /**
   * Stops and disables the service, removes the discovery symlink (USER mode) and unit file,
   * reloads systemd. Idempotent — missing pieces are not an error.
   */
  public void uninstall() throws IOException, InterruptedException, TimeoutException {
    shell.exec(systemctl("disable", "--now", UNIT_NAME));
    if (systemdLinkPath != null) {
      Files.deleteIfExists(systemdLinkPath);
    }
    Files.deleteIfExists(serviceFilePath);
    requireSuccess(shell.exec(systemctl("daemon-reload")), "Failed to reload systemd units");
  }

  /** Starts the service. */
  public void start() throws IOException, InterruptedException, TimeoutException {
    requireSuccess(shell.exec(systemctl("start", UNIT_NAME)), "Failed to start " + UNIT_NAME);
  }

  /** Stops the service. */
  public void stop() throws IOException, InterruptedException, TimeoutException {
    requireSuccess(shell.exec(systemctl("stop", UNIT_NAME)), "Failed to stop " + UNIT_NAME);
  }

  /** Restarts the service. */
  public void restart() throws IOException, InterruptedException, TimeoutException {
    requireSuccess(shell.exec(systemctl("restart", UNIT_NAME)), "Failed to restart " + UNIT_NAME);
  }

  /** Parsed systemctl status: ActiveState + MainPID + LoadState. */
  public ServiceStatus status() throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            systemctl("show", UNIT_NAME, "--property=ActiveState,SubState,MainPID,LoadState"));
    var fields = parseShowOutput(result.stdout());
    return new ServiceStatus(
        fields.getOrDefault("LoadState", "unknown"),
        fields.getOrDefault("ActiveState", "unknown"),
        fields.getOrDefault("SubState", "unknown"),
        parsePid(fields.get("MainPID")));
  }

  /**
   * Returns the raw output of {@code journalctl [--user] -u sail-api.service --no-pager -n
   * <lines>}. Used by {@code sail host service logs}.
   */
  public String journal(int lines) throws IOException, InterruptedException, TimeoutException {
    if (lines <= 0) {
      throw new IllegalArgumentException("lines must be positive");
    }
    var cmd = new ArrayList<String>();
    cmd.add("journalctl");
    if (mode == Mode.USER) {
      cmd.add("--user");
    }
    cmd.add("-u");
    cmd.add(UNIT_NAME);
    cmd.add("--no-pager");
    cmd.add("-n");
    cmd.add(Integer.toString(lines));
    var result = shell.exec(List.copyOf(cmd));
    if (!result.ok()) {
      throw new IOException("Failed to read journal: " + result.stderr());
    }
    return result.stdout();
  }

  /**
   * {@code loginctl show-user <user> --property=Linger} parsed value, or {@code "n/a"} in SYSTEM
   * mode (linger is a user-systemd concept). Returns {@code "unknown"} on lookup failure.
   */
  public String lingerStatus() throws IOException, InterruptedException, TimeoutException {
    if (mode == Mode.SYSTEM) {
      return "n/a";
    }
    var result =
        shell.exec(List.of("loginctl", "show-user", username, "--property=Linger", "--value"));
    if (!result.ok()) {
      return "unknown";
    }
    return result.stdout().strip();
  }

  /**
   * True when linger is enabled for the user. Always {@code true} in SYSTEM mode (system services
   * don't need linger; they start at boot regardless).
   */
  public boolean isLingerEnabled() throws IOException, InterruptedException, TimeoutException {
    if (mode == Mode.SYSTEM) {
      return true;
    }
    return "yes".equalsIgnoreCase(lingerStatus());
  }

  /**
   * The shell command an operator should run to enable linger so {@link #lingerStatus()} starts
   * returning {@code yes} and the user service survives logout. Returns an empty string in SYSTEM
   * mode (not applicable).
   */
  public String enableLingerCommand() {
    return mode == Mode.SYSTEM ? "" : "sudo loginctl enable-linger " + username;
  }

  /** Parsed service status. */
  public record ServiceStatus(String loadState, String activeState, String subState, Integer pid) {
    /** True when the service is active and running. */
    public boolean running() {
      return "active".equals(activeState) && "running".equals(subState);
    }
  }

  private List<String> systemctl(String... args) {
    return systemctl(mode, args);
  }

  static List<String> systemctl(Mode mode, String... args) {
    var cmd = new ArrayList<String>();
    cmd.add("systemctl");
    if (mode == Mode.USER) {
      cmd.add("--user");
    }
    for (var arg : args) {
      cmd.add(arg);
    }
    return List.copyOf(cmd);
  }

  private static Map<String, String> parseShowOutput(String output) {
    var map = new LinkedHashMap<String, String>();
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
    if (Strings.isBlank(raw)) {
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
