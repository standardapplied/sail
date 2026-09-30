/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostSyncCommandTest {

  private static final HostYaml BASE =
      new HostYaml(
          "dir",
          "devpool",
          null,
          "incusbr0",
          "singlr-base",
          "ubuntu/24.04",
          "6.21",
          "10.0.0.1",
          "2026-02-18T01:00:00Z",
          WebauthnConfig.disabled());

  @Test
  void asMainSetsTheMainRole() {
    var updated = HostSyncCommand.configure(BASE, true, null, "devbox");

    assertTrue(updated.sync().isMain());
    assertEquals("10.0.0.1", updated.serverIp(), "other host config is preserved");
  }

  @Test
  void aNodeTakingTheMainRoleDropsTheMainItPointedAt() {
    var node = HostSyncCommand.configure(BASE, false, "sail@maindevbox", "devbox");

    var promoted = HostSyncCommand.configure(node, true, null, "devbox");

    assertTrue(promoted.sync().isMain());
    assertNull(promoted.sync().main());
    assertEquals(node.sync().boxId(), promoted.sync().boxId());
  }

  @Test
  void mainTargetMakesItANodePointedAtMainInOneStep() {
    var updated = HostSyncCommand.configure(BASE, false, "sail@maindevbox", "devbox");

    assertEquals("node", updated.sync().role());
    assertEquals("sail@maindevbox", updated.sync().main());
  }

  @Test
  void takingARoleMintsABoxIdOnceAndKeepsAnExistingOne() {
    var asMain = HostSyncCommand.configure(BASE, true, null, "devbox");
    assertNotNull(asMain.sync().boxId());
    var asNode = HostSyncCommand.configure(asMain, false, "sail@maindevbox", "devbox");
    assertEquals(asMain.sync().boxId(), asNode.sync().boxId(), "a box keeps its identity");
    var another = HostSyncCommand.configure(BASE, false, "sail@maindevbox", "devbox");
    assertNotEquals(asMain.sync().boxId(), another.sync().boxId());
  }

  @Test
  void aBoxThatTookItsRoleBeforeIdsExistedAdoptsItsHostnameInsteadOfMintingOne() {
    var roledBeforeIds = HostConfigSetCommand.applyChange(BASE, "sync-role", "main");
    assertNull(roledBeforeIds.sync().boxId());
    var redeclared = HostSyncCommand.configure(roledBeforeIds, true, null, "devbox");
    assertEquals("devbox", redeclared.sync().boxId(), "peers checkpoint against the hostname");
    var asNode = HostSyncCommand.configure(redeclared, false, "sail@maindevbox", "elsewhere");
    assertEquals("devbox", asNode.sync().boxId(), "an id, once held, is kept");
  }

  @Test
  void aBoxTakingTheMainRoleStampsTheRunsItMadeWithNoNode(@TempDir Path dir) throws Exception {
    var hostYaml = dir.resolve("host.yaml");
    var dbPath = dir.resolve("sail.db");
    var named = HostConfigSetCommand.applyChange(BASE, "sync-handle", "ada");
    YamlUtil.dumpToFile(named.toMap(), hostYaml);
    String run;
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      run = DateTimeUtils.newId().toString();
      var id = run;
      Acting.system(
          () ->
              new RunStore(db)
                  .reserveDispatch(
                      id,
                      "acme",
                      null,
                      null,
                      "adhoc",
                      List.of(),
                      "claude-code",
                      "b",
                      "t",
                      "/l",
                      "u"));
    }

    var role =
        HostSyncCommand.takeRole(
            hostYaml,
            dbPath,
            named,
            true,
            null,
            "devbox",
            target -> {
              throw new IOException("a box becoming main asks no one");
            });

    assertTrue(role.isMain());
    assertTrue(HostYaml.fromMap(YamlUtil.parseFile(hostYaml)).sync().isMain());
    try (var db = Sqlite.open(dbPath)) {
      var stamped = new RunStore(db).findById(run).orElseThrow();
      assertEquals("ada", stamped.node());
      assertEquals("ada", stamped.owner());
    }
  }

  @Test
  void aMalformedMainTargetIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostSyncCommand.configure(BASE, false, "not a target!", "devbox"));
  }
}
