/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Manages Incus container device attachments via {@code incus config device}. Currently scoped to
 * the {@code sail-api-sock} disk device that bind-mounts the host's API event-ingress Unix domain
 * socket into the container.
 *
 * <p>Operations are idempotent: {@link #ensureEventSocket} checks the existing config and skips,
 * adds, or replaces as needed so {@code sail project apply} can be safely re-run. Every write goes
 * through {@link ContainerConfig}, under the container's lock.
 */
public final class IncusDeviceManager {

  /** Device name used for the API socket attachment. */
  public static final String EVENT_SOCKET_DEVICE = "sail-api-sock";

  private final ShellExec shell;
  private final ContainerConfig config;

  public IncusDeviceManager(ShellExec shell) {
    this(shell, SailPaths.locksDir());
  }

  /**
   * @param locks the box's lock directory, under which each write holds its container's lock
   */
  public IncusDeviceManager(ShellExec shell, Path locks) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.config = new ContainerConfig(shell, locks);
  }

  /**
   * Returns the result of an ensure operation so callers can render different output when a device
   * is added vs already-present.
   */
  public enum EnsureResult {
    /** No prior device — newly added. */
    ADDED,
    /** A device with the same source was already configured; no change. */
    ALREADY_PRESENT,
    /** A device existed with a different source; it was replaced. */
    REPLACED
  }

  /**
   * Idempotently attaches the {@code sail-api-sock} disk device to the given container, binding
   * {@code hostPath} on the host to {@code containerPath} inside the container: read, then remove
   * and add only when the source moved, all under the container's lock. A device already pointing
   * at {@code hostPath} is left alone; its persistent directory keeps one inode, so the mount
   * cannot be stranded behind a matching path.
   *
   * <p>Callers must pass <em>directory</em> paths (not the socket file itself). The {@code
   * sail-api} listener unlinks and recreates its socket on every start; a file-level bind mount
   * pins the container to the stale inode and breaks all in-container hook deliveries. A directory
   * mount stays valid because the kernel resolves the file lookup through the live mountpoint on
   * every {@code connect()}.
   */
  public EnsureResult ensureEventSocket(String container, Path hostPath, Path containerPath)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    Objects.requireNonNull(hostPath, "hostPath");
    Objects.requireNonNull(containerPath, "containerPath");
    return config.atomically(
        container,
        () -> {
          var existing = currentEventSocketSource(container);
          if (existing == null) {
            addEventSocket(container, hostPath, containerPath);
            return EnsureResult.ADDED;
          }
          if (existing.equals(hostPath.toString())) {
            return EnsureResult.ALREADY_PRESENT;
          }
          removeEventSocket(container);
          addEventSocket(container, hostPath, containerPath);
          return EnsureResult.REPLACED;
        });
  }

  /**
   * The trust gate: a container is Sail-managed only when it carries the API-socket device Sail
   * itself attached — instance-level provenance. A catalog row is name-level intent, never
   * provenance: {@code destroy} without {@code --purge} retains the row, and a foreign container
   * later created under the same name must neither inherit the API mount and box credential from a
   * stale entry ({@code apply --all}) nor be deleted for a project main stopped granting this box.
   * An unmarked container the operator does own is claimed through explicit single-project apply,
   * where naming it is the authorization.
   */
  public boolean sailManaged(String project)
      throws IOException, InterruptedException, TimeoutException {
    return NameValidator.isValidProjectName(project) && currentEventSocketSource(project) != null;
  }

  /**
   * Returns the host path that the existing {@code sail-api-sock} device points to, or {@code null}
   * if the device is not configured.
   */
  public String currentEventSocketSource(String container)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    var result =
        shell.exec(
            List.of("incus", "config", "device", "get", container, EVENT_SOCKET_DEVICE, "source"));
    if (!result.ok()) {
      return null;
    }
    var value = result.stdout().strip();
    return value.isBlank() ? null : value;
  }

  /** Lists configured device names on a container, parsed from YAML. */
  @SuppressWarnings("unchecked")
  public List<String> listDevices(String container)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    var result = shell.exec(List.of("incus", "config", "device", "show", container));
    if (!result.ok()) {
      throw new IOException("Failed to read devices on " + container + ": " + result.reason());
    }
    if (result.stdout().isBlank()) {
      return List.of();
    }
    Map<String, Object> map;
    try {
      map = YamlUtil.parseMap(result.stdout());
    } catch (RuntimeException e) {
      throw new IOException("Could not parse device output for " + container, e);
    }
    return List.copyOf(map.keySet());
  }

  private void removeEventSocket(String container)
      throws IOException, InterruptedException, TimeoutException {
    var result = config.device(container, "remove", List.of(EVENT_SOCKET_DEVICE));
    if (!result.ok()) {
      throw new IOException(
          "Failed to remove "
              + EVENT_SOCKET_DEVICE
              + " from "
              + container
              + ": "
              + result.reason());
    }
  }

  private void addEventSocket(String container, Path hostPath, Path containerPath)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        config.device(
            container,
            "add",
            List.of(EVENT_SOCKET_DEVICE, "disk", "source=" + hostPath, "path=" + containerPath));
    if (!result.ok()) {
      throw new IOException(
          "Failed to add " + EVENT_SOCKET_DEVICE + " to " + container + ": " + result.reason());
    }
  }
}
