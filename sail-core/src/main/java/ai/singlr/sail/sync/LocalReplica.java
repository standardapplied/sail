/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The node (local replica) side of a sync round. Tracks the merge base it last synced from main,
 * adopts authoritative revisions, and parks conflicts without ever overwriting the local row — the
 * no-lost-work guarantee. Comparable snapshots ({@code null} = deleted/absent) carry only the
 * fields that represent an FDE's work, so timestamps never cause false conflicts.
 */
public interface LocalReplica {

  /** Every entity id this node knows of, including tombstoned ones. */
  Set<String> entityIds();

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
