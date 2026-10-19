/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.FileMutexTest;
import ai.singlr.sail.engine.PairingTrust;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ProjectDemoCommandTest {

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new ProjectDemoCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--git-token"));
    assertTrue(usage.contains("demo project") || usage.contains("zero configuration"));
  }

  @Test
  void requiresRootWithoutDryRun() {
    var cmd = new CommandLine(new ProjectDemoCommand());
    cmd.setErr(new PrintWriter(new StringWriter()));
    cmd.setOut(new PrintWriter(new StringWriter()));
    var exitCode = cmd.execute();

    assertNotEquals(0, exitCode);
  }

  @Test
  void demoProjectNameIsDemo() {
    assertEquals("demo", ProjectDemoCommand.DEMO_PROJECT);
  }

  @Test
  void detectSshPublicKeyReturnsNullWhenNoKeys(@TempDir Path tempDir) {
    var result = ProjectDemoCommand.detectSshPublicKey();

    assertDoesNotThrow(() -> ProjectDemoCommand.detectSshPublicKey());
  }

  @Test
  void detectSshPublicKeyFindsEd25519(@TempDir Path tempDir) throws Exception {
    var sshDir = Path.of(System.getProperty("user.home"), ".ssh");
    var keyFile = sshDir.resolve("id_ed25519.pub");
    if (Files.exists(keyFile)) {
      var key = ProjectDemoCommand.detectSshPublicKey();
      assertNotNull(key);
      assertTrue(key.startsWith("ssh-"));
    }
  }

  @Test
  @Timeout(60)
  void theDemoReadsTheWorkstationKeyOnlyWhileNoPairingIsMadeOrRevoked(@TempDir Path dir)
      throws Exception {
    var database = dir.resolve("sail.db");
    var provisioned = new CountDownLatch(1);
    var demo =
        new FutureTask<Void>(
            () -> {
              ProjectDemoCommand.provisionTrusting(database, key -> provisioned.countDown());
              return null;
            });

    try (var pairing = PairingTrust.hold(database)) {
      FileMutexTest.awaitParked(Thread.ofPlatform().start(demo));
      assertEquals(1, provisioned.getCount(), "the demo waits before it reads the key");
    }

    demo.get();
    assertEquals(0, provisioned.getCount());
  }
}
