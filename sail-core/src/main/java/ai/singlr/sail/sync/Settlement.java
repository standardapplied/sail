/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Decidability;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.Standing;
import ai.singlr.sail.store.SyncLimits;
import ai.singlr.sail.store.SyncedStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a node settles an offer that can never be decided ({@link Decidability}): a born-in spec
 * whose room alone is gone is re-homed into its own identity room, under its own author, since main
 * never took it; an edit of something main holds is reverted to main's version; anything else is
 * withdrawn as a denial is settled, kept in the change log under a {@linkplain
 * ChangeLog.Entry#DENIED withdrawal}. Work still live here ({@link SyncedStore#live}) is left to
 * its pipeline, and an offer whose answer may merely be lost ({@link ChangeLog#offer}) to the
 * round's recovery. Each settlement is its own write transaction, so a replacement landing in
 * between is judged by the next round, and one offer that cannot be settled is reported and never
 * blocks the rest.
 */
public final class Settlement {

  /** One offer settled, and how: re-homed, reverted or withdrawn — or why it could not be. */
  public record Settled(String type, String id, String how) {
    public Settled {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(how, "how");
    }

    /** One line for the round's notices. */
    public String describe() {
      return type + " " + id + ": " + how;
    }
  }

  static final String RE_HOMED = "re-homed into its own room, its room being gone; offered next";
  static final String REVERTED = "reverted to the version main holds, its dependency being gone";
  static final String WITHDRAWN =
      "withdrawn, its dependency being gone; yours stays in its history";

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final Decidability rule;
  private final String boxHandle;

  /** Settlement on the node whose database is {@code db} and whose FDE is {@code boxHandle}. */
  public Settlement(Sqlite db, Decidability rule, String boxHandle) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = new ChangeLog(db);
    this.rule = Objects.requireNonNull(rule, "rule");
    this.boxHandle = boxHandle;
  }

  /**
   * Settles every unacknowledged offer whose dependency is gone, type by type in registry order,
   * and withdraws every outstanding offer of a file above main's ceiling ({@link SyncLimits}), so
   * an oversized file never breaks the channel mid-upload. Returns what it settled.
   */
  public List<Settled> settle() {
    var settled = new ArrayList<Settled>();
    for (var entity : SyncedEntities.all()) {
      var store = entity.store(db);
      for (var id : List.copyOf(store.dirtyIds())) {
        settleIfGone(entity.type(), id, store).ifPresent(settled::add);
      }
    }
    var ceiling = new SyncLimits(db).mainFileMax();
    for (var id : new FileStore(db).offersAbove(ceiling)) {
      settled.add(settleGone(Erasure.FILE, id));
    }
    return settled;
  }

  private Optional<Settled> settleIfGone(String type, String id, SyncedStore store) {
    try {
      return db.transaction(
          () -> {
            if (store.live(id, boxHandle) || changeLog.offer(type, id).isPresent()) {
              return Optional.empty();
            }
            var standing = rule.standing(type, id, store.currentForSync(id), boxHandle);
            if (!(standing instanceof Standing.Gone)) {
              return Optional.empty();
            }
            return Optional.of(settleGone(type, id));
          });
    } catch (RuntimeException e) {
      return Optional.of(new Settled(type, id, "could not be settled: " + e.getMessage()));
    }
  }

  private Settled settleGone(String type, String id) {
    var store = SyncedEntities.require(type).store(db);
    return db.transaction(
        () -> {
          var snapshot = store.currentForSync(id);
          var base = store.baseRevOf(id);
          if (Strings.isNotBlank(base)) {
            return new Settled(type, id, revertToBase(id, store, base));
          }
          if (rule.onlyRoomGone(type, id, snapshot)) {
            var author = Snapshots.text(snapshot, Snapshots.ACTOR);
            Actor.run(Actor.main(author), () -> new SpecStore(db).reHomeToOwnRoom(id));
            return new Settled(type, id, RE_HOMED);
          }
          Actor.run(Actor.main(), () -> store.adoptForSync(id, null, null));
          return new Settled(type, id, WITHDRAWN);
        });
  }

  /**
   * Main holds the entity at {@code base}: the row goes back to that version, or to main's deletion
   * when the base is the tombstone the row was written over.
   */
  private String revertToBase(String id, SyncedStore store, String base) {
    var held = store.comparableAtRev(id, base);
    if (held == null) {
      var author =
          changeLog
              .latestTombstone(store.entityType(), id)
              .map(ChangeLog.Entry::actor)
              .orElse(null);
      Actor.run(Actor.main(author), () -> store.adoptForSync(id, null, base));
      return WITHDRAWN;
    }
    var author = Snapshots.text(held, Snapshots.ACTOR);
    Actor.run(Actor.main(author), () -> store.applyRevision(id, held, base));
    return REVERTED;
  }
}
