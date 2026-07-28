/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuardrailWatcherTest {

  private static final Path LOG = Path.of("/tmp/watch.log");

  @Test
  void describesALaunchedUnitWithItsScope() {
    var line =
        GuardrailWatcher.describe(new WatcherSpawner.Unit("sail-watch-acme", "user", false), LOG);

    assertEquals(
        "Guardrail watcher started (unit sail-watch-acme, user scope, log: /tmp/watch.log)", line);
  }

  @Test
  void describesAnAdoptedUnitAsAlreadyActive() {
    var line =
        GuardrailWatcher.describe(new WatcherSpawner.Unit("sail-watch-acme", "system", true), LOG);

    assertEquals(
        "Guardrail watcher already active (unit sail-watch-acme, log: /tmp/watch.log)", line);
  }

  @Test
  void describesTheDegradedFallbackByPid() {
    var line = GuardrailWatcher.describe(new WatcherSpawner.Fallback(4242L), LOG);

    assertEquals(
        "Guardrail watcher started (pid 4242, detached process — no systemd available, log:"
            + " /tmp/watch.log)",
        line);
  }
}
