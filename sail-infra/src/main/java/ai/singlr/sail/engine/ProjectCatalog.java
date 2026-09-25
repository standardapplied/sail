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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records a project's definition into the control-plane catalog (the {@code projects} table) so it
 * becomes the shared, replicated source of truth. Best-effort by design: the on-disk {@code
 * sail.yaml} and the container are the operations that must succeed, so a catalog write that fails
 * (DB momentarily unavailable) prints a hint and is recovered by the import migration on the next
 * {@code sail migrate}. Only a node that cannot name its operator refuses, and its commands resolve
 * the operator before they change anything.
 */
public final class ProjectCatalog {

  private ProjectCatalog() {}

  /**
   * Refuses a project name that was pruned, before anything is provisioned under it: a pruned name
   * is spent for good. Reads the catalog without creating or migrating it, so a dry run changes
   * nothing; a catalog that is missing or cannot be read refuses nothing — recording stays
   * best-effort.
   */
  public static void requireUnpruned(String name) {
    requireUnpruned(SailPaths.controlPlaneDb(), name);
  }

  static void requireUnpruned(Path catalog, String name) {
    if (!Files.isRegularFile(catalog)) {
      return;
    }
    boolean pruned;
    try (var db = Sqlite.open(catalog)) {
      pruned = new ChangeLog(db).isErased(Erasure.PROJECT, name);
    } catch (Exception e) {
      return;
    }
    if (pruned) {
      throw new IllegalStateException(
          "Project '"
              + name
              + "' was pruned, and a pruned name is never used again. Choose a new name for the"
              + " project in its sail.yaml.");
    }
  }

  /**
   * Records the definition as {@code operator}, this box's operator ({@link CliOperator}), which
   * the caller resolves before it writes anything, so a node that cannot name it refuses the edit
   * rather than losing it. Returns true if the definition was recorded; false (with a printed hint)
   * on best-effort miss.
   */
  public static boolean record(String name, String definition, Actor operator) {
    return record(SailPaths.controlPlaneDb(), name, definition, operator);
  }

  static boolean record(Path catalog, String name, String definition, Actor operator) {
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      Actor.run(operator, () -> new ProjectStore(db).upsert(name, definition));
      return true;
    } catch (Exception e) {
      System.err.println(
          "  Note: project '"
              + name
              + "' was not recorded in the catalog ("
              + e.getMessage()
              + "). Run 'sail migrate' to import it.");
      return false;
    }
  }
}
