/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The host CLI is the box's FDE: the token {@code sail server init} mints, and one {@code sail
 * migrate} binds, acts as that FDE over HTTP, with the role the role rule gives it — admin for
 * main's operator, the synced roster role on a node, and whatever role the roster holds now.
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

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
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

    var minted = operations.identity().mintHostToken(configPath);

    assertEquals("uday", minted.fde());
    assertEquals(0, create(ServerConnectionConfig.savedToken(configPath), "auth"));
    assertEquals("uday", specs.findById("auth").orElseThrow().createdBy());
    assertEquals("uday", specs.findById("auth").orElseThrow().updatedBy());
  }

  @Test
  void afterMigrateBindsAnFdeLessHostTokenAHostCliWriteRecordsTheBoxsFde() throws Exception {
    fdes.add("uday", null, null, "member");
    var token = new TokenStore(db).create("admin", "admin").token();
    ServerConnectionConfig.saveLocalToken(token, configPath);
    serve(MAIN);
    assertEquals(0, create(token, "before"));
    assertNull(specs.findById("before").orElseThrow().createdBy(), "an FDE-less token is no one");

    MigrateCommand.bindHostToken(db, MAIN, configPath, true);

    assertEquals(0, create(token, "after"));
    assertEquals("uday", specs.findById("after").orElseThrow().createdBy());
    assertNull(
        ai.singlr.sail.engine.HostToken.bind(new TokenStore(db), fdes, MAIN, configPath),
        "a bound token is bound once");
  }

  @Test
  void aBoxWithNoSyncHandleKeepsAnFdeLessHostToken() throws Exception {
    serve(SyncConfig.unset());

    var minted = operations.identity().mintHostToken(configPath);

    assertNull(minted.fde());
    assertTrue(
        ai.singlr.sail.engine.HostToken.describe(minted, SyncConfig.unset())
            .contains("no sync handle"));
  }

  @Test
  void onANodeTheHostTokenActsWithTheSyncedRole() throws Exception {
    fdes.add("uday", null, null, "viewer");
    serve(NODE);
    operations.identity().mintHostToken(configPath);
    var token = ServerConnectionConfig.savedToken(configPath);

    assertNotEquals(0, create(token, "refused"), "a viewer node's CLI cannot write");
    assertTrue(specs.findById("refused").isEmpty());

    fdes.update("uday", null, null, "member");
    assertEquals(0, create(token, "allowed"));
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

  private void serve(SyncConfig box) throws IOException {
    operations =
        OperationsFactory.create(
                db, SHELL, "sail.yaml", null, null, SyncScheduler.disabled(), SessionYield.NONE)
            .useControlPlane(
                db,
                tempDir,
                new SyncOperations(
                    db,
                    "box",
                    tempDir,
                    () -> box,
                    target -> {
                      throw new IOException("main unavailable");
                    }));
    server =
        new SailApiServer(
            "127.0.0.1",
            0,
            operations,
            TestAuth.sessions(db, box),
            new EventBus(),
            null,
            null,
            null);
    server.start();
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
