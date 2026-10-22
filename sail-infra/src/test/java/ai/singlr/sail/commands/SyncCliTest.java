/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.DemoProject;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.HandleChange;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ContentMigration;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrations;
import ai.singlr.sail.store.DatabaseCatalog;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MaterializedFilesMigration;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewFindingsMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.ScopedHeadsMigration;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncHealth;
import ai.singlr.sail.store.SyncScope;
import ai.singlr.sail.sync.SyncEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
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

  @Test
  void aScheduledRoundSpeaksForTheOperatorWhoStartedTheCommand() throws Exception {
    runIsolated("sudo-round");
  }

  @Test
  void aNodeNeverSeedsTheDemoOnMigrateOrWhenAskedForIt() throws Exception {
    runIsolated("node-demo");
  }

  @Test
  void mainForgetsTheScopeItHeardAsANodeOnMigrate() throws Exception {
    runIsolated("scope-migrate-main", MAIN);
  }

  @Test
  void aNodeKeepsTheScopeMainNamedItOnMigrate() throws Exception {
    runIsolated("scope-migrate-node");
  }

  @Test
  void aStartDuringANodesIdentityWriteKeepsItsScope() throws Exception {
    runIsolated("scope-mid-write");
  }

  @Test
  void aPromotionThatDiedBeforeItsScopeClearIsRepairedOnTheNextStart() throws Exception {
    runIsolated("scope-start-main", MAIN);
  }

  @Test
  void aStandaloneBoxForgetsAnyScopeOnMigrate() throws Exception {
    runIsolated("scope-migrate-standalone", "storage_backend: dir\n");
  }

  @Test
  void aStandaloneBoxForgetsAnyScopeOnTheNextStart() throws Exception {
    runIsolated("scope-start-standalone", "storage_backend: dir\n");
  }

  @Test
  void aStartWhileHostYamlIsBeingMovedKeepsANodesScope() throws Exception {
    runIsolated("scope-start-gone");
  }

  @Test
  void aStartThatFindsHostYamlTruncatedKeepsANodesScope() throws Exception {
    runIsolated("scope-start-torn");
  }

  private static final String NODE =
      """
      sync:
        role: node
        main: sail@main
        handle: node
      """;

  private static final String MAIN =
      """
      sync:
        role: main
        handle: ada
      """;

  private void runIsolated(String scenario) throws Exception {
    runIsolated(scenario, NODE);
  }

  private void runIsolated(String scenario, String hostYaml) throws Exception {
    var data = Files.createDirectories(home.resolve(".sail"));
    Files.writeString(data.resolve("host.yaml"), hostYaml);
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
      if ("scope-mid-write".equals(args[0])) {
        verifyScopeDuringIdentityWrite(db);
        return;
      }
      if (args[0].startsWith("scope-")) {
        verifyScopeOffNode(args[0], db);
        return;
      }
      if ("sudo-round".equals(args[0])) {
        verifyScheduledRoundWording();
        return;
      }
      if ("node-demo".equals(args[0])) {
        assertFalse(DemoSeeder.seedIfAbsent(db));
        assertFalse(DemoSeeder.seedIfAbsent());
        assertThrows(IllegalStateException.class, () -> operations.catalog().demoDefinition());
        assertNull(new ProjectStore(db).latestRev(DemoProject.NAME));
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
        assertTrue(status.lastError().plain().contains("newer than this Sail binary supports"));
        assertThrows(IllegalStateException.class, () -> NodeSync.syncOnce(OperatorCommand.PLAIN));
        assertEquals(2, operations.syncStatus().consecutiveFailures());
        assertEquals(
            List.of(Event.WellKnownTypes.SYNC_DEGRADED),
            new EventStore(db).recent(10).stream().map(EventStore.EventRow::type).toList());
      } else {
        var failures = Integer.parseInt(args[0]);
        for (var i = 0; i < failures; i++) {
          health.failed(peer, previous, "unreachable", Remedy.of("offline"));
        }
        for (var invocation = 0; invocation < 2; invocation++) {
          try (var scheduler =
              NodeSync.scheduler(
                  HostSync.config(),
                  false,
                  Map.of(NodeSync.FRESHEN_TTL_ENV, "1"),
                  OperatorCommand.PLAIN)) {
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

  /**
   * A box main scoped as a node holds the projects {@code acme} and {@code beta} and was granted
   * only {@code acme}; {@code sail migrate} or the start of sail-api, which converge the database
   * the same way, then leave a node that scope and give any other box every project. A node whose
   * {@code host.yaml} is gone or empty at that moment — being moved into {@code /var/lib/sail}, or
   * truncated by a writer that holds no lock — is left its scope too: nothing whole said it is not
   * a node.
   */
  private static void verifyScopeOffNode(String scenario, Sqlite db) throws IOException {
    HeldProjects.hold(db, "acme", "beta");
    var granted = ProjectScope.of(List.of("acme"));
    new SyncScope(db).record(granted);
    new DatabaseCatalog(db).recordMain("cat-a");
    switch (scenario) {
      case "scope-start-gone" -> Files.delete(SailPaths.hostConfigPath());
      case "scope-start-torn" -> Files.writeString(SailPaths.hostConfigPath(), "");
      default -> {}
    }

    if (scenario.startsWith("scope-migrate-")) {
      MigrateCommand.runMigrations(true, true, OperatorCommand.PLAIN);
    } else {
      MigrationRunner.applyAll(db, DataMigrations.all(), DataMigration.Prompter.NON_INTERACTIVE);
    }

    var projects = new ProjectStore(db);
    var push = projects.refusesInto(Actor.sync("nia", Role.MEMBER), null, "beta", true);
    if (scenario.endsWith("-main") || scenario.endsWith("-standalone")) {
      assertEquals(ProjectScope.all(), new SyncScope(db).held());
      assertEquals(Optional.empty(), new DatabaseCatalog(db).main());
      assertEquals(Optional.empty(), push);
      Acting.as("ada", () -> new SpecStore(db).create(spec("b1", "beta")));
      assertEquals("beta", new SpecStore(db).findById("b1").orElseThrow().project());
      return;
    }
    assertEquals(granted, new SyncScope(db).held());
    assertEquals(Optional.of("cat-a"), new DatabaseCatalog(db).main());
    assertTrue(push.isPresent());
  }

  /**
   * A node rewrites its identity, unchanged, while sail-api starts: {@code host.yaml} is empty
   * between the truncation and the write, and a start that read it as a standalone box's would
   * forget the scope main granted it. The start runs to its end on the empty file and leaves the
   * scope: nothing whole said the box is not a node.
   */
  private static void verifyScopeDuringIdentityWrite(Sqlite db) throws Exception {
    var granted = ProjectScope.of(List.of("acme"));
    new SyncScope(db).record(granted);
    var hostYaml = SailPaths.hostConfigPath();
    var node = HostYaml.fromMap(YamlUtil.parseFile(hostYaml)).sync();
    var truncated = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var rewrite =
        new FutureTask<>(
            () ->
                HandleChange.apply(
                    SailPaths.controlPlaneDb(),
                    node,
                    node,
                    target -> {
                      throw new IOException("main is not asked on an unchanged identity");
                    },
                    OperatorCommand.PLAIN,
                    () -> {
                      var whole = Files.readString(hostYaml);
                      Files.writeString(hostYaml, "");
                      truncated.countDown();
                      resume.await();
                      Files.writeString(hostYaml, whole);
                    }));
    Thread.ofPlatform().start(rewrite);
    assertTrue(truncated.await(10, TimeUnit.SECONDS), "the rewrite never truncated host.yaml");
    MigrationRunner.applyAll(db, DataMigrations.all(), DataMigration.Prompter.NON_INTERACTIVE);
    assertEquals(granted, new SyncScope(db).held(), "the empty file decided nothing");
    resume.countDown();
    rewrite.get();

    assertEquals(node, NodeIdentity.config());
    assertEquals(granted, new SyncScope(db).held());
  }

  private static SpecStore.SpecRow spec(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        "the " + id + " spec",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "ada",
        "",
        "",
        "ada",
        List.of(),
        List.of());
  }

  private static void verifyScheduledRoundWording() throws Exception {
    Files.delete(SailPaths.hostConfigPath());
    var output = new ByteArrayOutputStream();
    var original = System.out;
    try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      NodeSync.syncOnce(OperatorCommand.SUDO);
    } finally {
      System.setOut(original);
    }
    assertEquals(
        "  Single devbox — nothing to sync. Set up sync with: sudo sail init --as-main --handle"
            + " <you>, or sudo sail init --main <main>.\n",
        output.toString(StandardCharsets.UTF_8));
  }

  private static void verifyPreHealthUpgrade(String scenario, Sqlite db, HostOperations operations)
      throws Exception {
    db.execute("DROP TABLE catalog");
    db.execute("DROP TABLE sync_scope");
    db.execute("ALTER TABLE change_heads DROP COLUMN project");
    db.execute("DROP TABLE project_grants");
    db.execute("ALTER TABLE fdes DROP COLUMN scope");
    db.execute("DELETE FROM data_migrations WHERE name = ?", ScopedHeadsMigration.NAME);
    db.execute("DROP TABLE fde_pairings");
    db.execute("ALTER TABLE reviews DROP COLUMN waiting_on");
    db.execute("ALTER TABLE events DROP COLUMN publisher");
    db.execute("DROP TABLE materialized_files");
    db.execute("DELETE FROM data_migrations WHERE name = ?", MaterializedFilesMigration.NAME);
    db.execute("ALTER TABLE reviews DROP COLUMN findings_hash");
    db.execute("ALTER TABLE review_findings DROP COLUMN followup");
    db.execute(
        "CREATE TABLE spec_source_findings (spec_id TEXT NOT NULL, finding_id TEXT NOT NULL,"
            + " PRIMARY KEY (spec_id, finding_id))");
    db.execute(
        "CREATE TABLE spec_attachments (id TEXT PRIMARY KEY, spec_id TEXT NOT NULL,"
            + " filename TEXT NOT NULL, content_type TEXT NOT NULL, size_bytes INTEGER NOT NULL,"
            + " storage_path TEXT NOT NULL, created_at TEXT NOT NULL)");
    db.execute("DELETE FROM data_migrations WHERE name = ?", ReviewFindingsMigration.NAME);
    db.execute("DROP TRIGGER change_log_kind_from_deleted");
    for (var index :
        List.of(
            "idx_change_log_tombstones",
            "idx_room_messages_reply",
            "idx_runs_room",
            "idx_rooms_project",
            "idx_specs_room",
            "idx_run_delivered_message")) db.execute("DROP INDEX " + index);
    db.execute("DROP TABLE erase_requests");
    db.execute("DROP TABLE fde_boxes");
    db.execute("DROP TABLE sync_offers");
    for (var column : List.of("remote_rev", "remote_author"))
      db.execute("ALTER TABLE sync_conflicts DROP COLUMN " + column);
    db.execute("DROP TRIGGER specs_status_since_insert");
    db.execute("DROP TRIGGER specs_status_since_update");
    for (var column : List.of("archived_at", "cancelled_at"))
      db.execute("ALTER TABLE specs DROP COLUMN " + column);
    db.execute("ALTER TABLE change_log DROP COLUMN kind");
    db.execute("DROP INDEX idx_change_log_file_version");
    for (var table : List.of("blobs", "chunks")) db.execute("DROP TABLE " + table);
    for (var column : List.of("body_hash", "plan_hash"))
      db.execute("ALTER TABLE specs DROP COLUMN " + column);
    for (var column : List.of("content_hash", "size", "mode", "kind"))
      db.execute("ALTER TABLE project_files DROP COLUMN " + column);
    db.execute("ALTER TABLE project_files ADD COLUMN content TEXT NOT NULL DEFAULT ''");
    db.execute("DELETE FROM data_migrations WHERE name = ?", ContentMigration.NAME);
    db.execute("DROP TABLE sync_health");
    db.execute("DROP TABLE change_heads");
    db.execute("DROP TABLE sync_state");
    db.execute("DROP TABLE main_limits");
    db.execute(
        "CREATE TABLE sync_state (peer TEXT PRIMARY KEY, checkpoint INTEGER NOT NULL DEFAULT 0,"
            + " updated_at TEXT NOT NULL)");
    db.execute("UPDATE schema_version SET version = ?", SchemaManager.SYNC_HEALTH_VERSION - 1);
    assertEquals(SchemaManager.SYNC_HEALTH_VERSION - 1, operations.schema().version());
    assertNull(
        operations.syncStatus().state(),
        "a database from before health reports nothing attempted, not a missing table");

    if ("upgrade-agent-stop".equals(scenario)) {
      db.execute(
          "INSERT INTO projects (name, definition, created_by, created_at, updated_by, updated_at)"
              + " VALUES ('acme', 'name: acme', 'it', 'now', 'it', 'now')");
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
      try (var scheduler = NodeSync.scheduler(false, OperatorCommand.PLAIN)) {
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
    try (var scheduler = NodeSync.scheduler(false, OperatorCommand.PLAIN)) {
      scheduler.freshenRead();
    }
    assertEquals(status, operations.syncStatus());
  }
}
