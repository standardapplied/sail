/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncPrincipal;
import ai.singlr.sail.sync.SyncRpcServer;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailOperationsSeamTest {
  @TempDir Path tempDir;
  private final ShellExec shell =
      new ShellExec() {
        public Result exec(List<String> command) {
          return new Result(0, "[]", "");
        }

        public Result exec(List<String> command, Path workDir, Duration timeout) {
          return exec(command);
        }

        public boolean isDryRun() {
          return false;
        }
      };

  private SailOperations operations(Sqlite db) {
    return OperationsFactory.create(
            db, shell, "sail.yaml", null, null, SyncScheduler.disabled(), SessionYield.NONE)
        .useControlPlane(
            db,
            tempDir,
            new SyncOperations(
                db,
                "node",
                tempDir,
                SyncConfig::unset,
                target -> {
                  throw new IOException("main unavailable");
                }));
  }

  @Test
  void filesAreSharedReadRemovedAndMaterializedThroughOperations() throws Exception {
    try (var db = Sqlite.openMemory();
        var operations = operations(db)) {
      new SchemaManager(db).migrate();
      var files = operations.projectFiles("acme");
      assertTrue(files.list().isEmpty());
      assertTrue(files.get("missing").isEmpty());
      var bytes = new byte[] {0, 1, 2, -1};
      assertEquals("dir/config", files.put("dir/config", bytes));
      assertArrayEquals(bytes, files.get("dir/config").orElseThrow());
      assertEquals(List.of("acme"), operations.projectsWithFiles());
      assertEquals("dir/config", files.list().getFirst().path());
      assertEquals(1, files.materialize().written());
      var local = tempDir.resolve("acme/files/dir/config");
      assertArrayEquals(bytes, Files.readAllBytes(local));
      assertEquals(0, files.materialize().written());
      Files.writeString(local, "local edit");
      files.put("dir/config", new byte[] {5});
      assertEquals(List.of("dir/config"), files.materialize().skipped());
      Files.delete(local);
      assertEquals(1, files.materialize().written());
      assertTrue(files.remove("dir/config"));
      assertFalse(Files.exists(local));
      assertFalse(files.remove("dir/config"));
      assertTrue(files.list().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> files.put("../escape", bytes));
      assertThrows(
          IllegalArgumentException.class,
          () -> files.put("large", new byte[ProjectFiles.MAX_BYTES + 1]));
      assertThrows(IllegalArgumentException.class, () -> operations.projectFiles("../escape"));
      assertEquals("cap", files.put("cap", new byte[ProjectFiles.MAX_BYTES]));
    }
  }

  @Test
  void conflictsCanBeListedInspectedAndResolvedWithoutOpeningStoresInCommands() {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      box.specs.create(SyncBox.spec("auth", "local", "pending"));
      var local = box.specs.comparableSnapshot("auth");
      var remote = new LinkedHashMap<>(local);
      remote.put("title", "remote");
      var base = new LinkedHashMap<>(local);
      base.put("title", "base");
      box.conflicts.record(
          "spec",
          "auth",
          YamlUtil.dumpJson(base),
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(remote),
          List.of("title"));
      assertEquals(1, operations.conflicts().size());
      assertEquals("auth", operations.conflict("auth").entityId());
      assertNull(operations.conflict("missing"));
      var resolved =
          operations.resolveConflict("auth", new Resolution(Resolution.Strategy.THEIRS, null));
      assertEquals("resolved", resolved.status());
      assertNotNull(resolved.resolvedRev());
      assertEquals("remote", box.specs.findById("auth").orElseThrow().title());
      assertTrue(operations.conflicts().isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> operations.resolveConflict("auth", new Resolution(Resolution.Strategy.MINE, null)));
      box.conflicts.record(
          "spec",
          "auth",
          YamlUtil.dumpJson(base),
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(remote),
          List.of("title"));
      operations.resolveConflict("auth", new Resolution(Resolution.Strategy.MINE, null));
      assertEquals("local", box.specs.findById("auth").orElseThrow().title());
      box.conflicts.record(
          "spec",
          "auth",
          YamlUtil.dumpJson(base),
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(remote),
          List.of("title"));
      var merged = new LinkedHashMap<>(local);
      merged.put("title", "merged");
      operations.resolveConflict(
          "auth", new Resolution(Resolution.Strategy.MERGE, YamlUtil.dumpJson(merged)));
      assertEquals("merged", box.specs.findById("auth").orElseThrow().title());
      box.conflicts.record("file", "auth", null, null, null, List.of("content"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              operations.resolveConflict(
                  "auth", new Resolution(Resolution.Strategy.MERGE, "title: edited")));
      box.conflicts.record("spec", "auth", null, null, null, List.of("title"));
      assertNull(operations.conflict("auth"), "an ambiguous cross-type identity is never guessed");
    }
  }

  @Test
  void aRoundAcrossTheTwoNodeHarnessReturnsTheAggregateAndProjectsThePulledFiles()
      throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var operations = operations(node.db)) {
      main.specs.create(SyncBox.spec("auth", "main spec", "pending"));
      var content = "shared config".getBytes(StandardCharsets.UTF_8);
      new FileStore(main.db).put("proj", "config", Base64.getEncoder().encodeToString(content));
      var targets = new ArrayList<String>();
      operations.useControlPlane(
          node.db,
          tempDir,
          new SyncOperations(
              node.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main-target", "node"),
              target -> {
                targets.add(target);
                return channel(main);
              }));
      operations.prepareSync();
      assertNull(operations.syncStatus().lastReport());
      var expected = new SyncEngine.Report(2, 0, 0, 0);
      assertEquals(expected, operations.sync(new SyncRequest(null)).report());
      assertEquals(expected, operations.syncStatus().lastReport());
      assertEquals("main-target", operations.syncStatus().main());
      assertEquals("node", operations.syncStatus().role());
      assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("proj/files/config")));
      assertEquals("main spec", node.specs.findById("auth").orElseThrow().title());
      assertEquals(0, operations.sync(new SyncRequest("override")).report().total());
      assertEquals(List.of("main-target", "override"), targets);
    }
  }

  @Test
  void standaloneAndMainAreNoOpsAndTransportFailuresRemainFailures() throws Exception {
    try (var db = Sqlite.openMemory();
        var operations = operations(db)) {
      new SchemaManager(db).migrate();
      assertTrue(operations.sync(new SyncRequest(null)).message().startsWith("Single devbox"));
      assertThrows(IOException.class, () -> operations.sync(new SyncRequest("offline")));
      assertNull(operations.syncStatus().lastReport());
      operations.useControlPlane(
          db,
          tempDir,
          new SyncOperations(
              db,
              "main",
              tempDir,
              () -> new SyncConfig("main", null, "owner"),
              target -> {
                throw new IOException("unexpected connection");
              }));
      assertTrue(
          operations.sync(new SyncRequest(null)).message().startsWith("This box is the main"));
      assertThrows(IllegalArgumentException.class, () -> new SyncRequest("-oProxyCommand=bad"));
      assertThrows(IllegalArgumentException.class, () -> new SyncRequest("bad\u0000host"));
    }
  }

  @Test
  void renameAndPurgeOnlyChangeTheCatalogAndRenameCanBeCompensated() {
    try (var db = Sqlite.openMemory();
        var operations = operations(db)) {
      new SchemaManager(db).migrate();
      var projects = new ProjectStore(db);
      var definition = "name: old\n";
      projects.upsert("old", definition, "owner");
      operations.projectFiles("old").put("config", new byte[] {1});
      assertFalse(operations.projectDestroy("old", false).purged());
      var renamed = operations.projectRename("old", "new");
      assertTrue(operations.catalogProject("old").isEmpty());
      assertTrue(operations.catalogProject("new").isPresent());
      assertEquals("config", operations.projectFiles("new").list().getFirst().path());
      operations.undoProjectRename(renamed);
      assertEquals(definition, operations.catalogProject("old").orElseThrow().definition());
      assertEquals(1, operations.catalogProjects().size());
      assertTrue(operations.projectDestroy("old", true).purged());
      assertFalse(operations.projectDestroy("old", true).purged());
    }
  }

  @Test
  void hostReadsAndCredentialsUseTheSameDatabaseAsTheApi() throws Exception {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      box.specs.create(SyncBox.spec("auth", "task", "pending"));
      box.specs.setContent("auth", "body", "plan");
      assertEquals("auth", operations.projectSpecs("proj").getFirst().id());
      assertEquals("body", operations.specContent("auth").orElseThrow().body());
      assertTrue(operations.latestRun("proj", "node").isEmpty());
      assertTrue(operations.runningRuns("proj", "node").isEmpty());
      assertNull(operations.projectSession("proj", "node"));
      assertFalse(operations.roomKnown(null));
      assertFalse(operations.roomKnown("auth"));
      new RoomStore(box.db)
          .create(
              new RoomStore.RoomRow(
                  "auth", "proj", "room", "owner", "on", "[]", "owner", null, null, "owner"));
      assertTrue(operations.roomKnown("auth"));
      var owner = new FdeStore(box.db).add("owner", null, null, "admin");
      assertEquals(owner, operations.fde("owner").orElseThrow());
      var token = operations.createToken("test", "admin", owner.id(), Duration.ofDays(1));
      assertNotNull(token.token());
      assertEquals("test", operations.tokens().getFirst().name());
      assertTrue(operations.revokeToken("test"));
      assertFalse(operations.revokeToken("test"));
      assertTrue(operations.tokens().isEmpty());
      assertTrue(operations.sshKeys().isEmpty());
      assertTrue(operations.schemaVersion() > 0);
      assertEquals(new PtyIdentity("owner", true), operations.ptyIdentity(null, "owner"));
      var session = new AuthSessionStore(box.db).create(owner.id(), Duration.ofHours(1));
      assertEquals("owner", operations.ptyIdentity(session.token(), null).fde());
      operations.admitPtyRoom("auth", "proj", new PtyIdentity("owner", true));
      assertThrows(
          IOException.class,
          () -> operations.admitPtyRoom("auth", "other", new PtyIdentity("owner", true)));
      assertTrue(
          operations.authorizeGateway("not-allowed", "owner") instanceof SshGateway.Rejected);
      operations.recordHostEvent(
          new EventStore.EventRow(
              0, "now", "pty_session_started", "proj", "auth", "owner", "node", "{}"));
      assertEquals(
          1L, box.db.queryOne("SELECT count(*) FROM events", row -> row.integer(0)).orElseThrow());
      assertTrue(operations.demoDefinition().contains("demo"));
      operations.useSyncScheduler(SyncScheduler.disabled());
    }
  }

  @Test
  void localRunQueriesAndReviewLogsRemainScopedToTheExecutingNode() throws Exception {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      var runs = new RunStore(box.db);
      var id = DateTimeUtils.newId().toString();
      runs.create(
          id, "proj", "auth", "node", "node", "build", "codex", "branch", "task", 1, null, "/log",
          "unit");
      assertEquals(id, operations.latestRun("proj", "node").orElseThrow().id());
      assertEquals(id, operations.activeRun("proj", "node").orElseThrow().id());
      assertTrue(operations.activeRun("proj", "other").isEmpty());
      assertTrue(operations.latestRun("proj", "other").isEmpty());
      assertEquals(1, operations.runningRuns("proj", "node").size());
      var fallback = operations.reviewLog("proj", "node");
      var review = new ReviewStore(box.db).createReview("auth", 1);
      assertEquals(AgentUnit.forReview(review).logPath(), operations.reviewLog("proj", "node"));
      assertEquals(fallback, operations.reviewLog("proj", "other"));
    }
  }

  @Test
  void hostLaunchEntryPointsRejectMissingProjectsBeforeStartingAnything() {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      var request = new DispatchOperations.AdhocRequest("task", null, null, true, false);
      assertThrows(ApiException.class, () -> operations.startAdhoc("absent", request, "node"));
      assertThrows(
          ApiException.class, () -> operations.startAdhoc("absent", request, "node", () -> {}));
      assertThrows(
          ApiException.class,
          () ->
              operations.stop(
                  new StopOperations.RunTarget("missing"),
                  Actor.cliOperator("node"),
                  "node",
                  false));
    }
  }

  private static SyncOperations.Channel channel(SyncBox main) throws IOException {
    var toServer = new PipedWriter();
    var serverIn = new PipedReader(toServer);
    var serverOut = new PipedWriter();
    var fromServer = new PipedReader(serverOut);
    var error = new AtomicReference<Throwable>();
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (serverIn;
                      serverOut) {
                    new SyncRpcServer(
                            new LinkedHashMap<>(SyncedEntities.replicas(main.db, "main", "main")),
                            new SyncPrincipal("node", true),
                            List::of)
                        .serve(serverIn, serverOut);
                  } catch (Throwable e) {
                    error.set(e);
                  }
                });
    return new SyncOperations.Channel() {
      public Reader reader() {
        return fromServer;
      }

      public Writer writer() {
        return toServer;
      }

      public void close() throws IOException {
        try {
          thread.join();
          if (error.get() != null) throw new IOException("main failed", error.get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        } finally {
          toServer.close();
          fromServer.close();
        }
      }
    };
  }
}
