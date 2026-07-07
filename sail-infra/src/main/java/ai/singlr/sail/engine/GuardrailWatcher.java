/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import java.nio.file.Path;
import picocli.CommandLine.Help.Ansi;

/**
 * Starts the background guardrail watcher ({@code sail agent watch}) for a project. Supervision is
 * on by default: the watcher is spawned for every dispatched agent and applies {@link
 * ai.singlr.sail.config.Guardrails#defaults()} when sail.yaml declares no guardrails of its own.
 * Shared by the CLI dispatch/run/launch commands, which all spawn it the same way through {@link
 * WatcherSpawner} — detached from this process, so it survives Ctrl-C on the dispatch stream and
 * the SSH session ending. Failures are reported but never fatal to the launch.
 */
public final class GuardrailWatcher {

  private GuardrailWatcher() {}

  /**
   * Spawns a detached watcher for the project's agent (no-op only when there is no agent block).
   */
  public static void launch(String project, String file, SailYaml config) {
    if (config == null || config.agent() == null) {
      return;
    }
    try {
      var sailYamlPath = SailPaths.resolveSailYaml(project, file);
      var watchLog = SailPaths.projectDir(project).resolve("watch.log");
      var spawner = new WatcherSpawner(new ShellExecutor(false), WatcherSpawner::spawnProcess);
      var spawned = spawner.spawn(project, sailYamlPath, watchLog);
      System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + describe(spawned, watchLog)));
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Failed to start guardrail watcher: "
                  + e.getMessage()
                  + ". Run manually: sail agent watch "
                  + project,
              Ansi.AUTO));
    }
  }

  static String describe(WatcherSpawner.Spawned spawned, Path watchLog) {
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
