/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

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
 * transient unit named {@code sail-watch-<project>} — the same mechanism that keeps the agent
 * itself alive, so the watcher is immune to Ctrl-C on the dispatch stream, SSH hangup, and daemon
 * restarts alike. Scope ladder: the caller's user manager first, the system scope second, and —
 * only when a {@link ProcessSpawner} fallback is supplied — a plain detached process last, loudly
 * marked degraded because no unit name can address it.
 *
 * <p>Two spawn intents with different collision semantics. {@link #spawn} serves dispatch: a fresh
 * session must get a fresh watcher whose deadline anchors to it, so any unit still active from the
 * previous session is stopped and replaced — an adopted stale watcher would enforce the old
 * session's nearly-spent deadline against a brand-new agent. {@link #spawnUnit} serves the
 * re-armer: it only runs against a session already verified uncovered, so an active unit means a
 * concurrent pass won the race and is adopted, never doubled.
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
   * Spawns a fresh watcher for a fresh session: any unit still active from a previous session is
   * stopped first — never adopted, because its wall-clock deadline anchors to the old session and
   * would be enforced against the new agent. Falls back to a detached process when no systemd scope
   * accepts and a fallback was supplied; throws when every rung failed or the thread was
   * interrupted mid-ladder, which the dispatch flow treats as a launch failure.
   */
  public Spawned spawn(String project, Path sailYaml, Path watchLog) throws IOException {
    stopExisting(project);
    ensureLogDirectory(watchLog);
    var unit = launch(project, watchCommand(project, sailYaml), watchLog);
    if (unit.isPresent()) {
      return unit.get();
    }
    requireNotInterrupted(project);
    if (fallback == null) {
      throw new IOException(
          "No systemd scope accepted unit " + unitName(project) + " and no fallback is allowed.");
    }
    var command = new ArrayList<String>();
    command.add("nohup");
    command.addAll(watchCommand(project, sailYaml));
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
   * Unit-or-nothing spawn for the re-armer, which has already verified the session is uncovered: an
   * active unit means a concurrent pass won the race and is adopted. Never falls back to a plain
   * process. Empty means no systemd scope is available.
   */
  public Optional<Unit> spawnUnit(String project, Path sailYaml, Path watchLog) throws IOException {
    return spawnUnit(project, watchCommand(project, sailYaml), watchLog);
  }

  Optional<Unit> spawnUnit(String project, List<String> argv, Path watchLog) throws IOException {
    var adopted = activeScope(project);
    if (adopted.isPresent()) {
      return Optional.of(new Unit(unitName(project), scopeName(adopted.get()), true));
    }
    ensureLogDirectory(watchLog);
    var launched = launch(project, argv, watchLog);
    if (launched.isPresent()) {
      return launched;
    }
    return activeScope(project).map(mode -> new Unit(unitName(project), scopeName(mode), true));
  }

  /** Whether a watcher unit for the project is active in either scope of this systemd view. */
  public boolean unitActive(String project) {
    return activeScope(project).isPresent();
  }

  /**
   * Whether any {@code sail agent watch} process for the project is running on this host —
   * unit-spawned in any user's manager, fallback-spawned, or run by hand. Process visibility is
   * global where unit visibility is scoped to one systemd view, so this is the coverage probe the
   * re-armer trusts: a watcher it cannot address is still a watcher it must not double.
   */
  public boolean watcherProcessRunning(String project) {
    return execOk(List.of("pgrep", "-f", "--", "agent watch " + project + " -f "));
  }

  private Optional<Unit> launch(String project, List<String> argv, Path watchLog) {
    for (var mode : Mode.values()) {
      if (execOk(systemdRun(project, argv, watchLog, mode))) {
        return Optional.of(new Unit(unitName(project), scopeName(mode), false));
      }
    }
    return Optional.empty();
  }

  private void stopExisting(String project) {
    for (var mode : Mode.values()) {
      if (execOk(
          SystemdServiceInstaller.systemctl(mode, "--quiet", "is-active", unitName(project)))) {
        execOk(SystemdServiceInstaller.systemctl(mode, "stop", unitName(project)));
        System.err.println(
            "  [watch] stopped stale watcher unit "
                + unitName(project)
                + " ("
                + scopeName(mode)
                + " scope) from a previous session before arming the new one");
      }
    }
  }

  private Optional<Mode> activeScope(String project) {
    for (var mode : Mode.values()) {
      if (execOk(
          SystemdServiceInstaller.systemctl(mode, "--quiet", "is-active", unitName(project)))) {
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

  private static void requireNotInterrupted(String project) throws IOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new IOException(
          "Interrupted while spawning watcher for " + project + "; not falling back.");
    }
  }

  private static String scopeName(Mode mode) {
    return mode == Mode.USER ? "user" : "system";
  }

  private List<String> systemdRun(String project, List<String> argv, Path watchLog, Mode mode) {
    var command = new ArrayList<String>();
    command.add("systemd-run");
    if (mode == Mode.USER) {
      command.add("--user");
    }
    command.add("--collect");
    command.add("--quiet");
    command.add("--unit");
    command.add(unitName(project));
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
