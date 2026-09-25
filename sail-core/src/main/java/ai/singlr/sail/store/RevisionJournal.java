/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The sync protocol every mutable synced store shares, in one place. Given an {@link EntitySchema}
 * for a particular entity it mints revisions, keeps each row's {@code base_rev} and tombstone
 * bookkeeping, journals every post-state into the {@link ChangeLog} within the mutating transaction
 * (the no-lost-work spine), performs the main-side compare-and-set commit, and resolves a parked
 * conflict by rebasing onto main and writing the chosen state.
 *
 * <p>Extracted from the byte-for-byte copies that lived in {@link SpecStore}, {@link RunStore},
 * {@link ReviewStore}, {@link ProjectStore}, and {@link FileStore}: each now holds one journal and
 * supplies only its schema. Conflict detection ignores the reserved {@code _actor} key, so
 * per-actor attribution rides through every revision without ever causing a false conflict.
 */
public final class RevisionJournal implements ConflictResolver {

  private static final String TOMBSTONE_BASE = "_base_rev";
  private static final String EMPTY_SNAPSHOT = "{}";

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final EntitySchema schema;

  public RevisionJournal(Sqlite db, ChangeLog changeLog, EntitySchema schema) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  /** Every entity id this replica knows of, including those present only as a tombstone. */
  public Set<String> entityIds() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT DISTINCT entity_id FROM change_log WHERE entity_type = ?",
            row -> row.text(0),
            schema.entityType()));
  }

  /**
   * Comparable snapshot of the current state, or null if the entity is absent/deleted. It carries
   * the recorded author as {@code _actor} — the row's own when its schema has an author column,
   * otherwise the journal head's — so every replica records the same author for the revision.
   */
  public Map<String, Object> comparableSnapshot(String id) {
    var map = schema.snapshotMap(id);
    if (map == null) {
      return null;
    }
    var author = changeLog.head(schema.entityType(), id).map(ChangeLog.Entry::actor).orElse(null);
    return authored(schema.comparable(map), author);
  }

  /**
   * Comparable snapshot recorded at a given revision (the merge base), or null if not recorded.
   * Like {@link #comparableSnapshot} it carries that revision's author.
   */
  public Map<String, Object> comparableAtRev(String id, String rev) {
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .at(schema.entityType(), id, rev)
        .map(e -> authored(schema.comparable(YamlUtil.parseMap(e.snapshot())), e.actor()))
        .orElse(null);
  }

  private static Map<String, Object> authored(Map<String, Object> comparable, String author) {
    if (author == null || comparable.containsKey(Snapshots.ACTOR)) {
      return comparable;
    }
    var snapshot = new LinkedHashMap<>(comparable);
    snapshot.put(Snapshots.ACTOR, author);
    return snapshot;
  }

  /** The current revision of a live row, or null if the row is absent. */
  public String revOf(String id) {
    var rev = currentRev(id);
    return rev.isBlank() ? null : rev;
  }

  /** The latest revision recorded for an entity, including a tombstone; null if never recorded. */
  public String latestRev(String id) {
    return changeLog.head(schema.entityType(), id).map(ChangeLog.Entry::rev).orElse(null);
  }

  /**
   * Every id with a change of this box's own that main has not acknowledged: a live row whose rev
   * is not its synced base, plus every entity whose head entry is a locally decided deletion. In
   * the order the rows were written, because a round offers them in this order and a child row must
   * reach main after its parent.
   */
  public Set<String> dirtyIds() {
    var dirty =
        new LinkedHashSet<>(
            db.query(
                """
                SELECT id FROM %s
                WHERE rev IS NULL OR base_rev IS NULL OR base_rev = '' OR rev <> base_rev
                ORDER BY rowid"""
                    .formatted(schema.table()),
                row -> row.text(0)));
    dirty.addAll(changeLog.localTombstones(schema.entityType()));
    return dirty;
  }

  /**
   * The revision this row last synced from main. For a live row it is the {@code base_rev} column;
   * for a locally deleted entity the row is gone, so it is recovered from the {@code _base_rev}
   * embedded in the tombstone — without which a local delete could not be told apart from a
   * delete-vs-edit conflict.
   */
  public String baseRevOf(String id) {
    if (schema.exists(id)) {
      return rawBaseRev(id);
    }
    return changeLog
        .head(schema.entityType(), id)
        .map(tombstone -> Snapshots.text(YamlUtil.parseMap(tombstone.snapshot()), TOMBSTONE_BASE))
        .orElse(null);
  }

  /** Appends a revision for the current state of {@code id}, minting a rev from the counter. */
  public String recordRevision(String id, String origin, boolean deleted) {
    return recordRevision(id, null, null, origin, deleted, false);
  }

  /**
   * Appends a revision for the current state of {@code id}, authored by the bound actor. With
   * {@code explicitRev} null the rev is minted from the current counter; otherwise the
   * caller-supplied rev is used verbatim (sync adopting main's authoritative rev). {@code
   * offeredAuthor} is the {@code _actor} a synced revision carries. {@code setBaseRev} records that
   * this revision is the new synced ancestor — set only when adopting from main, never on a local
   * edit.
   */
  private String recordRevision(
      String id,
      String explicitRev,
      String offeredAuthor,
      String origin,
      boolean deleted,
      boolean setBaseRev) {
    var map = schema.snapshotMap(id);
    if (map == null) {
      return null;
    }
    if (deleted) {
      map.put(TOMBSTONE_BASE, rawBaseRev(id));
    }
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(currentRev(id), snapshot);
    if (!deleted) {
      if (setBaseRev) {
        db.execute(
            "UPDATE " + schema.table() + " SET rev = ?, base_rev = ? WHERE id = ?", rev, rev, id);
      } else {
        db.execute("UPDATE " + schema.table() + " SET rev = ? WHERE id = ?", rev, id);
      }
    }
    changeLog.appendSynced(schema.entityType(), id, rev, offeredAuthor, origin, deleted, snapshot);
    return rev;
  }

  /** Removes the live row of {@code id} for an erasure, which journals itself. */
  public void eraseRow(String id) {
    if (schema.exists(id)) {
      schema.deleteRow(id);
    }
  }

  /**
   * Writes an authoritative state from main at its exact revision (no minting), marking it the new
   * synced ancestor ({@code base_rev = rev}). A null snapshot adopts a deletion. Used by the sync
   * engine; the revision is journaled with origin {@code sync}.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    db.transaction(
        () -> {
          if (snapshot == null) {
            if (schema.exists(id)) {
              recordRevision(id, rev, null, "sync", true, false);
              schema.deleteRow(id);
            } else {
              changeLog.append(schema.entityType(), id, rev, "sync", true, EMPTY_SNAPSHOT);
            }
          } else {
            schema.apply(id, snapshot);
            recordRevision(id, rev, Snapshots.text(snapshot, Snapshots.ACTOR), "sync", false, true);
          }
          return null;
        });
  }

  /**
   * Compare-and-set commit as main: mints a new authoritative rev only if {@code expectedRev} still
   * equals the entity's current rev (a brand-new entity expects {@code null}); otherwise returns
   * {@link PushOutcome.Stale} with main's present state, never overwriting a concurrent change. A
   * null snapshot commits a deletion. The check and the write share one transaction, so two nodes
   * pushing the same row can never both win. Used by the sync engine on the main side.
   */
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return db.transaction(
        () -> {
          if (!Objects.equals(latestRev(id), expectedRev)) {
            return new PushOutcome.Stale(latestRev(id), comparableSnapshot(id));
          }
          if (snapshot == null) {
            if (!schema.exists(id)) {
              return new PushOutcome.Accepted(latestRev(id));
            }
            var rev = recordRevision(id, null, null, "sync", true, false);
            schema.deleteRow(id);
            return new PushOutcome.Accepted(rev);
          }
          schema.apply(id, snapshot);
          return new PushOutcome.Accepted(
              recordRevision(
                  id, null, Snapshots.text(snapshot, Snapshots.ACTOR), "sync", false, false));
        });
  }

  /**
   * Resolves an open conflict locally by rebasing the row onto main's conflicting content {@code
   * remote} — recorded as the new merge base, so the next sync can never re-raise the same conflict
   * (base now equals remote) — and then writing {@code chosen} as the resolved state. When {@code
   * chosen} differs from {@code remote} the row becomes a forward local edit the next sync pushes;
   * when they match the row simply adopts main's value, and the earlier local version is still in
   * the {@link ChangeLog}. A {@code null} side is a deletion. Returns the rev the row now carries.
   * No work is ever lost: every state is journaled. The base is main's revision, adopted as {@link
   * Actor#main()} so it keeps the author main recorded; only {@code chosen} is the resolver's.
   */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote) {
    return db.transaction(
        () -> {
          var baseRev = Actor.call(Actor.main(), () -> adoptBase(id, remote));
          if (sameContent(chosen, remote)) {
            return baseRev;
          }
          return writeChosen(id, chosen);
        });
  }

  private String adoptBase(String id, Map<String, Object> remote) {
    if (remote == null) {
      if (schema.exists(id)) {
        var rev = recordRevision(id, null, null, "sync", true, false);
        schema.deleteRow(id);
        return rev;
      }
      var rev = Revisions.next(currentRev(id), EMPTY_SNAPSHOT);
      changeLog.append(schema.entityType(), id, rev, "sync", true, EMPTY_SNAPSHOT);
      return rev;
    }
    schema.apply(id, remote);
    return recordRevision(id, null, Snapshots.text(remote, Snapshots.ACTOR), "sync", false, true);
  }

  private String writeChosen(String id, Map<String, Object> chosen) {
    if (chosen == null) {
      if (!schema.exists(id)) {
        return latestRev(id);
      }
      var rev = recordRevision(id, null, null, "resolve", true, false);
      schema.deleteRow(id);
      return rev;
    }
    schema.apply(id, chosen);
    return recordRevision(id, null, null, "resolve", false, false);
  }

  private static boolean sameContent(Map<String, Object> a, Map<String, Object> b) {
    if (a == null || b == null) {
      return a == b;
    }
    var keys = new LinkedHashSet<String>();
    keys.addAll(a.keySet());
    keys.addAll(b.keySet());
    return keys.stream()
        .filter(key -> !key.startsWith("_"))
        .allMatch(key -> Objects.equals(a.get(key), b.get(key)));
  }

  private String rawBaseRev(String id) {
    var value =
        db.queryOne(
                "SELECT COALESCE(base_rev, '') FROM " + schema.table() + " WHERE id = ?",
                row -> row.text(0),
                id)
            .orElse("");
    return value.isBlank() ? null : value;
  }

  private String currentRev(String id) {
    return db.queryOne(
            "SELECT COALESCE(rev, '') FROM " + schema.table() + " WHERE id = ?",
            row -> row.text(0),
            id)
        .orElse("");
  }
}
