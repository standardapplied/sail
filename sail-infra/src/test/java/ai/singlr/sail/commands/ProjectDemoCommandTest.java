/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.FileMutexTest;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.ssh.TestSshKeys;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  void theDefinitionIsResolvedInTheTreeWithTheCallersOwnKeyOrNone() {
    var redacted =
        "name: demo\ngit:\n  name: '${GIT_NAME}'\n  email: '${GIT_EMAIL}'\nssh:\n"
            + "  user: dev\n  authorized_keys:\n    - '${SSH_PUBLIC_KEY}'\n";

    var withKey =
        ProjectDemoCommand.resolvedDefinition(
            redacted, "Dan O'Neil", "dan@x.io", "ssh-ed25519 AAAA dan@box", OperatorCommand.PLAIN);
    var withoutKey =
        ProjectDemoCommand.resolvedDefinition(
            redacted, "Dan O'Neil", "dan@x.io", null, OperatorCommand.PLAIN);

    assertEquals("Dan O'Neil", withKey.git().name());
    assertEquals(List.of("ssh-ed25519 AAAA dan@box"), withKey.ssh().authorizedKeys());
    assertEquals(
        List.of(),
        withoutKey.ssh().authorizedKeys(),
        "with no key of the caller's own the entry goes; step 6 trusts the registered key");
  }

  @Test
  void demoProjectNameIsDemo() {
    assertEquals("demo", ProjectDemoCommand.DEMO_PROJECT);
  }

  @Test
  void theCallersOwnKeyIsTheFirstUsualFileThatHoldsOne(@TempDir Path dir) throws Exception {
    var key = TestSshKeys.ed25519("own", "me@box");
    Files.writeString(dir.resolve("id_ed25519.pub"), "\n");
    Files.writeString(dir.resolve("id_rsa.pub"), "  " + key + "\n");
    Files.writeString(
        dir.resolve("id_ecdsa.pub"), TestSshKeys.ed25519("later", "later@box") + "\n");

    assertEquals(key, ProjectDemoCommand.detectSshPublicKey(dir));
  }

  @Test
  void aKeyFileThatHoldsNoKeyCountsAsNone(@TempDir Path dir) throws Exception {
    assertNull(ProjectDemoCommand.detectSshPublicKey(dir));
    Files.writeString(
        dir.resolve("id_ed25519.pub"),
        TestSshKeys.ed25519("a", "one") + "\n" + TestSshKeys.ed25519("b", "two") + "\n");
    assertNull(ProjectDemoCommand.detectSshPublicKey(dir));
    assertNull(ProjectDemoCommand.detectSshPublicKey(dir.resolve("absent")));
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
              ProjectDemoCommand.provisionTrusting(
                  database, OperatorCommand.PLAIN, key -> provisioned.countDown());
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
