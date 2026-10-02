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
import java.util.Optional;
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

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final EntitySchema schema;

  public RevisionJournal(Sqlite db, ChangeLog changeLog, EntitySchema schema) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  /** The schema's {@link EntitySchema#latestWinsFields}, the one place a store's are declared. */
  public Set<String> latestWinsFields() {
    return schema.latestWinsFields();
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
   * #comparableSnapshot} it carries that revision's author. The latest entry at the rev, because a
   * box adopts its own accepted offer at main's rev before it hears what main made of it, and the
   * base is what it last heard.
   */
  public Map<String, Object> comparableAtRev(String id, String rev) {
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .latestAt(schema.entityType(), id, rev)
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
   * The revision this row last synced from main. For a live row main has acknowledged it is the
   * {@code base_rev} column. Otherwise it is recovered from the entity's latest tombstone: the base
   * a local delete was made from, embedded in it as {@code _base_rev}, without which it could not
   * be told apart from a delete-vs-edit conflict, or, for a deletion adopted from main, the
   * tombstone itself. A live row written over that tombstone, re-created or restored and not yet
   * acknowledged, descends from the same base, so it is offered as the change it is rather than
   * conflict with what it was written over; its {@code base_rev} stays empty until main takes it,
   * which is how every store tells a row main holds from one it does not.
   */
  public String baseRevOf(String id) {
    var base = rawBaseRev(id);
    return base != null ? base : tombstoneBase(id);
  }

  /** {@link SyncedStore#liveBase}: the live row's {@code base_rev} as written. */
  public Optional<String> liveBase(String id) {
    return schema.exists(id) ? Optional.of(column("base_rev", id)) : Optional.empty();
  }

  /**
   * What this box holds of {@code id} as a replica reports it: its comparable snapshot, or, for a
   * deleted entity whose tombstone carries marks, those marks under the tombstone's author, so a
   * peer still holding the entity adopts the marked deletion instead of pushing its copy back and a
   * node adopting it holds the same marked deletion by the same author. Null for a plain deletion.
   */
  public Map<String, Object> currentForSync(String id) {
    var live = comparableSnapshot(id);
    if (live != null) {
      return live;
    }
    return changeLog
        .head(schema.entityType(), id)
        .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
        .flatMap(
            head ->
                Optional.of(schema.marks(YamlUtil.parseMap(head.snapshot())))
                    .filter(marks -> !marks.isEmpty())
                    .map(marks -> authored(marks, head.actor())))
        .orElse(null);
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
    return appendTombstone(id, null, origin, baseRevOf(id), marks, null);
  }

  /**
   * Appends a revision for the current live state of {@code id}, authored by the bound actor, and
   * returns its rev. With {@code explicitRev} null the rev is minted from the entity's latest
   * entry, tombstones included; otherwise the caller-supplied rev is used verbatim (sync adopting
   * main's authoritative rev). {@code offeredAuthor} is the {@code _actor} a synced revision
   * carries. {@code adopted} records that this revision is the new synced ancestor — set only when
   * adopting from main, never on a local edit. A row written over a tombstone, re-created or
   * restored, descends from the base that tombstone records ({@link #baseRevOf}), like any revision
   * after it, and keeps the counter going.
   */
  private String recordLive(
      String id, String explicitRev, String offeredAuthor, String origin, boolean adopted) {
    var map = schema.snapshotMap(id);
    if (map == null) {
      return null;
    }
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(latestRev(id), snapshot);
    var base = adopted ? rev : rawBaseRev(id);
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
    return Snapshots.isDeletion(snapshot) ? null : snapshot;
  }

  private Map<String, Object> marksOf(Map<String, Object> deletion) {
    return deletion == null ? Map.of() : schema.marks(deletion);
  }

  private static String authorOf(Map<String, Object> version) {
    return version == null ? null : Snapshots.text(version, Snapshots.ACTOR);
  }

  /**
   * The base the entity's latest tombstone records: a deletion adopted from main is its own merge
   * base — this box holds main's deletion, not one of its own to offer — a deletion this box
   * decided keeps the base it was made from, and a withdrawal of what main never held records none,
   * since main holds nothing to descend from. Null when the entity has none, or was erased since.
   */
  private String tombstoneBase(String id) {
    return changeLog
        .latestTombstone(schema.entityType(), id)
        .map(
            tombstone ->
                tombstone.heardFromMain()
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
   * Snapshots#isDeletion}) adopts it, recording its marks under the author it names, and the
   * tombstone is the new merge base ({@link #baseRevOf}), so a restore main makes later is pulled,
   * never undone. A deletion at no revision is main holding nothing of the entity: this box
   * withdraws its work, journaled as {@link ChangeLog.Entry#DENIED} so the withdrawal is never
   * mistaken for a deletion heard from main, yet never offered. Used by the sync engine; a version
   * of main's is journaled with origin {@code sync}.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    db.transaction(
        () -> {
          if (Snapshots.isDeletion(snapshot)) {
            var origin = rev == null ? ChangeLog.Entry.DENIED : ChangeLog.Entry.SYNC;
            appendTombstone(id, rev, origin, null, marksOf(snapshot), authorOf(snapshot));
            eraseRow(id);
          } else {
            schema.apply(id, snapshot);
            recordLive(id, rev, authorOf(snapshot), ChangeLog.Entry.SYNC, true);
          }
          return null;
        });
  }

  /**
   * Adopts {@code accepted}, main's version of {@code id} at {@code rev} that it took from this box
   * after the base held here — main took this box's offer and its answer never came back — as the
   * synced base. Main decides it is later than that base, in its own change log ({@link
   * ai.singlr.sail.sync.MainReplica#acceptedFrom}); a rev this box already holds as its base
   * changes nothing. The edits made here since the offer ({@link #offerAnswered}) are journaled on
   * top of it as the change main has not taken yet, under the author the latest entry already
   * names, so the round reconciles three-way against exactly what main took ({@link #rebased}); an
   * edit that clashes with a change of main's leaves the base where it is, and the round parks the
   * conflict. The offer is settled either way, and the base is adopted as the bound actor. A rev
   * this box already holds as its base changes nothing, and touches no offer: the row is done with
   * it. Returns whether it moved.
   */
  public boolean acknowledge(String id, Map<String, Object> accepted, String rev) {
    return db.transaction(
        () -> {
          if (rev.equals(baseRevOf(id))) {
            return false;
          }
          var offer = offerAnswered(id, accepted);
          changeLog.settleOffer(schema.entityType(), id);
          var now = currentForSync(id);
          if (offer.isEmpty() && !sameContent(now, accepted)) {
            return false;
          }
          var from = offer.isPresent() ? offer.get().from() : now;
          var took = offer.isPresent() ? tookOf(from, accepted) : accepted;
          var rebase =
              ConflictDetector.detect(
                  live(from),
                  live(now),
                  live(took),
                  schema.latestWinsFields(),
                  schema.fieldMerger());
          if (rebase instanceof ConflictDetector.Conflict) {
            return false;
          }
          var mine = rebased(rebase, now, took);
          var author = changeLog.head(schema.entityType(), id).map(ChangeLog.Entry::actor);
          applyRevision(id, took, rev);
          if (!sameContent(mine, took)) {
            Actor.run(
                Actor.main(author.orElse(null)), () -> write(id, mine, ChangeLog.Entry.LOCAL));
          }
          return true;
        });
  }

  /**
   * The record of the offer main answers with {@code accepted}, the state it was made from included
   * ({@link ChangeLog#recordOffer}). A record is trusted only when {@code accepted} is the version
   * it offered: main answers the latest version it took from this box, which is an earlier offer's
   * when the recorded one never reached it. Empty when there is none, or the box kept none: then
   * the row is acknowledged only when it is what main took, the one case where what main merged
   * into it and what this box changed since need no telling apart.
   */
  private Optional<ChangeLog.Offer> offerAnswered(String id, Map<String, Object> accepted) {
    return changeLog
        .offer(schema.entityType(), id)
        .filter(
            offer ->
                Snapshots.isDeletion(offer.offered())
                    ? accepted == null
                    : sameContent(offer.offered(), accepted));
  }

  /**
   * What main took of an offer made from {@code offeredFrom} when it answers {@code accepted}: a
   * deletion offered with marks, when main answers a deletion, otherwise its answer.
   */
  private Map<String, Object> tookOf(
      Map<String, Object> offeredFrom, Map<String, Object> accepted) {
    return accepted == null && offeredFrom != null && Snapshots.isDeletion(offeredFrom)
        ? marksOf(offeredFrom)
        : accepted;
  }

  /**
   * What this box holds once {@code rebase}, the three-way reading of what it holds now against the
   * offer and {@code took}, what main took of it, is applied: the edits made since the offer on top
   * of main's version, either side when only one moved ({@code null} a deletion). A clash is never
   * rebased; the round parks it.
   */
  private static Map<String, Object> rebased(
      ConflictDetector.Resolution rebase, Map<String, Object> now, Map<String, Object> took) {
    return switch (rebase) {
      case ConflictDetector.Merged merged -> merged.result();
      case ConflictDetector.TakeRemote ignored -> took;
      case ConflictDetector.KeepLocal ignored -> now;
      case ConflictDetector.Converged ignored -> now;
      case ConflictDetector.Conflict clash ->
          throw new IllegalStateException("A clash on " + clash.fields() + " parks, never rebases");
    };
  }

  /**
   * Compare-and-set commit as main: mints a new authoritative rev only if {@code expectedRev} still
   * equals the entity's current rev (a brand-new entity expects {@code null}); otherwise returns
   * {@link PushOutcome.Stale} with main's present state, never overwriting a concurrent change. A
   * snapshot that stands for a deletion ({@link Snapshots#isDeletion}) commits it, its tombstone
   * recording the marks it carries under the author it names, which {@code authority} checks as it
   * does a live revision's; a marked deletion is recorded even of an entity main holds no row of,
   * so the marks reach every box, and an unmarked one of such an entity changes nothing. Once the
   * offer is current, {@code authority} decides it for the bound actor before anything is written,
   * and a refusal is {@link PushOutcome.Denied} with main's present state; a read-only role's
   * content is never uploaded, so it is decided before the content an accepted revision needs is
   * required. The check, the decision and the write share one transaction, so two nodes pushing the
   * same row can never both win. Used by the sync engine on the main side.
   */
  public PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority) {
    return db.transaction(
        () -> {
          if (!Objects.equals(latestRev(id), expectedRev)) {
            return new PushOutcome.Stale(latestRev(id), currentForSync(id));
          }
          var deletion = Snapshots.isDeletion(snapshot);
          var marks = marksOf(snapshot);
          if (deletion && marks.isEmpty() && !schema.exists(id)) {
            return new PushOutcome.Accepted(latestRev(id));
          }
          var refusal = authority.decide(Actor.current(), id, held(id), snapshot);
          if (refusal.isPresent()) {
            return new PushOutcome.Denied(
                refusal.get().message(), latestRev(id), currentForSync(id));
          }
          if (deletion) {
            var rev =
                appendTombstone(
                    id, null, ChangeLog.Entry.SYNC, rawBaseRev(id), marks, authorOf(snapshot));
            eraseRow(id);
            return new PushOutcome.Accepted(rev);
          }
          schema.apply(id, snapshot);
          return new PushOutcome.Accepted(
              recordLive(id, null, authorOf(snapshot), ChangeLog.Entry.SYNC, false));
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
    if (Snapshots.isDeletion(state)) {
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
        .filter(key -> !ConflictDetector.isMetadata(key))
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
