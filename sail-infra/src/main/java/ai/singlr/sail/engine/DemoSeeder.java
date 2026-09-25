/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;

/**
 * Seeds the bundled {@link DemoProject} into the control-plane catalog so {@code sail project demo}
 * is just another database-resident project — no network, no GitHub. Idempotent: it inserts the
 * demo only when the catalog has no record of one at all, so an engineer who customised or purged
 * theirs is never clobbered or resurrected, and re-running on every install, upgrade, and daemon
 * start is a no-op once seeded.
 */
public final class DemoSeeder {

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
    var store = new ProjectStore(db);
    if (store.latestRev(DemoProject.NAME) != null) {
      return false;
    }
    Actor.run(Actor.system(), () -> store.upsert(DemoProject.NAME, DemoProject.DEFINITION));
    return true;
  }
}
