/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
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
  void aBoxWithNoCatalogYetRefusesNothingAndIsLeftWithoutOne() {
    var missing = dir.resolve("none.db");

    assertDoesNotThrow(() -> ProjectCatalog.requireUnpruned(missing, "any"));
    assertFalse(Files.exists(missing), "a dry run must not create the catalog");
  }

  @Test
  void aCatalogThatCannotBeReadRefusesNothing() throws Exception {
    var unreadable = Files.createDirectory(dir.resolve("not-a-database"));

    assertDoesNotThrow(() -> ProjectCatalog.requireUnpruned(unreadable, "any"));
  }
}
