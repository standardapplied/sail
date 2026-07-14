/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link WatcherSpawner} against the machine's <strong>real</strong> systemd (or its
 * absence): the scope ladder must land somewhere real, an active unit must be adopted rather than
 * doubled, and teardown must leave nothing behind. Both branches assert real behavior — a systemd
 * host proves the unit path (detachment from this JVM included, since each spawning shell has
 * already exited by the time the unit is probed), and a busless environment proves the degraded
 * fallback still yields a live, detached process.
 */
class WatcherSpawnerIT {

  private static final String PROJECT = "wsit-" + ProcessHandle.current().pid();

  @TempDir Path tempDir;

  private final ShellExecutor shell = new ShellExecutor(false);
  private final WatcherSpawner spawner = new WatcherSpawner(shell, WatcherSpawner::spawnProcess);

  @AfterEach
  void tearDown() {
    stopQuietly(List.of("systemctl", "--user", "stop", WatcherSpawner.unitName(PROJECT)));
    stopQuietly(List.of("systemctl", "stop", WatcherSpawner.unitName(PROJECT)));
  }

  private void stopQuietly(List<String> command) {
    try {
      shell.exec(command);
    } catch (Exception ignored) {
    }
  }

  private boolean unitActive(String unit) {
    return execOk(List.of("systemctl", "--user", "--quiet", "is-active", unit))
        || execOk(List.of("systemctl", "--quiet", "is-active", unit));
  }

  private boolean execOk(List<String> command) {
    try {
      return shell.exec(command).ok();
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void theLadderLandsOnARealScopeAndNeverStacksASecondWatcher() throws Exception {
    var log = tempDir.resolve("watch.log");
    var argv = List.of("sleep", "30");

    var unitName = WatcherSpawner.unitName(PROJECT);
    var spawned = spawner.spawnUnit(unitName, argv, log);

    if (spawned.isPresent()) {
      assertFalse(spawned.get().adopted(), "first spawn must launch, not adopt");
      assertTrue(unitActive(unitName), "unit must be active right after spawn");

      var second = spawner.spawnUnit(unitName, argv, log).orElseThrow();
      assertTrue(second.adopted(), "second spawn must adopt the active unit");
      assertEquals(WatcherSpawner.unitName(PROJECT), second.name());
    } else {
      var pid = WatcherSpawner.spawnProcess(List.of("sleep", "30"), log);
      var handle = ProcessHandle.of(pid).orElseThrow();
      assertTrue(handle.isAlive(), "degraded fallback must yield a live detached process");
      handle.destroy();
    }
  }

  @Test
  void aStoppedUnitIsCollectedSoTheNameIsReusable() throws Exception {
    var log = tempDir.resolve("watch.log");
    var unitName = WatcherSpawner.unitName(PROJECT);
    var first = spawner.spawnUnit(unitName, List.of("sleep", "30"), log);
    if (first.isEmpty()) {
      assertFalse(unitActive(unitName));
      return;
    }
    tearDown();

    assertFalse(unitActive(unitName), "stopped unit must not read as active");
    var respawned = spawner.spawnUnit(unitName, List.of("sleep", "30"), log).orElseThrow();
    assertFalse(respawned.adopted(), "collected name must be reusable for a fresh launch");
  }
}
