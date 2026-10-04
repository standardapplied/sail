/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ShellExec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A project container, and the host's systemd beside it, as the launch, watch and stop machinery
 * sees them through {@link ShellExec}: files that are written can be read back, an agent unit that
 * was started is active until it exits or is killed, and git answers for the spec's repos. It
 * stands in for the shell only — every sail class that talks to a container runs for real against
 * it.
 */
final class FakeContainer implements ShellExec {

  private static final String SERVICE_SUFFIX = ".service";
  private static final String WATCH_UNIT_PREFIX = "sail-watch-";

  private final String project;
  private final Map<String, String> files = new ConcurrentHashMap<>();
  private final Map<String, Agent> agents = new ConcurrentHashMap<>();
  private final List<String> launched = new CopyOnWriteArrayList<>();
  private final List<List<String>> commands = new CopyOnWriteArrayList<>();
  private final Map<String, String> dirty = new ConcurrentHashMap<>();
  private final List<String> commits = new CopyOnWriteArrayList<>();
  private final Set<String> watchers = ConcurrentHashMap.newKeySet();
  private final AtomicInteger pids = new AtomicInteger(1000);
  private volatile String branch = "feat/test";
  private volatile boolean running = true;
  private volatile boolean survivesKill;
  private volatile boolean unreachable;
  private volatile boolean refusesKill;
  private volatile boolean managerDown;
  private volatile String gitFailure;
  private volatile String gitFailingVerb;
  private volatile Runnable beforeGit = () -> {};
  private volatile String hostFragment = "";
  private volatile Runnable beforeHost = () -> {};
  private final AtomicReference<Runnable> afterAliveProbe = new AtomicReference<>();

  private static final class Agent {
    private final int pid;
    private volatile boolean alive = true;
    private volatile int exitCode;

    private Agent(int pid) {
      this.pid = pid;
    }
  }

  FakeContainer(String project) {
    this.project = project;
  }

  /** The agent of {@code runId} started: its unit is active and its pid file names it. */
  void started(String runId) {
    var agent = new Agent(pids.incrementAndGet());
    agents.put(runId, agent);
    launched.add(runId);
    files.put(AgentUnit.forRun(runId).pidPath(), String.valueOf(agent.pid));
  }

  /** The agent of {@code runId} exited on its own, having written {@code log}. */
  void exited(String runId, String log, int exitCode) {
    files.put(AgentUnit.forRun(runId).logPath(), log);
    var agent = agents.get(runId);
    agent.exitCode = exitCode;
    agent.alive = false;
  }

  /** The agent of {@code runId} wrote {@code log} and is still running. */
  void wrote(String runId, String log) {
    files.put(AgentUnit.forRun(runId).logPath(), log);
  }

  /** Whether a watcher process for {@code runId} is running on the host. */
  boolean watched(String runId) {
    return watchers.contains(runId);
  }

  /** The watcher of {@code runId} is gone: its watch ended, or it died. */
  void watcherGone(String runId) {
    watchers.remove(runId);
  }

  boolean alive(String runId) {
    var agent = agents.get(runId);
    return agent != null && agent.alive;
  }

  /** Every run launched here, oldest first. */
  List<String> launched() {
    return List.copyOf(launched);
  }

  /** The runs whose agent is still running, oldest first. */
  List<String> live() {
    return launched.stream().filter(this::alive).toList();
  }

  String file(String path) {
    return files.get(path);
  }

  List<List<String>> commands() {
    return List.copyOf(commands);
  }

  /** The commands, each joined on spaces, that contain {@code fragment}. */
  List<String> commandsContaining(String fragment) {
    return commands.stream()
        .map(command -> String.join(" ", command))
        .filter(joined -> joined.contains(fragment))
        .toList();
  }

  /** {@code repo} holds uncommitted work, as {@code git status --porcelain} reports it. */
  void dirty(String repo, String porcelain) {
    dirty.put(repo, porcelain);
  }

  void checkedOut(String branch) {
    this.branch = branch;
  }

  /** Every git command fails with {@code message}. */
  void gitFails(String message) {
    gitFails(message, null);
  }

  /** The git subcommand {@code verb} fails with {@code message}; every one when it is null. */
  void gitFails(String message, String verb) {
    this.gitFailure = message;
    this.gitFailingVerb = verb;
  }

  /** Runs {@code hook} before every git command, as something that happens while a rescue runs. */
  void beforeGit(Runnable hook) {
    this.beforeGit = hook;
  }

  /** The container is stopped: incus lists it so, and nothing inside it answers. */
  void stopped() {
    this.running = false;
  }

  /** Whether commands into the container fail while incus still lists it running. */
  void unreachable(boolean unreachable) {
    this.unreachable = unreachable;
  }

  /** Whether a kill leaves the agent running, as a unit that ignores its signals would. */
  void survivesKill(boolean survives) {
    this.survivesKill = survives;
  }

  /**
   * Runs {@code hook} once, right after the next probe of whether an agent's process is alive has
   * been answered: what happens between a status read and whatever acts on it.
   */
  void afterAliveProbe(Runnable hook) {
    afterAliveProbe.set(hook);
  }

  /** Whether the dev user's systemd manager is gone: every question about a unit fails. */
  void managerDown(boolean down) {
    this.managerDown = down;
  }

  /** Whether the container refuses to kill an agent's unit: the signal fails, and says so. */
  void refusesKill(boolean refuses) {
    this.refusesKill = refuses;
  }

  /** Runs {@code hook} before every host command that names {@code fragment}. */
  void beforeHost(String fragment, Runnable hook) {
    this.hostFragment = fragment;
    this.beforeHost = hook;
  }

  List<String> commits() {
    return List.copyOf(commits);
  }

  @Override
  public Result exec(List<String> command) {
    commands.add(List.copyOf(command));
    var boundary = command.indexOf("--");
    if (command.size() > 2 && "incus".equals(command.get(0)) && "list".equals(command.get(1))) {
      return ok(
          "[{\"name\": \"%s\", \"status\": \"%s\", \"state\": {}}]"
              .formatted(project, running ? "Running" : "Stopped"));
    }
    if (!"incus".equals(command.get(0)) || !"exec".equals(command.get(1)) || boundary < 0) {
      return host(command);
    }
    if (!running || unreachable) {
      return fail("Error: Instance is not running");
    }
    var inner = command.subList(boundary + 1, command.size());
    return switch (inner.get(0)) {
      case "cat" -> cat(inner.get(1));
      case "kill" -> kill(inner);
      case "bash" -> bash(inner);
      case "systemctl" -> managerDown ? fail("Failed to connect to bus") : systemctl(inner);
      case "git" -> git(inner);
      case "rm" -> {
        files.remove(inner.get(inner.size() - 1));
        yield ok("");
      }
      default -> ok("");
    };
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout) {
    return exec(command);
  }

  @Override
  public boolean isDryRun() {
    return false;
  }

  /**
   * Host-side commands: a watcher launched as a unit runs until its watch ends, and is found by its
   * process and by its unit while it does.
   */
  private Result host(List<String> command) {
    if (!hostFragment.isEmpty() && String.join(" ", command).contains(hostFragment)) {
      beforeHost.run();
    }
    return switch (command.get(0)) {
      case "pgrep" ->
          command.getLast().startsWith("agent watch ")
                  && watched(command.getLast().substring(command.getLast().indexOf("--run ") + 6))
              ? ok("")
              : fail("");
      case "systemctl" ->
          !command.contains("is-active")
                  || watched(command.getLast().replace(WATCH_UNIT_PREFIX, ""))
              ? ok("")
              : fail("");
      case "systemd-run" -> {
        watchers.add(command.get(command.indexOf("--run") + 1));
        yield ok("");
      }
      default -> ok("");
    };
  }

  private Result cat(String path) {
    if (path.equals(ContainerSailSetup.STAMP_PATH)) {
      return ok(ContainerSailSetup.fingerprint());
    }
    if (path.startsWith("/proc/")) {
      return ok(path.split("/")[2] + " (bash) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 4242 0 0");
    }
    var content = files.get(path);
    return content == null ? fail("cat: " + path + ": No such file or directory") : ok(content);
  }

  private Result kill(List<String> inner) {
    var pid = Integer.parseInt(inner.get(inner.size() - 1));
    var agent = agents.values().stream().filter(a -> a.pid == pid).findFirst().orElse(null);
    if (inner.contains("-0")) {
      var answer = agent != null && agent.alive ? ok("") : fail("no such process");
      Optional.ofNullable(afterAliveProbe.getAndSet(null)).ifPresent(Runnable::run);
      return answer;
    }
    end(agent);
    return ok("");
  }

  private Result bash(List<String> inner) {
    var script = inner.stream().filter(arg -> arg.contains("printf '%s' \"$1\" >")).findFirst();
    if (script.isPresent()) {
      files.put(inner.get(inner.size() - 1), inner.get(inner.size() - 2));
    }
    return ok("");
  }

  private Result systemctl(List<String> inner) {
    var service = inner.stream().filter(arg -> arg.endsWith(SERVICE_SUFFIX)).findFirst().orElse("");
    var agent =
        agents.get(service.replace(AgentUnit.RUN_UNIT_PREFIX, "").replace(SERVICE_SUFFIX, ""));
    var alive = agent != null && agent.alive;
    if (inner.contains("is-active")) {
      return alive ? ok("") : fail("");
    }
    if (inner.contains("kill")) {
      end(agent);
      return refusesKill && inner.contains("--signal=SIGKILL")
          ? fail("Failed to kill unit: permission denied")
          : ok("");
    }
    if (inner.contains("--property=MainPID")) {
      return ok(alive ? String.valueOf(agent.pid) : "0");
    }
    if (inner.contains("show")) {
      return ok(
          "ActiveState=%s\nExecMainStatus=%d\nEnvironment=\n"
              .formatted(alive ? "active" : "inactive", agent == null ? 0 : agent.exitCode));
    }
    return ok("");
  }

  private void end(Agent agent) {
    if (agent != null && !survivesKill && !refusesKill) {
      agent.alive = false;
    }
  }

  private Result git(List<String> inner) {
    beforeGit.run();
    if (gitFailure != null && (gitFailingVerb == null || gitFailingVerb.equals(inner.get(3)))) {
      return fail(gitFailure);
    }
    var repo = inner.get(2).substring(inner.get(2).lastIndexOf('/') + 1);
    return switch (inner.get(3)) {
      case "rev-parse" -> ok(branch + "\n");
      case "status" -> ok(dirty.getOrDefault(repo, ""));
      case "commit" -> {
        commits.add(repo + ": " + inner.get(inner.size() - 1));
        dirty.remove(repo);
        yield ok("");
      }
      default -> ok("");
    };
  }

  private static Result ok(String stdout) {
    return new Result(0, stdout, "");
  }

  private static Result fail(String stderr) {
    return new Result(1, "", stderr);
  }
}
