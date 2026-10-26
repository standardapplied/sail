/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerConfigTest {

  private static final ShellExec.Result OK = new ShellExec.Result(0, "", "");
  private static final Pattern WRITE =
      Pattern.compile(
          "\"config\",\\s*(\"(set|unset|edit)\"|\"device\",\\s*(\"(add|remove|set|unset|override)\"|verb))"
              + "|\"snapshot\",\\s*\"restore\"");

  @TempDir Path locks;

  @Test
  void aWriteWaitsWhileAnotherHoldsTheContainer() throws Exception {
    var shell = new ScriptedShellExecutor(OK);
    var config = new ContainerConfig(shell, locks);
    var writer = new FutureTask<>(() -> config.set("acme", List.of("user.sail.inputs=")));
    var thread = new Thread(writer);

    try (var held = ContainerConfig.hold(locks, "acme")) {
      thread.start();
      SharedIncus.awaitUntil(() -> SharedIncus.parkedOrDone().test(thread));
      assertEquals(List.of(), shell.invocations(), "the write waits for the holder");
    }

    assertTrue(writer.get().ok());
    assertEquals(List.of("incus config set acme user.sail.inputs="), shell.invocations());
  }

  @Test
  void aRestoreWaitsWhileAnotherHoldsTheContainer() throws Exception {
    var shell = new ScriptedShellExecutor(OK);
    var restorer =
        new FutureTask<>(
            () -> {
              new SnapshotManager(shell, locks).restore("acme", "pre-agent");
              return null;
            });
    var thread = new Thread(restorer);

    try (var held = ContainerConfig.hold(locks, "acme")) {
      thread.start();
      SharedIncus.awaitUntil(() -> SharedIncus.parkedOrDone().test(thread));
      assertEquals(List.of(), shell.invocations(), "the restore waits for the holder");
    }

    restorer.get();
    assertEquals(List.of("incus snapshot restore acme pre-agent"), shell.invocations());
  }

  @Test
  void aDeviceWriteNamesItsContainerAfterTheVerb() throws Exception {
    var shell = new ScriptedShellExecutor(OK);

    new ContainerConfig(shell, locks).device("acme", "override", List.of("root", "size=50GB"));

    assertEquals(List.of("incus config device override acme root size=50GB"), shell.invocations());
  }

  @Test
  void aDryRunWritesUnderNoLock() throws Exception {
    new ContainerConfig(new ShellExecutor(true), locks).set("acme", List.of("limits.cpu=2"));

    assertEquals(List.of(), Files.list(locks).toList(), "nothing changes, so nothing is held");
  }

  @Test
  void anInvalidContainerIsRefusedBeforeAnyWrite() {
    var shell = new ScriptedShellExecutor(OK);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ContainerConfig(shell, locks).set("../acme", List.of("limits.cpu=2")));
    assertEquals(List.of(), shell.invocations());
  }

  @Test
  void everyWriteOfAContainersConfigGoesThroughThisDoor() throws Exception {
    var writers = new ArrayList<String>();
    for (var root :
        List.of(
            Path.of("src", "main"),
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"))) {
      try (var walk = Files.walk(root)) {
        for (var source : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
          if (WRITE.matcher(Files.readString(source)).find()) {
            writers.add(source.getFileName().toString());
          }
        }
      }
    }

    assertEquals(List.of("ContainerConfig.java"), writers);
  }
}
