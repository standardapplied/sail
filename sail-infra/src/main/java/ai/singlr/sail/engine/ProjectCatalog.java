/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;

/**
 * Records a project's definition into the control-plane catalog (the {@code projects} table) so it
 * becomes the shared, replicated source of truth. Best-effort by design: the on-disk {@code
 * sail.yaml} and the container are the operations that must succeed, so a catalog write that fails
 * (DB momentarily unavailable) prints a hint and is recovered by the import migration on the next
 * {@code sail migrate} — it never aborts project creation.
 */
public final class ProjectCatalog {

  private ProjectCatalog() {}

  /**
   * Returns true if the definition was recorded; false (with a printed hint) on best-effort miss.
   */
  public static boolean record(String name, String definition, String actor) {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(db).migrate();
      new ProjectStore(db).upsert(name, definition, actor);
      return true;
    } catch (Exception e) {
      System.err.println(
          "  Note: project '"
              + name
              + "' was not recorded in the catalog ("
              + e.getMessage()
              + "). Run 'sail migrate' to backfill.");
      return false;
    }
  }
}
