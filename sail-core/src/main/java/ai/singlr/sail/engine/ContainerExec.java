/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Builds {@code incus exec} command lists that run inside an Incus container as the dev user (UID
 * 1000). Pure utility — no side effects, no shell execution. The returned lists are passed directly
 * to {@link ShellExec}.
 */
public final class ContainerExec {

  /** The dev user's UID inside Incus containers. */
  public static final String DEV_UID = "1000";

  /** The dev user's GID inside Incus containers. */
  public static final String DEV_GID = "1000";

  /** The dev user's home directory inside Incus containers. */
  public static final String DEV_HOME = "/home/dev";

  /** The dev user's XDG_RUNTIME_DIR inside Incus containers. */
  public static final String DEV_XDG_RUNTIME_DIR = "/run/user/1000";

  /** Where a container session opens by default — the engineer's checkout. */
  public static final String DEV_WORKSPACE = "/home/dev/workspace";

  private ContainerExec() {}

  /**
   * Builds an {@code incus exec} command that runs the given args as UID/GID 1000 (dev user) with
   * the correct environment variables set.
   *
   * @param containerName the Incus container name
   * @param args the command and arguments to run inside the container
   * @return an unmodifiable command list ready for {@link ShellExec#exec}
   */
  public static List<String> asDevUser(String containerName, List<String> args) {
    return devUser(containerName, false, null, Map.of(), args);
  }

  /**
   * Builds an interactive {@code incus exec -t} command as the dev user, opening in the workspace.
   * Unlike {@link #asDevUser}, this requests a container-side pseudo-terminal ({@code -t}) so the
   * child sees a real tty — required for a login shell driven by the pty session host, which
   * allocates the node-side pty and forwards its stdio into the container.
   *
   * @param containerName the Incus container name
   * @param args the command and arguments to run inside the container
   * @return an unmodifiable command list ready for {@link ShellExec#exec}
   */
  public static List<String> asDevUserTty(String containerName, List<String> args) {
    return asDevUserTty(containerName, Map.of(), args);
  }

  /**
   * As {@link #asDevUserTty(String, List)}, additionally exporting {@code env} into the container
   * process — {@code incus exec} forwards nothing from the caller's environment on its own, so
   * anything the child must inherit crosses as an explicit {@code --env}.
   */
  public static List<String> asDevUserTty(
      String containerName, Map<String, String> env, List<String> args) {
    return devUser(containerName, true, DEV_WORKSPACE, env, args);
  }

  private static List<String> devUser(
      String containerName, boolean tty, String cwd, Map<String, String> env, List<String> args) {
    NameValidator.requireValidProjectName(containerName);
    var prefix = new ArrayList<String>(List.of("incus", "exec", containerName));
    if (tty) {
      prefix.add("-t");
    }
    prefix.addAll(List.of("--user", DEV_UID, "--group", DEV_GID));
    if (cwd != null) {
      prefix.addAll(List.of("--cwd", cwd));
    }
    prefix.addAll(
        List.of(
            "--env",
            "HOME=" + DEV_HOME,
            "--env",
            "XDG_RUNTIME_DIR=" + DEV_XDG_RUNTIME_DIR,
            "--env",
            "DBUS_SESSION_BUS_ADDRESS=unix:path=" + DEV_XDG_RUNTIME_DIR + "/bus"));
    env.forEach((key, value) -> prefix.addAll(List.of("--env", key + "=" + value)));
    prefix.add("--");
    return Stream.concat(prefix.stream(), args.stream()).toList();
  }

  /**
   * Builds an {@code incus exec} command that runs the given args as root — the privilege apt and
   * dpkg need. Mirrors {@link #asDevUser} for the few operations (baseline package installs) that
   * cannot run as the dev user, and validates the container name the same way.
   *
   * @param containerName the Incus container name
   * @param args the command and arguments to run inside the container
   * @return an unmodifiable command list ready for {@link ShellExec#exec}
   */
  public static List<String> asRoot(String containerName, List<String> args) {
    NameValidator.requireValidProjectName(containerName);
    var prefix = List.of("incus", "exec", containerName, "--");
    return Stream.concat(prefix.stream(), args.stream()).toList();
  }

  /**
   * Queries running Podman containers inside an Incus container and extracts the published host
   * ports. Returns a deduplicated, sorted list of port numbers. Returns an empty list if no
   * containers are running or the query fails.
   *
   * @param shell the shell executor
   * @param containerName the Incus container name
   * @return sorted list of unique published port numbers
   */
  @SuppressWarnings("unchecked")
  public static List<Integer> queryServicePorts(ShellExec shell, String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = asDevUser(containerName, List.of("podman", "ps", "--format", "json"));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      return List.of();
    }
    var containers = YamlUtil.parseList(result.stdout());
    return containers.stream()
        .map(c -> c.get("Ports"))
        .filter(p -> p instanceof List<?>)
        .flatMap(p -> ((List<Map<String, Object>>) p).stream())
        .map(port -> port.get("host_port"))
        .filter(hp -> hp instanceof Number)
        .map(hp -> ((Number) hp).intValue())
        .filter(p -> p > 0)
        .distinct()
        .sorted()
        .toList();
  }
}
