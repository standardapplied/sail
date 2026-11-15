/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SpecCatalog;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentPresence;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "status",
    description = "Check agent session status. Omit project name to show all projects.",
    mixinStandardHelpOptions = true)
public final class AgentStatusCommand implements Runnable {

  private static final List<String> DEFAULT_REPO_PATHS = List.of(ContainerExec.DEV_WORKSPACE);

  @Parameters(index = "0", description = "Project name (omit for all projects).", arity = "0..1")
  private String name;

  @Mixin private RunSelector selector = new RunSelector();

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  private final Function<OperatorCommand, HostOperations> operations;
  private final ShellExec shell;

  public AgentStatusCommand() {
    this(operator -> OperationsFactory.open(operator::warn), new ShellExecutor(false));
  }

  /**
   * The status command over {@code operations}, asking this box's containers through {@code shell}.
   */
  AgentStatusCommand(Function<OperatorCommand, HostOperations> operations, ShellExec shell) {
    this.operations = operations;
    this.shell = shell;
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    if (name == null) {
      executeAll(operator);
    } else {
      executeSingle(operator);
    }
  }

  private void executeAll(OperatorCommand operator) throws Exception {
    var running =
        new BoxInstances(shell)
            .plain().stream()
                .map(BoxInstances.Instance::info)
                .filter(c -> c.state() instanceof ContainerState.Running)
                .toList();
    var runningContainers =
        ReadFilter.readable(Actor.reader(), running, ContainerManager.ContainerInfo::name);

    if (runningContainers.isEmpty()) {
      if (json) {
        System.out.println("[]");
      } else {
        System.out.println(Ansi.AUTO.string("  @|faint No running projects found.|@"));
      }
      return;
    }

    var checker = new GuardrailChecker(shell);
    var summaries = new ArrayList<AgentSummary>();
    List<Probe> probes;
    try (var operations = this.operations.apply(operator)) {
      var definitions = operations.catalog().definitions();
      probes =
          runningContainers.stream()
              .map(
                  container -> {
                    var run =
                        operations.dispatching().activeRun(container.name(), NodeIdentity.handle());
                    return new Probe(
                        container.name(),
                        sessionOrNull(run),
                        run.map(RunStore.RunRow::place)
                            .orElse(RunPlace.of(container.name(), null))
                            .instance(),
                        repoPathsOrDefault(definitions, container.name()));
                  })
              .toList();
    }

    for (var probe : probes) {
      var projectName = probe.project();
      var info = probe.info();

      var statusLabel = "No session";
      var elapsed = "";
      var commits = 0;

      if (info != null && info.running()) {
        statusLabel = "Running";
        if (!info.startedAt().isBlank()) {
          try {
            var started = Instant.parse(info.startedAt());
            elapsed = formatElapsed(Duration.between(started, DateTimeUtils.now()));
            for (var repoPath : probe.repoPaths()) {
              try {
                commits +=
                    checker.queryGitActivity(probe.instance(), repoPath, started).commitCount();
              } catch (Exception ignored) {
              }
            }
          } catch (Exception ignored) {
          }
        }
      } else if (info != null) {
        statusLabel = "Stopped";
      }

      summaries.add(new AgentSummary(projectName, statusLabel, elapsed, commits, info));
    }

    if (json) {
      var list = new ArrayList<Map<String, Object>>();
      for (var s : summaries) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", s.name);
        map.put("status", s.status);
        if (!s.elapsed.isEmpty()) {
          map.put("elapsed", s.elapsed);
        }
        if (s.commits > 0) {
          map.put("commits", s.commits);
        }
        if (s.info != null && !s.info.task().isBlank()) {
          map.put("task", s.info.task());
        }
        list.add(map);
      }
      System.out.println(YamlUtil.dumpJson(Map.of("agents", list)));
      return;
    }

    var rows = new ArrayList<String[]>();
    for (var s : summaries) {
      var detail = "";
      if (s.info != null && !s.info.task().isBlank()) {
        var t = s.info.task();
        detail = t.length() > 40 ? t.substring(0, 40) + "..." : t;
      }
      rows.add(
          new String[] {
            s.name,
            s.status,
            s.elapsed.isEmpty() ? "-" : s.elapsed,
            s.commits > 0 ? String.valueOf(s.commits) : "-",
            detail.isEmpty() ? "-" : detail
          });
    }
    Banner.printAgentStatusTable(rows, System.out, Ansi.AUTO);
  }

  private void executeSingle(OperatorCommand operator) throws Exception {
    NameValidator.requireValidProjectName(name);
    Optional<RunStore.RunRow> run;
    ProjectDefinition config;
    try (var operations = this.operations.apply(operator)) {
      operations.catalog().readable(name);
      run =
          selector.resolve(
              operations.dispatching(), "agent status", name, NodeIdentity.handle(), any -> true);
      config = operations.catalog().definitions().read(name).orElse(null);
    }
    var place = run.map(RunStore.RunRow::place).orElse(RunPlace.of(name, null));
    ContainerStateGuard.requireRunning(new AgentPresence(shell).state(place), place.instance());
    var info = sessionOf(run);

    var commitCount = 0;
    var lastCommitMinutesAgo = -1L;
    if (info != null && info.running()) {
      try {
        var checker = new GuardrailChecker(shell);
        var repoPaths = config != null ? config.repoPaths() : DEFAULT_REPO_PATHS;
        var since =
            !info.startedAt().isBlank() ? Instant.parse(info.startedAt()) : DateTimeUtils.now();
        for (var repoPath : repoPaths) {
          var activity = checker.queryGitActivity(place.instance(), repoPath, since);
          commitCount += activity.commitCount();
          if (activity.lastCommitEpoch() > 0) {
            var minutesAgo =
                (DateTimeUtils.now().getEpochSecond() - activity.lastCommitEpoch()) / 60;
            if (lastCommitMinutesAgo < 0 || minutesAgo < lastCommitMinutesAgo) {
              lastCommitMinutesAgo = minutesAgo;
            }
          }
        }
      } catch (Exception ignored) {
      }
    }

    Map<String, Integer> taskCounts = null;
    if (config != null && config.agent() != null && info != null) {
      try (var operations = this.operations.apply(operator)) {
        taskCounts = SpecCatalog.statusCounts(operations.catalog().projectSpecs(name));
      } catch (Exception ignored) {
      }
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      if (info == null) {
        map.put("agent_running", false);
      } else {
        map.put("agent_running", info.running());
        map.put("pid", info.pid());
        map.put("task", info.task());
        map.put("started_at", info.startedAt());
        map.put("branch", info.branch());
        map.put("log_path", info.logPath());
        map.put("commits_since_launch", commitCount);
        if (lastCommitMinutesAgo >= 0) {
          map.put("last_commit_minutes_ago", lastCommitMinutesAgo);
        }
        if (taskCounts != null) {
          map.put("tasks", taskCounts);
        }
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    if (info == null) {
      System.out.println(Ansi.AUTO.string("  @|faint No agent session found for " + name + ".|@"));
      return;
    }

    Banner.printAgentStatus(
        name,
        run.map(RunSelector::of).orElse(""),
        info,
        commitCount,
        lastCommitMinutesAgo,
        taskCounts,
        operator,
        System.out,
        Ansi.AUTO);
  }

  /** What the listing reads of one running project before anything long-running starts. */
  private record Probe(
      String project, AgentSession.SessionInfo info, String instance, List<String> repoPaths) {}

  static List<String> repoPathsOrDefault(ProjectReader definitions, String projectName) {
    try {
      return definitions
          .read(projectName)
          .map(ProjectDefinition::repoPaths)
          .orElse(DEFAULT_REPO_PATHS);
    } catch (Exception ignored) {
      return DEFAULT_REPO_PATHS;
    }
  }

  /** The session of {@code run}, the run the selector names, or none when there is none. */
  private AgentSession.SessionInfo sessionOf(Optional<RunStore.RunRow> run) throws Exception {
    if (run.isEmpty()) {
      return null;
    }
    var live = run.get();
    return new AgentSession(shell)
        .queryStatus(
            live.place().instance(),
            AgentUnit.recorded(live.id(), Objects.toString(live.unit(), "")));
  }

  private AgentSession.SessionInfo sessionOrNull(Optional<RunStore.RunRow> run) {
    try {
      return sessionOf(run);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String formatElapsed(Duration duration) {
    var hours = duration.toHours();
    var minutes = duration.toMinutesPart();
    if (hours > 0) {
      return hours + "h " + minutes + "m";
    }
    return minutes + "m";
  }

  public record AgentSummary(
      String name, String status, String elapsed, int commits, AgentSession.SessionInfo info) {}
}
