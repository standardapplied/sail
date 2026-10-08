/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Records a project's definition into the control-plane catalog (the {@code projects} table), the
 * shared, replicated source of truth that everything running a project reads. A definition that
 * does not reach the catalog is not recorded anywhere: the write fails loud, before the on-disk
 * copy is written. Only a node that cannot name its operator refuses, and its commands resolve the
 * operator before they change anything.
 */
public final class ProjectCatalog {

  private ProjectCatalog() {}

  /**
   * Refuses a project name that was pruned, before anything is provisioned under it: a pruned name
   * is spent for good. Reads the catalog without creating or migrating it, so a dry run changes
   * nothing; a catalog that is missing or cannot be read refuses nothing here, and the write that
   * follows fails on its own.
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
   * Refuses, before a command changes anything, a definition of {@code name} the journal will not
   * take from {@code operator}: the project rule's own decision, asked of the catalog as it stands.
   * A box with no catalog yet refuses nothing here; one whose catalog cannot be read fails here, as
   * the write that follows would.
   */
  public static void requireRecordable(String name, Actor operator) {
    requireRecordable(SailPaths.controlPlaneDb(), name, operator);
  }

  static void requireRecordable(Path catalog, String name, Actor operator) {
    if (!Files.isRegularFile(catalog)) {
      return;
    }
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      Actor.call(operator, () -> new ProjectStore(db).decide(name, Map.of()))
          .ifPresent(
              refused -> {
                throw new WriteRefused(refused);
              });
    }
  }

  /**
   * Records the definition as {@code operator}, this box's operator ({@link CliOperator}), which
   * the caller resolves before it writes anything, so a node that cannot name it refuses the edit
   * rather than losing it. Throws when the definition was not recorded, naming the cause; a write
   * the project rule refuses is thrown as the refusal it is, with its fix.
   */
  public static void record(String name, String definition, Actor operator) {
    record(SailPaths.controlPlaneDb(), name, definition, operator);
  }

  static void record(Path catalog, String name, String definition, Actor operator) {
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      Actor.run(operator, () -> new ProjectStore(db).upsert(name, definition));
    } catch (WriteRefused refused) {
      throw refused;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Project '" + name + "' was not recorded in the catalog: " + e.getMessage(), e);
    }
  }
}
