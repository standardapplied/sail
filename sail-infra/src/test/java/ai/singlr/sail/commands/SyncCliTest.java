/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncHealth;
import ai.singlr.sail.sync.SyncEngine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class SyncCliTest {

  @TempDir Path home;

  @Test
  void commandPreparationFailuresUpdatePreviouslySuccessfulHealth() throws Exception {
    runIsolated("command");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 4, 5})
  void cliFreshnessHonorsPersistedBackoffAndExplicitSyncStillProbes(int failures) throws Exception {
    runIsolated(Integer.toString(failures));
  }

  @ParameterizedTest
  @ValueSource(strings = {"upgrade-freshen", "upgrade-agent-stop"})
  void cliFreshnessMigratesThePreHealthSchemaAndContinuesLocally(String scenario) throws Exception {
    runIsolated(scenario);
  }

  private void runIsolated(String scenario) throws Exception {
    var data = Files.createDirectories(home.resolve(".sail"));
    Files.writeString(
        data.resolve("host.yaml"),
        """
        sync:
          role: node
          main: sail@main
          handle: node
        """);
    var bin = Files.createDirectories(home.resolve("bin"));
    var ssh = bin.resolve("ssh");
    Files.writeString(ssh, "#!/bin/sh\nread -r request\nexit 255\n");
    Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwxr-xr-x"));
    var incus = bin.resolve("incus");
    Files.writeString(
        incus,
        """
        #!/bin/sh
        if [ "$1" != "list" ]; then exit 1; fi
        printf '%s\\n' '[{"name":"acme","status":"Stopped"}]'
        """);
    Files.setPosixFilePermissions(incus, PosixFilePermissions.fromString("rwxr-xr-x"));
    var output = home.resolve("output.txt");
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                SyncCliTest.class.getName(),
                scenario)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    builder.environment().put("SAIL_DATA_DIR", data.toString());
    builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    builder.environment().remove("SAIL_NO_SYNC");
    var process = builder.start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "isolated sync check timed out");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  public static void main(String[] args) throws Exception {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb());
        var operations = OperationsFactory.open()) {
      var schema = new SchemaManager(db);
      schema.migrate();
      if (args[0].startsWith("upgrade-")) {
        verifyPreHealthUpgrade(args[0], db, operations);
        return;
      }
      var health = new SyncHealth(db);
      var peer = "sail@main";
      assertEquals(peer, HostSync.config().main());
      var previous = Instant.now().minusSeconds(2);
      health.begin(peer, previous);
      health.succeeded(peer, previous, new SyncEngine.Report(0, 0, 0, 0));
      db.execute(
          "INSERT INTO schema_version (version, applied_at) VALUES (?, datetime('now'))",
          schema.currentVersion() + 1);

      if ("command".equals(args[0])) {
        assertEquals(1, new SyncCommand().call());
        var status = operations.syncStatus();
        assertEquals("stale", status.state());
        assertEquals(1, status.consecutiveFailures());
        assertEquals(previous, status.lastSuccessAt());
        assertTrue(status.lastAttemptAt().isAfter(previous));
        assertEquals("store", status.lastErrorKind());
        assertTrue(status.lastError().contains("newer than this Sail binary supports"));
        assertThrows(IllegalStateException.class, NodeSync::syncOnce);
        assertEquals(2, operations.syncStatus().consecutiveFailures());
        assertEquals(
            List.of(Event.WellKnownTypes.SYNC_DEGRADED),
            new EventStore(db).recent(10).stream().map(EventStore.EventRow::type).toList());
      } else {
        var failures = Integer.parseInt(args[0]);
        for (var i = 0; i < failures; i++) {
          health.failed(peer, previous, "unreachable", "offline");
        }
        for (var invocation = 0; invocation < 2; invocation++) {
          try (var scheduler =
              NodeSync.scheduler(HostSync.config(), false, Map.of(NodeSync.FRESHEN_TTL_ENV, "1"))) {
            var errors = new ByteArrayOutputStream();
            var original = System.err;
            try (var capture = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
              System.setErr(capture);
              scheduler.freshenRead();
            } finally {
              System.setErr(original);
            }
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
            assertEquals(failures + invocation, operations.syncStatus().consecutiveFailures());
            scheduler.syncNow();
            assertEquals(failures + invocation + 1, operations.syncStatus().consecutiveFailures());
          }
        }
      }
    }
  }

  private static void verifyPreHealthUpgrade(String scenario, Sqlite db, HostOperations operations)
      throws Exception {
    db.execute("DROP INDEX idx_change_log_file_version");
    for (var table : List.of("blobs", "chunks")) db.execute("DROP TABLE " + table);
    for (var column : List.of("body_hash", "plan_hash"))
      db.execute("ALTER TABLE specs DROP COLUMN " + column);
    for (var column : List.of("content_hash", "size", "mode", "kind"))
      db.execute("ALTER TABLE project_files DROP COLUMN " + column);
    db.execute("ALTER TABLE project_files ADD COLUMN content TEXT NOT NULL DEFAULT ''");
    db.execute(
        "DELETE FROM data_migrations WHERE name = ?", ai.singlr.sail.store.ContentMigration.NAME);
    db.execute("DROP TABLE sync_health");
    db.execute("DROP TABLE change_heads");
    db.execute("DROP TABLE sync_state");
    db.execute(
        "CREATE TABLE sync_state (peer TEXT PRIMARY KEY, checkpoint INTEGER NOT NULL DEFAULT 0,"
            + " updated_at TEXT NOT NULL)");
    db.execute("UPDATE schema_version SET version = ?", SchemaManager.SYNC_HEALTH_VERSION - 1);
    assertEquals(SchemaManager.SYNC_HEALTH_VERSION - 1, operations.schema().version());
    assertNull(
        operations.syncStatus().state(),
        "a database from before health reports nothing attempted, not a missing table");

    if ("upgrade-agent-stop".equals(scenario)) {
      Files.writeString(
          Files.createDirectories(SailPaths.projectDir("acme")).resolve("sail.yaml"),
          "name: acme\n");
      var output = new ByteArrayOutputStream();
      var original = System.out;
      try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
        System.setOut(capture);
        assertEquals(0, new CommandLine(new Sail()).execute("agent", "stop", "acme", "--dry-run"));
      } finally {
        System.setOut(original);
      }
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("No running agent session"));
    } else {
      try (var scheduler = NodeSync.scheduler(false)) {
        scheduler.freshenRead();
      }
    }

    assertTrue(operations.schema().version() > SchemaManager.SYNC_HEALTH_VERSION);
    var status = operations.syncStatus();
    assertEquals("stale", status.state());
    assertEquals(1, status.consecutiveFailures());
    assertEquals("unreachable", status.lastErrorKind());
    assertEquals(
        List.of(Event.WellKnownTypes.SYNC_DEGRADED),
        new EventStore(db).recent(10).stream().map(EventStore.EventRow::type).toList());
    try (var scheduler = NodeSync.scheduler(false)) {
      scheduler.freshenRead();
    }
    assertEquals(status, operations.syncStatus());
  }
}
