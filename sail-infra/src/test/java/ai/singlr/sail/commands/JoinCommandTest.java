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
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITESTKEYBLOB sail-sync@node";

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
  void defaultHandleFallsBackWhenUsernameIsAbsent() {
    var original = System.getProperty("user.name");
    try {
      System.setProperty("user.name", "");
      assertEquals("your-handle", JoinCommand.defaultHandle());
      System.setProperty("user.name", "mady");
      assertEquals("mady", JoinCommand.defaultHandle());
    } finally {
      System.setProperty("user.name", original);
    }
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
