/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrations;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.sync.SyncedEntities;
import ai.singlr.sail.webauthn.RegisteredCredential;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Main's {@code _sync} server as a token resolves it: the session of a scoped FDE is served its
 * granted projects and the roster of the FDEs who share them, and a session that names no FDE is
 * served nothing.
 */
class ScopedSyncServerTest {

  @TempDir Path tempDir;
  private SyncDatabase main;
  private Sqlite mainDb;
  private Sqlite nodeDb;
  private FdeStore fdes;

  @BeforeEach
  void setUp() {
    var mainPath = tempDir.resolve("main.db");
    try (var db = Sqlite.open(mainPath)) {
      MigrationRunner.applyAll(db, DataMigrations.all(), DataMigration.Prompter.NON_INTERACTIVE);
    }
    main = SyncDatabase.converge(mainPath, "main");
    mainDb = main.db();
    nodeDb = Sqlite.open(tempDir.resolve("node.db"));
    new SchemaManager(nodeDb).migrate();
    fdes = new FdeStore(mainDb);
    fdes.add("ada", "Ada", "ada@x.dev", "admin");
    fdes.add("uma", "Uma", "uma@x.dev", "member");
    for (var scoped : List.of("nia", "sam", "yan", "bob")) {
      fdes.add(scoped, scoped, scoped + "@x.dev", "member");
      fdes.restrict(scoped);
    }
    fdes.grant("nia", "alpha");
    fdes.grant("sam", "alpha");
    fdes.grant("sam", "beta");
    fdes.grant("yan", "beta");
    Acting.system(
        () -> {
          var specs = new SpecStore(mainDb);
          specs.create(spec("a1", "alpha", "sam"));
          specs.create(spec("b1", "beta", "yan"));
        });
  }

  @AfterEach
  void tearDown() {
    nodeDb.close();
    main.close();
  }

  @Test
  void aScopedFdesSessionPullsItsProjectsAndTheRosterOfThoseWhoShareThem() throws Exception {
    var node = new FdeStore(nodeDb);
    for (var known : List.of("ada", "sam", "yan")) {
      node.add(known, known, known + "@old.dev", "member");
    }

    var round = roundAs(tokenOf("nia"));

    assertEquals(ProjectScope.of(List.of("alpha")), round.scope());
    assertEquals(List.of("a1"), nodeDb.query("SELECT id FROM specs", row -> row.text(0)));
    assertEquals(
        List.of(
            entry("ada", "Ada", "", "admin"),
            entry("nia", "nia", "nia@x.dev", "member"),
            entry("sam", "sam", "", "member"),
            entry("uma", "Uma", "", "member")),
        round.roster().stream().map(ScopedSyncServerTest::withoutCreatedAt).toList());

    assertEquals(List.of(), SyncOperations.applyFdes(node, round.roster(), round.scope(), "nia"));

    assertTrue(node.byHandle("sam").orElseThrow().active(), "an assignee in alpha stays enabled");
    assertTrue(node.byHandle("ada").orElseThrow().active());
    assertTrue(node.byHandle("yan").isEmpty(), "no one outside alpha stays on a scoped box");
    assertEquals("", node.byHandle("sam").orElseThrow().email());
    assertEquals("nia@x.dev", node.byHandle("nia").orElseThrow().email());
    assertEquals(ProjectScope.all(), node.scopeOf("nia"), "a node scopes no one");
  }

  @Test
  void aScopedNodeRemovesEveryoneMainNoLongerListsWithTheirCredentialsButItsOwnFde()
      throws Exception {
    var node = new FdeStore(nodeDb);
    var own = node.add("nia", "Nia", "nia@old.dev", "member");
    var yan = node.add("yan", "Yan", "yan@x.dev", "member");
    node.add("eng", "Eng", "eng@x.dev", "member");
    node.add("sam", "Sam", "sam@x.dev", "member");
    new TokenStore(nodeDb).create("mast-yan", "member", yan.id(), null);
    new WebauthnCredentialStore(nodeDb)
        .save(
            new RegisteredCredential(new byte[] {7}, new byte[] {1}, -7, 0, null, false, false),
            yan.id(),
            "laptop");
    new TokenStore(nodeDb).create("mast-nia", "member", own.id(), null);
    node.disableUnlisted(List.of("nia", "sam"));
    var round = roundAs(tokenOf("nia"));
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      assertEquals(List.of(), SyncOperations.applyFdes(node, round.roster(), round.scope(), "nia"));
    } finally {
      System.setErr(originalErr);
    }

    assertEquals(
        "  roster: removed 2 FDE(s) main no longer lists to this box\n",
        err.toString(StandardCharsets.UTF_8));
    assertEquals(
        round.roster().stream().map(fde -> fde.get("handle")).toList(),
        node.list().stream().map(FdeStore.Fde::handle).toList(),
        "the node's roster, which its GET /v1/fdes lists whole, is main's list to it");
    assertEquals(own.id(), node.byHandle("nia").orElseThrow().id());
    assertEquals(
        List.of("mast-nia"),
        nodeDb.query("SELECT name FROM api_tokens", row -> row.text(0)),
        "a removed FDE's token goes with it, and the box's own stays");
    assertEquals(0L, count("SELECT count(*) FROM webauthn_credentials"));

    var withoutItsOwn =
        round.roster().stream().filter(fde -> !"nia".equals(fde.get("handle"))).toList();
    SyncOperations.applyFdes(node, withoutItsOwn, round.scope(), "nia");
    assertEquals(own.id(), node.byHandle("nia").orElseThrow().id(), "its own FDE is never removed");
    assertEquals(
        List.of("ada", "nia", "sam", "uma"),
        node.list().stream().map(FdeStore.Fde::handle).toList());
  }

  @Test
  void anUnscopedNodeDisablesAnFdeMainRemovedAndKeepsItsRow() throws Exception {
    var node = new FdeStore(nodeDb);
    node.add("gone", "Gone", "gone@x.dev", "member");
    var round = roundAs(tokenOf("uma"));

    SyncOperations.applyFdes(node, round.roster(), round.scope(), "uma");

    var gone = node.byHandle("gone").orElseThrow();
    assertEquals("disabled", gone.status());
    assertEquals("gone@x.dev", gone.email());
  }

  @Test
  void aScopedFdeWithNoGrantPullsNothingButItsOwnRosterEntry() throws Exception {
    var round = roundAs(tokenOf("bob"));

    assertEquals(ProjectScope.none(), round.scope());
    assertEquals(0L, count("SELECT count(*) FROM change_log"));
    assertEquals(
        List.of(entry("bob", "bob", "bob@x.dev", "member")),
        round.roster().stream().map(ScopedSyncServerTest::withoutCreatedAt).toList());
  }

  @Test
  void anUnscopedFdesSessionPullsEverythingAndTheWholeRosterWithEmails() throws Exception {
    var round = roundAs(tokenOf("uma"));

    assertEquals(ProjectScope.all(), round.scope());
    assertEquals(
        List.of("a1", "b1"), nodeDb.query("SELECT id FROM specs ORDER BY id", row -> row.text(0)));
    assertEquals(SyncServerCommand.roster(mainDb, Actor.main()), round.roster());
    assertEquals(
        List.of("ada@x.dev", "bob@x.dev", "nia@x.dev", "sam@x.dev", "uma@x.dev", "yan@x.dev"),
        round.roster().stream().map(fde -> fde.get("email")).toList());
  }

  @Test
  void aSessionWithNoTokenReceivesNoRowAndNoRoster() throws Exception {
    for (var token : new String[] {null, "", "sess_never-issued"}) {
      var round = roundAs(token);

      assertEquals(ProjectScope.none(), round.scope());
      assertEquals(0L, count("SELECT count(*) FROM change_log"));
      assertEquals(List.of(), round.roster());
    }
  }

  private record Round(ProjectScope scope, List<Map<String, Object>> roster) {}

  /** One round of every type over a session main authenticates with {@code token}. */
  private Round roundAs(String token) throws Exception {
    var toServer = new PipedOutputStream();
    var serverIn = new BufferedInputStream(new PipedInputStream(toServer));
    var toClient = new PipedOutputStream();
    var clientIn = new BufferedInputStream(new PipedInputStream(toClient));
    var server =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    SyncServerCommand.serve(main, "main", token, serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
    try (var session =
        SyncSession.open(
            clientIn,
            toServer,
            SyncWire.Hello.of(SyncWire.UPGRADE_FLOOR, "node-box"),
            notice -> {},
            ignored -> {},
            nodeDb)) {
      var replicas = SyncedEntities.replicas(nodeDb, "node-box", "nia");
      for (var entity : SyncedEntities.all()) {
        Actor.call(
            Actor.main(), () -> session.reconcile(entity.type(), replicas.get(entity.type())));
      }
      return new Round(session.scope(), session.fetchFdes());
    } finally {
      server.join();
    }
  }

  private String tokenOf(String handle) {
    return new AuthSessionStore(mainDb)
        .create(fdes.byHandle(handle).orElseThrow().id(), Duration.ofMinutes(10))
        .token();
  }

  private static Map<String, Object> entry(String handle, String name, String email, String role) {
    return Map.of(
        "handle", handle, "display_name", name, "email", email, "role", role, "status", "active");
  }

  private static Map<String, Object> withoutCreatedAt(Map<String, Object> fde) {
    return fde.entrySet().stream()
        .filter(field -> !"created_at".equals(field.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private long count(String sql) {
    return nodeDb.queryOne(sql, row -> row.integer(0)).orElseThrow();
  }

  private static SpecStore.SpecRow spec(String id, String project, String owner) {
    return new SpecStore.SpecRow(
        id,
        project,
        "the " + project + " spec",
        SpecStatus.PENDING,
        owner,
        null,
        null,
        null,
        null,
        0,
        owner,
        "",
        "",
        owner,
        List.of(),
        List.of());
  }
}
