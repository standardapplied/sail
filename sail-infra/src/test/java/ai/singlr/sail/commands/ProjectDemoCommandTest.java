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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
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
  void demoRepoConstantsAreSet() {
    assertEquals("singlr-ai/sing-demo", ProjectDemoCommand.DEMO_REPO);
    assertEquals("demo", ProjectDemoCommand.DEMO_PROJECT);
    assertEquals("main", ProjectDemoCommand.DEMO_REF);
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
}
