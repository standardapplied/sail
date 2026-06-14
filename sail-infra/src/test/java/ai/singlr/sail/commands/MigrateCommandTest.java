/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ContainerSpecImporter;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrateCommandTest {

  @TempDir Path tempDir;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void schemaIsFullyMigratedBeforeSpecImportRuns() {
    var versionWhenImportRan = new AtomicInteger(-1);

    MigrateCommand.applyMigrations(
        db,
        "test.db",
        () -> {
          versionWhenImportRan.set(new SchemaManager(db).currentVersion());
          return ContainerSpecImporter.Report.empty();
        },
        DataMigration.Prompter.NON_INTERACTIVE,
        false,
        true);

    var finalVersion = new SchemaManager(db).currentVersion();
    assertTrue(finalVersion > 0, "schema should have migrated from empty");
    assertEquals(
        finalVersion,
        versionWhenImportRan.get(),
        "schema must be fully migrated before the spec import queries SpecStore");
  }

  @Test
  void specImportSeesCurrentSchemaColumns() {
    MigrateCommand.applyMigrations(
        db,
        "test.db",
        () -> {
          assertDoesNotThrow(() -> new ai.singlr.sail.store.SpecStore(db).findById("none"));
          return ContainerSpecImporter.Report.empty();
        },
        DataMigration.Prompter.NON_INTERACTIVE,
        false,
        true);
  }
}
