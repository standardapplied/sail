/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * Centralized path constants for CLI state files. All state lives under {@code ~/.sail/}: project
 * descriptors, provisioning state, host config, and client config. Every command works from any
 * directory by project name alone.
 */
public final class SailPaths {

  private SailPaths() {}

  private static final Path SAIL_DIR = Path.of(System.getProperty("user.home"), ".sail");
  private static final Path PROJECTS_DIR = SAIL_DIR.resolve("projects");
  public static final String PROJECT_DESCRIPTOR = "sail.yaml";

  /** Returns the base sail directory: {@code ~/.sail}. */
  public static Path sailDir() {
    return SAIL_DIR;
  }

  private static final Path SYSTEM_DATA_DIR = Path.of("/var/lib/sail");

  /**
   * Returns the control-plane data directory. Resolution: {@code $SAIL_DATA_DIR} when set;
   * otherwise the shared system directory {@code /var/lib/sail} when it already holds the database
   * (a host provisioned for SSH-key FDE login); otherwise {@link #sailDir()}. Auto-detecting the
   * provisioned location matters because every context must agree on the database without relying
   * on an env var being exported everywhere — the operator's interactive shell, the {@code sail}
   * user's SSH gateway (a non-login {@code bash -c}), and the {@code sail-api} service all reach
   * it. Solo/dev installs with no system database keep everything under {@code ~/.sail} unchanged.
   */
  public static Path dataDir() {
    return dataDir(
        System.getenv("SAIL_DATA_DIR"), Files.isReadable(SYSTEM_DATA_DIR.resolve("sail.db")));
  }

  /** Pure resolver; visible for tests so resolution can be exercised without the environment. */
  static Path dataDir(String configured, boolean provisionedSystemDb) {
    if (Strings.isNotBlank(configured)) {
      return Path.of(configured);
    }
    if (provisionedSystemDb) {
      return SYSTEM_DATA_DIR;
    }
    return SAIL_DIR;
  }

  /** Returns the control-plane database path: {@code <dataDir>/sail.db}. */
  public static Path controlPlaneDb() {
    return dataDir().resolve("sail.db");
  }

  /**
   * Creates the control-plane data directory, restricting the per-user home location ({@code
   * ~/.sail}) to owner-only ({@code 0700}) so no other local user can reach the token/session
   * database inside it. The shared system directory ({@code /var/lib/sail}) and any explicit {@code
   * $SAIL_DATA_DIR} are left untouched — provisioning owns their group-shared permissions
   * deliberately (the {@code sail} gateway user needs access). Best-effort on non-POSIX systems.
   */
  public static void ensureDataDir(Path dir) throws IOException {
    Files.createDirectories(dir);
    if (dir.startsWith(SAIL_DIR)
        && dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  /** Returns the projects base directory: {@code ~/.sail/projects}. */
  public static Path projectsDir() {
    return PROJECTS_DIR;
  }

  /** Returns the project-specific directory: {@code ~/.sail/projects/<name>}. */
  public static Path projectDir(String name) {
    return PROJECTS_DIR.resolve(name);
  }

  /** Returns the provision state file: {@code ~/.sail/projects/<name>/provision-state.yaml}. */
  public static Path provisionState(String name) {
    return projectDir(name).resolve("provision-state.yaml");
  }

  /** Returns the update check cache file: {@code ~/.sail/update-check.yaml}. */
  public static Path updateCheckFile() {
    return SAIL_DIR.resolve("update-check.yaml");
  }

  /**
   * Returns the host config file path. Resolution mirrors {@link #dataDir()}: the shared system
   * copy {@code /var/lib/sail/host.yaml} when it exists (a host provisioned for SSH-key FDE login),
   * otherwise {@code ~/.sail/host.yaml}. The shared location matters for the same reason the
   * database moved: commands arriving through the {@code sail} user's SSH gateway must read host
   * configuration (e.g. the webauthn origin for enrollment URLs), and the operator's home is
   * unreadable to them. Solo/dev installs stay under {@code ~/.sail} unchanged.
   */
  public static Path hostConfigPath() {
    return hostConfigPath(Files.exists(SYSTEM_DATA_DIR.resolve("host.yaml")));
  }

  /** Pure resolver; visible for tests so resolution can be exercised without the environment. */
  static Path hostConfigPath(boolean provisionedSystemConfig) {
    return provisionedSystemConfig
        ? SYSTEM_DATA_DIR.resolve("host.yaml")
        : SAIL_DIR.resolve("host.yaml");
  }

  /** Returns the client config file path: {@code ~/.sail/config.yaml}. */
  public static Path clientConfigPath() {
    return SAIL_DIR.resolve("config.yaml");
  }

  /**
   * Private SSH key this box presents when syncing to main: {@code ~/.sail/sync_ed25519}. A
   * sail-managed identity distinct from the engineer's personal keys, used only for the {@code ssh
   * sail@main sail _sync} lane so its public half is deterministic at join time and {@code sail
   * sync --watch} authenticates without a forwarded agent.
   */
  public static Path syncKeyPath() {
    return SAIL_DIR.resolve("sync_ed25519");
  }

  /** Public half of {@link #syncKeyPath()}: {@code ~/.sail/sync_ed25519.pub}. */
  public static Path syncPublicKeyPath() {
    return SAIL_DIR.resolve("sync_ed25519.pub");
  }

  /**
   * Resolves the project descriptor path for a project. Checks in order:
   *
   * <ol>
   *   <li>{@code ~/.sail/projects/<name>/sail.yaml} (canonical location)
   *   <li>The explicit {@code file} path (from {@code -f} flag)
   *   <li>{@code <name>/sail.yaml} in the current directory
   * </ol>
   *
   * Returns the first path that exists, or the canonical path for the error message.
   */
  public static Path resolveSailYaml(String name, String file) {
    if (name != null) {
      var canonical = projectDir(name).resolve(PROJECT_DESCRIPTOR);
      if (Files.exists(canonical)) {
        return canonical;
      }
    }
    var path = Path.of(file);
    if (Files.exists(path)) {
      return path;
    }
    if (name != null) {
      var namedPath = Path.of(name, PROJECT_DESCRIPTOR);
      if (Files.exists(namedPath)) {
        return namedPath;
      }
      return projectDir(name).resolve(PROJECT_DESCRIPTOR);
    }
    return path;
  }

  /**
   * Walks up from {@code start} (defaulting to the current working directory) looking for a {@code
   * sail.yaml}. Returns the directory containing it, or empty if none is found before reaching the
   * filesystem root or the user's home directory (whichever comes first). Pure I/O-free helper for
   * callers that pull additional metadata out of the descriptor.
   */
  public static Optional<Path> findSailYamlUpward(Path start) {
    var home = Path.of(System.getProperty("user.home"));
    var dir = start.toAbsolutePath().normalize();
    while (dir != null) {
      var candidate = dir.resolve(PROJECT_DESCRIPTOR);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate);
      }
      if (dir.equals(home)) {
        return Optional.empty();
      }
      dir = dir.getParent();
    }
    return Optional.empty();
  }

  /**
   * Expands a leading {@code ~} to the current user's home directory. Returns the path unchanged if
   * it does not start with {@code ~/}.
   */
  public static String expandHome(String path) {
    if (path != null && path.startsWith("~/")) {
      return System.getProperty("user.home") + path.substring(1);
    }
    return path;
  }

  /**
   * Path of the event-ingress Unix domain socket. Resolution order:
   *
   * <ol>
   *   <li>Running as {@code root} → {@code /run/sail/api.sock} (system-level systemd creates the
   *       parent via {@code RuntimeDirectory=sail} on the {@code sail-api.service} unit).
   *   <li>{@code $XDG_RUNTIME_DIR} is set → {@code $XDG_RUNTIME_DIR/sail/api.sock} (user-level
   *       systemd creates the parent via the same directive on the user unit).
   *   <li>Fallback for dev environments without {@code XDG_RUNTIME_DIR} → {@code
   *       ~/.sail/run/api.sock} (dedicated subdirectory so the parent can be bind-mounted without
   *       exposing the rest of {@code ~/.sail}).
   * </ol>
   */
  public static Path apiSocketPath() {
    return apiSocketPath(isRoot(), System.getenv("XDG_RUNTIME_DIR"));
  }

  /** Pure resolver; visible for tests so the lookup can be exercised without running as root. */
  static Path apiSocketPath(boolean root, String xdgRuntimeDir) {
    if (root) {
      return Path.of("/run/sail/api.sock");
    }
    if (Strings.isNotBlank(xdgRuntimeDir)) {
      return Path.of(xdgRuntimeDir, "sail", "api.sock");
    }
    return SAIL_DIR.resolve("run/api.sock");
  }

  /** Returns true when the current process runs as root — the single privilege check. */
  public static boolean isRoot() {
    return "root".equals(ProcessHandle.current().info().user().orElse(""));
  }

  /** Container-side mount point for {@link #apiSocketPath()}. Same on every project. */
  public static Path apiSocketContainerPath() {
    return Path.of("/run/sail/api.sock");
  }

  /**
   * Parent directory of {@link #apiSocketPath()} on the host. This is what gets bind-mounted into
   * project containers. Mounting the directory (not the socket file) is the only way to survive
   * {@code sail-api} restarts: the listener unlinks and recreates the socket on every start, which
   * strands any file-level bind mount on the old inode.
   */
  public static Path apiSocketHostDir() {
    return apiSocketPath().getParent();
  }

  /** Container-side directory corresponding to {@link #apiSocketHostDir()}. */
  public static Path apiSocketContainerDir() {
    return apiSocketContainerPath().getParent();
  }

  /**
   * Returns the path to the running binary. Uses {@code /proc/self/exe} on Linux, falls back to
   * {@code /usr/local/bin/sail}.
   */
  public static Path binaryPath() {
    var procSelf = Path.of("/proc/self/exe");
    try {
      if (Files.exists(procSelf)) {
        return procSelf.toRealPath();
      }
    } catch (IOException ignored) {
    }
    return Path.of("/usr/local/bin/sail");
  }
}
