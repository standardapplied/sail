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
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncCommandTest {

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
    var json = SyncCommand.render(new SyncEngine.Report(1, 2, 3, 4), true);
    assertEquals("{\"pulled\": 1, \"pushed\": 2, \"merged\": 3, \"conflicts\": 4}", json);
  }

  @Test
  void rendersConvergedHumanReport() {
    var text = SyncCommand.render(new SyncEngine.Report(0, 0, 0, 0), false);
    assertTrue(text.contains("Already in sync"));
  }

  @Test
  void rendersChangesWithoutConflicts() {
    var text = SyncCommand.render(new SyncEngine.Report(2, 1, 0, 0), false);
    assertTrue(text.contains("pulled"));
    assertFalse(text.contains("conflict"));
  }

  @Test
  void rendersConflictGuidanceWhenConflictsExist() {
    var text = SyncCommand.render(new SyncEngine.Report(0, 0, 0, 2), false);
    assertTrue(text.contains("2 conflict(s) need your decision"));
    assertTrue(text.contains("sail conflicts"));
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
