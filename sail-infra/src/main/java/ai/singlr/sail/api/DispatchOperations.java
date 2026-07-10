/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.BranchPolicy;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentTaskPrompt;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.RunRetention;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The single dispatch executor behind every lane. Owns the whole procedure — resolve, policy,
 * claim, branch, launch, run record, watcher, events — so a fix to any step lands exactly once.
 * {@link SailOperations} delegates its dispatch route here (as it delegates spec CRUD to {@link
 * GlobalSpecOperations}); {@code sail spec dispatch} constructs this class directly against the
 * control-plane database, so a standalone box dispatches in-process with no server required.
 *
 * <p>Lane differences are injected, never forked: where the events go ({@link EventSink}), whether
 * and how to snapshot ({@link Snapshotter}), how the launch command runs ({@link AgentLauncher}),
 * and what the operator sees ({@link Listener}). The procedure itself is identical on every lane.
 */
public final class DispatchOperations {

  private static final Duration SNAPSHOT_INTERVAL = Duration.ofHours(24);

  /** One dispatch invocation, lane-agnostic. {@code restart} is CLI-only until the API grows it. */
  public record Request(
      String specId, String mode, boolean dryRun, List<String> repos, boolean restart) {}

  /** What a dispatch produced. */
  public sealed interface Outcome permits NoSpecs, Dispatched {}

  /** The project has no ready spec assigned to this box. */
  public record NoSpecs() implements Outcome {}

  /**
   * A completed dispatch. {@code session}, {@code exitCode} (foreground only), {@code runId} and
   * {@code watcher} are absent on a dry run, which stops after the claim/branch phase.
   */
  public record Dispatched(
      Spec taskSpec,
      String branch,
      String agentType,
      String task,
      String runId,
      String snapshotLabel,
      boolean branchCreated,
      AgentSession.SessionInfo session,
      Integer exitCode,
      Optional<WatcherSpawner.Spawned> watcher)
      implements Outcome {}

  /**
   * Where dispatch lifecycle events go: the in-process bus on a server, the HTTP publisher from the
   * CLI (which may drop them best-effort when sail-api is unreachable).
   */
  @FunctionalInterface
  public interface EventSink {
    void publish(Event event);
  }

  /**
   * Decides whether to snapshot the container before launch and takes the snapshot. Returns the
   * snapshot label, or an empty string when skipped. The server lane auto-snapshots on a 24h
   * cadence ({@link #autoSnapshotter}); the CLI lane asks the operator.
   */
  @FunctionalInterface
  public interface Snapshotter {
    String snapshot(String project, SailYaml config);
  }

  /**
   * Runs the built launch command and returns its exit code. The server lane captures output
   * through the shell ({@link #shellLauncher}); the CLI lane inherits the terminal ({@link
   * #terminalLauncher}) so a foreground agent owns stdin/stdout.
   */
  @FunctionalInterface
  public interface AgentLauncher {
    int launch(List<String> command) throws Exception;
  }

  /**
   * Presentation hooks fired as the procedure advances, so an interactive lane can narrate without
   * the executor knowing about terminals. Every hook fires unconditionally with the facts; the
   * implementation decides what to show.
   */
  public interface Listener {
    Listener NONE = new Listener() {};

    default void claimed(Spec taskSpec, String task) {}

    default void branchReady(String branch, String repoPath, boolean reused) {}

    default void branchUnavailable(String branch) {}

    default void launching(boolean background, List<String> command) {}

    default void runsPruned(int count) {}

    default void sailSetupBackfilled(boolean backfilled) {}
  }

  /**
   * Outcome of {@link #resolveSpec}: the chosen spec, whether {@code restart} actually reset a
   * non-pending status (so the executor publishes a {@code spec_restarted} event), and the status
   * the spec held just before the reset.
   */
  record SpecResolution(Spec spec, boolean restarted, String previousStatus) {
    static SpecResolution none() {
      return new SpecResolution(null, false, null);
    }

    static SpecResolution of(Spec spec) {
      return new SpecResolution(spec, false, null);
    }

    static SpecResolution restarted(Spec spec, String previousStatus) {
      return new SpecResolution(spec, true, previousStatus);
    }
  }

  private final ShellExec shell;
  private final String file;
  private final ProjectLoader projects;
  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final RunStore runStore;
  private final FdeStore fdeStore;
  private final EventSink events;
  private final WatcherSpawner watcherSpawner;
  private final Snapshotter snapshotter;
  private final AgentLauncher launcher;
  private final Listener listener;

  /**
   * The one constructor: every collaborator is stated at the call site, so a wiring gap (a lane
   * that forgets the run store, the roster, or the watcher) is visible in the diff instead of
   * hidden behind a convenience overload — the #142 lesson. {@code reviewStore}, {@code runStore}
   * and {@code fdeStore} accept {@code null} only for boxes that genuinely keep no such aggregate;
   * passing {@code null} is an explicit decision, not a default.
   */
  public DispatchOperations(
      ShellExec shell,
      String file,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      FdeStore fdeStore,
      EventSink events,
      WatcherSpawner watcherSpawner,
      Snapshotter snapshotter,
      AgentLauncher launcher,
      Listener listener) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.file = Objects.requireNonNull(file, "file");
    this.projects = new ProjectLoader(shell, file);
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.runStore = runStore;
    this.fdeStore = fdeStore;
    this.events = Objects.requireNonNull(events, "events");
    this.watcherSpawner = Objects.requireNonNull(watcherSpawner, "watcherSpawner");
    this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /**
   * Executes one dispatch: resolve the spec (honoring {@code restart}), enforce {@link
   * DispatchPolicy}, claim it {@code in_progress} with its resolved repos and branch persisted,
   * publish the lifecycle events, snapshot and branch as configured, record the run row before the
   * agent starts, launch, and arm the guardrail watcher. Throws {@link ApiException} with a
   * structured code on every refusal or failure.
   */
  public Outcome dispatch(String project, Request request, Actor actor, String localHandle) {
    var loaded = projects.loadRunning(project);
    if (!request.mode().equals("background") && !request.mode().equals("foreground")) {
      throw new ApiException(
          ErrorCode.INVALID_MODE, "Dispatch mode must be background or foreground.");
    }
    if (Strings.isBlank(localHandle)) {
      throw refusal(DispatchPolicy.nodeHandleUnset());
    }
    requireTrustedRoster(localHandle);
    if (loaded.config().agent() == null) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED, "No agent configured in sail.yaml's agent block.");
    }

    var agentSession = new AgentSession(shell);
    var existing = querySession(agentSession, project);
    if (existing != null && existing.running()) {
      throw new ApiException(
          ErrorCode.AGENT_ALREADY_RUNNING,
          "Agent is already running for project '" + project + "'.",
          "Stop the active agent before dispatching another spec.");
    }

    var specs = specStore.projectSpecs(project);
    var resolution =
        resolveSpec(specs, request.specId(), request.restart(), actor, localHandle, specStore);
    if (resolution.spec() == null) {
      return new NoSpecs();
    }
    var nextSpec = resolution.spec();

    var targetRepos = DispatchRepos.resolve(loaded.config(), nextSpec, request.repos());
    var taskSpec = DispatchRepos.withTargetRepos(nextSpec, targetRepos);
    var branch = BranchPolicy.branchName(loaded.config(), nextSpec);
    specStore.updateReposAndStatus(nextSpec.id(), taskSpec.repos(), SpecStatus.IN_PROGRESS, branch);
    if (reviewStore != null) {
      reviewStore.supersedeForSpec(nextSpec.id());
    }
    var specBody = specStore.getContent(nextSpec.id()).map(SpecStore.SpecContent::body).orElse("");
    var task = AgentTaskPrompt.build(taskSpec, specBody.isBlank() ? nextSpec.title() : specBody);
    var agentType = taskSpec.agent() != null ? taskSpec.agent() : loaded.config().agent().type();
    listener.claimed(taskSpec, task);
    if (resolution.restarted()) {
      publish(
          project,
          nextSpec.id(),
          Event.WellKnownTypes.SPEC_RESTARTED,
          Map.of("note", "restarted from " + resolution.previousStatus()));
    }
    publish(
        project,
        nextSpec.id(),
        Event.WellKnownTypes.SPEC_DISPATCHED,
        DispatchEvents.dispatchedData(branch, request.mode()));
    var snapshot = snapshotter.snapshot(project, loaded.config());
    if (!snapshot.isEmpty()) {
      publish(project, null, Event.WellKnownTypes.SNAPSHOT_CREATED, Map.of("label", snapshot));
    }
    var branchCreated =
        checkoutBranch(project, loaded.config(), targetRepos, branch, resolution.restarted());

    if (request.dryRun()) {
      return new Dispatched(
          taskSpec,
          branch,
          agentType,
          task,
          null,
          snapshot,
          branchCreated,
          null,
          null,
          Optional.empty());
    }

    var background = request.mode().equals("background");
    var runId = DateTimeUtils.newId().toString();
    var runLogPath = AgentUnit.BUILD.runLogPath(runId);
    recordRun(runId, project, nextSpec.id(), localHandle, agentType, branch, task, runLogPath);
    try {
      var launch =
          launchAgent(
              project,
              loaded.config(),
              targetRepos,
              task,
              branch,
              background,
              taskSpec,
              agentType,
              runLogPath,
              runId);
      var status = querySession(agentSession, project);
      if (background) {
        updateRunProcess(runId, status, launch.watcher());
      } else {
        completeForegroundRun(runId, launch.exitCode());
      }
      if (status != null && status.running()) {
        publishAgentSessionStarted(
            project, nextSpec.id(), agentType, status.pid(), runId, launch.watcher());
      }
      return new Dispatched(
          taskSpec,
          branch,
          agentType,
          task,
          runId,
          snapshot,
          branchCreated,
          status,
          background ? null : launch.exitCode(),
          launch.watcher());
    } catch (RuntimeException e) {
      failRun(runId);
      throw e;
    }
  }

  /** The server-lane snapshotter: gated on {@code agent.auto_snapshot}, at most one per 24h. */
  public static Snapshotter autoSnapshotter(ShellExec shell) {
    return (project, config) -> {
      if (config.agent() == null || !config.agent().autoSnapshot()) {
        return "";
      }
      var snapMgr = new SnapshotManager(shell);
      if (!shouldSnapshot(snapMgr, project)) {
        return "";
      }
      var label = SnapshotManager.defaultLabel();
      try {
        snapMgr.create(project, label);
        return label;
      } catch (Exception e) {
        throw new ApiException(ErrorCode.SNAPSHOT_FAILED, "Failed to create dispatch snapshot.", e);
      }
    };
  }

  /** The server-lane launcher: runs the launch command through the shell, output captured. */
  public static AgentLauncher shellLauncher(ShellExec shell) {
    return command -> {
      try {
        return shell.exec(command).exitCode();
      } catch (Exception e) {
        throw new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed.", e);
      }
    };
  }

  /** The CLI-lane launcher: inherits the terminal so a foreground agent owns stdin/stdout. */
  public static AgentLauncher terminalLauncher() {
    return command -> {
      var process = new ProcessBuilder(command).inheritIO().start();
      return process.waitFor();
    };
  }

  /**
   * Picks the spec to dispatch. Without {@code specId}, the next ready spec assigned to this box's
   * FDE (or none). With {@code specId}, the spec must exist, pass {@link DispatchPolicy} (checked
   * before any mutation, so a refused caller can never reset a status), and be pending with its
   * dependencies met — unless {@code restart} is set, in which case a non-pending status is reset
   * to pending in the store and reported so the caller publishes {@code spec_restarted}.
   */
  static SpecResolution resolveSpec(
      List<Spec> specs,
      String specId,
      boolean restart,
      Actor actor,
      String localHandle,
      SpecStore store) {
    if (Strings.isBlank(specId)) {
      var next = SpecDirectory.nextReadyAssignedTo(specs, localHandle);
      if (next == null) {
        return SpecResolution.none();
      }
      requireAllowed(actor, next, localHandle);
      return SpecResolution.of(next);
    }
    var spec = SpecDirectory.findById(specs, specId);
    if (spec == null) {
      throw new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found.");
    }
    requireAllowed(actor, spec, localHandle);
    if (spec.status() == SpecStatus.PENDING) {
      if (!SpecDirectory.isReady(specs, spec)) {
        throw new ApiException(
            ErrorCode.SPEC_NOT_READY,
            "Spec '" + specId + "' is not ready for dispatch.",
            "Resolve dependencies or choose a ready spec.");
      }
      return SpecResolution.of(spec);
    }
    if (!restart) {
      throw new ApiException(
          ErrorCode.SPEC_NOT_READY,
          "Spec '"
              + specId
              + "' is not pending (current status: "
              + spec.status().wire()
              + "). A spec is dispatched only when pending.",
          "To dispatch it again, pass --restart (this resets status to pending and records the"
              + " restart as a lifecycle event).");
    }
    store.updateStatus(specId, SpecStatus.PENDING);
    return SpecResolution.restarted(spec, spec.status().wire());
  }

  private static void requireAllowed(Actor actor, Spec spec, String localHandle) {
    if (DispatchPolicy.check(actor, spec, localHandle)
        instanceof DispatchDecision.Refused refused) {
      throw refusal(refused);
    }
  }

  /**
   * Refuses dispatch when this box's FDE handle is missing from the synced roster: an unauthorized
   * handle means the specs assigned to it cannot be trusted. Enforced on every lane; a box that
   * keeps no roster ({@code fdeStore == null}) skips the check.
   */
  private void requireTrustedRoster(String localHandle) {
    if (fdeStore == null || fdeStore.byHandle(localHandle).isPresent()) {
      return;
    }
    throw new ApiException(
        ErrorCode.FDE_NOT_IN_ROSTER,
        "FDE '"
            + localHandle
            + "' is not in this box's roster, so its assigned specs cannot be trusted.",
        "Run 'sail sync' to pull the roster from main, or get authorized there first.");
  }

  /**
   * Checks out the work branch in every target repo that exists in the container: a fresh branch
   * with {@code checkout -b}, or — on a restart — a forced {@code checkout -f} onto the existing
   * branch so re-dispatch lands on the prior work even over a dirty tree the previous run left. A
   * collision on a non-restart dispatch fails loud, pointing the operator at {@code --restart}.
   */
  private boolean checkoutBranch(
      String project,
      SailYaml config,
      List<SailYaml.Repo> targetRepos,
      String branch,
      boolean restarted) {
    if (Strings.isBlank(branch)) {
      return false;
    }
    var created = false;
    for (var repo : targetRepos) {
      var repoDir = "/home/" + config.sshUser() + "/workspace/" + repo.path();
      var repoExists =
          exec(ContainerExec.asDevUser(project, List.of("test", "-d", repoDir + "/.git")));
      if (!repoExists.ok()) {
        continue;
      }
      var branchExists =
          exec(ContainerExec.asDevUser(
                  project,
                  List.of(
                      "git",
                      "-C",
                      repoDir,
                      "rev-parse",
                      "--verify",
                      "--quiet",
                      "refs/heads/" + branch)))
              .ok();
      var result =
          exec(
              ContainerExec.asDevUser(
                  project, branchCheckoutArgs(repoDir, branch, branchExists, restarted)));
      if (!result.ok()) {
        throw new ApiException(
            ErrorCode.BRANCH_CREATE_FAILED,
            "Failed to "
                + (branchExists ? "check out" : "create")
                + " branch '"
                + branch
                + "' in repo '"
                + repo.path()
                + "'.");
      }
      listener.branchReady(branch, repo.path(), branchExists);
      created = true;
    }
    if (!created) {
      listener.branchUnavailable(branch);
    }
    return created;
  }

  /**
   * The git checkout command for a dispatch's work branch. A fresh branch is created with {@code
   * checkout -b}; on a restart an already-present branch is reused with a <em>forced</em> {@code
   * checkout -f} so re-dispatch lands on the prior branch even when the previous run left a dirty
   * working tree, rather than resuming onto it and aborting.
   */
  static List<String> branchCheckoutArgs(
      String repoDir, String branchName, boolean branchExists, boolean restart) {
    if (branchExists && !restart) {
      throw new ApiException(
          ErrorCode.BRANCH_CREATE_FAILED,
          "Branch '" + branchName + "' already exists.",
          "Pass --restart to re-dispatch onto it, or delete it first.");
    }
    return branchExists
        ? List.of("git", "-C", repoDir, "checkout", "-f", branchName)
        : List.of("git", "-C", repoDir, "checkout", "-b", branchName);
  }

  /**
   * The outcome of a launch attempt: the launch command's exit code (for foreground, the agent's
   * own exit code, since its launch command blocks until the agent exits) and the guardrail
   * watcher, if one was spawned (background only).
   */
  private record LaunchOutcome(int exitCode, Optional<WatcherSpawner.Spawned> watcher) {}

  private LaunchOutcome launchAgent(
      String project,
      SailYaml config,
      List<SailYaml.Repo> targetRepos,
      String task,
      String branch,
      boolean background,
      Spec spec,
      String agentType,
      String logPath,
      String runId) {
    try {
      ensureSailSetup(project);
      var session = new AgentSession(shell);
      session.ensureDirectory(project);
      session.resetLog(project, AgentUnit.REVIEW);
      session.writeTaskFile(project, task);
      session.writeSession(
          project, task, Objects.requireNonNullElse(branch, ""), spec.id(), agentType, runId);
      var agentCli = AgentCli.fromYamlName(agentType);
      var workDir = AgentSession.launchWorkDir(config.sshUser(), targetRepos);
      var command =
          background
              ? AgentSession.buildBackgroundLaunchCommand(
                  project,
                  config.sshUser(),
                  workDir,
                  true,
                  agentCli,
                  spec.model(),
                  spec.reasoningEffort(),
                  spec.id(),
                  agentType,
                  logPath,
                  runId)
              : AgentSession.buildForegroundTaskCommand(
                  project,
                  config.sshUser(),
                  workDir,
                  true,
                  agentCli,
                  spec.model(),
                  spec.reasoningEffort(),
                  spec.id(),
                  agentType,
                  logPath,
                  runId);
      listener.launching(background, command);
      var exitCode = launcher.launch(command);
      if (background) {
        if (exitCode != 0) {
          throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.");
        }
        return new LaunchOutcome(exitCode, launchWatcherIfAgent(project, config));
      }
      return new LaunchOutcome(exitCode, Optional.empty());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.", e);
    }
  }

  /**
   * Spawns the detached watcher whenever the project declares an agent block — supervision is on by
   * default, with {@code Guardrails.defaults()} applying when none are declared, and the watcher is
   * also the authoritative stop observer the review pipeline depends on.
   */
  private Optional<WatcherSpawner.Spawned> launchWatcherIfAgent(String project, SailYaml config)
      throws IOException {
    if (config.agent() == null) {
      return Optional.empty();
    }
    return Optional.of(
        watcherSpawner.spawn(
            project,
            SailPaths.resolveSailYaml(project, file).toAbsolutePath(),
            SailPaths.projectDir(project).resolve("watch.log")));
  }

  private void ensureSailSetup(String project) {
    try {
      var result = ContainerSailSetup.ensureInstalled(shell, project);
      listener.sailSetupBackfilled(result == ContainerSailSetup.Result.BACKFILLED);
    } catch (Exception e) {
      System.err.println(
          "  [api] Warning: failed to backfill sail event helpers in "
              + project
              + ": "
              + e.getMessage());
    }
  }

  /**
   * Records the launched execution as a run stamped with this box's handle as its {@code node}, so
   * the provenance guard can serve local runs and refuse foreign ones, and prunes the container's
   * oldest run-log directories. Never fatal: a bookkeeping failure only forfeits the run's
   * metadata, never the launch. A run store is absent only on boxes that keep no run aggregate.
   */
  private void recordRun(
      String runId,
      String project,
      String specId,
      String node,
      String agentType,
      String branch,
      String task,
      String logPath) {
    if (runStore == null) {
      return;
    }
    try {
      runStore.create(
          runId, project, specId, node, "build", agentType, branch, task, null, null, logPath);
      var ids = runStore.listForProject(project).stream().map(RunStore.RunRow::id).toList();
      var pruned = RunRetention.prune(shell, project, ids, RunRetention.DEFAULT_KEEP);
      listener.runsPruned(pruned.size());
    } catch (Exception e) {
      System.err.println("  [api] Warning: could not record run " + runId + ": " + e.getMessage());
    }
  }

  /** Stamps the agent + watcher pids on a background run once launch has resolved them. */
  private void updateRunProcess(
      String runId, AgentSession.SessionInfo status, Optional<WatcherSpawner.Spawned> watcher) {
    Integer watcherPid =
        watcher.orElse(null) instanceof WatcherSpawner.Fallback fallback
            ? (int) fallback.pid()
            : null;
    runBookkeeping(
        "update run process " + runId,
        () -> runStore.updateProcess(runId, status != null ? status.pid() : null, watcherPid));
  }

  /**
   * Completes a foreground run explicitly: its launch command blocks until the agent exits, so the
   * exit code is known here and the run must not be left {@code running} waiting for a terminal
   * hook event that may never arrive.
   */
  private void completeForegroundRun(String runId, int exitCode) {
    runBookkeeping(
        "complete run " + runId,
        () -> runStore.complete(runId, exitCode == 0 ? "completed" : "failed", exitCode));
  }

  /**
   * Marks a run failed when its launch throws, so a created-but-never-launched row is not orphaned.
   */
  private void failRun(String runId) {
    runBookkeeping("mark run failed " + runId, () -> runStore.complete(runId, "failed", null));
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
      Optional<WatcherSpawner.Spawned> watcher) {
    var data = new LinkedHashMap<String, Object>();
    if (pid != null) {
      data.put("pid", pid);
    }
    if (Strings.isNotBlank(runId)) {
      data.put(Event.WellKnownData.RUN_ID, runId);
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

  private void publish(String project, String specId, String type, Map<String, Object> data) {
    events.publish(Event.of(project, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }

  private AgentSession.SessionInfo querySession(AgentSession session, String project) {
    try {
      return session.queryStatus(project);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STATUS_FAILED, "Failed to query agent status.", e);
    }
  }

  private static boolean shouldSnapshot(SnapshotManager snapMgr, String project) {
    try {
      var snapshots = snapMgr.list(project);
      if (snapshots.isEmpty()) {
        return true;
      }
      var latestTime = OffsetDateTime.parse(snapshots.getLast().createdAt()).toInstant();
      return DateTimeUtils.now().isAfter(latestTime.plus(SNAPSHOT_INTERVAL));
    } catch (Exception e) {
      System.err.println(
          "  [snapshot] Could not determine snapshot age for '"
              + project
              + "', taking one to be safe: "
              + e.getMessage());
      return true;
    }
  }

  private static ApiException refusal(DispatchDecision.Refused refused) {
    return new ApiException(refused.code(), refused.message(), refused.fix());
  }

  private ShellExec.Result exec(List<String> command) {
    try {
      return shell.exec(command);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed.", e);
    }
  }
}
