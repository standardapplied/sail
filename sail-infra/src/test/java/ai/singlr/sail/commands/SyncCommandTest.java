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

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.MainHostKey;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncHealth;
import ai.singlr.sail.sync.Settlement;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncTransportException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

class SyncCommandTest {

  @Test
  void aStoreFailureMainServesKeepsItsRemedyForTheNodesReader() throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      main.create(SyncBox.spec("taken", "Taken", "pending"));
      main.db.execute("UPDATE specs SET body_hash = NULL WHERE id = 'taken'");
      SyncTransportException failed;
      try (var link = SyncBox.connect(main.server(node.session()), node)) {
        failed =
            assertThrows(
                SyncTransportException.class,
                () -> link.reconcile("spec", node.replicas().get("spec")));
      }

      assertEquals("store", failed.kind());
      assertEquals(List.of(Remedy.sail("migrate")), failed.remedy().commands());
      var reason =
          "spec: spec: Spec taken has no content hash: this database's content migration never"
              + " finished.";
      assertEquals(
          reason + " Run 'sudo sail migrate'.", SyncCommand.reason(failed, OperatorCommand.SUDO));
      assertEquals(
          reason + " Run 'sail migrate'.", SyncCommand.reason(failed, OperatorCommand.PLAIN));
    }
  }

  @Test
  void gcUsesTheHostLaneAndReportsFreedContent() throws Exception {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      var hash = new BlobStore(db).putText("orphan");
      var operations =
          OperationsFactory.create(
                  db,
                  new ShellExecutor(true),
                  null,
                  null,
                  SyncScheduler.disabled(),
                  SessionYield.NONE)
              .useControlPlane(
                  db,
                  Path.of(System.getProperty("java.io.tmpdir")),
                  new SyncOperations(
                      db,
                      "box",
                      Path.of(System.getProperty("java.io.tmpdir")),
                      SyncConfig::unset,
                      target -> {
                        throw new IOException("offline");
                      },
                      SyncOperations.PLAIN_NOTICES));
      var output = new ByteArrayOutputStream();
      var previous = System.out;
      try (var stream = new PrintStream(output)) {
        System.setOut(stream);
        assertEquals(0, new SyncCommand.Gc(ignored -> operations).call());
      } finally {
        System.setOut(previous);
      }
      assertTrue(
          output.toString().contains("Compacted 0 history entries; freed 6 bytes"),
          output.toString());
      assertFalse(new BlobStore(db).has(hash));
    }
  }

  @Test
  void afterSyncGcCompactedItsHistoryMainStillRefreshesTheCopyItWrote(@TempDir Path home)
      throws Exception {
    try (var db = Sqlite.open(home.resolve("main.db"));
        var node = Sqlite.open(home.resolve("node.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      new SchemaManager(node).migrate();
      HeldProjects.hold(node, "acme");
      var files = new FileStore(db);
      var projectsDir = home.resolve("projects");
      ContentFixtures.put(files, "acme", "app.conf", "v0");
      new FileMaterializer(files, projectsDir).materialize("acme");
      SyncBox.round(db, node, "file");
      var last = SyncBox.pushPastHistory(db, node, "acme", "app.conf");
      var operations =
          OperationsFactory.create(
                  db,
                  new ShellExecutor(true),
                  null,
                  null,
                  SyncScheduler.disabled(),
                  SessionYield.NONE)
              .useControlPlane(
                  db,
                  home,
                  new SyncOperations(
                      db,
                      "box",
                      home,
                      SyncConfig::unset,
                      target -> {
                        throw new IOException("offline");
                      },
                      SyncOperations.PLAIN_NOTICES));
      var previous = System.out;
      try (var stream = new PrintStream(new ByteArrayOutputStream())) {
        System.setOut(stream);
        assertEquals(0, new SyncCommand.Gc(ignored -> operations).call());
      } finally {
        System.setOut(previous);
      }
      assertEquals(
          ChangeLog.HISTORY_REVISIONS, new ChangeLog(db).history("file", "acme/app.conf").size());

      var report = new FileMaterializer(files, projectsDir).materialize("acme");

      assertEquals(new FileMaterializer.Report(1, 0, List.of()), report);
      assertEquals(last, Files.readString(projectsDir.resolve("acme/files/app.conf")));
    }
  }

  @Test
  void syncStatusReadsThisBoxesStoredHealthInProcess(@TempDir Path home) throws Exception {
    try (var db = Sqlite.open(home.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      var config = new SyncConfig("node", "sail@main", "node");
      var operations =
          OperationsFactory.create(
                  db,
                  new ShellExecutor(true),
                  null,
                  null,
                  SyncScheduler.disabled(),
                  SessionYield.NONE)
              .useControlPlane(
                  db,
                  home,
                  new SyncOperations(
                      db,
                      "node",
                      home,
                      () -> config,
                      target -> {
                        throw new IOException("main unavailable");
                      },
                      SyncOperations.PLAIN_NOTICES));
      Supplier<SyncCommand.Status> status = () -> new SyncCommand.Status(ignored -> operations);
      assertEquals(
          Map.of(
              "role",
              "node",
              "main",
              "sail@main",
              "consecutive_failures",
              0,
              "pending_conflicts",
              0,
              "bytes_fetched",
              0,
              "bytes_sent",
              0,
              "bytes_freed",
              0,
              "denials",
              List.of()),
          nonNull(capture(() -> new CommandLine(status.get()).execute("--json"))),
          "nothing attempted yet: no state, no timestamps");

      var health = new SyncHealth(db);
      health.begin("sail@main", Instant.parse("2026-09-14T00:00:00Z"));
      health.failed(
          "sail@main",
          Instant.parse("2026-09-14T00:00:00Z"),
          "protocol",
          Remedy.of("message: page exceeded 4 MiB"));
      var stale = nonNull(capture(() -> new CommandLine(status.get()).execute("--json")));
      assertEquals("stale", stale.get("state"));
      assertEquals("protocol", stale.get("last_error_kind"));
      assertEquals(1, stale.get("consecutive_failures"));
      assertEquals(
          "Stale since 2026-09-14T00:00:00Z — message: page exceeded 4 MiB",
          SyncCommand.renderStatus(stale, OperatorCommand.PLAIN));

      health.failed(
          "sail@main",
          Instant.parse("2026-09-15T00:00:00Z"),
          "unreachable",
          Remedied.whole(
              new SyncTransportException(
                  "unreachable",
                  "hello: ssh said: Host key verification failed.",
                  MainHostKey.rebuiltRecovery("sail@main"),
                  null)));
      var rebuilt = nonNull(capture(() -> new CommandLine(status.get()).execute("--json")));
      assertEquals(
          "Stale since 2026-09-14T00:00:00Z — hello: ssh said: Host key verification failed. If"
              + " main was rebuilt from a copy of its database, remove the old key with 'sudo"
              + " ssh-keygen -R main', re-run 'sudo sail init --main sail@main' and compare the"
              + " fingerprint it prints with main's ('sudo ssh-keygen -lf"
              + " /etc/ssh/ssh_host_ed25519_key.pub' on main); otherwise something between this box"
              + " and main is answering for it.",
          SyncCommand.renderStatus(rebuilt, OperatorCommand.SUDO),
          "a node's stored failure read through sudo");
      assertEquals(
          "Stale since 2026-09-14T00:00:00Z — hello: ssh said: Host key verification failed. If"
              + " main was rebuilt from a copy of its database, remove the old key with 'ssh-keygen"
              + " -R main', re-run 'sail init --main sail@main' and compare the fingerprint it prints"
              + " with main's ('sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub' on main);"
              + " otherwise something between this box and main is answering for it.",
          SyncCommand.renderStatus(rebuilt, OperatorCommand.PLAIN),
          "the same failure read by root logged in directly");

      new SyncConflicts(db)
          .record("spec", "auth", "base", "mine", "theirs", null, null, List.of("title"));
      var conflicted = nonNull(capture(() -> new CommandLine(status.get()).execute("--json")));
      assertEquals(1, conflicted.get("pending_conflicts"));
    }
    assertEquals(
        "In sync with main — 1 conflict(s) need your decision: sail conflicts",
        SyncCommand.renderStatus(
            Map.of("state", "in_sync", "main", "main", "pending_conflicts", 1),
            OperatorCommand.PLAIN),
        "a round that parked a conflict is not the whole truth");
    assertEquals(
        "Syncing with main",
        SyncCommand.renderStatus(
            Map.of("state", "syncing", "main", "main"), OperatorCommand.PLAIN));
    assertEquals(
        "In sync with main",
        SyncCommand.renderStatus(
            Map.of("state", "in_sync", "main", "main"), OperatorCommand.PLAIN));
    assertEquals(
        "No sync round yet with main",
        SyncCommand.renderStatus(Map.of("main", "main"), OperatorCommand.PLAIN));
    assertEquals(
        "Not a node: nothing to sync with.",
        SyncCommand.renderStatus(Map.of(), OperatorCommand.PLAIN));
    assertEquals(
        "In sync with main — 10 bytes fetched, 2 bytes sent, 4096 bytes freed",
        SyncCommand.renderStatus(
            Map.of(
                "state",
                "in_sync",
                "main",
                "main",
                "last_report",
                Map.of(),
                "bytes_fetched",
                10,
                "bytes_sent",
                2,
                "bytes_freed",
                4096L),
            OperatorCommand.PLAIN),
        "a round whose collection freed content says how much");
  }

  private static Map<String, Object> capture(Runnable command) {
    var output = new ByteArrayOutputStream();
    var original = System.out;
    try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(stream);
      command.run();
    } finally {
      System.setOut(original);
    }
    return YamlUtil.parseMap(output.toString(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> nonNull(Map<String, Object> map) {
    var kept = new LinkedHashMap<String, Object>();
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
            "sail@override",
            new SyncConfig(SyncConfig.ROLE_NODE, "sail@cfg", null),
            OperatorCommand.PLAIN);
    assertEquals("sail@override", resolved.target());
  }

  @Test
  void resolveMainFallsBackToTheConfiguredMain() {
    var resolved =
        SyncCommand.resolveMain(
            null,
            new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox", null),
            OperatorCommand.PLAIN);
    assertEquals("sail@maindevbox", resolved.target());
  }

  @Test
  void resolveMainHasNoTargetWhenThisBoxIsMain() {
    var resolved =
        SyncCommand.resolveMain(
            null, new SyncConfig(SyncConfig.ROLE_MAIN, null, null), OperatorCommand.PLAIN);
    assertNull(resolved.target());
    assertTrue(resolved.message().plain().contains("main devbox"));
  }

  @Test
  void theMainBoxNeverSyncsAgainstAnotherBoxEvenWhenAsked() {
    var resolved =
        SyncCommand.resolveMain(
            "sail@other", new SyncConfig(SyncConfig.ROLE_MAIN, null, null), OperatorCommand.PLAIN);

    assertNull(resolved.target());
    assertTrue(resolved.message().plain().contains("main devbox"));
  }

  @Test
  void aBoxWithNoMainIsToldTheInitThatSetsUpSync() {
    var resolved = SyncCommand.resolveMain("  ", SyncConfig.unset(), OperatorCommand.PLAIN);
    assertNull(resolved.target());
    assertEquals(
        "Single devbox — nothing to sync. Set up sync with: sudo sail init --as-main --handle"
            + " <you>, or sudo sail init --main <main>.",
        OperatorCommand.SUDO.render(resolved.message()));
    assertEquals(
        "Single devbox — nothing to sync. Set up sync with: sail init --as-main --handle <you>, or"
            + " sail init --main <main>.",
        OperatorCommand.PLAIN.render(resolved.message()));
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
                        "spec", new SyncEngine.Report(1, 2, 3, 4), 2, 40, false, null, 456, 123))),
            true,
            OperatorCommand.PLAIN::render);
    var report = YamlUtil.parseMap(json);
    assertEquals(456, report.get("bytes_fetched"));
    assertEquals(123, report.get("bytes_sent"));
    assertEquals(3, report.get("merged"));
    assertEquals(4, report.get("conflicts"));
    var type = (Map<?, ?>) ((List<?>) report.get("types")).getFirst();
    assertEquals("spec", type.get("type"));
    assertEquals(456, type.get("bytes_fetched"));
    assertEquals(123, type.get("bytes_sent"));
  }

  private static SyncReport deniedRound() {
    return new SyncReport(
        SyncEngine.Report.NONE,
        null,
        List.of(
            new SyncSession.TypeReport(
                "spec",
                SyncEngine.Report.NONE,
                0,
                0,
                false,
                null,
                0,
                0,
                0,
                List.of(new SyncSession.Denial("spec", "auth", "your role is read-only")),
                List.of()),
            new SyncSession.TypeReport(
                "message",
                SyncEngine.Report.NONE,
                0,
                0,
                false,
                null,
                0,
                0,
                0,
                List.of(new SyncSession.Denial("message", "m1", "'ada' may not post as 'grace'")),
                List.of())));
  }

  @Test
  void aRoundWhoseOnlyNewsIsADenialIsNotReportedAsAlreadyInSync() {
    var text = SyncCommand.render(deniedRound(), false, OperatorCommand.PLAIN::render);
    assertFalse(text.contains("Already in sync"), text);
    assertTrue(text.contains("Synced with main"), text);
  }

  @Test
  void rendersDenialsInJsonByTypeIdAndReason() {
    var report =
        YamlUtil.parseMap(SyncCommand.render(deniedRound(), true, OperatorCommand.PLAIN::render));
    assertEquals(
        List.of(
            Map.of("type", "spec", "id", "auth", "reason", "your role is read-only"),
            Map.of("type", "message", "id", "m1", "reason", "'ada' may not post as 'grace'")),
        report.get("denials"));
  }

  @Test
  void theRoundsNoticesPrintOnceAsTheyHappenAndTheJsonCarriesThem() {
    var round =
        new SyncReport(
            SyncEngine.Report.NONE,
            null,
            List.of(
                new SyncSession.TypeReport(
                    "spec",
                    SyncEngine.Report.NONE,
                    0,
                    0,
                    false,
                    null,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of(
                        new SyncSession.Refusal("spec", "born", "main does not hold run 'r'")))),
            List.of(
                new Settlement.Settled(
                    "message", "m1", new Settlement.How.Withdrawn(), "room 'lab' is gone here")));

    var text = SyncCommand.render(round, false, OperatorCommand.PLAIN::render);
    var json = YamlUtil.parseMap(SyncCommand.render(round, true, OperatorCommand.PLAIN::render));

    assertEquals(
        String.join(
            "\n",
            Ansi.AUTO.string(
                "  @|green ✓|@ Synced with main: @|bold 0|@ pulled, @|bold 0|@ pushed, @|bold 0|@"
                    + " merged."),
            Ansi.AUTO.string(
                "    @|faint spec:|@ 0 pulled, 0 pushed, 0 merged @|faint (0 page(s), 0"
                    + " entries)|@")),
        text);
    assertEquals(
        List.of(Map.of("type", "spec", "id", "born", "reason", "main does not hold run 'r'")),
        json.get("refusals"));
    assertEquals(
        List.of(
            Map.of(
                "type",
                "message",
                "id",
                "m1",
                "how",
                "withdrawn; yours stays in this box's change log",
                "why",
                "room 'lab' is gone here")),
        json.get("settled"));
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
            false,
            OperatorCommand.PLAIN::render);
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
            false,
            OperatorCommand.PLAIN::render);
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
            false,
            OperatorCommand.PLAIN::render);
    assertEquals(
        String.join(
            "\n",
            Ansi.AUTO.string(
                "  @|green ✓|@ Synced with main: @|bold 0|@ pulled, @|bold 0|@ pushed, @|bold 0|@"
                    + " merged."),
            Banner.errorLine("run: run: main is away", Ansi.AUTO),
            Banner.warnLine(
                "2 conflict(s) need your decision. Run 'sail conflicts' to resolve.", Ansi.AUTO)),
        text);
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
      HeldProjects.hold(db, "acme");
      var fdes = new FdeStore(db);
      var roster =
          List.<Map<String, Object>>of(
              Map.of("handle", "ada", "role", "admin", "status", "active"),
              Map.of("handle", "bad", "role", "superuser", "status", "active"));

      var rejected = SyncOperations.applyFdes(fdes, roster, ProjectScope.all(), "ada");

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
    var plain = OperatorCommand.PLAIN;
    assertEquals("boom", SyncCommand.reason(new IllegalStateException("boom"), plain));
    assertEquals("RuntimeException", SyncCommand.reason(new RuntimeException(), plain));
    assertEquals(
        "IllegalStateException", SyncCommand.reason(new IllegalStateException("  "), plain));
    assertEquals(
        "Gone. Run 'sudo sail migrate'.",
        SyncCommand.reason(
            Remedied.state("Gone.", Remedy.of("Run '", Remedy.sail("migrate"), "'.")),
            OperatorCommand.SUDO));
  }

  @Test
  void pulledMessageEventsFireOnlyForNewlyAdoptedMessages(@TempDir Path tempDir) {
    try (var db = Sqlite.open(tempDir.resolve("pull.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      var specs = new SpecStore(db);
      var messages = new MessageStore(db);
      seedSpec(specs, "auth");
      Acting.system(() -> messages.append("auth", "uday", "posted before the round", null));
      var known = messages.syncEntityIds();
      var pulledId = DateTimeUtils.newId().toString();
      Acting.system(
          () ->
              messages.applyRevision(
                  pulledId,
                  Map.of(
                      "spec_id", "auth",
                      "author", "raj",
                      "body", "hello from main",
                      "created_at", DateTimeUtils.now().toString()),
                  "r1"));

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
      HeldProjects.hold(db, "acme");
      var specs = new SpecStore(db);
      var messages = new MessageStore(db);
      seedSpec(specs, "auth");
      var orphanId = DateTimeUtils.newId().toString();
      Acting.system(
          () ->
              messages.applyRevision(
                  orphanId,
                  Map.of(
                      "spec_id", "ghost",
                      "author", "raj",
                      "body", "orphaned on main",
                      "created_at", DateTimeUtils.now().toString()),
                  "r1"));

      assertTrue(SyncCommand.pulledMessageEvents(messages, specs, Set.of(), "devbox").isEmpty());
    }
  }

  private static void seedSpec(SpecStore specs, String id) {
    Acting.as(
        "uday",
        () ->
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
                    List.of("app"))));
  }
}
