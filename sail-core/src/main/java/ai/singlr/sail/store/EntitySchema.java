/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Map;

/**
 * The store-specific half of a synced entity, supplied to {@link RevisionJournal}. The journal owns
 * the sync <em>protocol</em> — rev minting, {@code base_rev}/tombstone bookkeeping, the
 * compare-and-set commit, and three-way conflict resolution — identically for every mutable synced
 * store; this strategy supplies the handful of things that genuinely differ: the entity's name, its
 * table (which must carry its key, {@code rev}, and {@code base_rev} columns), and how a row
 * projects to and from a snapshot. Who a revision is attributed to is the bound {@link
 * ai.singlr.sail.identity.Actor}, never the row.
 *
 * <p>Implemented by the six mutable synced stores that ride the journal: specs, rooms, runs,
 * reviews, files and projects, whose rename's tombstone carries a resurrection-blocking mark.
 * Messages, which never change, keep their own commit and do not ride it.
 */
public interface EntitySchema {

  /** The {@code change_log.entity_type} discriminator for this entity, e.g. {@code "spec"}. */
  String entityType();

  /** The row table, which must have its {@link #key}, {@code rev}, and {@code base_rev} columns. */
  String table();

  /**
   * The column of {@link #table} naming an entity's id: {@code id} unless a store names another.
   */
  default String key() {
    return "id";
  }

  /** Whether a live row exists for {@code id} (a tombstone is not a live row). */
  boolean exists(String id);

  /** The full current snapshot of {@code id} as a JSON-serializable map, or null if absent. */
  Map<String, Object> snapshotMap(String id);

  /**
   * Upserts {@code id} from an authoritative {@code snapshot}, injecting the surrogate key and
   * resolving the snapshot's {@code _actor} into the row's own author field. Runs inside the
   * journal's transaction.
   */
  void apply(String id, Map<String, Object> snapshot);

  /**
   * Projects a full snapshot onto the subset that carries an FDE's actual work — the fields
   * conflict detection compares — plus the reserved {@code _actor} attribution key, which {@link
   * ConflictDetector} ignores. Excludes surrogate keys and timestamps so two boxes never falsely
   * conflict on metadata.
   */
  Map<String, Object> comparable(Map<String, Object> full);

  /**
   * Deletes the live row for {@code id} (and any child rows). Runs inside the journal's
   * transaction.
   */
  void deleteRow(String id);

  /**
   * Whether {@code snapshot}, a version main sent, stands for a deletion: null always does, and a
   * store whose tombstones carry marks sends a marked tombstone as those marks — a project rename's
   * resurrection block. The journal adopts one as a tombstone recording them.
   */
  default boolean isDeletion(Map<String, Object> snapshot) {
    return snapshot == null;
  }
}
