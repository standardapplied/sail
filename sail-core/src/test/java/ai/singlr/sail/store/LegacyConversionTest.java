/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class LegacyConversionTest {

  private static final String CATALOG = "9fca02d4063672de73a5dd36ecb6523b";
  private static final String M1 = "01a19d7c-c364-7000-b3af-32fa03112e01";
  private static final String M2 = "01a19d7c-c364-7000-b3af-32fa03112e02";
  private static final String M3 = "01a19d7c-c364-7000-b3af-32fa03112e03";

  private static final String DEFINITION =
      """
      name: sail
      description: Isolated development environments for AI agents
      resources:
        cpu: 8
        memory: 32GB
        disk: 200GB
      image: ubuntu/24.04
      runtimes:
        jdk: 26
        node: 24.14.1
        maven: 3.9.14
      git:
        name: '${GIT_NAME}'
        email: '${GIT_EMAIL}'
        auth: token
      repos:
        - url: https://github.com/standardapplied/sail.git
          path: sail
      services:
        meilisearch:
          image: getmeili/meilisearch:latest
          ports: [7700]
          environment:
            MEILI_NO_ANALYTICS: true
      agent:
        type: claude-code
        auto_branch: true
        branch_prefix: agent/
        auto_snapshot: false
        guardrails:
          max_idle: 45m
        install:
          - claude-code
          - codex
        notifications:
          slack:
            channel: C0BELHHT26A
        review_pipeline:
          max_iterations: 6
          stages:
            - name: codeandsecurity
              type: agent
              agent: codex
              gate: all_clear
      ssh:
        user: dev
        authorized_keys:
          - '${SSH_PUBLIC_KEY}'
      """;

  private static final String CONVERTED =
      """
      {"name": "sail", "description": "Isolated development environments for AI agents",
       "resources": {"cpu": 8, "memory": "32GB", "disk": "200GB"}, "image": "ubuntu/24.04",
       "runtimes": {"jdk": 26, "node": "24.14.1", "maven": "3.9.14"},
       "repos": [{"url": "https://github.com/standardapplied/sail.git", "path": "sail"}],
       "services": {"meilisearch": {"image": "getmeili/meilisearch:latest", "ports": [7700],
         "environment": {"MEILI_NO_ANALYTICS": "true"}}},
       "agent": {"type": "claude-code", "auto_branch": true, "branch_prefix": "agent/",
         "auto_snapshot": false, "install": ["claude-code", "codex"],
         "guardrails": {"max_idle": "45m"}, "notifications": {"slack": {"channel": "C0BELHHT26A"}}},
       "pipeline": {"max_iterations": 6, "stages": [{"name": "codeandsecurity", "type": "agent",
         "agent": "codex", "gate": "all_clear"}]}}""";

  private static final Set<String> KEPT_TABLES =
      Set.of(
          "api_tokens",
          "blobs",
          "box_credential",
          "catalog",
          "change_heads",
          "change_log",
          "chunks",
          "fde_boxes",
          "fde_pairings",
          "fde_ssh_keys",
          "fdes",
          "materialized_files",
          "project_files",
          "projects",
          "room_messages",
          "rooms",
          "schema_version",
          "sessions",
          "slack_threads",
          "spec_content",
          "spec_dependencies",
          "spec_repos",
          "specs",
          "sqlite_sequence",
          "webauthn_credentials");

  @TempDir Path dir;
  private Path file;
  private Path hostYaml;

  @BeforeEach
  void setUp() {
    file = dir.resolve("sail.db");
    hostYaml = dir.resolve("host.yaml");
  }

  private void fleet(LegacyDatabase legacy) {
    legacy.fde("fde-uday", "uday");
    legacy.boxCredential("uday");
    legacy.project("sail", DEFINITION, "uday", "sail");
    legacy.file("sail", "setup-dev.sh", "#!/bin/sh\necho up\n".getBytes(StandardCharsets.UTF_8));
    legacy.spec("open-a", "draft", "sail", List.of());
    legacy.spec("done-c", "done", "sail", List.of());
    legacy.spec("archived-d", "archived", "sail", List.of());
    legacy.spec("cancelled-e", "cancelled", "sail", List.of());
    legacy.spec("open-b", "awaiting_merge", "sail", List.of("open-a", "done-c", "archived-d"));
    legacy.message(M1, "open-a", null, false);
    legacy.message(M2, "open-a", M1, true);
    legacy.message(M3, "done-c", null, false);
    legacy.slackThread("sail", "open-a");
    legacy.slackThread("sail", "done-c");
    legacy.run("run-1", "sail", "done-c", "completed", "uday");
    legacy.run("run-2", "sail", "open-b", "running", "rajesh");
    legacy.review("review-1", "done-c");
    legacy.event("agent_tool_started", "done-c");
    legacy.event("spec_stranded", "open-a");
  }

  private void legacyFleet() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      fleet(legacy);
    }
  }

  private LegacyConversion conversion() {
    return LegacyConversion.at(file, hostYaml, LegacyDatabase.QUIET).orElseThrow();
  }

  private LegacyConversion.Report converted() {
    var conversion = conversion();
    conversion.requireConvertible();
    return conversion.convert().orElseThrow();
  }

  private static long millis(String instant) {
    return Instant.parse(instant).toEpochMilli();
  }

  private static Instant instant(String instant) {
    return Instant.ofEpochMilli(millis(instant));
  }

  @Test
  void aMainKeepsItsProjectsFilesOpenSpecsTheirRoomsAndMessagesAndDropsTheRest() {
    legacyFleet();

    var report = converted();

    assertFalse(report.node());
    assertEquals(
        Map.of("projects", 1L, "files", 1L, "specs", 2L, "rooms", 2L, "messages", 2L),
        report.kept());
    assertEquals(3L, report.dropped().get("specs"));
    assertEquals(3L, report.dropped().get("rooms"));
    assertEquals(1L, report.dropped().get("messages"));
    assertEquals(2L, report.dropped().get("runs"));
    assertEquals(1L, report.dropped().get("reviews"));
    assertEquals(2L, report.dropped().get("events"));
    assertEquals(Optional.empty(), report.vacuumFailure());
    try (var db = Sqlite.open(file)) {
      assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(db).currentVersion());
      assertEquals("ok", db.queryOne("PRAGMA integrity_check", row -> row.text(0)).orElseThrow());
      assertEquals(0L, db.queryOne("PRAGMA freelist_count", row -> row.integer(0)).orElseThrow());
      assertEquals(
          CATALOG, db.queryOne("SELECT id FROM catalog", row -> row.text(0)).orElseThrow());
      for (var table :
          db.query(
              "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
              row -> row.text(0))) {
        if (!KEPT_TABLES.contains(table)) {
          assertEquals(
              0L,
              db.queryOne("SELECT COUNT(*) FROM \"" + table + "\"", row -> row.integer(0))
                  .orElseThrow(),
              table + " holds rows the conversion should have dropped");
        }
      }

      var specs = new SpecStore(db);
      var open = specs.findById("open-b").orElseThrow();
      assertEquals(SpecStatus.AWAITING_MERGE, open.status());
      assertEquals("Title of open-b", open.title());
      assertEquals("sail", open.project());
      assertEquals("uday", open.assignee());
      assertEquals("claude-code", open.agent());
      assertEquals("claude-opus-5-5", open.model());
      assertEquals("high", open.reasoningEffort());
      assertEquals("agent/open-b", open.branch());
      assertEquals(2, open.priority());
      assertEquals("uday", open.createdBy());
      assertEquals("mady", open.updatedBy());
      assertEquals(instant(LegacyDatabase.AT), open.createdAt());
      assertEquals(instant(LegacyDatabase.LATER), open.updatedAt());
      assertEquals(List.of("sail"), open.repos());
      assertEquals("open-b", open.roomId());
      assertEquals(
          "# open-b\n\nThe body of open-b.", specs.getContent("open-b").orElseThrow().body());
      assertTrue(specs.findById("open-a").isPresent());
      for (var dropped : List.of("done-c", "archived-d", "cancelled-e")) {
        assertTrue(specs.findById(dropped).isEmpty(), dropped);
      }

      var rooms = new RoomStore(db);
      var room = rooms.findById("open-a").orElseThrow();
      assertEquals("Room of open-a", room.title());
      assertEquals("mention", room.wake());
      assertEquals("uday", room.createdBy());
      assertEquals("mady", room.updatedBy());
      assertEquals(instant(LegacyDatabase.AT), room.createdAt());
      assertEquals(instant(LegacyDatabase.LATER), room.updatedAt());
      assertTrue(rooms.findById("done-c").isEmpty());

      var messages = new MessageStore(db).list("open-a", null, 10);
      assertEquals(Set.of(M1, M2), Set.copyOf(messages.stream().map(m -> m.id()).toList()));
      var reply = messages.stream().filter(m -> m.id().equals(M2)).findFirst().orElseThrow();
      assertEquals(M1, reply.replyTo());
      assertTrue(reply.question());
      assertEquals("rajesh", reply.author());
      assertEquals(instant(LegacyDatabase.AT), reply.createdAt());
      assertTrue(new MessageStore(db).findById(M3).isEmpty());

      var project = new ProjectStore(db).findByName("sail").orElseThrow();
      assertEquals(ProjectDefinitionJson.read(CONVERTED), project.definition());
      assertEquals("uday", project.createdBy());
      assertEquals(instant(LegacyDatabase.AT), project.createdAt());
      assertEquals(instant(LegacyDatabase.LATER), project.updatedAt());

      var files = new FileStore(db);
      var setup = files.find("sail", "setup-dev.sh").orElseThrow();
      assertEquals(493, setup.mode());
      assertEquals(
          millis(LegacyDatabase.LATER),
          db.queryOne("SELECT updated_at FROM project_files", row -> row.integer(0)).orElseThrow());
      try (var content = files.open(setup)) {
        assertEquals(
            "#!/bin/sh\necho up\n", new String(content.readAllBytes(), StandardCharsets.UTF_8));
      }

      assertEquals(
          List.of("sail|open-a|" + millis("2026-09-22T18:38:05Z")),
          db.query(
              "SELECT project || '|' || spec_id || '|' || created_at FROM slack_threads",
              row -> row.text(0)));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void everyKeptEntityIsJournaledOnceAtItsLegacyRevisionByItsLegacyAuthor() {
    legacyFleet();

    converted();

    try (var db = Sqlite.open(file)) {
      assertEquals(
          List.of(
              "file|sail/setup-dev.sh|4-sail/setup-dev.sh|revision|sync",
              "message|" + M1 + "|1-" + M1 + "|revision|sync",
              "message|" + M2 + "|1-" + M2 + "|revision|sync",
              "project|sail|3-sail|revision|sync",
              "room|open-a|2-open-a|revision|sync",
              "room|open-b|2-open-b|revision|sync",
              "spec|open-a|5-open-a|revision|sync",
              "spec|open-b|5-open-b|revision|sync"),
          db.query(
              """
              SELECT entity_type || '|' || entity_id || '|' || rev || '|' || kind || '|' || origin
              FROM change_log ORDER BY entity_type, entity_id""",
              row -> row.text(0)));
      assertEquals(
          List.of("message|rajesh", "project|sail", "room|mady", "spec|mady"),
          db.query(
              "SELECT DISTINCT entity_type || '|' || actor FROM change_log"
                  + " WHERE actor IS NOT NULL ORDER BY 1",
              row -> row.text(0)));
      assertEquals(
          8L,
          db.queryOne("SELECT COUNT(*) FROM change_heads", row -> row.integer(0)).orElseThrow());
    }
  }

  @Test
  void everyOpenSpecIsKeptWithItsRoomAndEveryClosedOneIsDropped() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      for (var status :
          List.of(
              "draft",
              "pending",
              "in_progress",
              "review",
              "awaiting_merge",
              "done",
              "cancelled",
              "archived")) {
        legacy.spec("spec-" + status, status, "sail", List.of());
      }
      legacy.db.execute("UPDATE specs SET room_id = NULL WHERE id = 'spec-review'");
    }

    converted();

    try (var db = Sqlite.open(file)) {
      var open =
          List.of(
              "spec-awaiting_merge",
              "spec-draft",
              "spec-in_progress",
              "spec-pending",
              "spec-review");
      assertEquals(open, db.query("SELECT id FROM specs ORDER BY id", row -> row.text(0)));
      assertEquals(open, db.query("SELECT id FROM rooms ORDER BY id", row -> row.text(0)));
      assertEquals("spec-review", new SpecStore(db).findById("spec-review").orElseThrow().roomId());
    }
  }

  @Test
  void aRowWhoseOwnerIsGoneFailsTheConversionOnTheForeignKeyItBreaks() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      fleet(legacy);
      legacy.db.execute("PRAGMA foreign_keys = OFF");
      legacy.db.execute(
          "INSERT INTO sessions (token_hash, fde_id, created_at, expires_at) VALUES ('orphan',"
              + " 'fde-gone', ?, ?)",
          LegacyDatabase.AT,
          LegacyDatabase.LATER);
    }

    var refused = assertThrows(IllegalStateException.class, () -> converted());

    assertEquals(
        "The converted database breaks its foreign keys in [sessions]", refused.getMessage());
    assertUnchanged();
  }

  @Test
  void aKeptSpecDependsOnlyOnTheSpecsThatAreKept() {
    legacyFleet();

    converted();

    try (var db = Sqlite.open(file)) {
      assertEquals(
          List.of("open-a"), new SpecStore(db).findById("open-b").orElseThrow().dependsOn());
    }
  }

  @Test
  void theBoxsOwnRowsAreKeptWithTheirTimesAsMillis() {
    legacyFleet();

    converted();

    try (var db = Sqlite.open(file)) {
      assertEquals(
          List.of("fde-uday|uday|admin|" + millis(LegacyDatabase.AT)),
          db.query(
              "SELECT id || '|' || handle || '|' || role || '|' || created_at FROM fdes",
              row -> row.text(0)));
      assertEquals(
          List.of(
              "session-uday|"
                  + millis(LegacyDatabase.AT)
                  + "|"
                  + millis("2026-12-01T00:00:00Z")
                  + "|"
                  + millis(LegacyDatabase.LATER)),
          db.query(
              "SELECT token_hash || '|' || created_at || '|' || expires_at || '|' || last_used_at"
                  + " FROM sessions",
              row -> row.text(0)));
      for (var table :
          List.of(
              "fde_ssh_keys",
              "webauthn_credentials",
              "api_tokens",
              "fde_pairings",
              "fde_boxes",
              "box_credential",
              "materialized_files")) {
        assertEquals(
            1L,
            db.queryOne("SELECT COUNT(*) FROM " + table, row -> row.integer(0)).orElseThrow(),
            table);
      }
      assertEquals(
          List.of(),
          db.query(
              """
              SELECT 'sessions' FROM sessions WHERE typeof(created_at) <> 'integer'
              UNION ALL SELECT 'fdes' FROM fdes WHERE typeof(created_at) <> 'integer'
              UNION ALL SELECT 'webauthn' FROM webauthn_credentials
                  WHERE typeof(last_used_at) <> 'integer'""",
              row -> row.text(0)));
    }
  }

  @Test
  void aNodeKeepsOnlyItsOwnRowsAndTheMainItSyncsWith() throws IOException {
    try (var legacy = LegacyDatabase.create(file, "node-catalog")) {
      fleet(legacy);
      legacy.syncsWith(CATALOG);
    }
    Files.writeString(hostYaml, "sync:\n  role: node\n  main: main.example\n  handle: mady\n");

    var report = converted();

    assertTrue(report.node());
    assertEquals(Map.of(), report.kept());
    assertEquals(5L, report.dropped().get("specs"));
    try (var db = Sqlite.open(file)) {
      assertEquals(
          List.of("node-catalog|" + CATALOG),
          db.query("SELECT id || '|' || main FROM catalog", row -> row.text(0)));
      for (var table :
          List.of(
              "specs",
              "rooms",
              "room_messages",
              "projects",
              "project_files",
              "blobs",
              "change_log",
              "slack_threads")) {
        assertEquals(
            0L,
            db.queryOne("SELECT COUNT(*) FROM " + table, row -> row.integer(0)).orElseThrow(),
            table);
      }
      assertEquals(
          List.of("uday"), db.query("SELECT handle FROM box_credential", row -> row.text(0)));
      assertEquals(
          1L,
          db.queryOne("SELECT COUNT(*) FROM materialized_files", row -> row.integer(0))
              .orElseThrow());
    }
  }

  @Test
  void aProjectTheLegacyJournalDeletedStaysDeletedSoTheDemoIsNotSeededAgain() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      legacy.project("sail", DEFINITION, "uday", "sail");
      legacy.journal("project", "demo", "2-demo", "uday", "erasure");
      legacy.journal("project", "old-site", "3-old-site", "mady", "tombstone");
    }

    converted();

    try (var db = Sqlite.open(file)) {
      var projects = new ProjectStore(db);
      assertTrue(projects.findByName("demo").isEmpty());
      assertEquals("2-demo", projects.latestRev("demo"));
      assertEquals("3-old-site", projects.latestRev("old-site"));
      assertEquals(
          List.of("demo|uday", "old-site|mady"),
          db.query(
              "SELECT entity_id || '|' || actor FROM change_log WHERE kind = 'tombstone'"
                  + " ORDER BY entity_id",
              row -> row.text(0)));
    }
  }

  @Test
  void aRunGoingOnThisBoxRefusesTheConversionAndChangesNothing() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      fleet(legacy);
      legacy.run("run-3", "sail", "open-a", "stopping", null);
    }

    var refused =
        assertThrows(IllegalStateException.class, () -> conversion().requireConvertible());
    var again = assertThrows(IllegalStateException.class, () -> conversion().convert());

    assertTrue(Remedied.reasonOf(refused).contains("run-3 (sail)"), refused.getMessage());
    assertEquals(
        List.of("systemctl --user stop sail-agent-<run>", "migrate"),
        Remedied.remedyOf(refused).commands().stream().map(Remedy.Command::line).toList());
    assertEquals(Remedied.reasonOf(refused), Remedied.reasonOf(again));
    assertUnchanged();
  }

  @Test
  void aRunOfThisBoxsFdeRefusesWhileARunOfAnotherNodeDoesNot() throws IOException {
    Files.writeString(hostYaml, "sync:\n  role: main\n  handle: rajesh\n");
    legacyFleet();

    var refused =
        assertThrows(IllegalStateException.class, () -> conversion().requireConvertible());

    assertTrue(refused.getMessage().contains("run-2 (sail)"), refused.getMessage());
    Files.writeString(hostYaml, "sync:\n  role: main\n  handle: uday\n");
    conversion().requireConvertible();
  }

  @Test
  void aRunOfThisBoxWhoseUnitStoppedIsARowItsWatcherNeverClosedAndDoesNotRefuse() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      fleet(legacy);
      legacy.run("run-3", "sail", "open-a", "running", null);
      legacy.run("run-4", "sail", "open-a", "running", null);
      legacy.db.execute(
          "UPDATE runs SET unit = 'sail-agent-' || id WHERE id IN ('run-3', 'run-4')");
    }
    var asked = new ArrayList<String>();
    var host =
        new LegacyConversion.Host() {
          @Override
          public boolean running(String project, String unit) {
            asked.add(project + " " + unit);
            return unit.equals("sail-agent-run-4");
          }

          @Override
          public void requireNoOtherHolder() {}
        };
    var conversion = LegacyConversion.at(file, hostYaml, host).orElseThrow();

    var refused = assertThrows(IllegalStateException.class, conversion::requireConvertible);

    assertTrue(Remedied.reasonOf(refused).endsWith(": run-4 (sail)."), refused.getMessage());
    assertEquals(List.of("sail sail-agent-run-3", "sail sail-agent-run-4"), asked);
    try (var db = Sqlite.open(file)) {
      db.execute("UPDATE runs SET status = 'stopped' WHERE id = 'run-4'");
    }
    assertTrue(conversion.convert().isPresent());
  }

  @Test
  void aProcessStillHoldingTheDatabaseUnderTheWriteLockFailsTheConversionAndChangesNothing() {
    legacyFleet();
    var host =
        new LegacyConversion.Host() {
          @Override
          public boolean running(String project, String unit) {
            return false;
          }

          @Override
          public void requireNoOtherHolder() {
            throw new IllegalStateException("pid 4242 holds it");
          }
        };

    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> LegacyConversion.at(file, hostYaml, host).orElseThrow().convert());

    assertEquals("pid 4242 holds it", refused.getMessage());
    assertUnchanged();
  }

  @Test
  void aDatabaseThatSyncsWithAMainOnABoxDeclaredNoNodeIsRefused() throws IOException {
    try (var legacy = LegacyDatabase.create(file, "node-catalog")) {
      fleet(legacy);
      legacy.syncsWith(CATALOG);
    }

    var missing = assertThrows(IllegalStateException.class, () -> conversion());
    Files.writeString(hostYaml, "sync:\n  role: main\n  handle: uday\n");
    var main = assertThrows(IllegalStateException.class, () -> conversion());

    assertTrue(
        missing.getMessage().contains("yet its database syncs with main " + CATALOG),
        missing.getMessage());
    assertEquals(Remedied.reasonOf(missing), Remedied.reasonOf(main));
    assertUnchanged();
  }

  @Test
  void aConversionAnotherMigrateMadeFirstLeavesNothingToDo() throws IOException {
    legacyFleet();
    var second = conversion();
    var first = conversion();
    var backup = first.convert().orElseThrow().backup();
    var aside = Files.move(backup, dir.resolve("aside.bak"));

    assertEquals(Optional.empty(), second.convert());

    assertEquals(SchemaManager.CURRENT_VERSION, versionOf(file));
    assertEquals(LegacyConversion.VERSION, versionOf(aside));
    assertFalse(Files.exists(backup));
  }

  @Test
  void aProjectKeepsItsLastEditorWhoBecomesItsCreatorWhenItRecordedNone() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      legacy.project("sail", DEFINITION, null, "sail");
      legacy.db.execute("UPDATE projects SET updated_by = 'mady'");
    }

    converted();

    try (var db = Sqlite.open(file)) {
      var project = new ProjectStore(db).findByName("sail").orElseThrow();
      assertEquals("mady", project.updatedBy());
      assertEquals("mady", project.createdBy());
    }
  }

  @Test
  void anExistingBackupRefusesTheConversionAndIsLeftAsItIs() throws IOException {
    legacyFleet();
    Files.writeString(file.resolveSibling("sail.db.0.58.bak"), "an earlier backup");

    var refused = assertThrows(IllegalStateException.class, () -> conversion().convert());

    assertTrue(refused.getMessage().contains("already at"), refused.getMessage());
    assertEquals("an earlier backup", Files.readString(conversion().backup()));
    assertEquals(LegacyConversion.VERSION, versionOf(file));
  }

  @Test
  void aHalfMadeBackupADeadConversionLeftIsClearedAndMadeAgain() throws IOException {
    legacyFleet();
    var staging = Files.createDirectory(file.resolveSibling("sail.db.0.58.bak.partial"));
    Files.writeString(staging.resolve("sail.db.0.58.bak"), "half a copy");

    var report = converted();

    assertEquals(LegacyConversion.VERSION, versionOf(report.backup()));
    assertFalse(Files.exists(staging));
  }

  @Test
  void aStagingDirectoryHoldingSomethingElseFailsTheConversionAndChangesNothing()
      throws IOException {
    legacyFleet();
    var staging = Files.createDirectory(file.resolveSibling("sail.db.0.58.bak.partial"));
    Files.writeString(staging.resolve("notes"), "not ours");

    var refused = assertThrows(IllegalStateException.class, () -> conversion().convert());

    assertTrue(
        Remedied.reasonOf(refused)
            .startsWith(
                "Could not back up "
                    + file
                    + " to "
                    + file.resolveSibling("sail.db.0.58.bak")
                    + " (java.nio.file.DirectoryNotEmptyException: "
                    + staging),
        refused.getMessage());
    assertTrue(
        refused.getMessage().contains("move " + staging + " aside if it is there"),
        refused.getMessage());
    assertEquals("not ours", Files.readString(staging.resolve("notes")));
    assertEquals(LegacyConversion.VERSION, versionOf(file));
    assertFalse(Files.exists(file.resolveSibling("sail.db.0.58.bak")));
  }

  @Test
  void aBackupThatFailsHalfwayTakesItsCopyAwayAndChangesNothing() throws IOException {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      fleet(legacy);
      legacy.run("run-3", "sail", "open-a", "running", null);
      legacy.db.execute("UPDATE runs SET unit = 'sail-agent-run-3' WHERE id = 'run-3'");
    }
    var backup = file.resolveSibling("sail.db.0.58.bak");
    var host =
        new LegacyConversion.Host() {
          @Override
          public boolean running(String project, String unit) {
            try {
              Files.writeString(Files.createDirectories(backup).resolve("in the way"), "x");
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            return false;
          }

          @Override
          public void requireNoOtherHolder() {}
        };

    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> LegacyConversion.at(file, hostYaml, host).orElseThrow().convert());

    assertTrue(
        Remedied.reasonOf(refused).startsWith("Could not back up " + file + " to " + backup),
        refused.getMessage());
    assertEquals(1, refused.getSuppressed().length);
    assertFalse(Files.exists(file.resolveSibling("sail.db.0.58.bak.partial")));
    assertEquals(LegacyConversion.VERSION, versionOf(file));
  }

  /** Ubuntu's SQLite refuses a path of 512 characters or more; Apple's takes longer ones. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aCopySqliteCannotMakeFailsWithTheRemedyAndChangesNothing() throws IOException {
    var deep = dir;
    while (482 - deep.toString().length() > 150) {
      deep = deep.resolve("d".repeat(100));
    }
    deep = deep.resolve("d".repeat(482 - deep.toString().length() - 1));
    var deepFile = Files.createDirectories(deep).resolve("sail.db");
    assertEquals(490, deepFile.toString().length());
    try (var legacy = LegacyDatabase.create(deepFile, CATALOG)) {
      fleet(legacy);
    }
    var staging = deepFile.resolveSibling("sail.db.0.58.bak.partial");

    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                LegacyConversion.at(deepFile, hostYaml, LegacyDatabase.QUIET)
                    .orElseThrow()
                    .convert());

    assertTrue(refused.getCause() instanceof SqliteException, refused.getMessage());
    assertTrue(
        refused.getMessage().contains("move " + staging + " aside if it is there"),
        refused.getMessage());
    assertFalse(Files.exists(staging));
    assertFalse(Files.exists(deepFile.resolveSibling("sail.db.0.58.bak")));
    assertEquals(LegacyConversion.VERSION, versionOf(deepFile));
  }

  @Test
  void aHostYamlThatCannotBeReadRefusesBeforeAnythingIsDecided() throws IOException {
    legacyFleet();

    Files.writeString(hostYaml, "");
    var empty = assertThrows(IllegalStateException.class, () -> conversion());
    Files.writeString(hostYaml, "sync: [not, a, map");
    var garbled = assertThrows(IllegalStateException.class, () -> conversion());

    assertTrue(empty.getMessage().contains("Cannot tell whether this box is main or a node"));
    assertTrue(empty.getMessage().contains("it is empty"), empty.getMessage());
    assertTrue(garbled.getMessage().contains(hostYaml.toString()), garbled.getMessage());
    assertUnchanged();
  }

  @Test
  void aDefinitionThisReleaseRefusesFailsTheWholeConversionAndChangesNothing() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      fleet(legacy);
      legacy.project("broken", "name: broken\nwebhooks: [one]\n", "uday", "uday");
    }

    var refused = assertThrows(IllegalStateException.class, () -> converted());

    assertTrue(refused.getMessage().contains("Project 'broken' cannot be converted"));
    assertTrue(refused.getMessage().contains("webhooks"), refused.getMessage());
    assertEquals(
        List.of("project edit broken", "migrate"),
        Remedied.remedyOf(refused).commands().stream().map(Remedy.Command::line).toList());
    assertUnchanged();
  }

  @Test
  void aTimeThatIsNoTimeFailsTheConversionNamingWhereItIs() {
    try (var legacy = LegacyDatabase.create(file, CATALOG)) {
      fleet(legacy);
      legacy.db.execute("UPDATE sessions SET last_used_at = 'yesterday'");
    }

    var refused = assertThrows(IllegalStateException.class, () -> converted());

    assertEquals(
        "sessions.last_used_at holds 'yesterday', which is not a time.", refused.getMessage());
    assertUnchanged();
  }

  @Test
  void theBackupHoldsTheDatabaseAsItWasConvertedAndOnlyItsOwnerMayReadIt() throws IOException {
    legacyFleet();

    var report = converted();

    assertEquals(file.resolveSibling("sail.db.0.58.bak"), report.backup());
    assertEquals(LegacyConversion.VERSION, versionOf(report.backup()));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(report.backup()));
    assertFalse(Files.exists(file.resolveSibling("sail.db.0.58.bak.partial")));
    try (var backup = Sqlite.open(report.backup())) {
      assertEquals(
          5L, backup.queryOne("SELECT COUNT(*) FROM specs", row -> row.integer(0)).orElseThrow());
      assertEquals(
          2L, backup.queryOne("SELECT COUNT(*) FROM runs", row -> row.integer(0)).orElseThrow());
    }
  }

  @Test
  void onlyADatabaseSail058MadeIsConverted() {
    assertTrue(LegacyConversion.at(file, hostYaml, LegacyDatabase.QUIET).isEmpty());
    try (var fresh = Sqlite.open(file)) {
      new SchemaManager(fresh).migrate();
    }
    assertTrue(LegacyConversion.at(file, hostYaml, LegacyDatabase.QUIET).isEmpty());

    var older = dir.resolve("older.db");
    try (var legacy = LegacyDatabase.create(older, CATALOG)) {
      legacy.db.execute("DELETE FROM schema_version WHERE version = ?", LegacyConversion.VERSION);
    }
    assertTrue(LegacyConversion.at(older, hostYaml, LegacyDatabase.QUIET).isEmpty());

    var unversioned = dir.resolve("unversioned.db");
    try (var db = Sqlite.open(unversioned)) {
      db.execute("CREATE TABLE notes (body TEXT)");
    }
    assertTrue(LegacyConversion.at(unversioned, hostYaml, LegacyDatabase.QUIET).isEmpty());
  }

  @Test
  void aConvertedDatabaseIsNotConvertedAgain() {
    legacyFleet();
    converted();

    assertTrue(LegacyConversion.at(file, hostYaml, LegacyDatabase.QUIET).isEmpty());
  }

  @Test
  void aDefinitionLosesItsGitAndSshBlocksAndItsPipelineMovesOutOfAgent() {
    assertEquals(
        ProjectDefinitionJson.read(CONVERTED), LegacyConversion.definition("sail", DEFINITION));
  }

  @Test
  void aDefinitionWithTwoPipelinesIsRefusedNamingTheProject() {
    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                LegacyConversion.definition(
                    "sail",
                    "name: sail\npipeline:\n  max_iterations: 2\nagent:\n  type: codex\n"
                        + "  review_pipeline:\n    max_iterations: 3\n"));

    assertEquals(
        "Project 'sail' cannot be converted: it declares both agent.review_pipeline and pipeline."
            + " Nothing was changed.",
        Remedied.reasonOf(refused));
  }

  @Test
  void aDefinitionWhosePortsClashIsRefusedAsEveryWriteRefusesIt() {
    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                LegacyConversion.definition(
                    "sail",
                    """
                    name: sail
                    services:
                      postgres:
                        image: postgres:16
                        ports: [5432]
                    processes:
                      api:
                        command: ./run
                        port: 5432
                    """));

    assertTrue(
        Remedied.reasonOf(refused).contains("Port 5432 is claimed by both"), refused.getMessage());
  }

  @Test
  void aTimeIsItsEpochMillisInEitherShape058StoredIt() {
    assertEquals(1790847015123L, LegacyConversion.millis("2026-10-01T09:30:15.123456789Z", "t"));
    assertEquals(1790847015120L, LegacyConversion.millis("2026-10-01T09:30:15.12Z", "t"));
    assertEquals(1790847015000L, LegacyConversion.millis("2026-10-01T09:30:15Z", "t"));
    assertEquals(1790847015999L, LegacyConversion.millis("2026-10-01T09:30:15.999999999Z", "t"));
    assertEquals(1790847015000L, LegacyConversion.millis("2026-10-01 09:30:15", "t"));
    var refused =
        assertThrows(
            IllegalStateException.class, () -> LegacyConversion.millis("2026-10-01", "a.b"));
    assertEquals("a.b holds '2026-10-01', which is not a time.", refused.getMessage());
  }

  private void assertUnchanged() {
    assertEquals(LegacyConversion.VERSION, versionOf(file));
    assertFalse(Files.exists(file.resolveSibling("sail.db.0.58.bak")));
    assertFalse(Files.exists(file.resolveSibling("sail.db.0.58.bak.partial")));
    try (var db = Sqlite.open(file)) {
      assertNotNull(db.queryOne("SELECT COUNT(*) FROM runs", row -> row.integer(0)).orElseThrow());
      assertEquals(
          List.of(),
          db.query(
              "SELECT name FROM sqlite_master WHERE name LIKE 'legacy_%'", row -> row.text(0)));
      assertTrue(
          db.queryOne("SELECT COUNT(*) FROM specs", row -> row.integer(0)).orElseThrow() >= 5);
    }
  }

  private static int versionOf(Path db) {
    try (var sqlite = Sqlite.open(db)) {
      return sqlite
          .queryOne("SELECT MAX(version) FROM schema_version", row -> (int) row.integer(0))
          .orElseThrow();
    }
  }
}
