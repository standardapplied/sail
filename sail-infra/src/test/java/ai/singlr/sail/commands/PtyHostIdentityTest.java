/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyHostIdentityTest {

  private static final SyncConfig UDAYS_NODE =
      new SyncConfig("node", "sail@main", "uday", "uday-box");

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

  @TempDir Path dir;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private PtyHostIdentity resolver(SyncConfig box) {
    return new PtyHostIdentity(
        () ->
            OperationsFactory.create(
                    db, SHELL, null, null, SyncScheduler.disabled(), SessionYield.NONE)
                .useControlPlane(
                    db,
                    dir,
                    new SyncOperations(
                        db,
                        "box",
                        dir,
                        () -> box,
                        target -> {
                          throw new IOException("main unavailable");
                        })));
  }

  @Test
  void aGatewayTokenResolvesToItsFdeWithItsRoleOnTheApiLane() throws Exception {
    var fde = new FdeStore(db).add("mady", "Mady", null, "admin");
    var token = new AuthSessionStore(db).create(fde.id(), Duration.ofMinutes(5)).token();

    var identity = resolver(SyncConfig.unset()).resolve(token);

    assertEquals(new Actor("mady", Role.ADMIN, Actor.Lane.API), identity.actor());
  }

  @Test
  void aBlankTokenIsTheBoxsPersonWithTheRosterRoleOnTheCliLane() throws Exception {
    new FdeStore(db).add("uday", "Uday", null, "member");

    var identity = resolver(UDAYS_NODE).resolve("");

    assertEquals(new Actor("uday", Role.MEMBER, Actor.Lane.CLI), identity.actor());
  }

  @Test
  void forgedTokensAndOwnerlessBoxesRefuseWithActionableMessages() {
    var forged = assertThrows(IOException.class, () -> resolver(UDAYS_NODE).resolve("nope"));
    assertTrue(forged.getMessage().contains("not valid"), forged.getMessage());

    var ownerless = assertThrows(IOException.class, () -> resolver(SyncConfig.unset()).resolve(""));
    assertTrue(ownerless.getMessage().contains("sync-handle"), ownerless.getMessage());
  }
}
