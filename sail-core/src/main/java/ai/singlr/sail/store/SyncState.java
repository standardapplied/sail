/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-peer, per-entity-type sync checkpoint: the highest main sequence this node has applied from a
 * given peer (the main devbox) for one entity type. Every pull is incremental from the checkpoint,
 * so a sync resumes cleanly after an interruption without re-applying or skipping changes. Keyed by
 * peer so a node can, in principle, track more than one upstream (only one — main — in the star
 * topology, but the key keeps it honest), and by type because each type pages independently and one
 * type's high-water says nothing about another's.
 *
 * <p>Beside each checkpoint the node keeps the last entry it read at or below it ({@link Mark}): a
 * main restored in place from an older copy keeps its box id and catalog and may grow past the
 * checkpoint again, so how far main's log reaches says nothing about whether it is the log the node
 * read. What sits at the seq the node read does.
 */
public final class SyncState {

  /**
   * How far a node read one type of a peer's log, {@code checkpoint}, and the last entry it read at
   * or below it: {@code seq}, the entity {@code id} and its {@code rev}. A scoped node reads
   * nothing of the heads its scope passes over, so {@code seq} may sit below the checkpoint.
   */
  public record Mark(long checkpoint, long seq, String id, String rev) {
    public Mark {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(rev, "rev");
      if (seq <= 0 || seq > checkpoint) {
        throw new IllegalArgumentException(
            "A mark reads seq " + seq + ", outside its checkpoint " + checkpoint);
      }
    }
  }

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

  /**
   * Forgets how far this node has read {@code peer}'s change log, for every type, so its next round
   * walks every head again: how a project newly in this node's scope, whose history sits below the
   * checkpoints, is paged at all. What the node already holds re-adopts at the same rev, unwritten.
   */
  public void reset(String peer) {
    db.execute(
        "UPDATE sync_state SET checkpoint = 0, read_seq = NULL, read_id = NULL, read_rev = NULL"
            + " WHERE peer = ?",
        peer);
  }

  /** Records {@code id} at {@code rev}, at {@code seq}, as the last entry of the type read. */
  public void read(String peer, String entityType, long seq, String id, String rev) {
    db.execute(
        """
        INSERT INTO sync_state (peer, entity_type, updated_at, read_seq, read_id, read_rev)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(peer, entity_type) DO UPDATE SET read_seq = excluded.read_seq,
            read_id = excluded.read_id, read_rev = excluded.read_rev""",
        peer,
        entityType,
        DateTimeUtils.now().toString(),
        seq,
        id,
        rev);
  }

  /**
   * The mark of every type this node read of {@code peer}'s log and holds a checkpoint over. A type
   * whose checkpoint is 0 is walked from the first head anyway, and one whose read sits past its
   * checkpoint was read by a page whose round never finished, so neither has a mark.
   */
  public Map<String, Mark> marks(String peer) {
    var marks = new LinkedHashMap<String, Mark>();
    db.query(
            """
            SELECT entity_type, checkpoint, read_seq, read_id, read_rev FROM sync_state
            WHERE peer = ? AND read_seq > 0 AND read_seq <= checkpoint ORDER BY entity_type""",
            row ->
                Map.entry(
                    row.text(0),
                    new Mark(row.integer(1), row.integer(2), row.text(3), row.text(4))),
            peer)
        .forEach(entry -> marks.put(entry.getKey(), entry.getValue()));
    return marks;
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
