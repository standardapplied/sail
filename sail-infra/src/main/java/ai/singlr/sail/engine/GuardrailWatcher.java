/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.nio.file.Path;

/**
 * Renders how a guardrail watcher ({@code sail agent watch}) ended up running, for the launch lanes
 * that spawn one through {@link WatcherSpawner} and narrate the result.
 */
public final class GuardrailWatcher {

  private GuardrailWatcher() {}

  public static String describe(WatcherSpawner.Spawned spawned, Path watchLog) {
    return switch (spawned) {
      case WatcherSpawner.Unit unit when unit.adopted() ->
          "Guardrail watcher already active (unit " + unit.name() + ", log: " + watchLog + ")";
      case WatcherSpawner.Unit unit ->
          "Guardrail watcher started (unit "
              + unit.name()
              + ", "
              + unit.scope()
              + " scope, log: "
              + watchLog
              + ")";
      case WatcherSpawner.Fallback fallback ->
          "Guardrail watcher started (pid "
              + fallback.pid()
              + ", detached process — no systemd available, log: "
              + watchLog
              + ")";
    };
  }
}
