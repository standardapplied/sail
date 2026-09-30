/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

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
  private static final String SYNC = "sync";

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
   * Comparable snapshot recorded at a given revision (the merge base), or null if not recorded or
   * recorded as a deletion: a tombstone's merge base is the entity's absence. Like {@link
   * #comparableSnapshot} it carries that revision's author.
   */
  public Map<String, Object> comparableAtRev(String id, String rev) {
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .at(schema.entityType(), id, rev)
        .filter(e -> !e.deleted())
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
                SELECT %s FROM %s
                WHERE rev IS NULL OR base_rev IS NULL OR base_rev = '' OR rev <> base_rev
                ORDER BY rowid"""
                    .formatted(schema.key(), schema.table()),
                row -> row.text(0)));
    dirty.addAll(changeLog.localTombstones(schema.entityType()));
    return dirty;
  }

  /**
   * The revision this row last synced from main. For a live row it is the {@code base_rev} column;
   * for a deleted entity the row is gone, so it is recovered from the {@code _base_rev} embedded in
   * the tombstone — the base a local delete was made from, without which it could not be told apart
   * from a delete-vs-edit conflict, or, for a deletion adopted from main, the tombstone itself.
   */
  public String baseRevOf(String id) {
    if (schema.exists(id)) {
      return rawBaseRev(id);
    }
    return tombstoneBase(id);
  }

  /** Appends a revision for the current state of {@code id}, minting a rev from the counter. */
  public String recordRevision(String id, String origin, boolean deleted) {
    return deleted
        ? recordTombstone(id, origin, Map.of())
        : recordLive(id, null, null, origin, false);
  }

  /**
   * Appends this box's own deletion of {@code id}, minting a rev from the counter, its tombstone
   * keeping the last state, the base the deletion was made from and {@code marks}, reserved keys a
   * store records beside them. The caller removes the live row in the same transaction.
   */
  public String recordTombstone(String id, String origin, Map<String, Object> marks) {
    return appendTombstone(id, null, origin, rawBaseRev(id), marks, null);
  }

  /**
   * Appends a revision for the current live state of {@code id}, authored by the bound actor, and
   * returns its rev. With {@code explicitRev} null the rev is minted from the entity's latest
   * entry, tombstones included; otherwise the caller-supplied rev is used verbatim (sync adopting
   * main's authoritative rev). {@code offeredAuthor} is the {@code _actor} a synced revision
   * carries. {@code adopted} records that this revision is the new synced ancestor — set only when
   * adopting from main, never on a local edit. A row written over a tombstone, re-created or
   * restored, descends from the base that tombstone records, like any revision after it.
   */
  private String recordLive(
      String id, String explicitRev, String offeredAuthor, String origin, boolean adopted) {
    var map = schema.snapshotMap(id);
    if (map == null) {
      return null;
    }
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(latestRev(id), snapshot);
    var base = adopted ? rev : heldBase(id);
    db.execute(
        "UPDATE %s SET rev = ?, base_rev = ? WHERE %s = ?".formatted(schema.table(), schema.key()),
        rev,
        base,
        id);
    changeLog.appendSynced(schema.entityType(), id, rev, offeredAuthor, origin, false, snapshot);
    return rev;
  }

  /**
   * Appends a tombstone of {@code id} at {@code explicitRev}, or a rev minted from the counter,
   * keeping the last live state when a row is present, {@code base} as the base it descends from
   * and {@code marks}. Authored by {@code offeredAuthor} where the bound actor honours one ({@link
   * Actor#authorOf}), otherwise by the bound actor.
   */
  private String appendTombstone(
      String id,
      String explicitRev,
      String origin,
      String base,
      Map<String, Object> marks,
      String offeredAuthor) {
    var map =
        Objects.requireNonNullElseGet(schema.snapshotMap(id), LinkedHashMap<String, Object>::new);
    map.put(TOMBSTONE_BASE, base);
    map.putAll(marks);
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(latestRev(id), snapshot);
    changeLog.appendSynced(schema.entityType(), id, rev, offeredAuthor, origin, true, snapshot);
    return rev;
  }

  /** {@code snapshot} as the three-way merge reads it: a marked deletion is absence. */
  private Map<String, Object> live(Map<String, Object> snapshot) {
    return schema.isDeletion(snapshot) ? null : snapshot;
  }

  private static Map<String, Object> marksOf(Map<String, Object> deletion) {
    var marks = new LinkedHashMap<String, Object>();
    if (deletion != null) {
      deletion.forEach(
          (key, value) -> {
            if (!Snapshots.ACTOR.equals(key)) {
              marks.put(key, value);
            }
          });
    }
    return marks;
  }

  private static String authorOf(Map<String, Object> version) {
    return version == null ? null : Snapshots.text(version, Snapshots.ACTOR);
  }

  /** The base the live row of {@code id} descends from, or the tombstone it was written over. */
  private String heldBase(String id) {
    var base = rawBaseRev(id);
    return base != null ? base : tombstoneBase(id);
  }

  /**
   * The base a tombstone at the head of {@code id} records: a deletion adopted from main is its own
   * merge base — this box holds main's deletion, not one of its own to offer — and a deletion this
   * box decided keeps the base it was made from. Null when the head is no tombstone.
   */
  private String tombstoneBase(String id) {
    return changeLog
        .head(schema.entityType(), id)
        .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
        .map(
            tombstone ->
                SYNC.equals(tombstone.origin())
                    ? tombstone.rev()
                    : Snapshots.text(YamlUtil.parseMap(tombstone.snapshot()), TOMBSTONE_BASE))
        .orElse(null);
  }

  /** Removes the live row of {@code id} for an erasure, which journals itself. */
  public void eraseRow(String id) {
    if (schema.exists(id)) {
      schema.deleteRow(id);
    }
  }

  /**
   * Writes an authoritative state from main at its exact revision (no minting), marking it the new
   * synced ancestor ({@code base_rev = rev}). A snapshot that stands for a deletion ({@link
   * EntitySchema#isDeletion}) adopts it, recording its marks under the author it names, and the
   * tombstone is the new merge base ({@link #baseRevOf}), so a restore main makes later is pulled,
   * never undone. Used by the sync engine; the revision is journaled with origin {@code sync}.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    db.transaction(
        () -> {
          if (schema.isDeletion(snapshot)) {
            appendTombstone(id, rev, SYNC, null, marksOf(snapshot), authorOf(snapshot));
            eraseRow(id);
          } else {
            schema.apply(id, snapshot);
            recordLive(id, rev, authorOf(snapshot), SYNC, true);
          }
          return null;
        });
  }

  /**
   * Adopts {@code accepted}, main's version of {@code id} at {@code rev} that it took from this box
   * after the base held here — main took this box's offer and its answer never came back — as the
   * synced base. Main decides it is later than that base, in its own change log ({@link
   * ai.singlr.sail.sync.MainReplica#acceptedFrom}); a rev this box already holds as its base
   * changes nothing. What {@code current} reads now is rebased from {@code offeredFrom}, the state
   * the offer was made from, onto {@code accepted}, which may carry main's changes the offer merged
   * in: the edits made here since are journaled on top as the change main has not taken yet,
   * recorded under the author its latest entry already names, so the round reconciles it three-way
   * against exactly what main took and the author main records is the one who wrote it. An edit
   * since the offer that clashes with a change of main's leaves the base where it is, so the round
   * parks the conflict. The base is adopted as the bound actor. Returns whether it moved.
   */
  public boolean acknowledge(
      String id,
      Map<String, Object> offeredFrom,
      Map<String, Object> accepted,
      String rev,
      Supplier<Map<String, Object>> current,
      Set<String> latestWins) {
    return db.transaction(
        () -> {
          if (rev.equals(baseRevOf(id))) {
            return false;
          }
          var now = current.get();
          var rebase =
              ConflictDetector.detect(live(offeredFrom), live(now), live(accepted), latestWins);
          if (rebase instanceof ConflictDetector.Conflict) {
            return false;
          }
          var mine =
              switch (rebase) {
                case ConflictDetector.Merged merged -> merged.result();
                case ConflictDetector.TakeRemote ignored -> accepted;
                default -> now;
              };
          var author = changeLog.head(schema.entityType(), id).map(ChangeLog.Entry::actor);
          applyRevision(id, accepted, rev);
          if (!sameContent(mine, accepted)) {
            Actor.run(Actor.main(author.orElse(null)), () -> write(id, mine, "local"));
          }
          return true;
        });
  }

  /**
   * Compare-and-set commit as main: mints a new authoritative rev only if {@code expectedRev} still
   * equals the entity's current rev (a brand-new entity expects {@code null}); otherwise returns
   * {@link PushOutcome.Stale} with main's present state, never overwriting a concurrent change. A
   * null snapshot commits a deletion. Once the offer is current, {@code authority} decides it for
   * the bound actor before anything is written, and a refusal is {@link PushOutcome.Denied} with
   * main's present state; a read-only role's content is never uploaded, so it is decided before the
   * content an accepted revision needs is required. The check, the decision and the write share one
   * transaction, so two nodes pushing the same row can never both win. Used by the sync engine on
   * the main side.
   */
  public PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority) {
    return commitRevision(id, snapshot, expectedRev, authority, Map.of());
  }

  /**
   * As {@link #commitRevision(String, Map, String, WriteAuthority)}, a deletion's tombstone
   * recording {@code marks} beside its base. A marked deletion is recorded even of an entity main
   * holds no row of, so the marks reach every box; an unmarked one of such an entity changes
   * nothing.
   */
  public PushOutcome commitRevision(
      String id,
      Map<String, Object> snapshot,
      String expectedRev,
      WriteAuthority authority,
      Map<String, Object> marks) {
    return db.transaction(
        () -> {
          if (!Objects.equals(latestRev(id), expectedRev)) {
            return new PushOutcome.Stale(latestRev(id), comparableSnapshot(id));
          }
          if (snapshot == null && marks.isEmpty() && !schema.exists(id)) {
            return new PushOutcome.Accepted(latestRev(id));
          }
          var refusal = authority.decide(Actor.current(), id, held(id), snapshot);
          if (refusal.isPresent()) {
            return new PushOutcome.Denied(
                refusal.get().message(), latestRev(id), comparableSnapshot(id));
          }
          if (snapshot == null) {
            var rev = appendTombstone(id, null, SYNC, rawBaseRev(id), marks, null);
            eraseRow(id);
            return new PushOutcome.Accepted(rev);
          }
          schema.apply(id, snapshot);
          return new PushOutcome.Accepted(recordLive(id, null, authorOf(snapshot), SYNC, false));
        });
  }

  /**
   * What this box holds of {@code id}, as a rule reads it: its comparable snapshot, or for an
   * entity whose latest word is a tombstone the last live state the tombstone kept, so a restore is
   * decided against what it restores. Null for an entity this box never held.
   */
  public Map<String, Object> held(String id) {
    var live = comparableSnapshot(id);
    if (live != null) {
      return live;
    }
    return changeLog
        .head(schema.entityType(), id)
        .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
        .map(head -> authored(schema.comparable(YamlUtil.parseMap(head.snapshot())), head.actor()))
        .orElse(null);
  }

  /**
   * Resolves an open conflict by adopting {@code theirs}, main's side as the conflict recorded it,
   * at main's rev and under the author main recorded, as the new merge base — exactly as a pull
   * would have, so the next round can never re-raise the same conflict — and then writing {@code
   * chosen} over it as this box's own revision when it differs. Taking main's side is therefore
   * main's revision itself; keeping or merging is a forward local edit the next round offers. A
   * {@code null} side is a deletion. Returns the rev the row now carries. Every state stays in the
   * {@link ChangeLog}, so no choice loses work.
   */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, MainVersion theirs) {
    Strings.requireNonBlank(theirs.rev(), "The revision of main's side");
    return db.transaction(
        () -> {
          Actor.run(
              Actor.main(theirs.author()),
              () -> applyRevision(id, theirs.snapshot(), theirs.rev()));
          return sameContent(chosen, theirs.snapshot())
              ? theirs.rev()
              : write(id, chosen, "resolve");
        });
  }

  /** Writes {@code state} as this box's own change of {@code id} ({@code null} deletes it). */
  private String write(String id, Map<String, Object> state, String origin) {
    if (schema.isDeletion(state)) {
      var marks = marksOf(state);
      if (!schema.exists(id) && marks.isEmpty()) {
        return latestRev(id);
      }
      var rev = recordTombstone(id, origin, marks);
      eraseRow(id);
      return rev;
    }
    schema.apply(id, state);
    return recordLive(id, null, null, origin, false);
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
    var value = column("base_rev", id);
    return value.isBlank() ? null : value;
  }

  private String currentRev(String id) {
    return column("rev", id);
  }

  private String column(String column, String id) {
    return db.queryOne(
            "SELECT COALESCE(%s, '') FROM %s WHERE %s = ?"
                .formatted(column, schema.table(), schema.key()),
            row -> row.text(0),
            id)
        .orElse("");
  }
}
