/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.DatabaseCatalog;
import ai.singlr.sail.store.Decidability;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncLimits;
import ai.singlr.sail.store.SyncScope;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a node does first in every round, once main has welcomed it, before it offers or adopts
 * anything. Main accepts a run only from the box that executed it, acting for that box's FDE, so a
 * node first agrees with main on who it is, then stamps as its own every run main has not taken.
 */
public final class NodeRound {

  /** Where main keeps its database: the org, which every node's catalog names. */
  private static final Path MAIN_DATABASE = SailPaths.systemDataDir().resolve("sail.db");

  private NodeRound() {}

  /**
   * Refuses {@code session}, before anything is offered, adopted or recorded, unless main at {@code
   * target} serves the database this node, whose database is {@code db}, synced with: main set up
   * empty at the same address welcomes the node as readily as the main it replaced. The first
   * welcome that names a catalog is recorded as main's ({@link DatabaseCatalog#recordMain}). A main
   * that names none predates catalogs, and could be any database at all, so it is refused too:
   * asked to upgrade before this node has recorded a catalog, told it is another database after.
   */
  public static void admit(SyncSession session, Sqlite db, String target) {
    var catalog = new DatabaseCatalog(db);
    var offered = session.catalog();
    var synced = offered.map(catalog::recordMain).or(catalog::main);
    if (synced.isEmpty()) {
      throw new SyncTransportException(
          "refused",
          "Main at "
              + target
              + " names no database catalog, so this box cannot tell whether it is the database"
              + " it synced with: upgrade main ('"
              + OperatorCommand.elsewhere("upgrade")
              + "' on main), then sync again.",
          null);
    }
    if (synced.equals(offered)) {
      return;
    }
    throw new SyncTransportException(
        "refused",
        "Main at "
            + target
            + " is not the database this box synced with (catalog "
            + synced.get()
            + ", now "
            + offered.orElse("none")
            + "). Restore main's database ("
            + MAIN_DATABASE
            + ") from a copy, then sync again.",
        null);
  }

  /**
   * Begins a round over {@code session} for the node whose database is {@code db} and whose
   * configured sync handle is {@code handle}. It fails, before anything is offered or adopted,
   * unless main authenticated the session as that handle; a main that predates saying which handle
   * it authenticated is not asked. Then every run main has never acknowledged is settled ({@link
   * #acknowledgeHeld}), every one main does not hold is stamped as this box's ({@link
   * #stampUnheld}), and so is every run of this box's that acts for no one, which an older release
   * reserved and main would never take. Main's file ceiling is recorded, and every offer whose
   * dependency can never arrive is settled ({@link Settlement}). Before any of that the box is
   * brought in line with the scope main named ({@link #rescope}). Returns what was settled and what
   * was dropped, for the round's notices.
   */
  public static List<Settlement.Settled> begin(
      SyncSession session, Sqlite db, String handle, OperatorCommand operator) {
    requireAgreed(session.handle(), handle, operator);
    var settled = new ArrayList<>(rescope(session, db, handle));
    stampUnheld(db, handle, acknowledgeHeld(session, db));
    var runs = new RunStore(db);
    runs.stamp(handle, runs.ownerless(handle));
    var limits = new SyncLimits(db);
    limits.recordMainFileMax(session.mainFileMax());
    acknowledgeOversized(session, db, limits.mainFileMax());
    settled.addAll(settle(db, handle));
    return List.copyOf(settled);
  }

  /**
   * Brings this box in line with the scope main's welcome named, before anything is asked of main.
   * Every project the box holds anything of that the scope does not admit is dropped whole ({@link
   * Erasure#dropLocal}), unpushed work included, which its notice counts: main sends nothing of it
   * again and would deny every offer of it, so the admin's revocation is how that work leaves this
   * box. It is decided from what the box holds, never from what it was last told, so a drop a
   * failed round or a run still under way here put off happens on the next round that finds it. A
   * project newly in scope has its history below this box's checkpoints, so they are reset and
   * main's heads walked again ({@link SyncState#reset}). The scope is then recorded as the one this
   * box works within.
   */
  private static List<Settlement.Settled> rescope(SyncSession session, Sqlite db, String handle) {
    var scope = session.scope();
    var known = new SyncScope(db);
    var settled = new ArrayList<Settlement.Settled>();
    if (!scope.unrestricted()) {
      var changeLog = new ChangeLog(db);
      for (var project : changeLog.heldProjects()) {
        if (!scope.admits(project)) {
          settled.add(drop(db, handle, project));
        }
      }
    }
    if (settled.stream().anyMatch(left -> left.how() instanceof Settlement.How.Dropped)) {
      session.contentLeft();
    }
    var widened = widenedFrom(known.held(), scope);
    db.transaction(
        () -> {
          if (widened) {
            new SyncState(db).reset(session.mainId());
          }
          known.record(scope);
          return null;
        });
    return settled;
  }

  /**
   * The projects {@code settled} kept whole for a run still under way ({@link
   * Settlement.How.Kept}), for the session to offer nothing of ({@link SyncSession#keep}).
   */
  public static Set<String> kept(List<Settlement.Settled> settled) {
    return settled.stream()
        .filter(
            each ->
                Erasure.PROJECT.equals(each.type()) && each.how() instanceof Settlement.How.Kept)
        .map(Settlement.Settled::id)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** Whether {@code now} admits a project {@code before} did not. */
  private static boolean widenedFrom(ProjectScope before, ProjectScope now) {
    return !before.unrestricted()
        && (now.unrestricted() || !before.projects().containsAll(now.projects()));
  }

  /**
   * Drops {@code project} and all that belongs to it from this box, unless a run in it is still
   * under way here: its agent and watcher act through the rows, so the project stays, named with
   * the run, until the first round after the run ends.
   */
  private static Settlement.Settled drop(Sqlite db, String handle, String project) {
    var how =
        db.<Settlement.How>transaction(
            () -> {
              var live =
                  new RunStore(db)
                      .listForProject(project).stream()
                          .filter(run -> run.liveOn(handle))
                          .findFirst();
              if (live.isPresent()) {
                return new Settlement.How.Kept(live.get().id());
              }
              var dropped = new Erasure(db).dropLocal(project);
              return new Settlement.How.Dropped(dropped.entities().size(), dropped.unpushed());
            });
    return new Settlement.Settled(
        Erasure.PROJECT, project, how, "main no longer grants this box the project");
  }

  /**
   * Asks main, before an oversized file is settled, which of the files above its ceiling this box
   * still offers it took — an earlier upload cut on the way leaves the offer recorded with no
   * answer — and adopts each version main took as that file's base ({@link FileStore#acknowledge}).
   * Every such record is then forgotten: main answers an oversized offer only with a refused
   * channel, never per offer, so a record still standing once main has been asked can only be of an
   * offer main never took. Settlement then judges exactly what is still an offer.
   */
  private static void acknowledgeOversized(SyncSession session, Sqlite db, long ceiling) {
    var files = new FileStore(db);
    var changeLog = new ChangeLog(db);
    var recorded =
        files.offersAbove(ceiling).stream()
            .filter(id -> changeLog.offer(files.entityType(), id).isPresent())
            .toList();
    if (recorded.isEmpty()) {
      return;
    }
    var heard = new LinkedHashMap<String, String>();
    recorded.forEach(
        id -> changeLog.latestHeard(files.entityType(), id).ifPresent(rev -> heard.put(id, rev)));
    for (var entry : session.held(files.entityType(), recorded, heard).accepted()) {
      Actor.run(
          Actor.main(entry.author()),
          () -> files.acknowledge(entry.id(), entry.snapshot(), entry.rev()));
    }
    recorded.forEach(id -> changeLog.settleOffer(files.entityType(), id));
  }

  /**
   * Ends a round: settles, with the deletions and erasures the round paged in hand, every offer the
   * round's denials and erasures left undecidable ({@link Settlement}), so a {@code gone} answer is
   * settled in the round that heard it. Returns what was settled, for the round's notices.
   */
  public static List<Settlement.Settled> end(Sqlite db, String handle) {
    return settle(db, handle);
  }

  private static List<Settlement.Settled> settle(Sqlite db, String handle) {
    return new Settlement(db, Decidability.onNode(db), handle).settle();
  }

  /**
   * Asks main which of the runs this box made that it never heard acknowledged main holds — its
   * answer lost on the way back — and adopts the version main took from this box as each one's base
   * ({@link RunStore#acknowledge}), keeping what changed here since the offer on top. Nothing is
   * offered, and nothing but main's acknowledgement is adopted. Returns the ids main holds.
   */
  public static Set<String> acknowledgeHeld(SyncSession session, Sqlite db) {
    var runs = new RunStore(db);
    var unacknowledged = runs.unacknowledged();
    if (unacknowledged.isEmpty()) {
      return Set.of();
    }
    var answer = session.held(runs.entityType(), unacknowledged);
    for (var entry : answer.accepted()) {
      Actor.run(
          Actor.main(entry.author()),
          () -> runs.acknowledge(entry.id(), entry.snapshot(), entry.rev()));
    }
    var held = new LinkedHashSet<String>();
    answer.current().forEach(entry -> held.add(entry.id()));
    return Collections.unmodifiableSet(held);
  }

  /**
   * Stamps as the box whose FDE handle is {@code handle} executes them ({@link RunStore#stamp})
   * every run this box made that main has never acknowledged and does not hold — {@code held} is
   * what main said it holds ({@link #acknowledgeHeld}) — so main takes each from this box. Returns
   * the ids stamped.
   */
  public static List<String> stampUnheld(Sqlite db, String handle, Set<String> held) {
    var runs = new RunStore(db);
    return runs.stamp(
        handle, runs.unacknowledged().stream().filter(id -> !held.contains(id)).toList());
  }

  /**
   * Fails naming both handles and the fix unless {@code configured} is the handle main
   * authenticated the session as, when main said.
   */
  static void requireAgreed(
      Optional<String> authenticated, String configured, OperatorCommand operator) {
    if (authenticated.isEmpty()
        || (Strings.isNotBlank(configured) && configured.equals(authenticated.get()))) {
      return;
    }
    var main = authenticated.get();
    var mine =
        Strings.isBlank(configured)
            ? "this box has no sync handle"
            : "this box's sync handle is '" + configured + "'";
    throw new SyncTransportException(
        "refused",
        (Strings.isBlank(main)
                ? "main authenticated this session as no FDE"
                : "main knows this box as FDE '" + main + "'")
            + ", but "
            + mine
            + ", so this round offers and adopts nothing. Set the sync handle to the one main knows"
            + (Strings.isBlank(main)
                ? ""
                : " ('" + operator.sail("host config set sync-handle " + main) + "')")
            + ", or have an admin tie this box's sync key to "
            + (Strings.isBlank(configured) ? "its FDE" : "FDE '" + configured + "'")
            + " on main.",
        null);
  }
}
