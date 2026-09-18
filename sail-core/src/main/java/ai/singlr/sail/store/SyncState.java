/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;

/**
 * Per-peer, per-entity-type sync checkpoint: the highest main sequence this node has applied from a
 * given peer (the main devbox) for one entity type. Every pull is incremental from the checkpoint,
 * so a sync resumes cleanly after an interruption without re-applying or skipping changes. Keyed by
 * peer so a node can, in principle, track more than one upstream (only one — main — in the star
 * topology, but the key keeps it honest), and by type because each type pages independently and one
 * type's high-water says nothing about another's.
 */
public final class SyncState {

  private final Sqlite db;

  public SyncState(Sqlite db) {
    this.db = db;
  }

  /**
   * The last applied checkpoint for {@code entityType} from {@code peer}; 0 if this node has never
   * synced that type with it.
   */
  public long checkpoint(String peer, String entityType) {
    return db.queryOne(
            "SELECT checkpoint FROM sync_state WHERE peer = ? AND entity_type = ?",
            row -> row.integer(0),
            peer,
            entityType)
        .orElse(0L);
  }

  /** Advances one type's checkpoint for {@code peer}. Monotonic: never moves it backward. */
  public void advance(String peer, String entityType, long checkpoint) {
    db.execute(
        """
        INSERT INTO sync_state (peer, entity_type, checkpoint, updated_at) VALUES (?, ?, ?, ?)
        ON CONFLICT(peer, entity_type) DO UPDATE SET
            checkpoint = max(checkpoint, excluded.checkpoint), updated_at = excluded.updated_at""",
        peer,
        entityType,
        checkpoint,
        DateTimeUtils.now().toString());
  }
}
