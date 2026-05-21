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
 * adds, or replaces as needed so {@code sail project sync} can be safely re-run.
 */
public final class IncusDeviceManager {

  /** Device name used for the API socket attachment. */
  public static final String EVENT_SOCKET_DEVICE = "sail-api-sock";

  private final ShellExec shell;

  public IncusDeviceManager(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
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
   * {@code hostPath} on the host to {@code containerPath} inside the container.
   */
  public EnsureResult ensureEventSocket(String container, Path hostPath, Path containerPath)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    Objects.requireNonNull(hostPath, "hostPath");
    Objects.requireNonNull(containerPath, "containerPath");
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
  }

  /** Removes the {@code sail-api-sock} device if present. Idempotent — no-op if absent. */
  public void removeEventSocket(String container)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    if (currentEventSocketSource(container) == null) {
      return;
    }
    var result =
        shell.exec(List.of("incus", "config", "device", "remove", container, EVENT_SOCKET_DEVICE));
    if (!result.ok()) {
      throw new IOException(
          "Failed to remove "
              + EVENT_SOCKET_DEVICE
              + " from "
              + container
              + ": "
              + result.stderr());
    }
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
      throw new IOException("Failed to read devices on " + container + ": " + result.stderr());
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

  private void addEventSocket(String container, Path hostPath, Path containerPath)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            List.of(
                "incus",
                "config",
                "device",
                "add",
                container,
                EVENT_SOCKET_DEVICE,
                "disk",
                "source=" + hostPath,
                "path=" + containerPath));
    if (!result.ok()) {
      throw new IOException(
          "Failed to add " + EVENT_SOCKET_DEVICE + " to " + container + ": " + result.stderr());
    }
  }
}
