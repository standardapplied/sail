/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrations;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncTransitionSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scoped node's round against a real Incus daemon: a container Sail provisioned for a project the
 * scope does not admit is deleted, and one Sail did not provision is left alone.
 */
class ScopeTeardownIT extends AbstractIncusIT {

  private static final String PROVISIONED = "sail-it-scope-gone";
  private static final String FOREIGN = "sail-it-scope-kept";

  @TempDir Path tempDir;

  @Test
  void aRoundDeletesTheContainerSailProvisionedForAProjectOutOfScopeAndNoOther() throws Exception {
    ensureIncusOrSkip();
    var socketDir = Files.createTempDirectory("sail-it-scope-socket");
    Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    try {
      launch(PROVISIONED);
      launch(FOREIGN);
      new IncusDeviceManager(shell)
          .ensureEventSocket(PROVISIONED, socketDir, Path.of("/var/lib/sail/run"));
      Files.createDirectories(tempDir.resolve("projects").resolve(PROVISIONED));

      roundScopedTo("acme");

      var containers = new ContainerManager(shell);
      assertInstanceOf(ContainerState.NotCreated.class, containers.queryState(PROVISIONED));
      assertInstanceOf(ContainerState.Running.class, containers.queryState(FOREIGN));
      assertFalse(Files.exists(tempDir.resolve("projects").resolve(PROVISIONED)));
      assertNull(new IncusDeviceManager(shell).currentEventSocketSource(FOREIGN));
    } finally {
      deleteContainerQuietly(PROVISIONED);
      deleteContainerQuietly(FOREIGN);
      deleteRecursively(socketDir);
    }
  }

  /** One round of a node whose FDE main scopes to {@code project}, asking this host's Incus. */
  private void roundScopedTo(String project) throws Exception {
    var mainPath = tempDir.resolve("main.db");
    try (var db = Sqlite.open(mainPath)) {
      MigrationRunner.applyAll(db, DataMigrations.all(), DataMigration.Prompter.NON_INTERACTIVE);
    }
    try (var main = SyncDatabase.converge(mainPath, "main");
        var nodeDb = Sqlite.open(tempDir.resolve("node.db"))) {
      var fdes = new FdeStore(main.db());
      fdes.add("ada", null, null, "admin");
      var nia = fdes.add("nia", null, null, "member");
      fdes.restrict("nia");
      fdes.grant("nia", project);
      var token = new AuthSessionStore(main.db()).create(nia.id(), Duration.ofHours(1)).token();
      new SchemaManager(nodeDb).migrate();
      var node =
          new SyncOperations(
              nodeDb,
              "node",
              tempDir.resolve("projects"),
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
      Actor.call(
          new Actor("nia", Role.MEMBER, Actor.Lane.CLI), () -> node.sync(new SyncRequest(null)));
    }
  }
}
