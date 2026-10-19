/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.MainHostKey;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncIdentity;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinCommandTest {

  private static final SyncOperations.Channels NO_MAIN =
      target -> {
        throw new IOException("a unit test reaches no main");
      };
  private static final SyncOperations.Channels NEVER_ASKED =
      target -> {
        throw new AssertionError("main has nothing to say about a key this join made");
      };
  private static final Actor MADY = Actor.sync("mady", Role.MEMBER);
  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);

  private static final String PUB =
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITESTKEYBLOB sail-sync:mady";

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

  private static final SshPublicKey MAIN =
      SshPublicKey.parse(TestSshKeys.ed25519("main", "root@main"));
  private static final SshPublicKey IMPOSTOR =
      SshPublicKey.parse(TestSshKeys.ed25519("impostor", "root@elsewhere"));

  @TempDir Path dir;

  private SshPublicKey answering = MAIN;
  private SyncBox main;

  @AfterEach
  void tearDown() {
    if (main != null) {
      main.close();
    }
  }

  private Path knownHosts() {
    return dir.resolve("ssh").resolve("known_hosts");
  }

  private MainHostKey pin() {
    return new MainHostKey(new Keyscan(), knownHosts(), OperatorCommand.SUDO);
  }

  private static String hashed(SshPublicKey key) {
    return "|1|c2FsdA==|aG1hYw== " + key.type() + " " + key.blob();
  }

  private JoinCommand.Plan join(Path hostConfig) throws Exception {
    return JoinCommand.plan(
        hostConfig,
        dir.resolve("sail.db"),
        identity(),
        "sail@maindevbox",
        "mady",
        null,
        null,
        NO_MAIN,
        pin(),
        OperatorCommand.PLAIN);
  }

  @Test
  void joinPinsMainsHostKeyAndHandsMainItsFingerprint() throws Exception {
    var plan = join(writeHostConfig());

    assertEquals(hashed(MAIN) + "\n", Files.readString(knownHosts()));
    assertEquals(
        "rwx------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(knownHosts().getParent())));
    assertEquals(
        "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(knownHosts())));
    assertTrue(human(plan).contains("--main-host-key " + MAIN.fingerprint()), human(plan));
    assertEquals(MAIN.fingerprint(), YamlUtil.parseMap(json(plan)).get("main_host_key"));
  }

  @Test
  void reJoiningTheSameMainLeavesTheKnownHostsByteIdentical() throws Exception {
    var hostConfig = writeHostConfig();
    join(hostConfig);
    var pinned = Files.readString(knownHosts());

    join(hostConfig);

    assertEquals(pinned, Files.readString(knownHosts()));
  }

  @Test
  void aMainAnsweringWithAnotherKeyIsRefusedBeforeTheHostConfigIsWritten() throws Exception {
    var hostConfig = writeHostConfig();
    var before = Files.readString(hostConfig);
    Files.createDirectories(knownHosts().getParent());
    Files.writeString(knownHosts(), hashed(MAIN) + "\n");
    answering = IMPOSTOR;

    var refused = assertThrows(IllegalStateException.class, () -> join(hostConfig));

    assertEquals(
        "maindevbox's SSH host key is "
            + IMPOSTOR.fingerprint()
            + ", but this box pinned "
            + MAIN.fingerprint()
            + ". If main was rebuilt, remove the old key with 'sudo ssh-keygen -R maindevbox' and"
            + " re-run 'sudo sail init --main sail@maindevbox'; otherwise something between this"
            + " box and main is answering for it.",
        refused.getMessage());
    assertEquals(before, Files.readString(hostConfig));
    assertEquals(hashed(MAIN) + "\n", Files.readString(knownHosts()));
  }

  @Test
  void aMainWhoseKeyCannotBeReadIsRefusedBeforeTheHostConfigIsWritten() throws Exception {
    var hostConfig = writeHostConfig();
    var before = Files.readString(hostConfig);
    answering = null;

    var refused = assertThrows(IllegalStateException.class, () -> join(hostConfig));

    assertEquals(
        "Couldn't read maindevbox's SSH host key on port 22: no ed25519 host key came back. Check"
            + " the address and firewall, then re-run sudo sail init --main sail@maindevbox.",
        refused.getMessage());
    assertEquals(before, Files.readString(hostConfig));
    assertFalse(Files.exists(dir.resolve("sync_ed25519")), "no sync key is made");
  }

  /** {@code ssh-keyscan} answering with main's key, and {@code ssh-keygen -F} finding it. */
  private final class Keyscan implements ShellExec {
    @Override
    public Result exec(List<String> command) throws IOException {
      if (command.getFirst().equals("ssh-keygen")) {
        return new Result(0, Files.readString(knownHosts()), "");
      }
      assertEquals(List.of("ssh-keyscan", "-H", "-t", "ed25519", "-T", "5"), command.subList(0, 6));
      return answering == null ? new Result(1, "", "") : new Result(0, hashed(answering), "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private Path writeHostConfig() throws IOException {
    var path = dir.resolve("host.yaml");
    YamlUtil.dumpToFile(BASE.toMap(), path);
    return path;
  }

  private SyncIdentity identity() {
    return new SyncIdentity(
        new StubKeygen(), dir.resolve("sync_ed25519"), dir.resolve("sync_ed25519.pub"));
  }

  @Test
  void planPointsTheBoxAtMainAndReturnsItsPublicKey() throws Exception {
    var hostConfig = writeHostConfig();

    var plan =
        JoinCommand.plan(
            hostConfig,
            dir.resolve("sail.db"),
            identity(),
            "sail@maindevbox",
            "mady",
            "Mady M",
            "mady@example.com",
            NO_MAIN,
            pin(),
            OperatorCommand.PLAIN);

    assertEquals("sail@maindevbox", plan.target());
    assertEquals("mady", plan.handle());
    assertEquals("Mady M", plan.name());
    assertEquals("mady@example.com", plan.email());
    assertEquals(PUB, plan.publicKey());
    assertEquals(MAIN.fingerprint(), plan.mainHostKey());

    var written = HostYaml.fromMap(YamlUtil.parseFile(hostConfig));
    assertEquals(SyncConfig.ROLE_NODE, written.sync().role());
    assertEquals("sail@maindevbox", written.sync().main());
    assertEquals("mady", written.sync().handle(), "join persists this box's FDE handle");
    assertNotNull(written.sync().boxId(), "join mints this box's sync identity");
    assertEquals("10.0.0.1", written.serverIp(), "unrelated host config is preserved");
  }

  private String reservedOn(Sqlite db, String handle) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            new RunStore(db)
                .reserveDispatch(
                    id, "p", null, handle, "adhoc", List.of(), "claude-code", "b", "t", "/l", "u"));
    return id;
  }

  @Test
  void joiningStampsEveryRunTheStandaloneBoxMadeWithTheHandleItJoinsAs() throws Exception {
    var hostConfig = writeHostConfig();
    var dbPath = dir.resolve("sail.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      var run = reservedOn(db, null);

      JoinCommand.plan(
          hostConfig,
          dbPath,
          identity(),
          "sail@main",
          "mady",
          null,
          null,
          NO_MAIN,
          pin(),
          OperatorCommand.PLAIN);

      assertEquals("mady", new RunStore(db).findById(run).orElseThrow().node());
      assertEquals("mady", new RunStore(db).findById(run).orElseThrow().owner());
    }
  }

  @Test
  void joiningAsAnotherHandleIsRefusedWhileARunMainHoldsUnderTheOldOneIsLive() throws Exception {
    var hostConfig = dir.resolve("host.yaml");
    YamlUtil.dumpToFile(
        BASE.withSync(new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada", "box")).toMap(),
        hostConfig);
    var dbPath = dir.resolve("sail.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      var runs = new RunStore(db);
      var held = reservedOn(db, "ada");
      Acting.system(() -> runs.applyRevision(held, runs.comparableSnapshot(held), "9-main"));

      var refused =
          assertThrows(
              IllegalStateException.class,
              () ->
                  JoinCommand.plan(
                      hostConfig,
                      dbPath,
                      identity(),
                      "sail@main",
                      "mady",
                      null,
                      null,
                      NO_MAIN,
                      pin(),
                      OperatorCommand.PLAIN));

      assertTrue(refused.getMessage().contains(held), refused.getMessage());
      assertEquals("ada", HostYaml.fromMap(YamlUtil.parseFile(hostConfig)).sync().handle());
    }
  }

  @Test
  void planRequiresAnInitializedHost() {
    var missing = dir.resolve("absent.yaml");

    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                JoinCommand.plan(
                    missing,
                    dir.resolve("sail.db"),
                    identity(),
                    "sail@main",
                    "mady",
                    null,
                    null,
                    NO_MAIN,
                    pin(),
                    OperatorCommand.PLAIN));
    assertTrue(error.getMessage().contains("host init"));
  }

  @Test
  void planRejectsAMalformedTarget() throws Exception {
    var hostConfig = writeHostConfig();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            JoinCommand.plan(
                hostConfig,
                dir.resolve("sail.db"),
                identity(),
                "not a target!",
                "mady",
                null,
                null,
                NO_MAIN,
                pin(),
                OperatorCommand.PLAIN));
  }

  @Test
  void authorizeLineCarriesNameAndEmailWhenPresent() {
    assertEquals(
        "sudo sail fde add mady --role member --name \"Mady M\" --email \"mady@example.com\""
            + " --key \""
            + PUB
            + "\" --main-host-key "
            + MAIN.fingerprint(),
        JoinCommand.authorizeLine("mady", "Mady M", "mady@example.com", PUB, MAIN.fingerprint()));
  }

  @Test
  void authorizeLineOmitsBlankNameAndEmail() {
    assertEquals(
        "sudo sail fde add mady --role member --key \""
            + PUB
            + "\" --main-host-key "
            + MAIN.fingerprint(),
        JoinCommand.authorizeLine("mady", null, "  ", PUB, MAIN.fingerprint()));
  }

  @Test
  void normalizeTargetPrependsTheImpliedSailUserForABareHost() {
    assertEquals("sail@maindevbox", JoinCommand.normalizeTarget("maindevbox"));
    assertEquals("sail@10.0.0.1", JoinCommand.normalizeTarget("  10.0.0.1 "));
  }

  @Test
  void normalizeTargetRespectsAnExplicitUser() {
    assertEquals("sail@host", JoinCommand.normalizeTarget("sail@host"));
    assertEquals("mady@host", JoinCommand.normalizeTarget("mady@host"));
  }

  @Test
  void nonSailUserWarningFiresOnlyForANonSailUser() {
    assertTrue(JoinCommand.nonSailUserWarning("mady@host").isPresent());
    assertTrue(JoinCommand.nonSailUserWarning("root@host").get().contains("sail@host"));
    assertTrue(JoinCommand.nonSailUserWarning("sail@host").isEmpty());
    assertTrue(JoinCommand.nonSailUserWarning("maindevbox").isEmpty());
  }

  @Test
  void defaultHandlePrefersThisBoxesSoleFde() {
    assertEquals("mady", JoinCommand.defaultHandle(List.of("mady"), "ignored", "ignored"));
  }

  @Test
  void defaultHandleFallsBackToSudoUserButNeverRoot() {
    assertEquals("mady", JoinCommand.defaultHandle(List.of(), "mady", null));
    assertEquals("alice", JoinCommand.defaultHandle(List.of("a", "b"), "alice", null));
  }

  @Test
  void defaultHandleUsesGitLocalPartWhenThereIsNoUsableSudoUser() {
    assertEquals("mady", JoinCommand.defaultHandle(List.of(), "root", "mady"));
    assertEquals("mady", JoinCommand.defaultHandle(List.of(), null, "mady"));
  }

  @Test
  void defaultHandleIsNullWhenNothingUsableAndNeverRoot() {
    assertEquals(null, JoinCommand.defaultHandle(List.of(), "root", "root"));
    assertEquals(null, JoinCommand.defaultHandle(List.of("a", "b"), null, null));
    assertEquals(null, JoinCommand.defaultHandle(List.of(), "", "  "));
  }

  @Test
  void handleChoiceUsesAnExplicitFlagAboveEverything() {
    var choice = JoinCommand.handleChoice("mady", false, true, "ignored");
    assertEquals(new JoinCommand.HandleChoice.Resolved("mady"), choice);
  }

  @Test
  void handleChoiceFailsWhenJsonHasNoHandle() {
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> JoinCommand.handleChoice(null, true, true, "x"));
    assertTrue(error.getMessage().contains("--json requires --handle"));
  }

  @Test
  void handleChoiceUsesTheFallbackWhenThereIsNoConsole() {
    assertEquals(
        new JoinCommand.HandleChoice.Resolved("mady"),
        JoinCommand.handleChoice(null, false, false, "mady"));
  }

  @Test
  void handleChoiceFailsWithoutAConsoleOrFallback() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> JoinCommand.handleChoice(null, false, false, null));
    assertTrue(error.getMessage().contains("No terminal to prompt"));
  }

  @Test
  void handleChoiceAsksToPromptWhenInteractive() {
    assertEquals(
        new JoinCommand.HandleChoice.NeedsPrompt("mady"),
        JoinCommand.handleChoice(null, false, true, "mady"));
  }

  @Test
  void chosenHandleTakesTheTypedLineOverTheFallback() {
    assertEquals("alice", JoinCommand.chosenHandle("  alice ", "mady"));
  }

  @Test
  void chosenHandleFallsBackOnAnEmptyLine() {
    assertEquals("mady", JoinCommand.chosenHandle("", "mady"));
    assertEquals("mady", JoinCommand.chosenHandle(null, "mady"));
  }

  @Test
  void chosenHandleRejectsAnEmptyResult() {
    assertThrows(IllegalArgumentException.class, () -> JoinCommand.chosenHandle("", null));
  }

  @Test
  void renderJsonEmitsTheAuthorizeCommand() {
    var out =
        JoinCommand.render(
            new JoinCommand.Plan(
                "sail@host", "mady", "Mady M", "mady@example.com", PUB, MAIN.fingerprint(), false),
            true,
            OperatorCommand.PLAIN);

    assertTrue(out.contains("\"target\""));
    assertTrue(out.contains("\"authorize_command\""));
    assertTrue(out.contains("\"suggested_name\""));
    assertTrue(out.contains("Mady M"));
    assertTrue(out.contains("sudo sail fde add mady --role member --name"));
    assertEquals(MAIN.fingerprint(), YamlUtil.parseMap(out).get("main_host_key"));
  }

  private static String human(JoinCommand.Plan plan) {
    return JoinCommand.render(plan, false, OperatorCommand.SUDO);
  }

  private static String json(JoinCommand.Plan plan) {
    return JoinCommand.render(plan, true, OperatorCommand.SUDO);
  }

  @Test
  void aFirstJoinHandsMainsOperatorTheLineThatAuthorizesThisBox() throws Exception {
    var plan = join(writeHostConfig());

    assertFalse(plan.mainHasKey());
    assertEquals(
        """

            ✓ This box is now a node syncing to sail@maindevbox.

            Send this to the operator of sail@maindevbox — they run it once:

              sudo sail fde add mady --role member --key "%s" --main-host-key %s

            Once they have, finish here with sudo sail sync. Only a public key leaves this box — \
        never a secret."""
            .formatted(PUB, MAIN.fingerprint()),
        human(plan));
  }

  private JoinCommand.Plan join(Path hostConfig, String handle, SyncOperations.Channels channels)
      throws Exception {
    return JoinCommand.plan(
        hostConfig,
        dir.resolve("sail.db"),
        identity(),
        "sail@maindevbox",
        handle,
        null,
        null,
        channels,
        pin(),
        OperatorCommand.PLAIN);
  }

  private SyncOperations.Channels mainServing(Actor as) {
    main = new SyncBox("main");
    return target -> PipedSyncChannel.to(main.server(as));
  }

  @Test
  void reJoiningTheMainThatWelcomesThisBoxsKeyAsksItsOperatorForNothing() throws Exception {
    var hostConfig = writeHostConfig();
    join(hostConfig);

    var plan = join(hostConfig, "mady", mainServing(MADY));

    assertTrue(plan.mainHasKey());
    assertEquals(
        """

            ✓ This box is now a node syncing to sail@maindevbox.

            Main already has this box's key. Finish with sudo sail sync.\
        """,
        human(plan));
    assertEquals(
        "sudo sail fde add mady --role member --key \""
            + PUB
            + "\" --main-host-key "
            + MAIN.fingerprint(),
        YamlUtil.parseMap(json(plan)).get("authorize_command"));
  }

  @Test
  void aFirstJoinNeverAsksMainAboutAKeyItJustMade() throws Exception {
    assertFalse(join(writeHostConfig(), "mady", NEVER_ASKED).mainHasKey());
  }

  @Test
  void aReJoinMainDoesNotWelcomeAsThisHandleHandsItsOperatorTheLine() throws Exception {
    var hostConfig = writeHostConfig();
    join(hostConfig);

    assertFalse(join(hostConfig).mainHasKey(), "main could not be asked");
    assertFalse(
        join(hostConfig, "mady", mainServing(ADA)).mainHasKey(),
        "main holds the key under another handle");
  }

  @Test
  void aReJoinWithAKeyMadeByHandHandsItsOperatorTheLineWithoutAskingMain() throws Exception {
    var hostConfig = writeHostConfig();
    join(hostConfig);
    Files.delete(dir.resolve("sync_ed25519"));
    Files.delete(dir.resolve("sync_ed25519.pub"));

    assertFalse(join(hostConfig, "mady", NEVER_ASKED).mainHasKey());
  }

  private final class StubKeygen implements ShellExec {
    @Override
    public Result exec(List<String> command) throws IOException {
      var target = Path.of(command.get(command.indexOf("-f") + 1));
      Files.writeString(target, "PRIVATE");
      Files.writeString(Path.of(target + ".pub"), PUB + "\n");
      return new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
