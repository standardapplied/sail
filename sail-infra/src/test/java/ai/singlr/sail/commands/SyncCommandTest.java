/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncCommandTest {

  @Test
  void syncStatusReadsThisBoxesStoredHealthInProcess(@TempDir Path home) throws Exception {
    try (var db = ai.singlr.sail.store.Sqlite.open(home.resolve("sail.db"))) {
      new ai.singlr.sail.store.SchemaManager(db).migrate();
      var config = new ai.singlr.sail.config.SyncConfig("node", "sail@main", "node");
      var operations =
          ai.singlr.sail.api.OperationsFactory.create(
                  db,
                  new ai.singlr.sail.engine.ShellExecutor(true),
                  "sail.yaml",
                  null,
                  null,
                  ai.singlr.sail.api.SyncScheduler.disabled(),
                  ai.singlr.sail.api.SessionYield.NONE)
              .useControlPlane(
                  db,
                  home,
                  new ai.singlr.sail.engine.SyncOperations(
                      db,
                      "node",
                      home,
                      () -> config,
                      target -> {
                        throw new java.io.IOException("main unavailable");
                      }));
      java.util.function.Supplier<SyncCommand.Status> status =
          () -> new SyncCommand.Status(() -> operations);
      assertEquals(
          Map.of("role", "node", "main", "sail@main", "consecutive_failures", 0),
          nonNull(capture(() -> new picocli.CommandLine(status.get()).execute("--json"))),
          "nothing attempted yet: no state, no timestamps");

      var health = new ai.singlr.sail.store.SyncHealth(db);
      health.begin("sail@main", java.time.Instant.parse("2026-09-14T00:00:00Z"));
      health.failed(
          "sail@main",
          java.time.Instant.parse("2026-09-14T00:00:00Z"),
          "protocol",
          "message: page exceeded 4 MiB");
      var stale = nonNull(capture(() -> new picocli.CommandLine(status.get()).execute("--json")));
      assertEquals("stale", stale.get("state"));
      assertEquals("protocol", stale.get("last_error_kind"));
      assertEquals(1, stale.get("consecutive_failures"));
      assertEquals(
          "Stale since 2026-09-14T00:00:00Z — message: page exceeded 4 MiB",
          SyncCommand.renderStatus(stale));
    }
    assertEquals(
        "Syncing with main", SyncCommand.renderStatus(Map.of("state", "syncing", "main", "main")));
    assertEquals(
        "In sync with main", SyncCommand.renderStatus(Map.of("state", "in_sync", "main", "main")));
    assertEquals("No sync round yet with main", SyncCommand.renderStatus(Map.of("main", "main")));
    assertEquals("Not a node: nothing to sync with.", SyncCommand.renderStatus(Map.of()));
  }

  private static Map<String, Object> capture(Runnable command) {
    var output = new java.io.ByteArrayOutputStream();
    var original = System.out;
    try (var stream =
        new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
      System.setOut(stream);
      command.run();
    } finally {
      System.setOut(original);
    }
    return ai.singlr.sail.config.YamlUtil.parseMap(
        output.toString(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static Map<String, Object> nonNull(Map<String, Object> map) {
    var kept = new java.util.LinkedHashMap<String, Object>();
    map.forEach(
        (k, v) -> {
          if (v != null) kept.put(k, v);
        });
    return kept;
  }

  @Test
  void resolveMainPrefersTheExplicitFlag() {
    var resolved =
        SyncCommand.resolveMain(
            "sail@override", new SyncConfig(SyncConfig.ROLE_NODE, "sail@cfg", null));
    assertEquals("sail@override", resolved.target());
  }

  @Test
  void resolveMainFallsBackToTheConfiguredMain() {
    var resolved =
        SyncCommand.resolveMain(
            null, new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox", null));
    assertEquals("sail@maindevbox", resolved.target());
  }

  @Test
  void resolveMainHasNoTargetWhenThisBoxIsMain() {
    var resolved = SyncCommand.resolveMain(null, new SyncConfig(SyncConfig.ROLE_MAIN, null, null));
    assertNull(resolved.target());
    assertTrue(resolved.message().contains("main devbox"));
  }

  @Test
  void resolveMainHasNoTargetAndGuidesWhenStandalone() {
    var resolved = SyncCommand.resolveMain("  ", SyncConfig.unset());
    assertNull(resolved.target());
    assertTrue(resolved.message().contains("Single devbox"));
    assertTrue(resolved.message().contains("sail host sync --main"));
  }

  @Test
  void rendersJsonReport() {
    var json =
        SyncCommand.render(
            new SyncReport(
                new SyncEngine.Report(1, 2, 3, 4),
                null,
                List.of(
                    new SyncSession.TypeReport(
                        "spec", new SyncEngine.Report(1, 2, 3, 4), 2, 40, false, null))),
            true);
    assertEquals(
        "{\"pulled\": 1, \"pushed\": 2, \"merged\": 3, \"conflicts\": 4, \"types\": [{\"type\":"
            + " \"spec\", \"pulled\": 1, \"pushed\": 2, \"merged\": 3, \"conflicts\": 4, \"pages\":"
            + " 2, \"entries\": 40, \"skipped\": false, \"failure\": null}]}",
        json);
  }

  @Test
  void rendersConvergedHumanReport() {
    var text =
        SyncCommand.render(
            new SyncReport(
                SyncEngine.Report.NONE,
                null,
                List.of(
                    new SyncSession.TypeReport("spec", SyncEngine.Report.NONE, 0, 0, true, null))),
            false);
    assertTrue(text.contains("Already in sync"));
    assertFalse(text.contains("spec"), "a skipped type earns no line");
  }

  @Test
  void rendersChangesWithoutConflicts() {
    var text =
        SyncCommand.render(
            new SyncReport(
                new SyncEngine.Report(2, 1, 0, 0),
                null,
                List.of(
                    new SyncSession.TypeReport(
                        "spec", new SyncEngine.Report(2, 1, 0, 0), 3, 12, false, null),
                    new SyncSession.TypeReport("file", SyncEngine.Report.NONE, 0, 0, true, null))),
            false);
    assertTrue(text.contains("pulled"));
    assertFalse(text.contains("conflict"));
    assertTrue(text.contains("spec:"), text);
    assertTrue(text.contains("3 page(s), 12 entries"), text);
    assertFalse(text.contains("file"), "a skipped type earns no line");
  }

  @Test
  void rendersConflictGuidanceWhenConflictsExist() {
    var text =
        SyncCommand.render(
            new SyncReport(
                new SyncEngine.Report(0, 0, 0, 2),
                null,
                List.of(SyncSession.TypeReport.failed("run", "run: main is away"))),
            false);
    assertTrue(text.contains("2 conflict(s) need your decision"));
    assertTrue(text.contains("sail conflicts"));
    assertTrue(text.contains("run: main is away"), "a failed type is named");
  }

  @Test
  void notifiesOnlyWhenAroundBringsRemoteWorkOrAConflict() {
    assertTrue(SyncCommand.shouldNotify(new SyncEngine.Report(1, 0, 0, 0)));
    assertTrue(SyncCommand.shouldNotify(new SyncEngine.Report(0, 0, 1, 0)));
    assertTrue(SyncCommand.shouldNotify(new SyncEngine.Report(0, 0, 0, 1)));
    assertFalse(
        SyncCommand.shouldNotify(new SyncEngine.Report(0, 5, 0, 0)), "a pure push is local");
    assertFalse(SyncCommand.shouldNotify(new SyncEngine.Report(0, 0, 0, 0)));
  }

  @Test
  void applyFdesMirrorsValidEntriesAndReportsRejectedOnes(@TempDir Path tempDir) {
    try (var db = Sqlite.open(tempDir.resolve("test.db"))) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      var roster =
          List.<Map<String, Object>>of(
              Map.of("handle", "ada", "role", "admin", "status", "active"),
              Map.of("handle", "bad", "role", "superuser", "status", "active"));

      var rejected = SyncCommand.applyFdes(fdes, roster);

      assertEquals(List.of("bad"), rejected);
      assertEquals("admin", fdes.byHandle("ada").orElseThrow().role());
      assertTrue(fdes.byHandle("bad").isEmpty());
    }
  }

  @Test
  void boardUpdatedEventCarriesTheCountsAndScope() {
    var event = SyncCommand.boardUpdatedEvent("devbox", new SyncEngine.Report(3, 1, 2, 4));

    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("devbox", event.host());
    assertEquals(3, event.data().get("pulled"));
    assertEquals(2, event.data().get("merged"));
    assertEquals(4, event.data().get("conflicts"));
  }

  @Test
  void reasonUsesTheMessageOrFallsBackToTheExceptionType() {
    assertEquals("boom", SyncCommand.reason(new IllegalStateException("boom")));
    assertEquals("RuntimeException", SyncCommand.reason(new RuntimeException()));
    assertEquals("IllegalStateException", SyncCommand.reason(new IllegalStateException("  ")));
  }

  @Test
  void pulledMessageEventsFireOnlyForNewlyAdoptedMessages(@TempDir Path tempDir) {
    try (var db = Sqlite.open(tempDir.resolve("pull.db"))) {
      new SchemaManager(db).migrate();
      var specs = new SpecStore(db);
      var messages = new MessageStore(db);
      seedSpec(specs, "auth");
      messages.append("auth", "uday", "posted before the round", null);
      var known = messages.syncEntityIds();
      var pulledId = DateTimeUtils.newId().toString();
      messages.applyRevision(
          pulledId,
          Map.of(
              "spec_id", "auth",
              "author", "raj",
              "body", "hello from main",
              "created_at", DateTimeUtils.now().toString()),
          "r1");

      var events = SyncCommand.pulledMessageEvents(messages, specs, known, "devbox");

      assertEquals(1, events.size());
      var event = events.getFirst();
      assertEquals(Event.WellKnownTypes.SPEC_MESSAGE_POSTED, event.type());
      assertEquals("acme", event.project());
      assertEquals("auth", event.spec());
      assertEquals("raj", event.agent());
      assertEquals("devbox", event.host());
      assertEquals(pulledId, event.data().get("message_id"));
      assertEquals("hello from main", event.data().get("preview"));
      assertEquals(Event.WellKnownData.SOURCE_SYNC, event.data().get(Event.WellKnownData.SOURCE));
      assertTrue(
          SyncCommand.pulledMessageEvents(messages, specs, messages.syncEntityIds(), "devbox")
              .isEmpty(),
          "a message already known before the round never refires");
    }
  }

  @Test
  void aPulledMessageWhoseSpecNeverLandedIsSkipped(@TempDir Path tempDir) {
    try (var db = Sqlite.open(tempDir.resolve("orphan.db"))) {
      new SchemaManager(db).migrate();
      var specs = new SpecStore(db);
      var messages = new MessageStore(db);
      seedSpec(specs, "auth");
      var orphanId = DateTimeUtils.newId().toString();
      messages.applyRevision(
          orphanId,
          Map.of(
              "spec_id", "ghost",
              "author", "raj",
              "body", "orphaned on main",
              "created_at", DateTimeUtils.now().toString()),
          "r1");

      assertTrue(SyncCommand.pulledMessageEvents(messages, specs, Set.of(), "devbox").isEmpty());
    }
  }

  private static void seedSpec(SpecStore specs, String id) {
    specs.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "OAuth flow",
            SpecStatus.DONE,
            "uday",
            null,
            null,
            null,
            null,
            0,
            "uday",
            "",
            "",
            null,
            List.of(),
            List.of("app")));
  }
}
