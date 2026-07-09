/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.LocalReplica;
import ai.singlr.sail.sync.RunReplica;
import ai.singlr.sail.sync.SpecReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncTransportException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link SyncServerCommand#serve} — the main-side command body — across a byte pipe from a
 * node's engine, so the token-to-role write gate is exercised exactly as it runs over an SSH-key
 * session, without spawning a real {@code ssh}.
 */
class SyncServerCommandTest {

  @TempDir Path tempDir;
  private SyncDatabase mainReplicaDb;
  private Sqlite mainDb;
  private Sqlite nodeDb;
  private SpecStore mainSpecs;
  private SpecStore nodeSpecs;
  private SpecReplica nodeReplica;

  @BeforeEach
  void setUp() {
    mainReplicaDb = SyncDatabase.converge(tempDir.resolve("main.db"), "main");
    mainDb = mainReplicaDb.db();
    nodeDb = open("node");
    mainSpecs = new SpecStore(mainDb);
    nodeSpecs = new SpecStore(nodeDb);
    nodeReplica =
        new SpecReplica(
            "node",
            nodeSpecs,
            new ChangeLog(nodeDb),
            new SyncConflicts(nodeDb),
            new SyncState(nodeDb));
  }

  @AfterEach
  void tearDown() {
    nodeDb.close();
    mainReplicaDb.close();
  }

  private Sqlite open(String name) {
    var db = Sqlite.open(tempDir.resolve(name + ".db"));
    new SchemaManager(db).migrate();
    return db;
  }

  private String tokenFor(String role) {
    var fde = new FdeStore(mainDb).add("uday", null, null, role);
    return new AuthSessionStore(mainDb).create(fde.id(), Duration.ofMinutes(10)).token();
  }

  private SpecStore.SpecRow spec(String id, String title) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire("pending"),
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  private SyncEngine.Report syncWithToken(String token) throws Exception {
    return syncWithToken(token, "spec", nodeReplica);
  }

  private SyncEngine.Report syncWithToken(String token, String entityType, LocalReplica replica)
      throws Exception {
    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));

    var serverThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    SyncServerCommand.serve(mainReplicaDb, "main", token, serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });

    try (var session = new SyncSession(clientIn, toServer)) {
      return new SyncEngine().reconcile(replica, session.replica(entityType));
    } finally {
      serverThread.join();
    }
  }

  @Test
  void aMemberTokenMayPushToMain() throws Exception {
    nodeSpecs.create(spec("auth", "Auth"));
    var report = syncWithToken(tokenFor("member"));

    assertEquals(1, report.pushed());
    assertEquals("Auth", mainSpecs.findById("auth").orElseThrow().title());
  }

  @Test
  void aViewerTokenMayPullButNotPush() throws Exception {
    mainSpecs.create(spec("board", "Shared"));
    var token = tokenFor("viewer");

    var pull = syncWithToken(token);
    assertEquals(1, pull.pulled());
    assertEquals("Shared", nodeSpecs.findById("board").orElseThrow().title());

    nodeSpecs.create(spec("mine", "Local only"));
    assertThrows(SyncTransportException.class, () -> syncWithToken(token));
    assertTrue(mainSpecs.findById("mine").isEmpty());
  }

  @Test
  void anAbsentTokenIsTreatedAsReadOnly() throws Exception {
    nodeSpecs.create(spec("auth", "Auth"));
    assertThrows(SyncTransportException.class, () -> syncWithToken(null));
    assertTrue(mainSpecs.findById("auth").isEmpty());
  }

  private RunReplica nodeRunReplica() {
    return new RunReplica(
        "node",
        new RunStore(nodeDb),
        new ChangeLog(nodeDb),
        new SyncConflicts(nodeDb),
        new SyncState(nodeDb));
  }

  private String createNodeRun(String node) {
    var id = DateTimeUtils.newId().toString();
    new RunStore(nodeDb)
        .create(
            id, "proj", "auth", node, "build", "claude-code", "feat/x", "task", 1, null, "/log");
    return id;
  }

  @Test
  void aMemberMayPushARunStampedWithItsOwnHandle() throws Exception {
    var runId = createNodeRun("uday");

    var report = syncWithToken(tokenFor("member"), "run", nodeRunReplica());

    assertEquals(1, report.pushed());
    assertEquals("uday", new RunStore(mainDb).findById(runId).orElseThrow().node());
  }

  @Test
  void aMemberCannotForgeARunStampedWithAnotherNode() throws Exception {
    var runId = createNodeRun("grace");
    var token = tokenFor("member");

    assertThrows(SyncTransportException.class, () -> syncWithToken(token, "run", nodeRunReplica()));
    assertTrue(new RunStore(mainDb).findById(runId).isEmpty());
  }

  @Test
  void rosterExposesMainsFdesAsMaps() {
    new ai.singlr.sail.store.FdeStore(mainDb).add("ada", "Ada", "ada@x.dev", "admin");

    var roster = SyncServerCommand.roster(mainDb);

    var ada = roster.stream().filter(m -> "ada".equals(m.get("handle"))).findFirst().orElseThrow();
    assertEquals("admin", ada.get("role"));
    assertEquals("active", ada.get("status"));
    assertEquals("Ada", ada.get("display_name"));
  }
}
