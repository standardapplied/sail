/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one run-launch engine every lane shares: stage the run-scoped task and session files, build
 * and run the launch command for the run's own systemd unit, and — once the process is confirmed —
 * verify it against a concurrent cancel and publish {@code agent_session_started}. The build,
 * ad-hoc, room, and invite lanes differ only in the {@link LaunchSpec} they fill and the {@link
 * RunContext} they finish with; keeping the sequence here means the reserve→launch→verify→publish
 * shape has a single definition rather than a copy per lane.
 */
public final class RunLauncher {

  private final ShellExec shell;
  private final String file;
  private final DispatchOperations.AgentLauncher launcher;
  private final DispatchOperations.Listener listener;
  private final WatcherSpawner watcherSpawner;
  private final RunStore runStore;
  private final DispatchOperations.EventSink events;

  public RunLauncher(
      ShellExec shell,
      String file,
      DispatchOperations.AgentLauncher launcher,
      DispatchOperations.Listener listener,
      WatcherSpawner watcherSpawner,
      RunStore runStore,
      DispatchOperations.EventSink events) {
    this.shell = shell;
    this.file = file;
    this.launcher = launcher;
    this.listener = listener;
    this.watcherSpawner = watcherSpawner;
    this.runStore = runStore;
    this.events = events;
  }

  record LaunchOutcome(int exitCode, Optional<WatcherSpawner.Spawned> watcher) {}

  /**
   * Everything one agent launch needs, in one value so the launch seam is a single parameter rather
   * than the 16-way signature the lanes used to spread by hand. The build, ad-hoc, room, and invite
   * lanes differ only in the fields they fill: a build carries a spec id, model, and reasoning
   * effort; an ad-hoc a blank spec id; a room/invite a viewer role and no repo reservation. {@code
   * task}, {@code branch}, and {@code repoPaths} stage the session file and so are unused when only
   * the launch command is built.
   */
  record LaunchSpec(
      String project,
      SailYaml config,
      String workDir,
      boolean fullPermissions,
      String model,
      String reasoningEffort,
      String specId,
      String agentType,
      String task,
      String branch,
      List<String> repoPaths,
      boolean background,
      AgentUnit unit,
      String runId,
      String runCredential,
      String role,
      String resumeSessionId) {}

  /**
   * The run identity the post-launch tail needs to verify the process, complete a foreground run,
   * and publish {@code agent_session_started}. One value so every lane runs the identical tail —
   * the reserve→launch→verify→publish sequence whose four hand-copied variants hosted the #142/#148
   * field bugs — differing only in the {@code specId}/{@code role} it carries. A background run
   * (room, invite, background dispatch/ad-hoc) skips the foreground completion via {@code
   * background}.
   */
  record RunContext(
      String project,
      AgentUnit unit,
      String runId,
      String specId,
      String agentType,
      String role,
      boolean background) {}

  LaunchOutcome launchAgent(
      String project,
      SailYaml config,
      List<SailYaml.Repo> targetRepos,
      String task,
      String branch,
      boolean background,
      Spec spec,
      String agentType,
      AgentUnit unit,
      String runId,
      String runCredential) {
    return launchSession(
        new LaunchSpec(
            project,
            config,
            AgentSession.launchWorkDir(config.sshUser(), targetRepos),
            true,
            spec.model(),
            spec.reasoningEffort(),
            spec.id(),
            agentType,
            task,
            branch,
            targetRepos.stream().map(SailYaml.Repo::path).toList(),
            background,
            unit,
            runId,
            runCredential,
            "build",
            null));
  }

  /**
   * The one post-launch tail every lane runs: read the launched process's status, fail the launch
   * if a concurrent cancel already claimed the run, complete a foreground run, and — once the agent
   * is confirmed live — publish {@code agent_session_started}. Returns the queried status so the
   * lane can build its own response. Replaces four hand-copied copies of this sequence.
   */
  AgentSession.SessionInfo finishLaunch(RunContext ctx, LaunchOutcome launch) {
    var status = querySession(new AgentSession(shell), ctx.project(), ctx.unit());
    if (!updateRunProcess(ctx.runId(), ctx.project(), status, launch.watcher())) {
      throw launchLostToCancel(ctx.runId(), ctx.project(), ctx.unit());
    }
    if (!ctx.background()) {
      completeForegroundRun(ctx.runId(), launch.exitCode());
    }
    if (status != null && status.running()) {
      publishAgentSessionStarted(
          ctx.project(),
          ctx.specId(),
          ctx.agentType(),
          status.pid(),
          ctx.runId(),
          ctx.role(),
          launch.watcher());
    }
    return status;
  }

  LaunchOutcome launchSession(LaunchSpec s) {
    try {
      ensureSailSetup(s.project());
      var session = new AgentSession(shell);
      session.ensureDirectory(s.project());
      session.writeTaskFile(s.project(), s.task(), s.unit());
      session.writeSession(
          s.project(),
          s.task(),
          Objects.requireNonNullElse(s.branch(), ""),
          s.specId(),
          s.agentType(),
          s.runId(),
          s.role(),
          s.repoPaths(),
          s.unit());
      var command = launchCommand(s);
      listener.launching(s.background(), redactCredential(command, s.runCredential()));
      var exitCode = launcher.launch(command);
      if (s.background()) {
        if (exitCode != 0) {
          throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.");
        }
        return new LaunchOutcome(
            exitCode, launchWatcherIfAgent(s.project(), s.config(), s.runId(), s.unit()));
      }
      return new LaunchOutcome(exitCode, Optional.empty());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.", e);
    }
  }

  /**
   * The command as listeners may see it: the plaintext run credential replaced with a marker.
   * Listeners print launch commands to terminals and logs, and a leaked live credential
   * authenticates spec and event writes until the run finishes — only the launcher ever receives
   * the real value.
   */
  private static List<String> redactCredential(List<String> command, String runCredential) {
    if (Strings.isBlank(runCredential)) {
      return command;
    }
    return command.stream()
        .map(argument -> argument.equals(runCredential) ? "<redacted>" : argument)
        .toList();
  }

  static List<String> launchCommand(LaunchSpec s) {
    var agentCli = AgentCli.fromYamlName(s.agentType());
    return s.background()
        ? AgentSession.buildBackgroundLaunchCommand(
            s.project(),
            s.config().sshUser(),
            s.workDir(),
            s.fullPermissions(),
            agentCli,
            s.model(),
            s.reasoningEffort(),
            s.specId(),
            s.agentType(),
            s.unit().logPath(),
            s.runId(),
            s.runCredential(),
            s.role(),
            s.resumeSessionId())
        : AgentSession.buildForegroundTaskCommand(
            s.project(),
            s.config().sshUser(),
            s.workDir(),
            s.fullPermissions(),
            agentCli,
            s.model(),
            s.reasoningEffort(),
            s.specId(),
            s.agentType(),
            s.unit().logPath(),
            s.runId(),
            s.runCredential(),
            s.role());
  }

  /**
   * Spawns the detached run-addressed watcher whenever the project declares an agent block —
   * supervision is on by default, with {@code Guardrails.defaults()} applying when none are
   * declared, and the watcher is also the authoritative stop observer the review pipeline depends
   * on. One watcher per dispatch, supervising exactly this run's recorded unit.
   */
  private Optional<WatcherSpawner.Spawned> launchWatcherIfAgent(
      String project, SailYaml config, String runId, AgentUnit unit) throws IOException {
    if (config.agent() == null) {
      return Optional.empty();
    }
    return Optional.of(
        watcherSpawner.spawnForRun(
            project,
            SailPaths.resolveSailYaml(project, file).toAbsolutePath(),
            runId,
            unit.unitName()));
  }

  /**
   * Installs or upgrades the in-container {@code sail spec} and event helpers before any agent
   * launches. Failure aborts the launch: every local-socket route now requires the run's bearer
   * credential, so an agent left with stale unauthenticated helpers would run apparently normally
   * while every spec operation 401s and every lifecycle event is silently dropped.
   */
  private void ensureSailSetup(String project) {
    try {
      var result = ContainerSailSetup.ensureInstalled(shell, project);
      listener.sailSetupUpdated(result == ContainerSailSetup.Result.UPDATED);
    } catch (Exception e) {
      throw new ApiException(
          ErrorCode.AGENT_LAUNCH_FAILED,
          "Failed to install the authenticated sail helpers in " + project + ".",
          "Repair the container's sail socket mount and retry the dispatch.",
          e);
    }
  }

  private boolean updateRunProcess(
      String runId,
      String project,
      AgentSession.SessionInfo status,
      Optional<WatcherSpawner.Spawned> watcher) {
    if (runStore == null) {
      return true;
    }
    Integer watcherPid =
        watcher.orElse(null) instanceof WatcherSpawner.Fallback fallback
            ? (int) fallback.pid()
            : null;
    Integer pid = status != null ? status.pid() : null;
    var pidTicks =
        status != null && status.running() ? readStartTicks(project, status.pid()) : null;
    try {
      return runStore.updateProcess(runId, pid, pidTicks, watcherPid);
    } catch (RuntimeException e) {
      System.err.println(
          "  [api] Warning: could not update run process " + runId + ": " + e.getMessage());
      return true;
    }
  }

  private Long readStartTicks(String project, int pid) {
    try {
      return new AgentSession(shell).readProcessStartTicks(project, pid);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Tears down a launch whose run was cancelled during preparation: the operator's stop already
   * recorded the terminal outcome, so the just-started agent must die rather than run unrecorded
   * against a released claim. Halting is best-effort — the unit is transient and run-scoped, so a
   * halt that races the process's own exit is a no-op — and the conflict names what happened.
   */
  private ApiException launchLostToCancel(String runId, String project, AgentUnit unit) {
    try {
      StopOperations.sessionHalter(shell).halt(project, unit);
    } catch (Exception e) {
      System.err.println(
          "  [api] Warning: could not halt cancelled launch " + runId + ": " + e.getMessage());
    }
    return new ApiException(
        ErrorCode.CONFLICT,
        "Run " + runId + " was cancelled while its launch was preparing; the agent was torn down.",
        "The cancel already recorded the run's outcome; no retry is needed.");
  }

  /**
   * Completes a foreground run explicitly: its launch command blocks until the agent exits, so the
   * exit code is known here and the run must not be left {@code running} waiting for a terminal
   * hook event that may never arrive. Compare-and-set from {@code running}, exactly like {@link
   * RunTracker}: an operator stop that already recorded the run terminal wins, and this finisher
   * only enriches the missing exit code instead of overwriting the cancel with {@code completed}.
   */
  private void completeForegroundRun(String runId, int exitCode) {
    runBookkeeping(
        "complete run " + runId,
        () -> {
          if (runStore.transition(
              runId, "running", exitCode == 0 ? "completed" : "failed", exitCode)) {
            return;
          }
          var current = runStore.findById(runId).orElse(null);
          if (current != null && current.exitCode() == null) {
            runStore.recordExitCode(runId, exitCode);
          }
        });
  }

  /**
   * Runs a best-effort run-store bookkeeping update. A missing store (a box that keeps no run
   * aggregate) is a silent no-op, and a store error is logged but never propagated: bookkeeping
   * must never fail a launch or mask the agent's real outcome.
   */
  private void runBookkeeping(String action, Runnable op) {
    if (runStore == null) {
      return;
    }
    try {
      op.run();
    } catch (RuntimeException e) {
      System.err.println("  [api] Warning: could not " + action + ": " + e.getMessage());
    }
  }

  private void publishAgentSessionStarted(
      String project,
      String specId,
      String agentType,
      Integer pid,
      String runId,
      String role,
      Optional<WatcherSpawner.Spawned> watcher) {
    var data = new LinkedHashMap<String, Object>();
    if (pid != null) {
      data.put("pid", pid);
    }
    if (Strings.isNotBlank(runId)) {
      data.put(Event.WellKnownData.RUN_ID, runId);
    }
    if (Strings.isNotBlank(role)) {
      data.put(Event.WellKnownData.RUN_ROLE, role);
    }
    if (watcher.orElse(null) instanceof WatcherSpawner.Fallback fallback) {
      data.put(Event.WellKnownData.WATCHER_PID, fallback.pid());
    }
    events.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.AGENT_SESSION_STARTED,
            agentType,
            HostInfo.hostname(),
            data));
  }

  private AgentSession.SessionInfo querySession(
      AgentSession session, String project, AgentUnit unit) {
    try {
      return session.queryStatus(project, unit);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STATUS_FAILED, "Failed to query agent status.", e);
    }
  }
}
