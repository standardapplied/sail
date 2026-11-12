/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.FileMutexTest;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import java.io.PrintWriter;
import java.io.StringWriter;
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
  void aDemoWhoseContainerExistsIsRefusedSayingWhatToDo() {
    var operator = OperatorCommand.SUDO;
    var destroy = "\n  Destroy first:  sudo sail project destroy demo";

    assertEquals(
        "Demo project is already running.\n  Get a shell:    sudo sail project shell demo"
            + destroy,
        refusal(new ContainerState.Running("10.0.0.2"), operator));
    assertEquals(
        "Demo project exists but is frozen.\n  Resume it:      sudo sail project start demo"
            + destroy,
        refusal(new ContainerState.Frozen(), operator));
    assertEquals(
        "Demo project exists but is stopped.\n  Start it:       sudo sail project start demo"
            + destroy,
        refusal(new ContainerState.Stopped(), operator));
    ProjectDemoCommand.requireAbsent(new ContainerState.NotCreated(), operator);
    ProjectDemoCommand.requireAbsent(new ContainerState.Error("unreadable"), operator);
  }

  private static String refusal(ContainerState state, OperatorCommand operator) {
    return assertThrows(
            IllegalStateException.class, () -> ProjectDemoCommand.requireAbsent(state, operator))
        .getMessage();
  }

  @Test
  void demoProjectNameIsDemo() {
    assertEquals("demo", ProjectDemoCommand.DEMO_PROJECT);
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
