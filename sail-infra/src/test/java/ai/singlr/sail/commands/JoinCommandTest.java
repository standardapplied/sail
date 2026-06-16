/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinCommandTest {

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

  @TempDir Path dir;

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

    var plan = JoinCommand.plan(hostConfig, identity(), "sail@maindevbox", "mady");

    assertEquals("sail@maindevbox", plan.target());
    assertEquals("mady", plan.handle());
    assertEquals(PUB, plan.publicKey());

    var written = HostYaml.fromMap(YamlUtil.parseFile(hostConfig));
    assertEquals(SyncConfig.ROLE_NODE, written.sync().role());
    assertEquals("sail@maindevbox", written.sync().main());
    assertEquals("10.0.0.1", written.serverIp(), "unrelated host config is preserved");
  }

  @Test
  void planRequiresAnInitializedHost() {
    var missing = dir.resolve("absent.yaml");

    var error =
        assertThrows(
            IllegalStateException.class,
            () -> JoinCommand.plan(missing, identity(), "sail@main", "mady"));
    assertTrue(error.getMessage().contains("host init"));
  }

  @Test
  void planRejectsAMalformedTarget() throws Exception {
    var hostConfig = writeHostConfig();

    assertThrows(
        IllegalArgumentException.class,
        () -> JoinCommand.plan(hostConfig, identity(), "not a target!", "mady"));
  }

  @Test
  void authorizeLineIsACopyPasteFdeAddCommand() {
    assertEquals(
        "sail fde add mady --role member --key \"" + PUB + "\"",
        JoinCommand.authorizeLine("mady", PUB));
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
  void hostForProbeStripsTheUserAndPort() {
    assertEquals("host", JoinCommand.hostForProbe("sail@host"));
    assertEquals("10.0.0.1", JoinCommand.hostForProbe("sail@10.0.0.1:2222"));
    assertEquals("maindevbox", JoinCommand.hostForProbe("maindevbox"));
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
    var out = JoinCommand.render(new JoinCommand.Plan("sail@host", "mady", PUB), true);

    assertTrue(out.contains("\"target\""));
    assertTrue(out.contains("\"authorize_command\""));
    assertTrue(out.contains("sail fde add mady --role member --key"));
  }

  @Test
  void renderHumanShowsTheTargetAndAuthorizeLine() {
    var out = JoinCommand.render(new JoinCommand.Plan("sail@host", "mady", PUB), false);

    assertTrue(out.contains("sail@host"));
    assertTrue(out.contains("sail fde add mady --role member"));
    assertTrue(out.contains("sail sync"));
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
