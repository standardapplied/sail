/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.LegacyDataMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
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
    if (db != null) {
      db.close();
    }
  }

  @Test
  void applyMigrationsBringsTheSchemaCurrent() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true);

    assertTrue(
        new SchemaManager(db).currentVersion() > 0, "schema should have migrated from empty");
  }

  @Test
  void migratedSchemaExposesTheCurrentSpecColumns() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true);

    assertDoesNotThrow(() -> new SpecStore(db).findById("none"));
  }

  @Test
  void migrateRegistersTheV1DataFloorOnce() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true);

    assertEquals(
        1L,
        db.queryOne(
                "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                row -> row.integer(0),
                LegacyDataMigration.NAME)
            .orElseThrow());
  }

  @Test
  void ensurePtyHostServiceInstallsOnlyOnAProvisionedHost(@TempDir Path home) {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var binary = Path.of("/usr/local/bin/sail");

    MigrateCommand.ensurePtyHostService(
        false, shell, SystemdServiceInstaller.Mode.USER, home, binary, true);
    assertTrue(shell.invocations().isEmpty(), "no systemctl where the box has no api service");
    assertFalse(Files.exists(home.resolve(".sail/services/sail-pty-host.service")));

    MigrateCommand.ensurePtyHostService(
        true, shell, SystemdServiceInstaller.Mode.USER, home, binary, true);
    assertTrue(
        Files.exists(home.resolve(".sail/services/sail-pty-host.service")),
        "a provisioned host gets the pty-host unit");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("systemctl --user restart sail-pty-host.service")),
        "and it is enabled and (re)started so the upgraded binary takes effect");
  }

  @Test
  void containersToRelocateSelectsOnlyDevicesStillOnTheOldSource() {
    UnaryOperator<String> sourceOf =
        name ->
            switch (name) {
              case "old-box" -> "/run/sail";
              case "new-box" -> "/var/lib/sail/run";
              default -> null;
            };

    var targets =
        MigrateCommand.containersToRelocate(
            List.of("old-box", "new-box", "no-device"), sourceOf, "/var/lib/sail/run");

    assertEquals(
        List.of("old-box"), targets, "only a device still on the old source is re-pointed");
  }
}
