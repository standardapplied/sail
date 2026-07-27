/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.SystemdServiceInstaller.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spawns the guardrail watcher ({@code sail agent watch}) detached from the caller, as a systemd
 * transient unit — the same mechanism that keeps the agent itself alive, so the watcher is immune
 * to Ctrl-C on the dispatch stream, SSH hangup, and daemon restarts alike. Scope ladder: the
 * caller's user manager first, the system scope second, and — only when a {@link ProcessSpawner}
 * fallback is supplied — a plain detached process last, loudly marked degraded because no unit name
 * can address it.
 *
 * <p>Every watcher is run-addressed: unit {@code sail-watch-<runId>}, watching exactly the agent
 * unit recorded on the run, logging to the run's own host-side file {@code
 * <project-dir>/runs/<runId>/watch.log} so concurrent watchers never interleave one file. Per-run
 * unit names cannot collide, so a fresh launch never needs to stop a previous watcher — and must
 * not: a watcher still active from another run owns that run's agent and is left to drain it.
 *
 * <p>Two spawn intents with different collision semantics. {@link #spawnForRun} serves launches and
 * always spawns fresh. {@link #spawnUnitForRun} serves the re-armer: it only runs against a run
 * already verified uncovered, so an active unit of the same name means a concurrent pass won the
 * race and is adopted, never doubled.
 *
 * <p>{@code Type=exec} makes exec-phase failures (unopenable log path, missing binary) fail the
 * rung visibly instead of reporting a unit that died at birth, and the {@code SAIL_*} environment a
 * shell-configured deployment relies on is forwarded explicitly — a transient unit starts from
 * systemd's clean environment, not the dispatcher's. The unit needs no teardown of its own: the
 * watcher exits after observing the agent unit stop, and {@code --collect} garbage-collects the
 * unit whatever its exit status.
 */
public final class WatcherSpawner {

  static final String UNIT_PREFIX = "sail-watch-";

  private static final List<String> FORWARDED_ENV =
      List.of("SAIL_TOKEN", "SAIL_TOKEN_FILE", "SAIL_SERVER", "SAIL_DATA_DIR");

  /** How a watcher ended up running. */
  public sealed interface Spawned permits Unit, Fallback {}

  /**
   * A watcher running as a systemd transient unit; {@code adopted} means an already-active unit was
   * found covering the run rather than a new one launched.
   */
  public record Unit(String name, String scope, boolean adopted) implements Spawned {}

  /** A watcher running as a plain detached process — the degraded, unaddressable path. */
  public record Fallback(long pid) implements Spawned {}

  /** Spawns the fallback watcher process and returns its pid. A seam for tests. */
  @FunctionalInterface
  public interface ProcessSpawner {
    long spawn(List<String> command, Path logPath) throws IOException;
  }

  private final ShellExec shell;
  private final ProcessSpawner fallback;

  /** With a {@code null} fallback the spawner is unit-only: no systemd, no watcher. */
  public WatcherSpawner(ShellExec shell, ProcessSpawner fallback) {
    this.shell = shell;
    this.fallback = fallback;
  }

  /** The run-scoped watcher unit name: {@code sail-watch-<runId>}. */
  public static String unitNameForRun(String runId) {
    return UNIT_PREFIX + Ids.requireUuid(runId);
  }

  /** The run's host-side watch log: {@code <project-dir>/runs/<runId>/watch.log}. */
  public static Path watchLogForRun(String project, String runId) {
    return SailPaths.projectDir(project)
        .resolve("runs")
        .resolve(Ids.requireUuid(runId))
        .resolve("watch.log");
  }

  /**
   * The run-addressed watcher argv: the watcher supervises exactly one run, probing the agent unit
   * the run was launched with (recorded on the run row, never re-derived) and filtering heartbeats
   * by the run id.
   */
  public static List<String> watchCommandForRun(
      String project, Path sailYaml, String runId, String agentUnit) {
    return List.of(
        SailPaths.binaryPath().toString(),
        "agent",
        "watch",
        project,
        "--run",
        Ids.requireUuid(runId),
        "--unit",
        agentUnit,
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
   * Spawns a fresh run-addressed watcher for a launched run. Per-run unit names cannot collide, so
   * nothing is stopped first — a still-active watcher from another run (or a pre-upgrade
   * project-scoped one) owns its own agent and is left alone. Falls back to a detached process when
   * no systemd scope accepts and a fallback was supplied; throws when every rung failed or the
   * thread was interrupted mid-ladder, which the caller treats as a launch failure.
   */
  public Spawned spawnForRun(String project, Path sailYaml, String runId, String agentUnit)
      throws IOException {
    return spawnFresh(
        unitNameForRun(runId),
        watchCommandForRun(project, sailYaml, runId, agentUnit),
        watchLogForRun(project, runId));
  }

  private Spawned spawnFresh(String unit, List<String> argv, Path watchLog) throws IOException {
    ensureLogDirectory(watchLog);
    var launched = launch(unit, argv, watchLog);
    if (launched.isPresent()) {
      return launched.get();
    }
    requireNotInterrupted(unit);
    if (fallback == null) {
      throw new IOException(
          "No systemd scope accepted unit " + unit + " and no fallback is allowed.");
    }
    var command = new ArrayList<String>();
    command.add("nohup");
    command.addAll(argv);
    var pid = fallback.spawn(command, watchLog);
    System.err.println(
        "  [watch] degraded: no systemd scope accepted unit "
            + unit
            + "; watcher runs as plain process "
            + pid
            + " (dies with its session, cannot be re-armed)");
    return new Fallback(pid);
  }

  /**
   * Unit-or-nothing spawn for the re-armer, which has already verified the run is uncovered: an
   * active unit of the same name means a concurrent pass won the race and is adopted. Never falls
   * back to a plain process. Empty means no systemd scope is available.
   */
  public Optional<Unit> spawnUnitForRun(
      String project, Path sailYaml, String runId, String agentUnit) throws IOException {
    return spawnUnit(
        unitNameForRun(runId),
        watchCommandForRun(project, sailYaml, runId, agentUnit),
        watchLogForRun(project, runId));
  }

  Optional<Unit> spawnUnit(String unit, List<String> argv, Path watchLog) throws IOException {
    var adopted = activeScope(unit);
    if (adopted.isPresent()) {
      return Optional.of(new Unit(unit, scopeName(adopted.get()), true));
    }
    ensureLogDirectory(watchLog);
    var launched = launch(unit, argv, watchLog);
    if (launched.isPresent()) {
      return launched;
    }
    return activeScope(unit).map(mode -> new Unit(unit, scopeName(mode), true));
  }

  /**
   * Whether any {@code sail agent watch} process for the given run is running on this host —
   * unit-spawned in any user's manager, fallback-spawned, or run by hand. Process visibility is
   * global where unit visibility is scoped to one systemd view, so this is the coverage probe the
   * re-armer trusts: a watcher it cannot address is still a watcher it must not double.
   */
  public boolean watcherProcessRunningForRun(String runId) {
    return execOk(List.of("pgrep", "-f", "--", "--run " + Ids.requireUuid(runId)));
  }

  private Optional<Unit> launch(String unit, List<String> argv, Path watchLog) {
    for (var mode : Mode.values()) {
      if (execOk(systemdRun(unit, argv, watchLog, mode))) {
        return Optional.of(new Unit(unit, scopeName(mode), false));
      }
    }
    return Optional.empty();
  }

  private Optional<Mode> activeScope(String unit) {
    for (var mode : Mode.values()) {
      if (execOk(SystemdServiceInstaller.systemctl(mode, "--quiet", "is-active", unit))) {
        return Optional.of(mode);
      }
    }
    return Optional.empty();
  }

  private boolean execOk(List<String> command) {
    try {
      return shell.exec(command).ok();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  private static void requireNotInterrupted(String unit) throws IOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new IOException(
          "Interrupted while spawning watcher unit " + unit + "; not falling back.");
    }
  }

  private static String scopeName(Mode mode) {
    return mode == Mode.USER ? "user" : "system";
  }

  private List<String> systemdRun(String unit, List<String> argv, Path watchLog, Mode mode) {
    var command = new ArrayList<String>();
    command.add("systemd-run");
    if (mode == Mode.USER) {
      command.add("--user");
    }
    command.add("--collect");
    command.add("--quiet");
    command.add("--unit");
    command.add(unit);
    command.add("--property");
    command.add("Type=exec");
    command.add("--property");
    command.add("StandardOutput=append:" + watchLog.toAbsolutePath());
    command.add("--property");
    command.add("StandardError=append:" + watchLog.toAbsolutePath());
    for (var name : FORWARDED_ENV) {
      var value = System.getenv(name);
      if (Strings.isNotBlank(value)) {
        command.add("--setenv");
        command.add(name + "=" + value);
      }
    }
    command.addAll(argv);
    return command;
  }

  private static void ensureLogDirectory(Path watchLog) throws IOException {
    Files.createDirectories(watchLog.toAbsolutePath().getParent());
  }
}
