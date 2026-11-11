/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxDatabase;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncTransitionSink;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A node syncing with main as in the field: each round, main's SSH gateway authorizes {@code _sync}
 * for the node's FDE, and main serves the session the token it minted opens.
 */
final class GatewayNode implements AutoCloseable {

  private final SyncDatabase main;
  private final SyncConfig mainConfig;
  private final SyncConfig config;
  private final Sqlite db;
  private final SyncOperations sync;

  GatewayNode(Path dir, SyncDatabase main, SyncConfig mainConfig, SyncConfig config) {
    this.main = main;
    this.mainConfig = mainConfig;
    this.config = config;
    this.db = Sqlite.open(dir.resolve(config.handle() + "-node.db"));
    new SchemaManager(db).migrate();
    this.sync =
        new SyncOperations(
            db,
            config.boxId(),
            dir.resolve(config.handle() + "-projects"),
            () -> config,
            target ->
                PipedSyncChannel.to(
                    (in, out) -> {
                      var authorized =
                          assertInstanceOf(
                              SshGateway.Authorized.class,
                              gateway(main, mainConfig, "sail _sync", config.handle()));
                      SyncServerCommand.serve(
                          main,
                          "main",
                          authorized.sessionToken(),
                          in,
                          out,
                          SyncTransitionSink.NONE,
                          () -> mainConfig);
                    }),
            event -> {},
            remedy -> {},
            new ScriptedShellExecutor(new ShellExec.Result(0, "[]", "")));
    sync.prepare();
  }

  /**
   * Main's database at {@code path}, prepared and converged as {@code sail init --as-main} does.
   */
  static SyncDatabase main(Path path) {
    try (var db = Sqlite.open(path)) {
      BoxDatabase.prepare(db);
    }
    return SyncDatabase.converge(path, "main");
  }

  /** What main's SSH gateway decides of {@code command} by FDE {@code handle}. */
  static SshGateway.Decision gateway(
      SyncDatabase main, SyncConfig mainConfig, String command, String handle) {
    var roster = new FdeStore(main.db());
    return SshGateway.authorize(
        command,
        handle,
        roster,
        new RoleRule(() -> mainConfig, roster),
        new AuthSessionStore(main.db()));
  }

  Sqlite db() {
    return db;
  }

  SyncConfig config() {
    return config;
  }

  /** One round of the node, as {@code sail sync} runs it, saying nothing on the terminal. */
  SyncReport round() throws Exception {
    var err = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try {
      return sync.sync(new SyncRequest(null));
    } finally {
      System.setErr(err);
    }
  }

  @Override
  public void close() {
    db.close();
  }
}
