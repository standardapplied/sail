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
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

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

    var taken =
        HostSyncCommand.takeRole(
            hostYaml, dbPath, named, true, null, null, "devbox", NO_ONE, OperatorCommand.PLAIN);

    assertTrue(taken.sync().isMain());
    assertTrue(taken.enrolled().isEmpty());
    assertTrue(HostYaml.fromMap(YamlUtil.parseFile(hostYaml)).sync().isMain());
    try (var db = Sqlite.open(dbPath)) {
      var stamped = new RunStore(db).findById(run).orElseThrow();
      assertEquals("ada", stamped.node());
      assertEquals("ada", stamped.owner());
    }
  }

  private static final SyncOperations.Channels NO_ONE =
      target -> {
        throw new IOException("a box becoming main asks no one");
      };

  private static HostSyncCommand.Taken asMainOperatedBy(Path dir, String handle) throws Exception {
    var hostYaml = dir.resolve("host.yaml");
    var host = HostYaml.fromMap(YamlUtil.parseFile(hostYaml));
    var own =
        handle == null ? null : new HostSyncCommand.OwnFde(handle, "Ada L", "ada@example.com");
    return HostSyncCommand.takeRole(
        hostYaml,
        dir.resolve("sail.db"),
        host,
        true,
        null,
        own,
        "devbox",
        NO_ONE,
        OperatorCommand.PLAIN);
  }

  private static List<FdeStore.Fde> roster(Path dir) {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      return new FdeStore(db).list();
    }
  }

  @Test
  void aFreshMainTakesItsOperatorAsAdminAndActsAsIt(@TempDir Path dir) throws Exception {
    YamlUtil.dumpToFile(BASE.toMap(), dir.resolve("host.yaml"));

    var taken = asMainOperatedBy(dir, "ada");

    var role = taken.sync();
    assertTrue(role.isMain());
    assertEquals("ada", role.handle());
    assertEquals(
        "ada", HostYaml.fromMap(YamlUtil.parseFile(dir.resolve("host.yaml"))).sync().handle());
    assertEquals(Optional.of("  @|green ✓|@ FDE added: ada (admin)"), taken.enrolled());
    var ada = roster(dir).getFirst();
    assertEquals(
        List.of("ada", "Ada L", "ada@example.com", "admin", "active"),
        List.of(ada.handle(), ada.displayName(), ada.email(), ada.role(), ada.status()));
    assertEquals(
        "  ✓ This box is main.\n  FDE: ada",
        HostSyncCommand.roleLines(role, OperatorCommand.PLAIN, Ansi.OFF));
  }

  @Test
  void takingTheSameOperatorTwiceChangesNothingAndSaysSo(@TempDir Path dir) throws Exception {
    YamlUtil.dumpToFile(BASE.toMap(), dir.resolve("host.yaml"));
    asMainOperatedBy(dir, "ada");
    var config = Files.readString(dir.resolve("host.yaml"));
    var before = roster(dir);

    var again = asMainOperatedBy(dir, "ada");

    assertEquals(config, Files.readString(dir.resolve("host.yaml")));
    assertEquals(before, roster(dir));
    assertEquals(
        Optional.of("  @|faint FDE ada is already on the roster (admin).|@"), again.enrolled());
  }

  @Test
  void anFdeAlreadyOnTheRosterKeepsItsRoleAndIsToldHowToBecomeAdmin(@TempDir Path dir)
      throws Exception {
    YamlUtil.dumpToFile(BASE.toMap(), dir.resolve("host.yaml"));
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add("ada", null, null, "member");
    }

    assertEquals(
        Optional.of(
            "  @|faint FDE ada is already on the roster (member).|@ Make it admin: @|bold sail fde"
                + " update ada --role admin|@"),
        asMainOperatedBy(dir, "ada").enrolled());
    assertEquals("member", roster(dir).getFirst().role());
  }

  @Test
  void aDisabledFdeOnTheRosterIsSaidToBe(@TempDir Path dir) throws Exception {
    YamlUtil.dumpToFile(BASE.toMap(), dir.resolve("host.yaml"));
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      fdes.add("ada", null, null, "admin");
      fdes.disableUnlisted(List.of());
    }

    assertEquals(
        Optional.of("  @|faint FDE ada is already on the roster (admin, disabled).|@"),
        asMainOperatedBy(dir, "ada").enrolled());
  }

  @Test
  void aMainActingAsAnotherFdeIsRefusedNamingBothAndLeftAsItIs(@TempDir Path dir) throws Exception {
    var grace =
        HostConfigSetCommand.applyChange(
            HostSyncCommand.configure(BASE, true, null, "devbox"), "sync-handle", "grace");
    YamlUtil.dumpToFile(grace.toMap(), dir.resolve("host.yaml"));
    var config = Files.readString(dir.resolve("host.yaml"));

    var refused = assertThrows(IllegalStateException.class, () -> asMainOperatedBy(dir, "ada"));

    assertEquals(
        "This box already acts as 'grace', not 'ada'. Re-run with --handle grace, or change its"
            + " handle first: sail host config set sync-handle ada",
        refused.getMessage());
    assertEquals(config, Files.readString(dir.resolve("host.yaml")));
  }

  @Test
  void takingTheMainRoleWithNoHandleKeepsTheOneTheBoxHas(@TempDir Path dir) throws Exception {
    var grace =
        HostConfigSetCommand.applyChange(
            HostSyncCommand.configure(BASE, true, null, "devbox"), "sync-handle", "grace");
    YamlUtil.dumpToFile(grace.toMap(), dir.resolve("host.yaml"));

    assertEquals("grace", asMainOperatedBy(dir, null).sync().handle());
  }

  private static String refusalOf(String... args) {
    var refusal = new String[1];
    var command = new CommandLine(new HostSyncCommand());
    command.setErr(new PrintWriter(new StringWriter()));
    command.setExecutionExceptionHandler(
        (failure, line, parsed) -> {
          refusal[0] = failure.getMessage();
          return 1;
        });
    assertEquals(1, command.execute(args));
    return refusal[0];
  }

  @Test
  void aHandleNamesMainsOwnFdeSoANodeIsToldWhereItsHandleIsSet() {
    var originalErr = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try {
      var nodeRefusal = refusalOf("--main", "sail@m", "--handle", "ada");
      assertTrue(
          nodeRefusal.startsWith(
              "--handle names main's own FDE, so it goes with --as-main. A node takes its handle"
                  + " when it joins: "),
          nodeRefusal);
      assertTrue(nodeRefusal.endsWith("sail join sail@m --handle ada"), nodeRefusal);
      assertEquals(
          "--name and --email describe the FDE --handle names. Pass --handle <handle> too.",
          refusalOf("--as-main", "--email", "ada@example.com"));
      assertEquals(
          "Invalid email: 'not-an-email'. Must be one address, like ada@example.com.",
          refusalOf("--as-main", "--handle", "ada", "--email", "not-an-email"));
      assertEquals(
          "Invalid FDE handle: 'a b'. Must match [A-Za-z0-9][A-Za-z0-9._-]*, max 63 characters.",
          refusalOf("--as-main", "--handle", "a b"));
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void aMalformedMainTargetIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostSyncCommand.configure(BASE, false, "not a target!", "devbox"));
  }
}
