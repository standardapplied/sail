/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;

/**
 * Per-peer sync checkpoint: the highest main sequence this node has applied from a given peer (the
 * main devbox). Pulls are incremental from the checkpoint, so a sync resumes cleanly after an
 * interruption without re-applying or skipping changes. Keyed by peer so a node can, in principle,
 * track more than one upstream (only one — main — in the star topology, but the key keeps it
 * honest).
 */
public final class SyncState {

  private final Sqlite db;

  public SyncState(Sqlite db) {
    this.db = db;
  }

  /** The last applied checkpoint for {@code peer}; 0 if this node has never synced with it. */
  public long checkpoint(String peer) {
    return db.queryOne(
            "SELECT checkpoint FROM sync_state WHERE peer = ?", row -> row.integer(0), peer)
        .orElse(0L);
  }

  /** Advances the checkpoint for {@code peer}. Monotonic: never moves the checkpoint backward. */
  public void advance(String peer, long checkpoint) {
    db.execute(
        """
        INSERT INTO sync_state (peer, checkpoint, updated_at) VALUES (?, ?, ?)
        ON CONFLICT(peer) DO UPDATE SET checkpoint = max(checkpoint, excluded.checkpoint),
            updated_at = excluded.updated_at""",
        peer,
        checkpoint,
        DateTimeUtils.now().toString());
  }
}
