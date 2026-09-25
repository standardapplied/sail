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
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.MainReplica;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncRpcServer;
import ai.singlr.sail.sync.SyncTransitionSink;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
  void healthIsStoredAcrossOperationsInstancesAndVisibleWhileARoundRuns() throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var operations = operations(node.db)) {
      var offline = new java.util.concurrent.atomic.AtomicBoolean(true);
      var clock = new BackoffTest.TestClock();
      operations.useSyncClock(clock);
      var sync =
          new SyncOperations(
              node.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main", "node", "node-box"),
              target -> {
                assertEquals("syncing", operations.syncStatus().state());
                if (offline.get()) {
                  clock.advance(Duration.ofSeconds(10));
                  throw new IOException("connection refused");
                }
                return channel(main);
              });
      operations.useControlPlane(node.db, tempDir, sync);
      assertThrows(IOException.class, () -> operations.sync(new SyncRequest(null)));
      var first = operations.syncStatus();
      assertEquals("stale", first.state());
      assertEquals("unreachable", first.lastErrorKind());
      assertEquals("connection refused", first.lastError());
      assertEquals(1, first.consecutiveFailures());
      assertEquals(
          clock.instant(), first.lastAttemptAt(), "backoff starts after the failure completes");
      clock.advance(Duration.ofDays(3));
      assertThrows(IOException.class, () -> operations.sync(new SyncRequest(null)));
      assertEquals(first.staleSince(), operations.syncStatus().staleSince());
      assertEquals(2, operations.syncStatus().consecutiveFailures());
      try (var reopened = operations(node.db)) {
        reopened.useControlPlane(node.db, tempDir, sync);
        assertEquals(operations.syncStatus(), reopened.syncStatus());
      }
      offline.set(false);
      operations.sync(new SyncRequest(null));
      assertEquals("in_sync", operations.syncStatus().state());
      assertEquals(0, operations.syncStatus().consecutiveFailures());
      assertNull(operations.syncStatus().lastError());
      assertEquals(clock.instant(), operations.syncStatus().lastSuccessAt());
      assertNotNull(operations.syncStatus().lastReport());
    }
  }

  @Test
  void automaticRoundsBackOffExposeStaleThroughHttpAndRecoverOnceWithoutAWrite() throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var bus = new EventBus();
        var operations =
            OperationsFactory.create(
                node.db,
                shell,
                "sail.yaml",
                bus,
                null,
                SyncScheduler.disabled(),
                SessionYield.NONE);
        var server = server(operations, node.db)) {
      var clock = new BackoffTest.TestClock();
      var nanos = new java.util.concurrent.atomic.AtomicLong();
      var attempts = new java.util.concurrent.atomic.AtomicInteger();
      var offline = new java.util.concurrent.atomic.AtomicBoolean(true);
      var events = new java.util.concurrent.LinkedBlockingQueue<Event>();
      bus.subscribe(
          new EventSubscriber() {
            public String name() {
              return "health-test";
            }

            public java.util.function.Predicate<Event> filter() {
              return e -> e.type().startsWith("sync_");
            }

            public void onEvent(Event event) {
              events.add(event);
            }
          });
      operations
          .useSyncClock(clock)
          .useControlPlane(
              node.db,
              tempDir,
              new SyncOperations(
                  node.db,
                  "node",
                  tempDir,
                  () -> new SyncConfig("node", "main", "owner", "node-box"),
                  target -> {
                    attempts.incrementAndGet();
                    if (offline.get()) throw new IOException("main port blocked");
                    return channel(main);
                  }));
      java.util.function.Consumer<Duration> advance =
          duration -> {
            clock.advance(duration);
            nanos.addAndGet(duration.toNanos());
          };
      var scheduler =
          new SyncScheduler(
              () -> operations.sync(new SyncRequest(null)),
              Duration.ZERO,
              Duration.ofSeconds(15),
              new DirectExecutorService(),
              nanos::get,
              advance::accept,
              clock::instant);
      operations.useSyncScheduler(scheduler);
      var token = credential(node.db, "owner", "member", "token");
      scheduler.freshenRead();
      assertEquals(1, attempts.get());
      var stale = send(server, "GET", "/v1/sync", token, "");
      assertEquals("stale", YamlUtil.parseMap(stale.body()).get("state"));
      assertTrue(stale.body().contains("main port blocked"));
      assertEquals(
          java.util.Set.of(
              "schema_version",
              "role",
              "main",
              "state",
              "last_attempt_at",
              "consecutive_failures",
              "last_error_kind",
              "last_error",
              "stale_since",
              "bytes_fetched",
              "bytes_sent",
              "bytes_freed",
              "denials"),
          YamlUtil.parseMap(stale.body()).keySet());
      scheduler.freshenRead();
      assertEquals(1, attempts.get());
      for (var count = 2; count <= 5; count++) {
        advance.accept(Duration.ofSeconds(145));
        scheduler.tick();
        assertEquals(count, attempts.get());
      }
      advance.accept(Duration.ofMinutes(5));
      scheduler.freshenRead();
      assertEquals(5, attempts.get(), "freshen never probes an open circuit");
      scheduler.tick();
      scheduler.tick();
      assertEquals(6, attempts.get(), "half-open probes once");
      offline.set(false);
      advance.accept(Duration.ofMinutes(5));
      scheduler.tick();
      assertEquals(7, attempts.get());
      assertEquals(
          "in_sync",
          YamlUtil.parseMap(send(server, "GET", "/v1/sync", token, "").body()).get("state"));
      scheduler.syncNow();
      assertEquals(
          Event.WellKnownTypes.SYNC_DEGRADED,
          events.poll(5, java.util.concurrent.TimeUnit.SECONDS).type());
      assertEquals(
          Event.WellKnownTypes.SYNC_RECOVERED,
          events.poll(5, java.util.concurrent.TimeUnit.SECONDS).type());
      assertTrue(events.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preparationFailuresMarkHealthStaleBackOffAndRecover(boolean previouslySynced)
      throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var operations = operations(node.db)) {
      var clock = new BackoffTest.TestClock();
      operations
          .useSyncClock(clock)
          .useControlPlane(
              node.db,
              tempDir,
              new SyncOperations(
                  node.db,
                  "node",
                  tempDir,
                  () -> new SyncConfig("node", "main", "node", "node-box"),
                  target -> channel(main)));
      if (previouslySynced) operations.sync(new SyncRequest(null));
      var lastSuccess = operations.syncStatus().lastSuccessAt();
      var newerVersion = new SchemaManager(node.db).currentVersion() + 1;
      node.db.execute(
          "INSERT INTO schema_version (version, applied_at) VALUES (?, datetime('now'))",
          newerVersion);
      clock.advance(Duration.ofMinutes(1));
      var attempts = new AtomicInteger();
      try (var scheduler =
          new SyncScheduler(
              () -> {
                attempts.incrementAndGet();
                operations.sync(new SyncRequest(null));
              },
              Duration.ZERO,
              SyncScheduler.DEFAULT_FRESHEN_TTL,
              new DirectExecutorService(),
              () -> TimeUnit.MILLISECONDS.toNanos(clock.millis()),
              clock::advance,
              clock::instant)) {
        operations.useSyncScheduler(scheduler);
        for (var count = 1; count <= 5; count++) {
          scheduler.tick();
          var status = operations.syncStatus();
          assertEquals("stale", status.state());
          assertEquals("store", status.lastErrorKind());
          assertTrue(status.lastError().contains("newer than this Sail binary supports"));
          assertEquals(lastSuccess, status.lastSuccessAt());
          assertEquals(clock.instant(), status.lastAttemptAt());
          assertEquals(count, status.consecutiveFailures());
          scheduler.tick();
          assertEquals(count, attempts.get());
          var minimumDelay = Duration.ofSeconds(count == 5 ? 300 : 12L << (count - 1));
          clock.advance(minimumDelay.minusSeconds(1));
          scheduler.tick();
          assertEquals(count, attempts.get(), "retries must wait beyond the freshness window");
          clock.advance(Duration.ofSeconds(145));
        }
        assertEquals(
            List.of(Event.WellKnownTypes.SYNC_DEGRADED),
            new EventStore(node.db).recent(10).stream().map(EventStore.EventRow::type).toList());
        node.db.execute("DELETE FROM schema_version WHERE version = ?", newerVersion);
        scheduler.freshenRead();
        assertEquals(5, attempts.get(), "freshen must not probe the open circuit");
        scheduler.tick();
        assertEquals(6, attempts.get());
        assertEquals("in_sync", operations.syncStatus().state());
        assertEquals(0, operations.syncStatus().consecutiveFailures());
        assertEquals(
            List.of(Event.WellKnownTypes.SYNC_RECOVERED, Event.WellKnownTypes.SYNC_DEGRADED),
            new EventStore(node.db).recent(10).stream().map(EventStore.EventRow::type).toList());
      }
    }
  }

  @Test
  void anUnavailableHealthStoreDoesNotMaskThePreparationFailure() {
    try (var node = new SyncBox("node");
        var operations = operations(node.db)) {
      node.db.execute("UPDATE schema_version SET version = version + 1");
      node.db.execute("DROP TABLE sync_health");

      var failure =
          assertThrows(IllegalStateException.class, () -> operations.sync(new SyncRequest("main")));

      assertTrue(failure.getMessage().contains("newer than this Sail binary supports"));
      assertEquals(1, failure.getSuppressed().length);
      assertTrue(failure.getSuppressed()[0].getMessage().contains("sync_health"));
    }
  }

  @Test
  void aFreshDatabaseIsPreparedBeforeRecordingHealth() throws Exception {
    try (var main = new SyncBox("main");
        var db = Sqlite.openMemory();
        var operations = operations(db)) {
      operations.useControlPlane(
          db,
          tempDir,
          new SyncOperations(
              db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main", "node", "node-box"),
              target -> channel(main)));

      operations.sync(new SyncRequest(null));

      assertEquals("in_sync", operations.syncStatus().state());
      assertNotNull(operations.syncStatus().lastSuccessAt());
      assertEquals(0, operations.syncStatus().consecutiveFailures());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"refused", "protocol", "store"})
  void healthKeepsTheFailureKindAndEntityContext(String kind) {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      operations.useControlPlane(
          box.db,
          tempDir,
          new SyncOperations(
              box.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main", "node", "node-box"),
              target -> {
                throw new ai.singlr.sail.sync.SyncTransportException(
                    kind, "message m1: failure cause", null);
              }));
      assertThrows(
          ai.singlr.sail.sync.SyncTransportException.class,
          () -> operations.sync(new SyncRequest(null)));
      assertEquals(kind, operations.syncStatus().lastErrorKind());
      assertEquals("message m1: failure cause", operations.syncStatus().lastError());
    }
  }

  @Test
  void lifecycleAndMessageConflictsResolveThroughTheOperationsRegistry() {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      var runs = new RunStore(box.db);
      var run = DateTimeUtils.newId().toString();
      Acting.system(
          () ->
              runs.create(
                  run, "proj", "auth", "node", "node", "build", "codex", "branch", "task", 1, null,
                  "/log", "unit"));
      var review = Acting.system(() -> new ReviewStore(box.db).createReview("auth", 1));
      Acting.system(
          () ->
              new RoomStore(box.db)
                  .create(
                      new RoomStore.RoomRow(
                          "room", "proj", "Room", "node", "on", "[]", "node", null, null, "node")));
      var message =
          Acting.system(
              () ->
                  new ai.singlr.sail.store.MessageStore(box.db)
                      .append("room", "node", "local", null)
                      .id());
      for (var entry : Map.of("run", run, "review", review, "message", message).entrySet()) {
        var store = SyncedEntities.require(entry.getKey()).store(box.db);
        var local = store.comparableSnapshot(entry.getValue());
        var remote = new LinkedHashMap<>(local);
        var field = entry.getKey().equals("message") ? "body" : "status";
        var value = entry.getKey().equals("message") ? "remote" : "failed";
        remote.put(field, value);
        box.conflicts.record(
            entry.getKey(),
            entry.getValue(),
            null,
            YamlUtil.dumpJson(local),
            YamlUtil.dumpJson(remote),
            List.of(field));
        operations.resolveConflict(
            entry.getKey(), entry.getValue(), new Resolution(Resolution.Strategy.THEIRS, null));
        assertEquals(value, store.comparableSnapshot(entry.getValue()).get(field));
      }
      assertTrue(operations.conflicts().isEmpty());
    }
  }

  @ParameterizedTest
  @CsvSource({"owner,member,200", "other,member,403", "other,admin,200", "owner,viewer,403"})
  void localBoxCredentialsUseTheSameConflictOwnerPolicy(String handle, String role, int expected) {
    try (var box = new SyncBox("node");
        var operations = operations(box.db);
        var bus = new EventBus()) {
      operations.useControlPlane(
          box.db,
          tempDir,
          new SyncOperations(
              box.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main", "owner", "node-box"),
              target -> {
                throw new IOException("unused");
              }));
      new FdeStore(box.db).add(handle, null, null, role);
      var token = new ai.singlr.sail.store.BoxCredentialStore(box.db).replace(handle);
      Acting.system(() -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
      var snapshot = YamlUtil.dumpJson(box.specs.comparableSnapshot("auth"));
      box.conflicts.record("spec", "auth", null, snapshot, snapshot, List.of("title"));
      var response =
          new LocalApiRouter(bus, operations)
              .handle(
                  new LocalApiRequest(
                      "POST",
                      "/v1/conflicts/auth/resolve",
                      Map.of(),
                      Map.of("authorization", "Bearer " + token),
                      "strategy=mine".getBytes(StandardCharsets.UTF_8)));
      assertEquals(expected, response.status(), response.body().toString());
      assertEquals(expected == 200, box.conflicts.pending().isEmpty());
    }
  }

  @ParameterizedTest
  @CsvSource({"uday,sailrun_test,200", "other,sailrun_test,403", "uday,sailroom_test,403"})
  void localRunCredentialsResolveOnlyForTheirOwnerAndWriteLane(
      String nodeOwner, String credential, int expected) {
    try (var box = new SyncBox("node");
        var operations = operations(box.db);
        var bus = new EventBus()) {
      operations.useControlPlane(
          box.db,
          tempDir,
          new SyncOperations(
              box.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main", nodeOwner, "node-box"),
              target -> {
                throw new IOException("unused");
              }));
      Acting.system(() -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
      var snapshot = YamlUtil.dumpJson(box.specs.comparableSnapshot("auth"));
      box.conflicts.record("spec", "auth", null, snapshot, snapshot, List.of("title"));
      var lane =
          new TestOperations() {
            @Override
            public ai.singlr.sail.store.SyncConflicts.Conflict resolveConflict(
                String type, String id, Resolution resolution, Actor actor) {
              return operations.resolveConflict(type, id, resolution, actor);
            }
          };
      var response =
          new LocalApiRouter(bus, lane)
              .handle(
                  new LocalApiRequest(
                      "POST",
                      "/v1/conflicts/auth/resolve",
                      Map.of(),
                      Map.of(
                          "authorization",
                          "Bearer " + credential,
                          "content-type",
                          "application/json"),
                      "{\"strategy\":\"mine\"}".getBytes(StandardCharsets.UTF_8)));
      assertEquals(expected, response.status(), response.body().toString());
    }
  }

  @ParameterizedTest
  @CsvSource({"owner,member,200", "other,member,403", "other,admin,200", "owner,viewer,403"})
  void conflictResolutionBelongsToTheBoxOwnerOrAnAdminInBothWebCredentialLanes(
      String handle, String role, int expected) throws Exception {
    for (var lane : List.of("token", "session")) {
      try (var box = new SyncBox("node");
          var operations = operations(box.db);
          var server = server(operations, box.db)) {
        operations.useControlPlane(
            box.db,
            tempDir,
            new SyncOperations(
                box.db,
                "node",
                tempDir,
                () -> new SyncConfig("node", "main", "owner", "node-box"),
                target -> {
                  throw new IOException("unused");
                }));
        Acting.system(() -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
        var local = box.specs.comparableSnapshot("auth");
        var remote = new LinkedHashMap<>(local);
        remote.put("title", "remote");
        box.conflicts.record(
            "spec",
            "auth",
            null,
            YamlUtil.dumpJson(local),
            YamlUtil.dumpJson(remote),
            List.of("title"));
        var token = credential(box.db, handle, role, lane);
        var result =
            send(server, "POST", "/v1/conflicts/auth/resolve", token, "{\"strategy\":\"theirs\"}");
        assertEquals(expected, result.statusCode(), result.body());
        assertEquals(
            expected == 200 ? "remote" : "local", box.specs.findById("auth").orElseThrow().title());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"token", "session"})
  void onlyAdminsCanChooseTheSyncPeerThroughHttp(String lane) throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var operations = operations(node.db);
        var server = server(operations, node.db)) {
      var targets = new ArrayList<String>();
      operations.useControlPlane(
          node.db,
          tempDir,
          new SyncOperations(
              node.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "trusted-main", "node", "node-box"),
              target -> {
                targets.add(target);
                return channel(main);
              }));
      var member = credential(node.db, "member", "member", lane);
      var denied = send(server, "POST", "/v1/sync", member, "{\"main\":\"untrusted-peer\"}");
      assertEquals(403, denied.statusCode(), denied.body());
      assertTrue(targets.isEmpty());
      assertEquals("member", new FdeStore(node.db).byHandle("member").orElseThrow().role());

      for (var body : List.of("{}", "{\"main\":null}", "{\"main\":\"\"}", "{\"main\":\" \"}")) {
        var response = send(server, "POST", "/v1/sync", member, body);
        assertEquals(200, response.statusCode(), response.body());
      }
      assertEquals(
          List.of("trusted-main", "trusted-main", "trusted-main", "trusted-main"), targets);

      var admin = credential(node.db, "admin", "admin", lane);
      var allowed = send(server, "POST", "/v1/sync", admin, "{\"main\":\"alternate\"}");
      assertEquals(200, allowed.statusCode(), allowed.body());
      assertEquals("alternate", targets.getLast());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "token,mine",
    "token,theirs",
    "token,merge",
    "session,mine",
    "session,theirs",
    "session,merge"
  })
  void onlyAdminsCanReplaceSpecSnapshotsThroughHttp(String lane, String strategy) throws Exception {
    try (var box = new SyncBox("node");
        var operations = operations(box.db);
        var server = server(operations, box.db)) {
      Acting.system(() -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
      var local = box.specs.comparableSnapshot("auth");
      var remote = new LinkedHashMap<>(local);
      remote.put("title", "remote");
      remote.put("assignee", "someone-else");
      box.conflicts.record(
          "spec",
          "auth",
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(remote),
          List.of("title"));
      var theirs =
          new LinkedHashMap<>(
              ConflictMerge.parseTemplate(operations.conflictMergeTemplate("spec", "auth")));
      theirs.putAll(YamlUtil.parseMap(operations.conflict("spec", "auth").remoteSnapshot()));
      var body =
          YamlUtil.dumpJson(Map.of("strategy", strategy, "merged", YamlUtil.dumpJson(theirs)));
      for (var handle : List.of("uday", "other")) {
        var member = credential(box.db, handle, "member", lane);
        assertEquals(200, send(server, "GET", "/v1/conflicts/auth", member, "").statusCode());
        var denied = send(server, "POST", "/v1/conflicts/auth/resolve", member, body);
        assertEquals(403, denied.statusCode(), denied.body());
        assertEquals(local, box.specs.comparableSnapshot("auth"));
        assertEquals(1, box.conflicts.pending().size());
      }

      var admin = credential(box.db, "admin", "admin", lane);
      var allowed = send(server, "POST", "/v1/conflicts/auth/resolve", admin, body);
      assertEquals(200, allowed.statusCode(), allowed.body());
      var resolved = new LinkedHashMap<>(box.specs.comparableSnapshot("auth"));
      var expected = new LinkedHashMap<>(strategy.equals("mine") ? local : remote);
      assertEquals(
          strategy.equals("mine") ? "admin" : expected.get(Snapshots.ACTOR),
          resolved.remove(Snapshots.ACTOR),
          "keeping mine is the resolver's edit; main's version keeps main's author");
      expected.remove(Snapshots.ACTOR);
      assertEquals(expected, resolved);
      assertTrue(box.conflicts.pending().isEmpty());
    }
  }

  @Test
  void httpUploadsMaterializeNewAndUpdatedFilesWhilePreservingLocalEdits() throws Exception {
    try (var box = new SyncBox("node");
        var operations = operations(box.db);
        var server = server(operations, box.db)) {
      var member = credential(box.db, "member", "member", "token");
      var path = "/v1/projects/proj/files/dir/config";
      var local = tempDir.resolve("proj/files/dir/config");
      assertEquals(200, send(server, "PUT", path, member, "first\u0000bytes").statusCode());
      assertEquals("first\u0000bytes", Files.readString(local));
      assertEquals(200, send(server, "PUT", path, member, "updated").statusCode());
      assertEquals("updated", Files.readString(local));
      Files.writeString(local, "local edit");
      assertEquals(200, send(server, "PUT", path, member, "latest").statusCode());
      assertEquals("local edit", Files.readString(local));
      assertEquals("latest", send(server, "GET", path, member, "").body());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST"})
  void httpConflictRoutesPreserveWhitespaceInsteadOfAddressingAnotherFile(String method)
      throws Exception {
    try (var box = new SyncBox("node");
        var operations = operations(box.db);
        var server = server(operations, box.db)) {
      var files = new FileStore(box.db);
      for (var path : List.of("dir/ /config", "dir/config")) {
        ai.singlr.sail.store.ContentFixtures.put(files, "proj", path, "local");
        var id = "proj/" + path;
        var local = files.comparableSnapshot(id);
        var remote = new LinkedHashMap<>(local);
        remote.put("content_hash", files.blobs().putText("remote"));
        box.conflicts.record(
            "file",
            id,
            null,
            YamlUtil.dumpJson(local),
            YamlUtil.dumpJson(remote),
            List.of("content"));
      }
      var admin = credential(box.db, "admin", "admin", "token");
      var path = "/v1/conflicts/proj/dir/%20/config" + (method.equals("POST") ? "/resolve" : "");
      var response = send(server, method, path, admin, "{\"strategy\":\"theirs\"}");
      assertEquals(200, response.statusCode(), response.body());
      assertEquals("proj/dir/ /config", YamlUtil.parseMap(response.body()).get("entity_id"));
      assertNotNull(operations.conflict("file", "proj/dir/config"));
      assertArrayEquals(
          "local".getBytes(StandardCharsets.UTF_8),
          operations.projectFiles("proj").get("dir/config").orElseThrow().readAllBytes());
      if (method.equals("POST")) {
        assertNull(operations.conflict(null, "proj/dir/ /config"));
        assertArrayEquals(
            "remote".getBytes(StandardCharsets.UTF_8),
            operations.projectFiles("proj").get("dir/ /config").orElseThrow().readAllBytes());
      }
    }
  }

  private static SailApiServer server(SailOperations operations, Sqlite db) throws IOException {
    var auth =
        new SessionAwareAuth(
            new AuthSessionStore(db), new FdeStore(db), new TokenAuth(new TokenStore(db)));
    var server =
        new SailApiServer("127.0.0.1", 0, operations, auth, new EventBus(), null, null, null);
    server.start();
    return server;
  }

  private static String credential(Sqlite db, String handle, String role, String lane) {
    var fde = new FdeStore(db).add(handle, null, null, role);
    return lane.equals("session")
        ? new AuthSessionStore(db).create(fde.id(), Duration.ofMinutes(30)).token()
        : new TokenStore(db).create(handle, role, fde.id(), null).token();
  }

  private static HttpResponse<String> send(
      SailApiServer server, String method, String path, String token, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
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
      assertEquals(
          "dir/config", ai.singlr.sail.engine.ProjectFileFixtures.put(files, "dir/config", bytes));
      assertArrayEquals(bytes, files.get("dir/config").orElseThrow().readAllBytes());
      assertEquals(List.of("acme"), operations.catalog().projectsWithFiles());
      assertEquals("dir/config", files.list().getFirst().path());
      assertEquals(1, files.materialize().written());
      var local = tempDir.resolve("acme/files/dir/config");
      assertArrayEquals(bytes, Files.readAllBytes(local));
      assertEquals(0, files.materialize().written());
      Files.writeString(local, "local edit");
      ai.singlr.sail.engine.ProjectFileFixtures.put(files, "dir/config", new byte[] {5});
      assertEquals(List.of("dir/config"), files.materialize().skipped());
      Files.delete(local);
      assertEquals(1, files.materialize().written());
      assertTrue(Acting.system(() -> files.remove("dir/config")));
      assertFalse(Files.exists(local));
      assertFalse(Acting.system(() -> files.remove("dir/config")));
      assertTrue(files.list().isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> ai.singlr.sail.engine.ProjectFileFixtures.put(files, "../escape", bytes));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              files.put(
                  "large",
                  java.io.InputStream.nullInputStream(),
                  ai.singlr.sail.config.FileLimits.DEFAULT_MAX + 1,
                  0644));
      assertThrows(IllegalArgumentException.class, () -> operations.projectFiles("../escape"));
      assertEquals(
          "cap",
          Acting.system(
              () ->
                  files.put(
                      "cap",
                      java.io.InputStream.nullInputStream(),
                      ai.singlr.sail.config.FileLimits.DEFAULT_MAX,
                      0644)));
    }
  }

  @Test
  void conflictsCanBeListedInspectedAndResolvedWithoutOpeningStoresInCommands() {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      Acting.system(() -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
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
      assertEquals("auth", operations.conflict(null, "auth").entityId());
      assertNull(operations.conflict(null, "missing"));
      var resolved =
          operations.resolveConflict(
              null, "auth", new Resolution(Resolution.Strategy.THEIRS, null));
      assertEquals("resolved", resolved.status());
      assertNotNull(resolved.resolvedRev());
      assertEquals("remote", box.specs.findById("auth").orElseThrow().title());
      assertTrue(operations.conflicts().isEmpty());
      var settled =
          assertThrows(
              ApiException.class,
              () ->
                  operations.resolveConflict(
                      "spec", "auth", new Resolution(Resolution.Strategy.MINE, null)));
      assertEquals(404, settled.status());
      Acting.system(() -> box.specs.update(SyncBox.spec("auth", "local", "pending")));
      box.conflicts.record(
          "spec",
          "auth",
          YamlUtil.dumpJson(base),
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(remote),
          List.of("title"));
      operations.resolveConflict("spec", "auth", new Resolution(Resolution.Strategy.MINE, null));
      assertEquals("local", box.specs.findById("auth").orElseThrow().title());
      box.conflicts.record(
          "spec",
          "auth",
          YamlUtil.dumpJson(base),
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(remote),
          List.of("title"));
      var merged =
          new LinkedHashMap<>(
              ConflictMerge.parseTemplate(operations.conflictMergeTemplate("spec", "auth")));
      merged.put("title", "merged");
      operations.resolveConflict(
          "spec", "auth", new Resolution(Resolution.Strategy.MERGE, YamlUtil.dumpJson(merged)));
      assertEquals("merged", box.specs.findById("auth").orElseThrow().title());
      box.conflicts.record("file", "auth", null, null, null, List.of("content"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              operations.resolveConflict(
                  "file", "auth", new Resolution(Resolution.Strategy.MERGE, "title: edited")));
      box.conflicts.record("spec", "auth", null, null, null, List.of("title"));
      var ambiguous = assertThrows(ApiException.class, () -> operations.conflict(null, "auth"));
      assertEquals(
          "'auth' has open conflicts as file and spec: pass --type", ambiguous.getMessage());
      assertEquals("spec", operations.conflict("spec", "auth").entityType());
    }
  }

  @Test
  void aRoundAcrossTheTwoNodeHarnessReturnsTheAggregateAndProjectsThePulledFiles()
      throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var operations = operations(node.db)) {
      Acting.system(() -> main.specs.create(SyncBox.spec("auth", "main spec", "pending")));
      var content = "shared config".getBytes(StandardCharsets.UTF_8);
      Acting.system(
          () ->
              new FileStore(main.db)
                  .put("proj", "config", new java.io.ByteArrayInputStream(content), 0644));
      var targets = new ArrayList<String>();
      operations.useControlPlane(
          node.db,
          tempDir,
          new SyncOperations(
              node.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main-target", "node", "node-box"),
              target -> {
                targets.add(target);
                return channel(main);
              }));
      operations.schema().prepareSync();
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
  void aPartiallyFailedRoundStillAnnouncesTheMessagesItPulledExactlyOnce() throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var operations = operations(node.db)) {
      Acting.system(() -> main.specs.create(SyncBox.spec("auth", "main spec", "pending")));
      Acting.system(() -> new MessageStore(main.db).append("auth", "raj", "hello from main", null));
      var replicas =
          new LinkedHashMap<String, MainReplica>(SyncedEntities.replicas(main.db, "main", "node"));
      replicas.remove("file");
      var events = new ArrayList<Event>();
      operations.useControlPlane(
          node.db,
          tempDir,
          new SyncOperations(
              node.db,
              "node",
              tempDir,
              () -> new SyncConfig("node", "main", "node", "node-box"),
              target ->
                  channel(
                      new SyncRpcServer(
                              replicas,
                              Actor.sync("node", Role.MEMBER),
                              List::of,
                              SyncTransitionSink.NONE,
                              new ChangeLog(main.db)::headsAfter,
                              SyncWire.UPGRADE_FLOOR)
                          .content(main.db, ai.singlr.sail.config.FileLimits.defaults())),
              events::add));
      operations.schema().prepareSync();
      java.util.function.Supplier<List<String>> posted =
          () ->
              events.stream()
                  .map(Event::type)
                  .filter(Event.WellKnownTypes.SPEC_MESSAGE_POSTED::equals)
                  .toList();

      var failure = assertThrows(Exception.class, () -> operations.sync(new SyncRequest(null)));
      assertTrue(failure.getMessage().contains("file"), failure.getMessage());
      assertEquals("hello from main", body(node, "auth"));
      assertEquals(
          List.of(Event.WellKnownTypes.SPEC_MESSAGE_POSTED),
          posted.get(),
          "a message the failed round still pulled is announced");

      events.clear();
      assertThrows(Exception.class, () -> operations.sync(new SyncRequest(null)));
      assertEquals(List.of(), posted.get(), "the next round never announces it again");
    }
  }

  private static String body(SyncBox box, String room) {
    return new MessageStore(box.db).list(room, null, 10).getFirst().body();
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
              () -> new SyncConfig("main", null, "owner", "main-box"),
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
  void pruneAndTheRetentionSweeperWorkOnTheHostsOneDatabase() {
    try (var box = new SyncBox("main");
        var operations = operations(box.db)) {
      Acting.system(() -> box.specs.create(SyncBox.spec("old", "Old", "archived")));
      Acting.system(() -> box.specs.create(SyncBox.spec("kept", "Kept", "pending")));
      var admin = new Actor("uday", Role.ADMIN, Actor.Lane.API);

      var rehearsed =
          Acting.by(
              admin, () -> operations.pruneSpecs(PruneRequest.ids(List.of("old"), true), admin));
      var erased =
          Acting.by(
              admin, () -> operations.pruneSpecs(PruneRequest.ids(List.of("old"), false), admin));

      assertEquals(1, ((Result.Success<PruneReport>) rehearsed).value().specs());
      assertEquals(1, ((Result.Success<PruneReport>) erased).value().specs());
      assertTrue(box.specs.findById("old").isEmpty(), "a standalone box authors the erasure");
      assertEquals(
          List.of("kept"),
          ((Result.Success<GlobalSpecsListResponse>)
                  operations.globalSpecs(SpecStore.SpecFilter.all()))
              .value().specs().stream().map(GlobalSpecView::id).toList(),
          "the board reads the same stores the prune erased from");
      try (var sweeper = operations.retentionSweeper()) {
        assertNull(sweeper.retain(), "a box with no retention block erases nothing on its own");
      }
    }
  }

  @Test
  void aNodesPurgeIsAskedOfMainAndErasesNothingHereYet() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert("old", "name: old\n"));
      db.execute("UPDATE projects SET base_rev = rev WHERE name = 'old'");
      new FdeStore(db).add("node", "Node", "node@example.com", "admin");
      try (var operations =
          OperationsFactory.create(
                  db, shell, "sail.yaml", null, null, SyncScheduler.disabled(), SessionYield.NONE)
              .useControlPlane(
                  db,
                  tempDir,
                  new SyncOperations(
                      db,
                      "node",
                      tempDir,
                      () -> new SyncConfig("node", "main", "node", "node-box"),
                      target -> {
                        throw new IOException("main unavailable");
                      }))) {
        var destroyed = operations.catalog().destroy("old", true);

        assertTrue(destroyed.purged());
        assertTrue(destroyed.requested());
        assertTrue(
            operations.catalog().project("old").isPresent(), "main erases; this box follows");
        assertEquals(List.of("old"), new EraseRequests(db).pending("project"));
      }
    }
  }

  @Test
  void aNodeThatHasNotSyncedItsRosterSaysSoInsteadOfGuessingARole() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert("old", "name: old\n"));
      try (var operations =
          OperationsFactory.create(
                  db, shell, "sail.yaml", null, null, SyncScheduler.disabled(), SessionYield.NONE)
              .useControlPlane(
                  db,
                  tempDir,
                  new SyncOperations(
                      db,
                      "node",
                      tempDir,
                      () -> new SyncConfig("node", "main", "node", "node-box"),
                      target -> {
                        throw new IOException("main unavailable");
                      }))) {
        var refused =
            assertThrows(ApiException.class, () -> operations.catalog().purgeSummary("old"));

        assertEquals(ErrorCode.CONFLICT, refused.failure().errorCode());
        assertTrue(refused.getMessage().contains("does not know its FDE's role yet"));
      }
    }
  }

  @Test
  void aMembersNodeIsToldAPurgeIsAdminOnlyBeforeAnythingIsAsked() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert("old", "name: old\n"));
      new FdeStore(db).add("node", "Node", "node@example.com", "member");
      try (var operations =
          OperationsFactory.create(
                  db, shell, "sail.yaml", null, null, SyncScheduler.disabled(), SessionYield.NONE)
              .useControlPlane(
                  db,
                  tempDir,
                  new SyncOperations(
                      db,
                      "node",
                      tempDir,
                      () -> new SyncConfig("node", "main", "node", "node-box"),
                      target -> {
                        throw new IOException("main unavailable");
                      }))) {
        var refused =
            assertThrows(ApiException.class, () -> operations.catalog().purgeSummary("old"));

        assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, refused.failure().errorCode());
        assertEquals(List.of(), new EraseRequests(db).pending("project"));
      }
    }
  }

  @Test
  void renameChangesOnlyTheCatalogAndPurgeErasesTheProjectWithItsFiles() {
    try (var db = Sqlite.openMemory();
        var operations = operations(db)) {
      new SchemaManager(db).migrate();
      var projects = new ProjectStore(db);
      var definition = "name: old\n";
      Acting.system(() -> projects.upsert("old", definition));
      ai.singlr.sail.engine.ProjectFileFixtures.put(
          operations.projectFiles("old"), "config", new byte[] {1});
      assertFalse(operations.catalog().destroy("old", false).purged());
      var renamed = operations.catalog().rename("old", "new");
      assertTrue(operations.catalog().project("old").isEmpty());
      assertTrue(operations.catalog().project("new").isPresent());
      assertEquals("config", operations.projectFiles("new").list().getFirst().path());
      operations.catalog().undoRename(renamed);
      assertEquals(definition, operations.catalog().project("old").orElseThrow().definition());
      assertEquals(1, operations.catalog().projects().size());
      assertEquals(
          "0 specs, 0 rooms, 0 messages, 0 runs, 0 reviews, 1 files, 1 projects, 0 events and 0"
              + " bytes of content",
          operations.catalog().purgeSummary("old"),
          "the purge is rehearsed first; the rename's history still holds the file's content");
      assertEquals("config", operations.projectFiles("old").list().getFirst().path());
      assertTrue(operations.catalog().destroy("old", true).purged());
      assertTrue(operations.projectFiles("old").list().isEmpty(), "its files go with it");
      assertEquals(
          List.of("erasure"),
          db.query(
              "SELECT kind FROM change_log WHERE entity_type = 'project' AND entity_id = 'old'",
              row -> row.text(0)),
          "a purge leaves only the audit row of the project");
      assertFalse(operations.catalog().destroy("old", true).purged());
    }
  }

  @Test
  void hostReadsAndCredentialsUseTheSameDatabaseAsTheApi() throws Exception {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      Acting.system(() -> box.specs.create(SyncBox.spec("auth", "task", "pending")));
      Acting.system(() -> box.specs.setContent("auth", "body", "plan"));
      assertEquals("auth", operations.catalog().projectSpecs("proj").getFirst().id());
      assertEquals("body", operations.catalog().specContent("auth").orElseThrow().body());
      assertTrue(operations.dispatching().latestRun("proj", "node").isEmpty());
      assertTrue(operations.dispatching().runningRuns("proj", "node").isEmpty());
      assertNull(operations.dispatching().projectSession("proj", "node"));
      assertFalse(operations.catalog().roomKnown(null));
      assertFalse(operations.catalog().roomKnown("auth"));
      Acting.system(
          () ->
              new RoomStore(box.db)
                  .create(
                      new RoomStore.RoomRow(
                          "auth", "proj", "room", "owner", "on", "[]", "owner", null, null,
                          "owner")));
      assertTrue(operations.catalog().roomKnown("auth"));
      var owner = new FdeStore(box.db).add("owner", null, null, "admin");
      assertEquals(owner, operations.identity().fde("owner").orElseThrow());
      var token =
          operations.identity().createToken("test", "admin", owner.id(), Duration.ofDays(1));
      assertNotNull(token.token());
      assertEquals("test", operations.identity().tokens().getFirst().name());
      assertTrue(operations.identity().revokeToken("test"));
      assertFalse(operations.identity().revokeToken("test"));
      assertTrue(operations.identity().tokens().isEmpty());
      assertTrue(operations.identity().sshKeys().isEmpty());
      assertTrue(operations.schema().version() > 0);
      assertEquals(new PtyIdentity("owner", true), operations.pty().identity(null, "owner"));
      var session = new AuthSessionStore(box.db).create(owner.id(), Duration.ofHours(1));
      assertEquals("owner", operations.pty().identity(session.token(), null).fde());
      operations.pty().admitRoom("auth", "proj", new PtyIdentity("owner", true));
      assertThrows(
          IOException.class,
          () -> operations.pty().admitRoom("auth", "other", new PtyIdentity("owner", true)));
      assertTrue(
          operations.identity().authorizeGateway("not-allowed", "owner")
              instanceof SshGateway.Rejected);
      operations
          .pty()
          .recordEvent(
              new EventStore.EventRow(
                  0, "now", "pty_session_started", "proj", "auth", "owner", "node", "{}"));
      assertEquals(
          1L, box.db.queryOne("SELECT count(*) FROM events", row -> row.integer(0)).orElseThrow());
      assertTrue(operations.catalog().demoDefinition().contains("demo"));
      assertTrue(operations.catalog().destroy("demo", true).purged());
      var missing =
          assertThrows(IllegalStateException.class, () -> operations.catalog().demoDefinition());
      assertTrue(missing.getMessage().contains("sail migrate"), "a purged demo is not resurrected");
      operations.useSyncScheduler(SyncScheduler.disabled());
    }
  }

  @Test
  void localRunQueriesAndReviewLogsRemainScopedToTheExecutingNode() throws Exception {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      var runs = new RunStore(box.db);
      var id = DateTimeUtils.newId().toString();
      Acting.system(
          () ->
              runs.create(
                  id, "proj", "auth", "node", "node", "build", "codex", "branch", "task", 1, null,
                  "/log", "unit"));
      assertEquals(id, operations.dispatching().latestRun("proj", "node").orElseThrow().id());
      assertEquals(id, operations.dispatching().activeRun("proj", "node").orElseThrow().id());
      assertTrue(operations.dispatching().activeRun("proj", "other").isEmpty());
      assertTrue(operations.dispatching().latestRun("proj", "other").isEmpty());
      assertEquals(1, operations.dispatching().runningRuns("proj", "node").size());
      var fallback = operations.dispatching().reviewLog("proj", "node");
      var review = Acting.system(() -> new ReviewStore(box.db).createReview("auth", 1));
      assertEquals(
          AgentUnit.forReview(review).logPath(),
          operations.dispatching().reviewLog("proj", "node"));
      assertEquals(fallback, operations.dispatching().reviewLog("proj", "other"));
    }
  }

  @Test
  void hostLaunchEntryPointsRejectMissingProjectsBeforeStartingAnything() {
    try (var box = new SyncBox("node");
        var operations = operations(box.db)) {
      var request = new DispatchOperations.AdhocRequest("task", null, null, true, false);
      assertThrows(
          ApiException.class, () -> operations.dispatching().startAdhoc("absent", request, "node"));
      assertThrows(
          ApiException.class,
          () -> operations.dispatching().startAdhoc("absent", request, "node", () -> {}));
      assertThrows(
          ApiException.class,
          () ->
              operations
                  .dispatching()
                  .stop(
                      new StopOperations.RunTarget("missing"),
                      Actor.cliOperator("node"),
                      "node",
                      false));
    }
  }

  private static SyncOperations.Channel channel(SyncBox main) throws IOException {
    return channel(
        SyncRpcServer.over(
            main.db,
            "main",
            Actor.sync("node", Role.MEMBER),
            List::of,
            SyncTransitionSink.NONE,
            SyncWire.UPGRADE_FLOOR));
  }

  private static SyncOperations.Channel channel(SyncRpcServer server) throws IOException {
    var toServer = new PipedOutputStream();
    var serverIn = new PipedInputStream(toServer);
    var serverOut = new PipedOutputStream();
    var fromServer = new PipedInputStream(serverOut);
    var error = new AtomicReference<Throwable>();
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (serverIn;
                      serverOut) {
                    server.serve(serverIn, serverOut);
                  } catch (Throwable e) {
                    error.set(e);
                  }
                });
    return new SyncOperations.Channel() {
      public InputStream reader() {
        return fromServer;
      }

      public OutputStream writer() {
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
