/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCatalogTest {

  @TempDir Path dir;

  @Test
  void aPrunedNameIsRefusedBeforeAnythingIsProvisionedUnderItAndOthersPass() {
    var catalog = dir.resolve("sail.db");
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert("gone", "name: gone\n"));
      Acting.system(() -> new ProjectStore(db).upsert("kept", "name: kept\n"));
      var erasure = new Erasure(db);
      Acting.system(
          () ->
              erasure.erase(
                  erasure.closure(List.of(new Erasure.Target(Erasure.PROJECT, "gone"))), "local"));
    }

    var refused =
        assertThrows(
            IllegalStateException.class, () -> ProjectCatalog.requireUnpruned(catalog, "gone"));

    assertTrue(refused.getMessage().contains("'gone' was pruned"), refused.getMessage());
    assertDoesNotThrow(() -> ProjectCatalog.requireUnpruned(catalog, "kept"));
    assertDoesNotThrow(() -> ProjectCatalog.requireUnpruned(catalog, "fresh"));
  }

  @Test
  void aNodeRefusesAProjectOutsideTheScopeMainNamedBeforeAnythingIsWritten() {
    var catalog = dir.resolve("sail.db");
    var nia = new Actor("nia", Role.ADMIN, Actor.Lane.CLI);
    assertDoesNotThrow(() -> ProjectCatalog.requireInScope(catalog, "any"));
    assertFalse(Files.exists(catalog), "a check must not create the catalog");
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
    }
    assertDoesNotThrow(() -> ProjectCatalog.requireInScope(catalog, "beta"));
    try (var db = Sqlite.open(catalog)) {
      new SyncScope(db).record(ProjectScope.of(List.of("alpha")));
    }

    var refused =
        assertThrows(
            IllegalStateException.class, () -> ProjectCatalog.requireInScope(catalog, "beta"));

    assertEquals(
        "Project 'beta' is outside the projects main grants this box, so main would take nothing"
            + " of it. Ask an admin to grant it on main: sudo sail fde grant <handle> beta.",
        refused.getMessage());
    assertEquals(
        refused.getMessage(),
        assertThrows(
                IllegalStateException.class,
                () -> ProjectCatalog.requireRecordable(catalog, "beta", nia))
            .getMessage());
    assertDoesNotThrow(() -> ProjectCatalog.requireInScope(catalog, "alpha"));
    assertDoesNotThrow(() -> ProjectCatalog.requireRecordable(catalog, "alpha", nia));
  }

  @Test
  void aBoxWithNoCatalogYetRefusesNothingAndIsLeftWithoutOne() {
    var missing = dir.resolve("none.db");

    assertDoesNotThrow(() -> ProjectCatalog.requireUnpruned(missing, "any"));
    assertFalse(Files.exists(missing), "a dry run must not create the catalog");
  }

  @Test
  void aDefinitionIsRecordedAsTheOperatorTheCallerResolved() {
    var catalog = dir.resolve("sail.db");
    var mady = new Actor("mady", Role.MEMBER, Actor.Lane.CLI);

    assertDoesNotThrow(() -> ProjectCatalog.record(catalog, "web", "name: web\n", mady));

    try (var db = Sqlite.open(catalog)) {
      assertEquals("mady", new ProjectStore(db).findByName("web").orElseThrow().updatedBy());
      assertEquals("mady", new ChangeLog(db).head("project", "web").orElseThrow().actor());
    }
  }

  @Test
  void aDefinitionTheCatalogDidNotTakeIsNotRecordedAndTheFailureNamesTheProject() throws Exception {
    var unopenable = Files.createDirectory(dir.resolve("not-a-database"));

    var failed =
        assertThrows(
            IllegalStateException.class,
            () ->
                ProjectCatalog.record(unopenable, "web", "name: web\n", Actor.cliOperator("uday")));

    assertTrue(
        failed.getMessage().startsWith("Project 'web' was not recorded in the catalog: "),
        failed.getMessage());
    assertNotNull(failed.getCause(), "the cause rides along");
  }

  @Test
  void aCatalogThatCannotBeReadRefusesNothing() throws Exception {
    var unreadable = Files.createDirectory(dir.resolve("not-a-database"));

    assertDoesNotThrow(() -> ProjectCatalog.requireUnpruned(unreadable, "any"));
  }
}
