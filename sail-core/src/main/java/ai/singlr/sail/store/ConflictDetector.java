/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure three-way merge for one synced entity. Given the common ancestor ({@code base} — the
 * snapshot the local row descended from on main), the current {@code local} state, and the current
 * {@code remote} (main) state, it classifies the sync outcome at <em>field</em> granularity: edits
 * to different fields auto-merge; only edits to the <em>same</em> field with different values are a
 * true {@link Conflict} that must go to the user (the locked db-sync decision — no silent
 * last-write-wins).
 *
 * <p>A {@code null} snapshot means the entity is deleted/absent on that side, so delete-vs-edit is
 * detected as a conflict rather than a silent resurrection or silent deletion. Stateless and
 * side-effect-free: the engine (brick 3b) supplies the three snapshots and acts on the result.
 */
public final class ConflictDetector {

  private ConflictDetector() {}

  public sealed interface Resolution permits Converged, TakeRemote, KeepLocal, Merged, Conflict {}

  /** Local and remote already agree; nothing to do. */
  public record Converged() implements Resolution {}

  /** Only remote moved since base; adopt remote wholesale (fast-forward), including a delete. */
  public record TakeRemote() implements Resolution {}

  /** Only local moved since base; main fast-forwards to local, including a delete. */
  public record KeepLocal() implements Resolution {}

  /** Both moved on disjoint fields; {@code result} is the field-level auto-merge. */
  public record Merged(Map<String, Object> result) implements Resolution {}

  /** Both changed the same field(s) differently (or delete-vs-edit); needs a human decision. */
  public record Conflict(List<String> fields) implements Resolution {}

  /** The single field name used to report a delete-vs-edit conflict. */
  public static final String DELETED_FIELD = "<deleted>";

  public static Resolution detect(
      Map<String, Object> base, Map<String, Object> local, Map<String, Object> remote) {
    var localDeleted = local == null;
    var remoteDeleted = remote == null;

    if (localDeleted && remoteDeleted) {
      return new Converged();
    }
    if (localDeleted) {
      if (base == null) {
        return new TakeRemote();
      }
      return equalSnapshots(base, remote) ? new KeepLocal() : new Conflict(List.of(DELETED_FIELD));
    }
    if (remoteDeleted) {
      if (base == null) {
        return new KeepLocal();
      }
      return equalSnapshots(base, local) ? new TakeRemote() : new Conflict(List.of(DELETED_FIELD));
    }

    if (equalSnapshots(local, remote)) {
      return new Converged();
    }

    var safeBase = base == null ? Map.<String, Object>of() : base;
    var localChanged = changedFields(safeBase, local);
    var remoteChanged = changedFields(safeBase, remote);

    if (remoteChanged.isEmpty()) {
      return new KeepLocal();
    }
    if (localChanged.isEmpty()) {
      return new TakeRemote();
    }

    var conflicts = new ArrayList<String>();
    for (var field : localChanged) {
      if (remoteChanged.contains(field) && !Objects.equals(local.get(field), remote.get(field))) {
        conflicts.add(field);
      }
    }
    if (!conflicts.isEmpty()) {
      return new Conflict(List.copyOf(conflicts));
    }
    return new Merged(merge(safeBase, local, remote, localChanged, remoteChanged));
  }

  private static Map<String, Object> merge(
      Map<String, Object> base,
      Map<String, Object> local,
      Map<String, Object> remote,
      LinkedHashSet<String> localChanged,
      LinkedHashSet<String> remoteChanged) {
    var result = new LinkedHashMap<String, Object>();
    for (var field : workKeys(base, local, remote)) {
      if (localChanged.contains(field)) {
        result.put(field, local.get(field));
      } else if (remoteChanged.contains(field)) {
        result.put(field, remote.get(field));
      } else {
        result.put(field, base.get(field));
      }
    }
    local.forEach(
        (key, value) -> {
          if (isMetadata(key)) {
            result.put(key, value);
          }
        });
    return result;
  }

  private static LinkedHashSet<String> changedFields(
      Map<String, Object> base, Map<String, Object> side) {
    var changed = new LinkedHashSet<String>();
    for (var key : workKeys(base, side)) {
      if (!Objects.equals(base.get(key), side.get(key))) {
        changed.add(key);
      }
    }
    return changed;
  }

  private static boolean equalSnapshots(Map<String, Object> a, Map<String, Object> b) {
    var left = a == null ? Map.<String, Object>of() : a;
    var right = b == null ? Map.<String, Object>of() : b;
    for (var key : workKeys(left, right)) {
      if (!Objects.equals(left.get(key), right.get(key))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Keys carrying an FDE's work, excluding reserved metadata ({@code _}-prefixed, e.g. {@code
   * _actor} — who authored the revision). Metadata rides with a snapshot so it propagates, but it
   * never counts toward a conflict and never becomes a clashing field; a merge keeps the local
   * box's metadata since the local box produced the merged result.
   */
  @SafeVarargs
  private static LinkedHashSet<String> workKeys(Map<String, Object>... maps) {
    var keys = new LinkedHashSet<String>();
    for (var map : maps) {
      for (var key : map.keySet()) {
        if (!isMetadata(key)) {
          keys.add(key);
        }
      }
    }
    return keys;
  }

  private static boolean isMetadata(String key) {
    return key.startsWith("_");
  }
}
