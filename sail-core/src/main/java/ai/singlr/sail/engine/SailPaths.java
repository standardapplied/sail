/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

  /** Returns the host config file path: {@code ~/.sail/host.yaml}. */
  public static Path hostConfigPath() {
    return SAIL_DIR.resolve("host.yaml");
  }

  /** Returns the client config file path: {@code ~/.sail/config.yaml}. */
  public static Path clientConfigPath() {
    var override = System.getProperty("sail.client.config.path");
    return override != null ? Path.of(override) : SAIL_DIR.resolve("config.yaml");
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
    if (xdgRuntimeDir != null && !xdgRuntimeDir.isBlank()) {
      return Path.of(xdgRuntimeDir, "sail", "api.sock");
    }
    return SAIL_DIR.resolve("run/api.sock");
  }

  private static boolean isRoot() {
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
