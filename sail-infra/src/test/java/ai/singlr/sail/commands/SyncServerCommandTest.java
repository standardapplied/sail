/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.SpecReplica;
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
  private Sqlite mainDb;
  private Sqlite nodeDb;
  private SpecStore mainSpecs;
  private SpecStore nodeSpecs;
  private SpecReplica nodeReplica;

  @BeforeEach
  void setUp() {
    mainDb = open("main");
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
    mainDb.close();
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
    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));

    var serverThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    SyncServerCommand.serve(mainDb, "main", token, serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });

    try (var session = new SyncSession(clientIn, toServer)) {
      return new SyncEngine().reconcile(nodeReplica, session.replica("spec"));
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
