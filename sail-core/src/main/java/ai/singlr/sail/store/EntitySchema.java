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
 * table (which must carry {@code id}, {@code rev}, and {@code base_rev} columns), how a row
 * projects to and from a snapshot, and who a local revision is attributed to.
 *
 * <p>Implemented by the five mutable synced stores (specs, runs, reviews, projects, files).
 * Immutable or specially-authorized entities (e.g. messages) keep their own bespoke logic and do
 * not ride this journal.
 */
public interface EntitySchema {

  /** The {@code change_log.entity_type} discriminator for this entity, e.g. {@code "spec"}. */
  String entityType();

  /** The row table, which must have {@code id}, {@code rev}, and {@code base_rev} columns. */
  String table();

  /** Whether a live row exists for {@code id} (a tombstone is not a live row). */
  boolean exists(String id);

  /** The full current snapshot of {@code id} as a JSON-serializable map, or null if absent. */
  Map<String, Object> snapshotMap(String id);

  /**
   * The author a <em>local</em> revision of {@code id} is attributed to in the journal — the row's
   * own last writer (e.g. a spec's {@code updated_by}, a run's node). Null when the entity carries
   * no per-row author.
   */
  String author(String id);

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
}
