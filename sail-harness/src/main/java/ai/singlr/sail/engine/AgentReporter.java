/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Generates a comprehensive morning-after report for an overnight agent session. Aggregates session
 * info, task progress, git activity, guardrail triggers, and rollback history into a single {@link
 * Report} record.
 */
public final class AgentReporter {

  private final ShellExec shell;

  public AgentReporter(ShellExec shell) {
    this.shell = shell;
  }

  /**
   * Comprehensive report data for an agent session.
   *
   * @param name project name
   * @param sessionStatus "Running", "Completed", "Killed by guardrail", "Rolled back"
   * @param startedAt when the session started (nullable)
   * @param endedAt approximate end time (nullable if still running)
   * @param duration human-readable duration
   * @param branch git branch used
   * @param specs the project's specs from the control-plane database (empty if none)
   * @param commitCount total commits since launch
   * @param lastCommitMinutesAgo minutes since last commit (-1 if unknown)
   * @param guardrailTriggered whether a guardrail was triggered
   * @param guardrailReason the reason if triggered (nullable)
   * @param guardrailAction the action taken (nullable)
   * @param rolledBack whether auto-rollback occurred
   * @param rollbackSnapshot snapshot restored (nullable)
   * @param exitCode the agent process's exit code (nullable if unknown / still running)
   */
  public record Report(
      String name,
      String sessionStatus,
      String startedAt,
      String endedAt,
      String duration,
      String branch,
      List<Spec> specs,
      int commitCount,
      long lastCommitMinutesAgo,
      boolean guardrailTriggered,
      String guardrailReason,
      String guardrailAction,
      boolean rolledBack,
      String rollbackSnapshot,
      Integer exitCode) {

    /** Converts to a map for JSON serialization. */
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("session_status", sessionStatus);
      if (startedAt != null) {
        map.put("started_at", startedAt);
      }
      if (endedAt != null) {
        map.put("ended_at", endedAt);
      }
      if (duration != null) {
        map.put("duration", duration);
      }
      if (branch != null && !branch.isBlank()) {
        map.put("branch", branch);
      }
      if (!specs.isEmpty()) {
        map.put("specs", specs.stream().map(Spec::toMap).toList());
      }
      map.put("commits_since_launch", commitCount);
      if (lastCommitMinutesAgo >= 0) {
        map.put("last_commit_minutes_ago", lastCommitMinutesAgo);
      }
      map.put("guardrail_triggered", guardrailTriggered);
      if (guardrailReason != null) {
        map.put("guardrail_reason", guardrailReason);
      }
      if (guardrailAction != null) {
        map.put("guardrail_action", guardrailAction);
      }
      map.put("rolled_back", rolledBack);
      if (rollbackSnapshot != null) {
        map.put("rollback_snapshot", rollbackSnapshot);
      }
      if (exitCode != null) {
        map.put("exit_code", exitCode);
      }
      return map;
    }
  }

  /**
   * Generates a full report for the given project. Aggregates data from 5 sources: session info,
   * task file, git activity, guardrail trigger file, and rollback file. All queries are
   * best-effort: failures produce degraded output rather than errors.
   *
   * @param containerName the Incus container name
   * @param config the project's SailYaml config
   */
  public Report generate(
      String containerName, SailYaml config, List<Spec> specs, SessionStore.SessionRow session)
      throws IOException, InterruptedException, TimeoutException {
    return generate(containerName, config, specs, session, SailPaths.projectDir(containerName));
  }

  /**
   * Generates a full report with an explicit state directory (enables testing without /etc/sail).
   * Specs come from the control-plane database. When {@code session} is present it is the source of
   * truth for the run's start and end times — so the duration is the agent's real run-time, not
   * wall-clock since dispatch (the agent-session file records only the start).
   */
  Report generate(
      String containerName,
      SailYaml config,
      List<Spec> specs,
      SessionStore.SessionRow session,
      Path stateDir)
      throws IOException, InterruptedException, TimeoutException {

    var agentSession = new AgentSession(shell);
    var info = agentSession.queryStatus(containerName);
    var running = info != null && info.running();
    var startedAt =
        session != null && session.startedAt() != null
            ? session.startedAt()
            : info != null ? info.startedAt() : null;
    var branch = info != null ? info.branch() : "";

    var commitCount = 0;
    var lastCommitMinutesAgo = -1L;
    var startInstant = parseInstantSafe(startedAt);
    if (startInstant == null) {
      startInstant = Instant.now().minus(Duration.ofHours(12));
    }
    var repoPaths = config.repoPaths();
    var checker = new GuardrailChecker(shell);
    for (var repoPath : repoPaths) {
      try {
        var activity = checker.queryGitActivity(containerName, repoPath, startInstant);
        commitCount += activity.commitCount();
        if (activity.lastCommitEpoch() > 0) {
          var minutesAgo = (Instant.now().getEpochSecond() - activity.lastCommitEpoch()) / 60;
          if (lastCommitMinutesAgo < 0 || minutesAgo < lastCommitMinutesAgo) {
            lastCommitMinutesAgo = minutesAgo;
          }
        }
      } catch (Exception ignored) {
      }
    }

    var guardrailTriggered = false;
    String guardrailReason = null;
    String guardrailAction = null;
    try {
      var triggerCmd =
          ContainerExec.asDevUser(
              containerName, List.of("cat", "/home/dev/guardrail-triggered.yaml"));
      var triggerResult = shell.exec(triggerCmd);
      if (triggerResult.ok() && !triggerResult.stdout().isBlank()) {
        var triggerMap = YamlUtil.parseMap(triggerResult.stdout());
        guardrailTriggered = true;
        guardrailReason = Objects.toString(triggerMap.get("reason"), null);
        guardrailAction = Objects.toString(triggerMap.get("action"), null);
      }
    } catch (Exception ignored) {
    }

    var rolledBack = false;
    String rollbackSnapshot = null;
    var rollbackPath = stateDir.resolve("last-rollback.yaml");
    if (Files.exists(rollbackPath)) {
      try {
        var rollbackMap = YamlUtil.parseFile(rollbackPath);
        rolledBack = true;
        rollbackSnapshot = Objects.toString(rollbackMap.get("snapshot_restored"), null);
      } catch (Exception ignored) {
      }
    }

    String sessionStatus;
    String endedAt = null;
    if (running) {
      sessionStatus = "Running";
    } else if (rolledBack) {
      sessionStatus = "Rolled back";
      try {
        var rollbackMap = YamlUtil.parseFile(rollbackPath);
        endedAt = Objects.toString(rollbackMap.get("rolled_back_at"), null);
      } catch (Exception ignored) {
      }
    } else if (guardrailTriggered) {
      sessionStatus = "Killed by guardrail";
    } else if (info != null) {
      sessionStatus = "Completed";
    } else {
      sessionStatus = "No session";
    }

    if (session != null && session.completedAt() != null) {
      endedAt = session.completedAt();
    }

    var exitCode = session != null ? session.exitCode() : null;
    if (!running && !rolledBack && !guardrailTriggered && exitCode != null && exitCode != 0) {
      sessionStatus = "Failed (exit " + exitCode + ")";
    }

    String durationStr = null;
    if (startInstant != null) {
      var endInstant = parseInstantSafe(endedAt);
      if (endInstant == null) {
        endInstant = Instant.now();
      }
      durationStr = GuardrailChecker.formatDuration(Duration.between(startInstant, endInstant));
    }

    return new Report(
        containerName,
        sessionStatus,
        startedAt,
        endedAt,
        durationStr,
        branch,
        specs,
        commitCount,
        lastCommitMinutesAgo,
        guardrailTriggered,
        guardrailReason,
        guardrailAction,
        rolledBack,
        rollbackSnapshot,
        exitCode);
  }

  private static Instant parseInstantSafe(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      return null;
    }
  }
}
