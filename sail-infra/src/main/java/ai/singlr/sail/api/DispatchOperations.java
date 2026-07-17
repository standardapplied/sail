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
import ai.singlr.sail.store.DispatchGate;
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
import java.util.stream.Collectors;

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

  /** One dispatch invocation, lane-agnostic. */
  public record Request(
      String specId, String mode, boolean dryRun, List<String> repos, boolean restart) {}

  /** What a dispatch produced. */
  public sealed interface Outcome permits NoSpecs, Dispatched {}

  /** The project has no ready spec assigned to this box. */
  public record NoSpecs() implements Outcome {}

  /**
   * A completed dispatch. {@code restarted} reports whether this dispatch was a re-dispatch that
   * reset a non-pending spec, so callers can message it as an iteration. {@code session}, {@code
   * exitCode} (foreground only), {@code runId} and {@code watcher} are absent on a dry run, which
   * stops after the claim/branch phase.
   */
  public record Dispatched(
      Spec taskSpec,
      String branch,
      String agentType,
      String task,
      String runId,
      String snapshotLabel,
      boolean branchCreated,
      boolean restarted,
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
   * DispatchPolicy}, refuse while an ad-hoc agent is live, atomically reserve the run against the
   * target repo set ({@link #reserveRun} — the binding concurrency gate), then claim the spec
   * {@code in_progress} with its resolved repos and branch persisted, publish the lifecycle events,
   * snapshot and branch as configured, launch, and arm the guardrail watcher. Every refusal fires
   * before any mutation; a failure after the reservation marks the run failed so the reservation is
   * released. Throws {@link ApiException} with a structured code on every refusal or failure.
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

    var specs = specStore.projectSpecs(project);
    var resolution = resolveSpec(specs, request.specId(), request.restart(), actor, localHandle);
    if (resolution.spec() == null) {
      return new NoSpecs();
    }
    var nextSpec = resolution.spec();

    var targetRepos = DispatchRepos.resolve(loaded.config(), nextSpec, request.repos());
    var taskSpec = DispatchRepos.withTargetRepos(nextSpec, targetRepos);
    var branch = BranchPolicy.branchName(loaded.config(), nextSpec);
    var specBody = specStore.getContent(nextSpec.id()).map(SpecStore.SpecContent::body).orElse("");
    var task = AgentTaskPrompt.build(taskSpec, specBody.isBlank() ? nextSpec.title() : specBody);
    var agentType = taskSpec.agent() != null ? taskSpec.agent() : loaded.config().agent().type();

    if (request.dryRun()) {
      requireNoRepoOverlap(project, localHandle, taskSpec.id(), taskSpec.repos());
      var prepared =
          claimAndPrepare(
              project,
              loaded.config(),
              targetRepos,
              resolution,
              taskSpec,
              branch,
              task,
              request.mode());
      return new Dispatched(
          taskSpec,
          branch,
          agentType,
          task,
          null,
          prepared.snapshot(),
          prepared.branchCreated(),
          resolution.restarted(),
          null,
          null,
          Optional.empty());
    }

    var background = request.mode().equals("background");
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(runId);
    reserveRun(
        runId,
        project,
        nextSpec.id(),
        localHandle,
        taskSpec.repos(),
        agentType,
        branch,
        task,
        unit,
        background);
    try {
      var prepared =
          claimAndPrepare(
              project,
              loaded.config(),
              targetRepos,
              resolution,
              taskSpec,
              branch,
              task,
              request.mode());
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
              unit,
              runId);
      var status = querySession(new AgentSession(shell), project, unit);
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
          prepared.snapshot(),
          prepared.branchCreated(),
          resolution.restarted(),
          status,
          background ? null : launch.exitCode(),
          launch.watcher());
    } catch (RuntimeException e) {
      releaseIfAbsent(runId, project, unit, background);
      throw e;
    }
  }

  /** What the claim/branch phase produced: the snapshot label and whether a branch was set up. */
  private record PreparedClaim(String snapshot, boolean branchCreated) {}

  /**
   * The claim/branch phase, identical on the dry and live lanes: reset a restarted spec, claim it
   * {@code in_progress} with its resolved repos and branch, supersede stale reviews, publish the
   * lifecycle events, snapshot as configured, and check out the work branch. On the live lane this
   * runs only after {@link #reserveRun} has won the repo reservation, so two concurrent dispatches
   * can never both mutate spec state or race their checkouts in a shared repo.
   */
  private PreparedClaim claimAndPrepare(
      String project,
      SailYaml config,
      List<SailYaml.Repo> targetRepos,
      SpecResolution resolution,
      Spec taskSpec,
      String branch,
      String task,
      String mode) {
    if (resolution.restarted()) {
      specStore.updateStatus(taskSpec.id(), SpecStatus.PENDING);
    }
    specStore.updateReposAndStatus(taskSpec.id(), taskSpec.repos(), SpecStatus.IN_PROGRESS, branch);
    if (reviewStore != null) {
      reviewStore.supersedeForSpec(taskSpec.id());
    }
    listener.claimed(taskSpec, task);
    if (resolution.restarted()) {
      publish(
          project,
          taskSpec.id(),
          Event.WellKnownTypes.SPEC_RESTARTED,
          Map.of("note", "restarted from " + resolution.previousStatus()));
    }
    publish(
        project,
        taskSpec.id(),
        Event.WellKnownTypes.SPEC_DISPATCHED,
        DispatchEvents.dispatchedData(branch, mode));
    var snapshot = snapshotter.snapshot(project, config);
    if (!snapshot.isEmpty()) {
      publish(project, null, Event.WellKnownTypes.SNAPSHOT_CREATED, Map.of("label", snapshot));
    }
    var branchCreated =
        checkoutBranch(project, config, targetRepos, branch, resolution.restarted());
    return new PreparedClaim(snapshot, branchCreated);
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
   * FDE (or none) — unless {@code restart} is set, which {@link RestartResolution} refuses because
   * a restart must name its target. With {@code specId}, the spec must exist and pass {@link
   * DispatchPolicy}; {@link RestartResolution} then decides how its status is treated: pending
   * dispatches normally with its dependencies met, and a non-pending status is either refused or —
   * on {@code restart} — reported as restarted so the caller resets it and publishes {@code
   * spec_restarted}. Pure: every refusal here (policy, readiness, the caller's later repo-overlap
   * gate) fires before any mutation, so a refused caller can never reset a status.
   */
  static SpecResolution resolveSpec(
      List<Spec> specs, String specId, boolean restart, Actor actor, String localHandle) {
    var spec = Strings.isBlank(specId) ? null : SpecDirectory.findById(specs, specId);
    if (spec == null) {
      if (RestartResolution.decide(specId, null, restart)
          instanceof RestartResolution.Refused refused) {
        throw refusal(refused);
      }
      var next = SpecDirectory.nextReadyAssignedTo(specs, localHandle);
      if (next == null) {
        return SpecResolution.none();
      }
      requireAllowed(actor, next, localHandle);
      return SpecResolution.of(next);
    }
    requireAllowed(actor, spec, localHandle);
    return switch (RestartResolution.decide(specId, spec, restart)) {
      case RestartResolution.Refused refused -> throw refusal(refused);
      case RestartResolution.NotRestarted ignored -> {
        if (!SpecDirectory.isReady(specs, spec)) {
          throw new ApiException(
              ErrorCode.SPEC_NOT_READY,
              "Spec '" + specId + "' is not ready for dispatch.",
              "Resolve dependencies or choose a ready spec.");
        }
        yield SpecResolution.of(spec);
      }
      case RestartResolution.Restarted restarted ->
          SpecResolution.restarted(spec, restarted.previousStatus());
    };
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
      List<String> checkoutArgs;
      if (branchExists) {
        checkoutArgs = RestartResolution.branchCheckoutArgs(repoDir, branch, true, restarted);
      } else {
        var base = baseBranch(project, repoDir, repo);
        checkoutArgs =
            RestartResolution.freshBranchArgs(
                repoDir, branch, base, fetchLatestBase(project, repoDir, base));
      }
      var result = exec(ContainerExec.asDevUser(project, checkoutArgs));
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
   * The mainline a fresh work branch forks from: the repo's configured branch when {@code
   * sail.yaml} pins one, else origin's default branch (the clone's {@code origin/HEAD}), else
   * blank. Deriving it from the repo config or the remote default — never the current {@code HEAD}
   * — keeps a new branch off the mainline even when a prior dispatch left the checkout on an
   * earlier work branch.
   */
  private String baseBranch(String project, String repoDir, SailYaml.Repo repo) {
    if (Strings.isNotBlank(repo.branch())) {
      return repo.branch();
    }
    var result =
        exec(
            ContainerExec.asDevUser(
                project,
                List.of("git", "-C", repoDir, "rev-parse", "--abbrev-ref", "origin/HEAD")));
    if (!result.ok()) {
      return "";
    }
    var ref = result.stdout().trim();
    return ref.startsWith("origin/") ? ref.substring("origin/".length()) : ref;
  }

  /**
   * Fetches {@code base} from origin so a fresh work branch forks from the current upstream tip
   * rather than a stale local checkout. Best-effort: returns true only when {@code origin/<base>}
   * is available to branch from afterwards, so an offline box, a repo with no {@code origin}, or a
   * detached base falls back to the local {@code HEAD} instead of failing the dispatch.
   */
  private boolean fetchLatestBase(String project, String repoDir, String base) {
    if (Strings.isBlank(base) || "HEAD".equals(base)) {
      return false;
    }
    exec(
        ContainerExec.asDevUser(
            project, List.of("git", "-C", repoDir, "fetch", "--quiet", "origin", base)));
    return exec(ContainerExec.asDevUser(
            project,
            List.of(
                "git",
                "-C",
                repoDir,
                "rev-parse",
                "--verify",
                "--quiet",
                "refs/remotes/origin/" + base)))
        .ok();
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
      AgentUnit unit,
      String runId) {
    try {
      ensureSailSetup(project);
      var session = new AgentSession(shell);
      session.ensureDirectory(project);
      session.writeTaskFile(project, task, unit);
      session.writeSession(
          project,
          task,
          Objects.requireNonNullElse(branch, ""),
          spec.id(),
          agentType,
          runId,
          targetRepos.stream().map(SailYaml.Repo::path).toList(),
          unit);
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
                  unit.logPath(),
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
                  unit.logPath(),
                  runId);
      listener.launching(background, command);
      var exitCode = launcher.launch(command);
      if (background) {
        if (exitCode != 0) {
          throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.");
        }
        return new LaunchOutcome(exitCode, launchWatcherIfAgent(project, config, runId, unit));
      }
      return new LaunchOutcome(exitCode, Optional.empty());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.", e);
    }
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
   * Refuses the dry-run dispatch when a local run of this project is still {@code running} with a
   * reserved repo set intersecting the target's — the {@link DispatchGate} decision over run rows
   * only. This read-only check serves the dry lane, which must not reserve; a live dispatch is
   * gated atomically inside {@link #reserveRun} instead. A row whose agent already died without a
   * stop signal is healed by the missed-stop sweep within its interval, so a stale row can only
   * ever block briefly.
   */
  private void requireNoRepoOverlap(
      String project, String localHandle, String specId, List<String> targetRepos) {
    DispatchGate.decide(specId, targetRepos, runningLocalRuns(project, localHandle))
        .ifPresent(
            conflict -> {
              throw overlapRefusal(conflict);
            });
  }

  private static ApiException overlapRefusal(DispatchGate.Conflict conflict) {
    return new ApiException(
        ErrorCode.AGENT_ALREADY_RUNNING,
        "Agent run "
            + conflict.run().runId()
            + " is already working spec '"
            + conflict.run().specId()
            + "' in "
            + (conflict.overlap().isEmpty() ? "this container" : "repo(s) " + conflict.overlap())
            + ".",
        "Wait for it to finish or stop it, or dispatch a spec targeting disjoint repos.");
  }

  /**
   * The project's runs this box is executing right now, each with the repo set its dispatch
   * reserved. Uses the same {@link #ownsLiveAgent} reading as the live reservation, so the dry lane
   * counts a mid-stop ({@code stopping}) run as occupying its repos exactly like {@code
   * reserveDispatch} does. A box that keeps no run aggregate has nothing to consult and allows the
   * dispatch. A run recorded before repos were persisted reads as no repos, which the gate treats
   * as whole-container: refusing is the safe reading of a row it cannot scope, and it heals when
   * the run finishes.
   */
  private List<DispatchGate.RunningRun> runningLocalRuns(String project, String localHandle) {
    if (runStore == null) {
      return List.of();
    }
    return runStore.listForProject(project).stream()
        .filter(DispatchOperations::ownsLiveAgent)
        .filter(run -> SailOperations.ownsRun(run.node(), localHandle))
        .map(run -> new DispatchGate.RunningRun(run.id(), run.specId(), run.repos()))
        .toList();
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
   * Atomically reserves the dispatch as a {@code running} run stamped with this box's handle and
   * the target repo set: {@link RunStore#reserveDispatch} checks every running local run for a repo
   * overlap and inserts the row in one {@code BEGIN IMMEDIATE} transaction, so two concurrent
   * dispatches — even from separate processes — can never both claim the same repo. A conflict or a
   * store failure aborts the dispatch before any spec mutation or launch: the row is what every
   * later overlap check and provenance guard depends on, so proceeding without it is not safe. Also
   * prunes the container's oldest run-log directories (best-effort). A run store is absent only on
   * boxes that keep no run aggregate, which have nothing to reserve against.
   *
   * <p>A foreground dispatch records a blank unit: it runs as a plain child process and creates no
   * systemd unit, so the missed-stop reconciler must skip it (it skips blank-unit runs) rather than
   * probe a unit that never exists and falsely stop the still-running agent — which would release
   * its repo mid-run. The foreground run completes when its blocking launcher returns.
   */
  private void reserveRun(
      String runId,
      String project,
      String specId,
      String node,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      boolean background) {
    if (runStore == null) {
      return;
    }
    var recordedUnit = background ? unit.unitName() : "";
    Optional<DispatchGate.Conflict> conflict;
    try {
      conflict =
          runStore.reserveDispatch(
              runId,
              project,
              specId,
              node,
              repos,
              agentType,
              branch,
              task,
              unit.logPath(),
              recordedUnit);
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Failed to record the dispatch run.", e);
    }
    if (conflict.isPresent()) {
      throw overlapRefusal(conflict.get());
    }
    pruneRuns(project);
  }

  private void pruneRuns(String project) {
    try {
      var runs = runStore.listForProject(project);
      var ids = runs.stream().map(RunStore.RunRow::id).toList();
      var active =
          runs.stream()
              .filter(DispatchOperations::ownsLiveAgent)
              .map(RunStore.RunRow::id)
              .collect(Collectors.toUnmodifiableSet());
      var pruned = RunRetention.prune(shell, project, ids, active, RunRetention.DEFAULT_KEEP);
      listener.runsPruned(pruned.size());
    } catch (Exception e) {
      System.err.println("  [api] Warning: could not prune runs: " + e.getMessage());
    }
  }

  /**
   * Whether a run may still own a live agent process, so its run-scoped files (the pid file the
   * stop's kill reads, the log an operator is following) must survive retention. A {@code stopping}
   * run is mid-stop, not terminal: pruning its directory would turn {@code killAgent} into a no-op
   * that can never verify, wedging the claim until the process exits on its own.
   */
  static boolean ownsLiveAgent(RunStore.RunRow run) {
    return "running".equals(run.status()) || StopOperations.STOPPING.equals(run.status());
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
   * Marks a run failed when its launch throws, so a created-but-never-launched row is not orphaned.
   */
  private void failRun(String runId) {
    runBookkeeping("mark run failed " + runId, () -> runStore.complete(runId, "failed", null));
  }

  /**
   * Releases the run's repo reservation on a launch failure only when the agent is proven absent. A
   * failure before or during launch leaves no live unit, so the run is failed and its repo freed.
   * But once a background systemd unit has started, a later failure (watcher spawn, status probe)
   * leaves a live agent on its run-scoped unit; failing the run would free the repo under it and
   * admit an overlapping dispatch. An unprobeable unit is treated as live for the same reason — the
   * missed-stop reconciler releases a genuinely dead unit on its next pass. A foreground run
   * creates no unit, so a foreground launch failure always frees the reservation.
   */
  private void releaseIfAbsent(String runId, String project, AgentUnit unit, boolean background) {
    if (background && backgroundAgentLive(project, unit)) {
      return;
    }
    failRun(runId);
  }

  private boolean backgroundAgentLive(String project, AgentUnit unit) {
    try {
      var status = new AgentSession(shell).queryStatus(project, unit);
      return status != null && status.running();
    } catch (Exception e) {
      return true;
    }
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

  private AgentSession.SessionInfo querySession(
      AgentSession session, String project, AgentUnit unit) {
    try {
      return session.queryStatus(project, unit);
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

  private static ApiException refusal(RestartResolution.Refused refused) {
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
