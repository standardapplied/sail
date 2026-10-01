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
import java.util.function.Supplier;

/**
 * How a node settles an offer that can never be decided ({@link Decidability}): a born-in spec
 * whose room alone is gone is re-homed into its own identity room, under its own author, since main
 * never took it; an edit of something main holds is reverted to main's version; anything else is
 * withdrawn as a denial is settled, kept in the change log under a {@linkplain
 * ChangeLog.Entry#DENIED withdrawal}. An outstanding offer of a file above main's ceiling ({@link
 * SyncLimits}) is settled the same way, so an oversized file never breaks the channel mid-upload.
 * Work still live here ({@link SyncedStore#live}) is left to its pipeline, and an offer whose
 * answer may merely be lost ({@link ChangeLog#offer}) to the round's recovery. Each settlement is
 * its own write transaction, so a replacement landing in between is judged by the next round, and
 * one offer that cannot be settled is reported and never blocks the rest.
 */
public final class Settlement {

  /** What became of a settled offer. */
  public sealed interface How {
    /** A born-in spec moved into its own room, to be offered next round. */
    record ReHomed() implements How {}

    /** Reverted to the version main holds. */
    record Reverted() implements How {}

    /** Withdrawn, the work kept in the change log. */
    record Withdrawn() implements How {}

    /** Could not be settled; {@code reason} says why, and the next round tries again. */
    record Failed(String reason) implements How {
      public Failed {
        Objects.requireNonNull(reason, "reason");
      }
    }

    /** One phrase for the round's notices. */
    default String describe() {
      return switch (this) {
        case ReHomed ignored -> "re-homed into its own room, its room being gone; offered next";
        case Reverted ignored -> "reverted to the version main holds";
        case Withdrawn ignored -> "withdrawn; yours stays in this box's change log";
        case Failed failed -> "could not be settled: " + failed.reason();
      };
    }
  }

  /** One offer settled, and {@linkplain How how}, with why it had to be. */
  public record Settled(String type, String id, How how, String why) {
    public Settled {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(how, "how");
      why = Objects.requireNonNullElse(why, "");
    }

    /** One line for the round's notices. */
    public String describe() {
      return type + " " + id + ": " + how.describe() + (why.isBlank() ? "" : " — " + why);
    }
  }

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
   * then every outstanding offer of a file above main's ceiling. Returns what it settled.
   */
  public List<Settled> settle() {
    var settled = new ArrayList<Settled>();
    for (var entity : SyncedEntities.all()) {
      var store = entity.store(db);
      for (var id : List.copyOf(store.dirtyIds())) {
        settle(entity.type(), id, store, () -> gone(entity.type(), id, store))
            .ifPresent(settled::add);
      }
    }
    var files = new FileStore(db);
    var ceiling = new SyncLimits(db).mainFileMax();
    for (var id : files.offersAbove(ceiling)) {
      settle(Erasure.FILE, id, files, () -> Optional.of(aboveCeiling(ceiling)))
          .ifPresent(settled::add);
    }
    return settled;
  }

  private Optional<String> gone(String type, String id, SyncedStore store) {
    return rule.standing(type, id, store.currentForSync(id), boxHandle)
            instanceof Standing.Gone gone
        ? Optional.of(gone.reason())
        : Optional.empty();
  }

  private static String aboveCeiling(long ceiling) {
    return "above main's limits.file_max (" + ceiling + " bytes)";
  }

  /**
   * Settles {@code id} when {@code cause} finds a reason to, in one write transaction with the
   * judgement, unless the work is still live here or its offer is still recorded, its answer
   * perhaps merely lost ({@link NodeRound#begin} asks main about an oversized one first).
   */
  private Optional<Settled> settle(
      String type, String id, SyncedStore store, Supplier<Optional<String>> cause) {
    try {
      return db.transaction(
          () -> {
            if (store.live(id, boxHandle) || changeLog.offer(type, id).isPresent()) {
              return Optional.empty();
            }
            return cause
                .get()
                .map(
                    why -> {
                      changeLog.settleOffer(type, id);
                      return new Settled(type, id, settleGone(type, id, store), why);
                    });
          });
    } catch (RuntimeException e) {
      var reason = Objects.requireNonNullElse(e.getMessage(), e.toString());
      return Optional.of(new Settled(type, id, new How.Failed(reason), ""));
    }
  }

  private How settleGone(String type, String id, SyncedStore store) {
    var snapshot = store.currentForSync(id);
    var base = store.baseRevOf(id);
    if (Strings.isNotBlank(base)) {
      return revertToBase(id, store, base);
    }
    if (rule.onlyRoomGone(type, id, snapshot)) {
      var author = Snapshots.text(snapshot, Snapshots.ACTOR);
      Actor.run(Actor.main(author), () -> new SpecStore(db).reHomeToOwnRoom(id));
      return new How.ReHomed();
    }
    Actor.run(Actor.main(null), () -> store.adoptForSync(id, null, null));
    return new How.Withdrawn();
  }

  /**
   * Main holds the entity at {@code base}: the row goes back to that version, or to main's deletion
   * when the base is the tombstone the row was written over.
   */
  private How revertToBase(String id, SyncedStore store, String base) {
    var held = store.comparableAtRev(id, base);
    if (held == null) {
      var author =
          changeLog
              .latestTombstone(store.entityType(), id)
              .map(ChangeLog.Entry::actor)
              .orElse(null);
      Actor.run(Actor.main(author), () -> store.adoptForSync(id, null, base));
      return new How.Withdrawn();
    }
    var author = Snapshots.text(held, Snapshots.ACTOR);
    Actor.run(Actor.main(author), () -> store.applyRevision(id, held, base));
    return new How.Reverted();
  }
}
