/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The host CLI is the box's FDE: the token {@code sail server init} mints, and the FDE-less one an
 * existing box already holds, acts over HTTP as the FDE the box's sync handle names when it is
 * used, with the role the role rule gives it — admin for main's operator, the synced roster role on
 * a node, and whatever role the roster holds now.
 */
class HostCliIdentityTest {

  private static final SyncConfig MAIN = new SyncConfig("main", null, "uday", "main-box");
  private static final SyncConfig NODE = new SyncConfig("node", "sail@main", "uday", "node-box");

  private static final ShellExec SHELL =
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

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private SpecStore specs;
  private Path configPath;
  private SailOperations operations;
  private SailApiServer server;
  private final AtomicReference<SyncConfig> box = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    fdes = new FdeStore(db);
    specs = new SpecStore(db);
    configPath = tempDir.resolve("config.yaml");
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (operations != null) {
      operations.close();
    }
    db.close();
  }

  @Test
  void afterServerInitAHostCliWriteRecordsTheBoxsFde() throws Exception {
    fdes.add("uday", null, null, "member");
    serve(MAIN);

    operations.identity().mintHostToken(configPath);

    assertEquals(0, create(savedToken(), "auth"));
    assertEquals("uday", specs.findById("auth").orElseThrow().createdBy());
    assertEquals("uday", specs.findById("auth").orElseThrow().updatedBy());
    assertTrue(HostToken.describe(operations.identity().box()).contains("'uday'"));
  }

  @Test
  void theFdeLessHostTokenAnExistingBoxHoldsActsAsItsFdeWithNoMigration() throws Exception {
    var token = new TokenStore(db).create(HostToken.NAME, "admin").token();
    serve(MAIN);

    assertEquals(0, create(token, "auth"), "main's operator is admin before its FDE is added");
    assertEquals("uday", specs.findById("auth").orElseThrow().createdBy());
  }

  @Test
  void aBoxWithNoSyncHandleHasAHostTokenThatActsAsNoFde() throws Exception {
    serve(SyncConfig.unset());

    operations.identity().mintHostToken(configPath);

    assertEquals(0, create(savedToken(), "auth"));
    assertNull(specs.findById("auth").orElseThrow().createdBy());
    assertTrue(HostToken.describe(operations.identity().box()).contains("no sync handle"));
  }

  @Test
  void onANodeTheHostTokenActsWithTheSyncedRole() throws Exception {
    fdes.add("uday", null, null, "viewer");
    serve(NODE);
    operations.identity().mintHostToken(configPath);

    assertNotEquals(0, create(savedToken(), "refused"), "a viewer node's CLI cannot write");
    assertTrue(specs.findById("refused").isEmpty());

    fdes.update("uday", null, null, "member");
    assertEquals(0, create(savedToken(), "allowed"));
    assertEquals("uday", specs.findById("allowed").orElseThrow().createdBy());
  }

  @Test
  void aNodeThatHasNotPulledItsRosterRefusesTheHostTokenAsAConflictToResolve() throws Exception {
    serve(NODE);
    operations.identity().mintHostToken(configPath);

    var refused = assertThrows(IOException.class, () -> whoami(savedToken()));

    assertTrue(refused.getMessage().contains("HTTP 409"), refused.getMessage());
    assertTrue(
        OperatorCommand.SUDO.render((Remedied) refused).contains("Run 'sudo sail sync' first."),
        refused.getMessage());
    fdes.add("uday", null, null, "member");
    assertEquals("uday", whoami(savedToken()).get("fde"));
  }

  @Test
  void aNodeThatNamesNoFdeRefusesEveryTokenThatNamesNone() throws Exception {
    fdes.add("uday", null, null, "admin");
    var machine = new TokenStore(db).create("ci", "admin").token();
    serve(new SyncConfig("node", "sail@main", null, "node-box"));
    operations.identity().mintHostToken(configPath);

    for (var token : List.of(savedToken(), machine)) {
      var refused = assertThrows(IOException.class, () -> whoami(token));
      assertTrue(refused.getMessage().contains("HTTP 409"), refused.getMessage());
      assertTrue(refused.getMessage().contains("sync-handle"), refused.getMessage());
    }
    assertTrue(HostToken.describe(operations.identity().box()).contains("refused"));
  }

  @Test
  void theHostTokenFollowsTheBoxsSyncHandle() throws Exception {
    fdes.add("uday", null, null, "member");
    fdes.add("raj", null, null, "member");
    serve(NODE);
    operations.identity().mintHostToken(configPath);
    assertEquals("uday", whoami(savedToken()).get("fde"));

    box.set(new SyncConfig("node", "sail@main", "raj", "node-box"));

    assertEquals(0, create(savedToken(), "auth"));
    assertEquals("raj", specs.findById("auth").orElseThrow().createdBy());
  }

  @Test
  void aMachineTokenOnANodeActsAsNoFdeWithItsBoxsRole() throws Exception {
    fdes.add("uday", null, null, "member");
    var token = new TokenStore(db).create("ci", "admin").token();
    serve(NODE);

    var whoami = whoami(token);

    assertNull(whoami.get("fde"));
    assertEquals("ci", whoami.get("name"));
    assertEquals("member", whoami.get("role"));
    assertEquals(0, create(token, "auth"));
    assertNull(specs.findById("auth").orElseThrow().createdBy());
  }

  @Test
  void aTokenNeverOutranksItsFdesRosterRole() throws Exception {
    var raj = fdes.add("raj", null, null, "member");
    var token = new TokenStore(db).create("raj", "admin", raj.id(), null).token();
    serve(MAIN);

    var whoami = whoami(token);

    assertEquals("raj", whoami.get("fde"));
    assertEquals("member", whoami.get("role"), "the roster caps a token minted as admin");
  }

  @Test
  void aDemotedFdesTokenActsWithTheDemotedRole() throws Exception {
    var raj = fdes.add("raj", null, null, "admin");
    var token = new TokenStore(db).create("raj", "admin", raj.id(), null).token();
    serve(MAIN);
    assertEquals(0, create(token, "before"));

    fdes.update("raj", null, null, "viewer");

    assertNotEquals(0, create(token, "after"));
    assertTrue(specs.findById("after").isEmpty());
  }

  @Test
  void aMainSetUpAgainFromAnotherBoxsCopyReissuesItsHostTokenAndKeepsEveryOther() throws Exception {
    fdes.add("uday", null, null, "admin");
    var tokens = new TokenStore(db);
    var ci = tokens.create("ci", "member").token();
    tokens.create(HostToken.NAME, "admin");
    try (var own = Sqlite.open(tempDir.resolve("own.db"))) {
      new SchemaManager(own).migrate();
      HostToken.mint(new TokenStore(own), null, configPath);
    }
    serve(MAIN);
    assertNotEquals(0, create(savedToken(), "refused"), "the copy holds another box's token");

    var outcome = HostToken.ensure(tokens, configPath);

    assertEquals(HostToken.Outcome.REISSUED, outcome);
    assertEquals(
        "API token re-issued for this box: the database came from another box.",
        HostToken.said(outcome, configPath, MAIN).orElseThrow());
    assertEquals(0, create(savedToken(), "auth"));
    assertEquals("uday", specs.findById("auth").orElseThrow().createdBy());
    assertEquals("uday", whoami(savedToken()).get("fde"));
    assertEquals("ci", whoami(ci).get("name"), "every other token in the copy is kept");
    assertEquals(
        List.of("admin", "ci"),
        tokens.list().stream().map(TokenStore.TokenInfo::name).sorted().toList());
  }

  @Test
  void aConfigTokenTheDatabaseHoldsIsLeftAsItIs() throws Exception {
    var tokens = new TokenStore(db);
    HostToken.mint(tokens, null, configPath);
    var before = Files.readString(configPath);

    var outcome = HostToken.ensure(tokens, configPath);

    assertEquals(HostToken.Outcome.HELD, outcome);
    assertEquals(before, Files.readString(configPath));
    assertEquals(1, tokens.list().size());
    assertEquals(Optional.empty(), HostToken.said(outcome, configPath, MAIN));
  }

  @Test
  void aDatabaseWithNoTokenMintsTheHostToken() throws Exception {
    var outcome = HostToken.ensure(new TokenStore(db), configPath);

    assertEquals(HostToken.Outcome.MINTED, outcome);
    assertEquals(
        "API token created and saved to " + configPath + "; it acts as this box's FDE 'uday'",
        HostToken.said(outcome, configPath, MAIN).orElseThrow());
    assertEquals(HostToken.NAME, new TokenStore(db).validate(savedToken()).orElseThrow().name());
  }

  @Test
  void aConfigWithNoHostTokenToWeighIsLeftAsItIs() throws Exception {
    var tokens = new TokenStore(db);
    tokens.create("ci", "member");
    assertEquals(HostToken.Outcome.HELD, HostToken.ensure(tokens, configPath), "no config");

    Files.writeString(configPath, "server: http://localhost:7070\n");
    assertEquals(HostToken.Outcome.HELD, HostToken.ensure(tokens, configPath), "no token");

    Files.writeString(configPath, "token: sess_0123\n");
    assertEquals(HostToken.Outcome.HELD, HostToken.ensure(tokens, configPath), "a session");
    assertEquals("token: sess_0123\n", Files.readString(configPath));
    assertEquals(List.of("ci"), tokens.list().stream().map(TokenStore.TokenInfo::name).toList());
  }

  private void serve(SyncConfig config) throws IOException {
    box.set(config);
    operations =
        OperationsFactory.create(db, SHELL, null, null, SyncScheduler.disabled(), SessionYield.NONE)
            .useControlPlane(
                db,
                tempDir,
                new SyncOperations(
                    db,
                    "box",
                    tempDir,
                    box::get,
                    target -> {
                      throw new IOException("main unavailable");
                    },
                    SyncOperations.PLAIN_NOTICES));
    server =
        new SailApiServer(
            "127.0.0.1",
            0,
            operations,
            TestAuth.sessions(db, box::get),
            new EventBus(),
            null,
            null,
            null);
    server.start();
  }

  private String savedToken() throws IOException {
    return YamlUtil.parseFile(configPath).get("token").toString();
  }

  private Map<String, Object> whoami(String token) throws IOException {
    try (var client = new SailApiClient("http://127.0.0.1:" + server.port(), token)) {
      return client.get("/v1/whoami");
    }
  }

  private int create(String token, String id) {
    return new CommandLine(new Sail())
        .execute(
            "spec",
            "create",
            "--server",
            "http://127.0.0.1:" + server.port(),
            "--token",
            token,
            "--no-sync",
            "--project",
            "acme",
            "--id",
            id,
            "--title",
            "Title " + id);
  }
}
