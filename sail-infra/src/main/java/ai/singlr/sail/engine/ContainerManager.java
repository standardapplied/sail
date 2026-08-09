/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Manages Incus container lifecycle: query state, start, stop, delete. All state is queried live
 * from Incus — no local caching. Used by {@code up}, {@code down}, {@code switch}, {@code project
 * list}, and {@code project destroy} commands.
 */
public final class ContainerManager {

  private static final int READINESS_ATTEMPTS = 30;
  private static final long READINESS_POLL_MILLIS = 500;

  private final ShellExec shell;

  public ContainerManager(ShellExec shell) {
    this.shell = shell;
  }

  /** Resource limits configured on a container (raw strings from Incus config). */
  public record ResourceLimits(String cpu, String memory) {}

  /** Summary of a container's name, current state, and resource limits. */
  public record ContainerInfo(String name, ContainerState state, ResourceLimits limits) {}

  /** Queries the current state of a single container by name. */
  public ContainerState queryState(String name)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "list", "^" + name + "$", "--format", "json"));
    if (!result.ok()) {
      return new ContainerState.Error("incus list failed: " + result.stderr());
    }
    var entries = YamlUtil.parseList(result.stdout());
    if (entries.isEmpty()) {
      return new ContainerState.NotCreated();
    }
    return parseContainerState(entries.getFirst());
  }

  /** Lists all containers with their current state. */
  public List<ContainerInfo> listAll() throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "list", "--format", "json"));
    if (!result.ok()) {
      throw new IOException("incus list failed: " + result.stderr());
    }
    var entries = YamlUtil.parseList(result.stdout());
    return entries.stream()
        .map(
            entry -> {
              var name = Objects.toString(entry.get("name"), "unknown");
              var state = parseContainerState(entry);
              var limits = extractResourceLimits(entry);
              return new ContainerInfo(name, state, limits);
            })
        .toList();
  }

  /** Starts a container. Throws on failure. */
  public void start(String name) throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "start", name));
    if (!result.ok()) {
      throw new IOException("Failed to start container '" + name + "': " + result.stderr());
    }
  }

  /** Restarts a container. Throws on failure. */
  public void restart(String name) throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "restart", name));
    if (!result.ok()) {
      throw new IOException("Failed to restart container '" + name + "': " + result.stderr());
    }
  }

  /**
   * Blocks until the dev user's session bus is up inside the container — the readiness signal that
   * {@code incus exec} as the dev user (sail plumbing, agent context) will succeed. {@code incus
   * start} returns once init begins, well before this. Best-effort: returns {@code false} if the
   * container is not ready within the timeout, so callers proceed and report rather than hang.
   */
  public boolean waitUntilReady(String name)
      throws IOException, InterruptedException, TimeoutException {
    var busPath = ContainerExec.DEV_XDG_RUNTIME_DIR + "/bus";
    for (var attempt = 0; attempt < READINESS_ATTEMPTS; attempt++) {
      if (shell.exec(List.of("incus", "exec", name, "--", "test", "-S", busPath)).ok()) {
        return true;
      }
      Thread.sleep(READINESS_POLL_MILLIS);
    }
    return false;
  }

  /** Stops a container. Throws on failure. */
  public void stop(String name) throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "stop", name));
    if (!result.ok()) {
      throw new IOException("Failed to stop container '" + name + "': " + result.stderr());
    }
  }

  /**
   * Renames the container from {@code old} to {@code renamed} (snapshots travel with it). Incus
   * requires the instance to be stopped first. Throws on failure.
   */
  public void rename(String old, String renamed)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "rename", old, renamed));
    if (!result.ok()) {
      throw new IOException(
          "Failed to rename container '" + old + "' to '" + renamed + "': " + result.stderr());
    }
  }

  /**
   * Reconciles the guest hostname to {@code name} — its live value, {@code /etc/hostname}, and the
   * {@code 127.0.1.1} line in {@code /etc/hosts} — so the in-container {@code spec} CLI, which
   * derives its project from {@code $(hostname)}, resolves the right project. Idempotent: returns
   * {@code false} without touching the container when the hostname already matches, {@code true}
   * when it had drifted and was corrected. The container must be running. Throws on failure.
   */
  public boolean setHostname(String name)
      throws IOException, InterruptedException, TimeoutException {
    if (hostnameMatches(name)) {
      return false;
    }
    var script =
        """
        set -e
        printf '%s\\n' "$1" > /etc/hostname
        hostname "$1"
        sed -i "s/^\\(127\\.0\\.1\\.1[[:space:]]*\\).*/\\1$1/" /etc/hosts 2>/dev/null || true
        """;
    var result =
        shell.exec(List.of("incus", "exec", name, "--", "bash", "-c", script, "bash", name));
    if (!result.ok()) {
      throw new IOException(
          "Failed to set hostname for container '" + name + "': " + result.stderr());
    }
    return true;
  }

  /** Reads the live guest hostname and reports whether it already equals {@code name}. */
  public boolean hostnameMatches(String name)
      throws IOException, InterruptedException, TimeoutException {
    var current = shell.exec(List.of("incus", "exec", name, "--", "hostname"));
    return current.ok() && current.stdout().strip().equals(name);
  }

  /** Applies CPU and memory limits to a container. Throws on failure. */
  public void setResourceLimits(String name, ResourceLimits limits)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            List.of(
                "incus",
                "config",
                "set",
                name,
                "limits.cpu=" + limits.cpu(),
                "limits.memory=" + limits.memory()));
    if (!result.ok()) {
      throw new IOException(
          "Failed to set resource limits for container '" + name + "': " + result.stderr());
    }
  }

  /** Applies the root disk quota to a container. Throws on failure. */
  public void setDiskQuota(String name, String disk)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(List.of("incus", "config", "device", "override", name, "root", "size=" + disk));
    if (result.ok()) {
      return;
    }
    if (result.stderr().contains("already exists")) {
      var setResult =
          shell.exec(List.of("incus", "config", "device", "set", name, "root", "size=" + disk));
      if (!setResult.ok()) {
        throw new IOException(
            "Failed to set disk quota for container '" + name + "': " + setResult.stderr());
      }
      return;
    }
    throw new IOException(
        "Failed to set disk quota for container '" + name + "': " + result.stderr());
  }

  /** Reads a container's current root disk quota, empty when it is unset or cannot be read. */
  public Optional<String> queryDiskQuota(String name)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "config", "device", "get", name, "root", "size"));
    if (!result.ok()) {
      return Optional.empty();
    }
    var value = result.stdout().strip();
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }

  /** Force-deletes a container (stops it first if running). Throws on failure. */
  public void forceDelete(String name) throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "delete", name, "--force"));
    if (!result.ok()) {
      throw new IOException("Failed to delete container '" + name + "': " + result.stderr());
    }
  }

  /** Queries a single container by name, returning full info including resource limits. */
  public ContainerInfo queryInfo(String name)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "list", "^" + name + "$", "--format", "json"));
    if (!result.ok()) {
      return new ContainerInfo(
          name, new ContainerState.Error("incus list failed: " + result.stderr()), null);
    }
    var entries = YamlUtil.parseList(result.stdout());
    if (entries.isEmpty()) {
      return new ContainerInfo(name, new ContainerState.NotCreated(), null);
    }
    var entry = entries.getFirst();
    return new ContainerInfo(name, parseContainerState(entry), extractResourceLimits(entry));
  }

  @SuppressWarnings("unchecked")
  private static ResourceLimits extractResourceLimits(Map<String, Object> entry) {
    var config = (Map<String, Object>) entry.get("config");
    if (config == null) {
      return null;
    }
    var cpu = config.get("limits.cpu");
    var memory = config.get("limits.memory");
    if (cpu == null && memory == null) {
      return null;
    }
    return new ResourceLimits(
        cpu != null ? cpu.toString() : null, memory != null ? memory.toString() : null);
  }

  @SuppressWarnings("unchecked")
  private static ContainerState parseContainerState(Map<String, Object> entry) {
    var status = (String) entry.get("status");
    if ("Running".equals(status)) {
      var ipv4 = extractIpv4(entry);
      return new ContainerState.Running(ipv4);
    } else if ("Stopped".equals(status)) {
      return new ContainerState.Stopped();
    } else {
      return new ContainerState.Error("Unexpected container status: " + status);
    }
  }

  @SuppressWarnings("unchecked")
  private static String extractIpv4(Map<String, Object> entry) {
    var state = (Map<String, Object>) entry.get("state");
    if (state == null) {
      return null;
    }
    var network = (Map<String, Object>) state.get("network");
    if (network == null) {
      return null;
    }
    var eth0 = (Map<String, Object>) network.get("eth0");
    if (eth0 == null) {
      return null;
    }
    var addresses = (List<Map<String, Object>>) eth0.get("addresses");
    if (addresses == null) {
      return null;
    }
    return addresses.stream()
        .filter(a -> "inet".equals(a.get("family")))
        .map(a -> (String) a.get("address"))
        .findFirst()
        .orElse(null);
  }
}
