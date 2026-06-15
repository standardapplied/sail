/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "status",
    description = "Check agent session status. Omit project name to show all projects.",
    mixinStandardHelpOptions = true)
public final class AgentStatusCommand implements Runnable {

  @Parameters(index = "0", description = "Project name (omit for all projects).", arity = "0..1")
  private String name;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (name == null) {
      executeAll();
    } else {
      executeSingle();
    }
  }

  private void executeAll() throws Exception {
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);
    var containers = mgr.listAll();

    var runningContainers =
        containers.stream().filter(c -> c.state() instanceof ContainerState.Running).toList();

    if (runningContainers.isEmpty()) {
      if (json) {
        System.out.println("[]");
      } else {
        System.out.println(Ansi.AUTO.string("  @|faint No running projects found.|@"));
      }
      return;
    }

    var agentSession = new AgentSession(shell);
    var checker = new GuardrailChecker(shell);
    var summaries = new ArrayList<AgentSummary>();

    for (var container : runningContainers) {
      var projectName = container.name();
      AgentSession.SessionInfo info = null;
      try {
        info = agentSession.queryStatus(projectName);
      } catch (Exception ignored) {
      }

      var statusLabel = "No session";
      var elapsed = "";
      var commits = 0;

      if (info != null && info.running()) {
        statusLabel = "Running";
        if (!info.startedAt().isBlank()) {
          try {
            var started = Instant.parse(info.startedAt());
            elapsed = formatElapsed(Duration.between(started, DateTimeUtils.now()));
            var repoPaths = resolveRepoPaths(projectName);
            for (var repoPath : repoPaths) {
              try {
                commits += checker.queryGitActivity(projectName, repoPath, started).commitCount();
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

  private void executeSingle() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);

    var agentSession = new AgentSession(shell);
    var info = agentSession.queryStatus(name);

    SailYaml config = null;
    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (Files.exists(singYamlPath)) {
      config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));
    }

    var commitCount = 0;
    var lastCommitMinutesAgo = -1L;
    if (info != null && info.running()) {
      try {
        var checker = new GuardrailChecker(shell);
        var repoPaths = config != null ? config.repoPaths() : List.of("/home/dev/workspace");
        var since =
            !info.startedAt().isBlank() ? Instant.parse(info.startedAt()) : DateTimeUtils.now();
        for (var repoPath : repoPaths) {
          var activity = checker.queryGitActivity(name, repoPath, since);
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
    if (config != null
        && config.agent() != null
        && config.agent().specsDir() != null
        && info != null) {
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        taskCounts = SpecDirectory.statusCounts(new SpecStore(db).projectSpecs(name));
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
        name, info, commitCount, lastCommitMinutesAgo, taskCounts, System.out, Ansi.AUTO);
  }

  private List<String> resolveRepoPaths(String projectName) {
    try {
      var singYamlPath = SailPaths.resolveSailYaml(projectName, file);
      if (Files.exists(singYamlPath)) {
        var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));
        return config.repoPaths();
      }
    } catch (Exception ignored) {
    }
    return List.of("/home/dev/workspace");
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
