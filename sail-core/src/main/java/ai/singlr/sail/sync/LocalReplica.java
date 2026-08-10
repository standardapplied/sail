/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The node (local replica) side of a sync round. Tracks the merge base it last synced from main,
 * adopts authoritative revisions, and parks conflicts without ever overwriting the local row — the
 * no-lost-work guarantee. Comparable snapshots ({@code null} = deleted/absent) carry only the
 * fields that represent an FDE's work, so timestamps never cause false conflicts.
 */
public interface LocalReplica {

  /** Every entity id this node knows of, including tombstoned ones. */
  Set<String> entityIds();

  /**
   * Whether this node may push its own change to {@code id} up to main, or may only pull main's
   * version. Multi-writer entities (specs, files, projects) always may — the default. A
   * single-writer entity like a run overrides this so a reader box never pushes a run it did not
   * author: when its local copy of a foreign run diverges, the engine adopts main's authoritative
   * version instead of offering an un-owned push that main would only reject.
   */
  default boolean mayPush(String id) {
    return true;
  }

  /** A comparable snapshot paired with the exact revision it was read at. */
  record Captured(Map<String, Object> snapshot, String rev) {}

  /**
   * Runs {@code work} in one atomic scope on the local store, excluding every concurrent local
   * writer for the whole scope.
   */
  <T> T atomically(Supplier<T> work);

  /**
   * Samples the current snapshot and its revision as one atomic read, so a local write landing
   * mid-sample can never pair an old snapshot with a newer revision — the torn pair that would
   * later defeat the stale-adoption guard and overwrite the newer work.
   */
  default Captured capture(String id) {
    return atomically(() -> new Captured(current(id), currentRev(id)));
  }

  /**
   * Adopts an authoritative state only if the local row still sits at {@code expectedRev}. The
   * check and the adoption are one atomic step, so a local write can never land between them and be
   * overwritten. Returns {@code false} when the row moved; the caller re-reconciles instead.
   */
  default boolean adoptIfCurrent(
      String id, String expectedRev, Map<String, Object> snapshot, String rev) {
    return atomically(
        () -> {
          if (!Objects.equals(currentRev(id), expectedRev)) {
            return false;
          }
          adopt(id, snapshot, rev);
          return true;
        });
  }

  /** Current comparable state; {@code null} if deleted or absent. */
  Map<String, Object> current(String id);

  /**
   * The merge base — the comparable state this row last synced from main; {@code null} if never.
   */
  Map<String, Object> base(String id);

  /** Latest local revision (including a tombstone); {@code null} if unknown. */
  String currentRev(String id);

  /** Adopts an authoritative state at main's exact rev ({@code null} snapshot = delete). */
  void adopt(String id, Map<String, Object> snapshot, String rev);

  /** Parks a conflict for human resolution; the local row is left untouched. */
  void recordConflict(
      String id,
      Map<String, Object> base,
      Map<String, Object> local,
      Map<String, Object> remote,
      List<String> fields);

  /** Advances the checkpoint for {@code peerId} to main's high-water sequence. */
  void advanceCheckpoint(String peerId, long seq);
}
