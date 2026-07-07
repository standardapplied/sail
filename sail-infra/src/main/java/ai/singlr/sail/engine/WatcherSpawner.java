/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spawns the guardrail watcher ({@code sail agent watch}) detached from the caller, as a systemd
 * transient unit named {@code sail-watch-<project>} — the same mechanism that keeps the agent
 * itself alive, so the watcher is immune to Ctrl-C on the dispatch stream, SSH hangup, and daemon
 * restarts alike. The unit name is deterministic and one agent runs per project, so liveness is
 * probed ({@link #unitActive}) rather than recorded, and spawning against an already-active unit
 * adopts it instead of stacking a second watcher.
 *
 * <p>Scope ladder: a user-manager unit first, a system-scope unit second, and — only when a {@link
 * ProcessSpawner} fallback is supplied — a plain detached process last, loudly marked degraded
 * because nothing can re-arm or address it. Unit-only callers (the re-armer) pass no fallback, so a
 * doubled watcher is unrepresentable on that path. The unit needs no explicit teardown: the watcher
 * exits on its own after observing the agent unit stop, and {@code --collect} garbage-collects the
 * unit whatever its exit status.
 */
public final class WatcherSpawner {

  static final String UNIT_PREFIX = "sail-watch-";

  /** How a watcher ended up running. */
  public sealed interface Spawned permits Unit, Fallback {}

  /**
   * A watcher running as a systemd transient unit; {@code adopted} means an already-active unit was
   * found covering the project rather than a new one launched.
   */
  public record Unit(String name, String scope, boolean adopted) implements Spawned {}

  /** A watcher running as a plain detached process — the degraded, unaddressable path. */
  public record Fallback(long pid) implements Spawned {}

  /** Spawns the fallback watcher process and returns its pid. A seam for tests. */
  @FunctionalInterface
  public interface ProcessSpawner {
    long spawn(List<String> command, Path logPath) throws IOException;
  }

  private static final String USER_SCOPE = "user";
  private static final String SYSTEM_SCOPE = "system";

  private final ShellExec shell;
  private final ProcessSpawner fallback;

  /** With a {@code null} fallback the spawner is unit-only: no systemd, no watcher. */
  public WatcherSpawner(ShellExec shell, ProcessSpawner fallback) {
    this.shell = shell;
    this.fallback = fallback;
  }

  public static String unitName(String project) {
    return UNIT_PREFIX + project;
  }

  /** The watcher argv, shared by every spawn path. */
  public static List<String> watchCommand(String project, Path sailYaml) {
    return List.of(
        SailPaths.binaryPath().toString(),
        "agent",
        "watch",
        project,
        "-f",
        sailYaml.toAbsolutePath().toString());
  }

  /** Starts a detached process, mirroring the historic fallback spawn. */
  public static long spawnProcess(List<String> command, Path logPath) throws IOException {
    return new ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.to(logPath.toFile()))
        .redirectErrorStream(true)
        .start()
        .pid();
  }

  /**
   * Spawns the project's watcher: adopt an active unit, else launch one, else fall back to a
   * detached process when a fallback was supplied. Throws only when every rung failed — the
   * caller's dispatch flow treats that as a launch failure.
   */
  public Spawned spawn(String project, Path sailYaml, Path watchLog) throws IOException {
    var unit = spawnUnit(project, sailYaml, watchLog);
    if (unit.isPresent()) {
      return unit.get();
    }
    if (fallback == null) {
      throw new IOException(
          "No systemd scope accepted unit " + unitName(project) + " and no fallback is allowed.");
    }
    var command = new ArrayList<String>();
    command.add("nohup");
    command.addAll(watchCommand(project, sailYaml));
    ensureLogDirectory(watchLog);
    var pid = fallback.spawn(command, watchLog);
    System.err.println(
        "  [watch] degraded: no systemd scope accepted unit "
            + unitName(project)
            + "; watcher runs as plain process "
            + pid
            + " (dies with its session, cannot be re-armed)");
    return new Fallback(pid);
  }

  /**
   * Unit-or-nothing spawn: adopts the active unit when one covers the project, otherwise tries the
   * user then system scope. Empty means no systemd scope is available — never a plain process, so
   * callers that must not risk a doubled watcher use this path.
   */
  public Optional<Unit> spawnUnit(String project, Path sailYaml, Path watchLog) {
    return spawnUnit(project, watchCommand(project, sailYaml), watchLog);
  }

  Optional<Unit> spawnUnit(String project, List<String> argv, Path watchLog) {
    var adopted = activeScope(project);
    if (adopted.isPresent()) {
      return Optional.of(new Unit(unitName(project), adopted.get(), true));
    }
    ensureLogDirectory(watchLog);
    for (var scope : List.of(USER_SCOPE, SYSTEM_SCOPE)) {
      if (execOk(systemdRun(project, argv, watchLog, scope))) {
        return Optional.of(new Unit(unitName(project), scope, false));
      }
    }
    return activeScope(project).map(scope -> new Unit(unitName(project), scope, true));
  }

  /** Whether a watcher unit for the project is active in either systemd scope. */
  public boolean unitActive(String project) {
    return activeScope(project).isPresent();
  }

  private Optional<String> activeScope(String project) {
    for (var scope : List.of(USER_SCOPE, SYSTEM_SCOPE)) {
      if (execOk(systemctlIsActive(project, scope))) {
        return Optional.of(scope);
      }
    }
    return Optional.empty();
  }

  private boolean execOk(List<String> command) {
    try {
      return shell.exec(command).ok();
    } catch (Exception e) {
      return false;
    }
  }

  private static List<String> systemctlIsActive(String project, String scope) {
    var command = new ArrayList<String>();
    command.add("systemctl");
    if (USER_SCOPE.equals(scope)) {
      command.add("--user");
    }
    command.add("--quiet");
    command.add("is-active");
    command.add(unitName(project));
    return command;
  }

  private static List<String> systemdRun(
      String project, List<String> argv, Path watchLog, String scope) {
    var command = new ArrayList<String>();
    command.add("systemd-run");
    if (USER_SCOPE.equals(scope)) {
      command.add("--user");
    }
    command.add("--collect");
    command.add("--quiet");
    command.add("--unit");
    command.add(unitName(project));
    command.add("--property");
    command.add("StandardOutput=append:" + watchLog.toAbsolutePath());
    command.add("--property");
    command.add("StandardError=append:" + watchLog.toAbsolutePath());
    command.addAll(argv);
    return command;
  }

  private static void ensureLogDirectory(Path watchLog) {
    try {
      Files.createDirectories(watchLog.toAbsolutePath().getParent());
    } catch (IOException e) {
      System.err.println("  [watch] could not create log directory: " + e.getMessage());
    }
  }
}
