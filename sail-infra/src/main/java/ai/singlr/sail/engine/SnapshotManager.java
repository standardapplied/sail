/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Manages Incus container snapshots: create, restore, list, delete. All state is queried live from
 * Incus — no local caching. Used by {@code snap}, {@code restore}, {@code snaps}, and {@code agent}
 * commands.
 *
 * <p>Snapshot creation on the dir backend is a full filesystem copy and can take many minutes for
 * large workspaces, so mutating operations default to a 10-minute timeout — well above the 120s
 * default of {@link ShellExecutor}. Pass an explicit {@link Duration} to override.
 */
public final class SnapshotManager {

  private static final DateTimeFormatter LABEL_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  /** Default timeout for snapshot mutations (create/restore/delete). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

  private final ShellExec shell;

  public SnapshotManager(ShellExec shell) {
    this.shell = shell;
  }

  /** Metadata for a single Incus snapshot. */
  public record SnapshotInfo(String name, String createdAt) {}

  /** Creates a snapshot of the given container with the specified label. */
  public void create(String containerName, String label)
      throws IOException, InterruptedException, TimeoutException {
    create(containerName, label, DEFAULT_TIMEOUT);
  }

  /** Creates a snapshot with an explicit timeout. */
  public void create(String containerName, String label, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = List.of("incus", "snapshot", "create", containerName, label);
    var result = execMutation(cmd, timeout, "create snapshot '" + label + "'");
    if (!result.ok()) {
      throw new IOException(
          "Failed to create snapshot '"
              + label
              + "' for '"
              + containerName
              + "': "
              + result.stderr());
    }
  }

  /** Restores a container to the specified snapshot label. */
  public void restore(String containerName, String label)
      throws IOException, InterruptedException, TimeoutException {
    restore(containerName, label, DEFAULT_TIMEOUT);
  }

  /** Restores with an explicit timeout. */
  public void restore(String containerName, String label, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = List.of("incus", "snapshot", "restore", containerName, label);
    var result = execMutation(cmd, timeout, "restore snapshot '" + label + "'");
    if (!result.ok()) {
      throw new IOException(
          "Failed to restore snapshot '"
              + label
              + "' for '"
              + containerName
              + "': "
              + result.stderr());
    }
  }

  /** Lists all snapshots for a container, parsed from JSON. Returns empty list if none exist. */
  public List<SnapshotInfo> list(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(List.of("incus", "snapshot", "list", containerName, "--format", "json"));
    if (!result.ok()) {
      throw new IOException(
          "Failed to list snapshots for '" + containerName + "': " + result.stderr());
    }
    var entries = YamlUtil.parseList(result.stdout());
    return entries.stream().map(SnapshotManager::parseSnapshotInfo).toList();
  }

  /** Deletes a specific snapshot. */
  public void delete(String containerName, String label)
      throws IOException, InterruptedException, TimeoutException {
    delete(containerName, label, DEFAULT_TIMEOUT);
  }

  /** Deletes a specific snapshot with an explicit timeout. */
  public void delete(String containerName, String label, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = List.of("incus", "snapshot", "delete", containerName, label);
    var result = execMutation(cmd, timeout, "delete snapshot '" + label + "'");
    if (!result.ok()) {
      throw new IOException(
          "Failed to delete snapshot '"
              + label
              + "' for '"
              + containerName
              + "': "
              + result.stderr());
    }
  }

  /** Generates a default snapshot label from the current timestamp. */
  public static String defaultLabel() {
    return "snap-" + LABEL_FORMAT.format(DateTimeUtils.now());
  }

  private ShellExec.Result execMutation(List<String> cmd, Duration timeout, String description)
      throws IOException, InterruptedException, TimeoutException {
    try {
      return shell.exec(cmd, null, timeout);
    } catch (TimeoutException e) {
      throw new TimeoutException(
          "Timed out after "
              + timeout.toMinutes()
              + " minutes trying to "
              + description
              + ". On the dir storage backend, snapshots are full filesystem copies and can be"
              + " slow for large workspaces. Consider the zfs backend (sail host init --storage"
              + " zfs --disk /dev/sdX) for instant snapshots, or pass a larger timeout.");
    }
  }

  private static SnapshotInfo parseSnapshotInfo(Map<String, Object> entry) {
    var name = Objects.toString(entry.get("name"), "unknown");
    var createdAt = Objects.toString(entry.get("created_at"), "");
    return new SnapshotInfo(name, createdAt);
  }
}
