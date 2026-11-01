/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;

/**
 * Seeds the bundled {@link DemoProject} into the control-plane catalog so {@code sail project demo}
 * is just another database-resident project — no network, no GitHub. Idempotent: it inserts the
 * demo only when the catalog has no record of one at all, so an engineer who customised or purged
 * theirs is never clobbered or resurrected, and re-running on every install, upgrade, and daemon
 * start is a no-op once seeded. Only main or a box that syncs with nobody seeds it ({@link
 * NodeIdentity#authoritative}): a node gets the demo from main like any project, once an admin
 * grants it, and one seeded there would be dropped by its first round.
 */
public final class DemoSeeder {

  private static final String ENTITY = "project";

  private DemoSeeder() {}

  /**
   * Seeds the demo into the catalog if absent. Best-effort and self-contained: opens its own
   * connection and swallows failure with a hint, so it never blocks {@code host init} or {@code
   * migrate}. Returns {@code true} only when this call inserted the demo.
   */
  public static boolean seedIfAbsent() {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(db).migrate();
      return seedIfAbsent(db);
    } catch (Exception e) {
      System.err.println(
          "  Note: demo project was not seeded (" + e.getMessage() + "). It will retry next run.");
      return false;
    }
  }

  /**
   * Seeds the demo into {@code store}'s database only if the catalog has never recorded it; returns
   * true when it inserted it. The check is the change-log history, not just the live row, so a demo
   * that was purged ({@code project destroy demo --purge} leaves a deletion tombstone) is not
   * resurrected on the next daemon start — which would otherwise undo the team-wide purge on the
   * next sync. Seeded by this box's machinery.
   */
  public static boolean seedIfAbsent(Sqlite db) {
    return seedIfAbsent(db, NodeIdentity.authoritative());
  }

  /** As {@link #seedIfAbsent(Sqlite)} on a box that is main or standalone when {@code seeds}. */
  static boolean seedIfAbsent(Sqlite db, boolean seeds) {
    var store = new ProjectStore(db);
    if (!seeds || store.latestRev(DemoProject.NAME) != null) {
      return false;
    }
    Actor.run(Actor.system(), () -> store.upsert(DemoProject.DEFINITION));
    return true;
  }

  /**
   * Lets go of the demo this box seeded while it synced with nobody — {@code sudo sail init --main}
   * provisions a box before it joins — untouched since, once it syncs as a node: a node gets the
   * demo from main like any project, so the seed is nobody's work, and the node's round would
   * otherwise announce dropping it. A demo main took, anyone changed, or anything was put into
   * stays, to be offered or dropped as work. Returns whether it let go. Decided and dropped under
   * one write lock, so a write into the demo either lands first and keeps it or waits until it is
   * gone.
   */
  public static boolean unseed(Sqlite db) {
    return db.transaction(() -> unseedWhileLocked(db));
  }

  private static boolean unseedWhileLocked(Sqlite db) {
    var history = new ChangeLog(db).history(ENTITY, DemoProject.NAME);
    var seed =
        history.size() == 1
            && history.getFirst().kind() == ChangeLog.Kind.REVISION
            && ChangeLog.Entry.LOCAL.equals(history.getFirst().origin())
            && Actor.SYSTEM_HANDLE.equals(history.getFirst().actor())
            && new ProjectStore(db).liveBase(DemoProject.NAME).filter(String::isBlank).isPresent()
            && db.queryOne(
                        "SELECT count(*) FROM change_heads WHERE project = ? AND entity_type <> ?",
                        row -> row.integer(0),
                        DemoProject.NAME,
                        ENTITY)
                    .orElseThrow()
                == 0;
    if (seed) {
      new Erasure(db).dropLocal(DemoProject.NAME);
    }
    return seed;
  }
}
