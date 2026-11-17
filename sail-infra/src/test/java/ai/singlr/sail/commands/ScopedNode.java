/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxDatabase;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncTransitionSink;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A node whose person, nia, main scopes to one project, its rounds asking Incus through the shell
 * it is given: main is a real {@code _sync} server in {@code dir}, and so is the node's database.
 */
public final class ScopedNode implements AutoCloseable {

  private final SyncDatabase main;
  private final Sqlite nodeDb;
  private final SyncOperations node;

  public ScopedNode(Path dir, String project, ShellExec shell) throws Exception {
    var mainPath = dir.resolve("main.db");
    try (var db = Sqlite.open(mainPath)) {
      BoxDatabase.prepare(db);
    }
    main = SyncDatabase.converge(mainPath, "main");
    var fdes = new FdeStore(main.db());
    fdes.add("ada", null, null, "admin");
    var nia = fdes.add("nia", null, null, "member");
    fdes.restrict("nia");
    fdes.grant("nia", project);
    var token = new AuthSessionStore(main.db()).create(nia.id(), Duration.ofHours(1)).token();
    nodeDb = Sqlite.open(dir.resolve("node.db"));
    new SchemaManager(nodeDb).migrate();
    node =
        new SyncOperations(
            nodeDb,
            "node",
            dir.resolve("projects"),
            () -> new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "nia", "node-box"),
            target ->
                PipedSyncChannel.to(
                    (in, out) ->
                        SyncServerCommand.serve(
                            main,
                            "main",
                            token,
                            in,
                            out,
                            SyncTransitionSink.NONE,
                            () -> new SyncConfig(SyncConfig.ROLE_MAIN, null, "ada", "main"))),
            event -> {},
            SyncOperations.PLAIN_NOTICES,
            shell);
    node.prepare();
  }

  /** One round of the node, as nia. */
  public void round() throws Exception {
    Actor.call(
        new Actor("nia", Role.MEMBER, Actor.Lane.CLI), () -> node.sync(new SyncRequest(null)));
  }

  @Override
  public void close() {
    nodeDb.close();
    main.close();
  }
}
