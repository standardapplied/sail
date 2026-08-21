/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.BranchPolicy;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecCatalog;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentTaskPrompt;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The build lane — {@code sail dispatch}: the spec → review → fix loop's entry point. Resolves the
 * spec (honoring {@code restart} and {@link DispatchPolicy}), atomically reserves the run against
 * its target repos through {@link RunReservation}, claims the spec {@code in_progress} with its
 * repos and branch, supersedes stale reviews, publishes the lifecycle events, snapshots and checks
 * out the work branch, and launches through {@link RunLauncher}. The one lane that mutates spec
 * state and a working tree; every other lane launches around it. Dry and live share the
 * claim/branch phase, so a preview reports exactly what a live dispatch would do.
 */
public final class BuildDispatch {

  private final ProjectLoader projects;
  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final RunStore runStore;
  private final Supplier<MessageStore> messageStore;
  private final LaunchAdmission admission;
  private final RunReservation runReservation;
  private final RunLauncher runLauncher;
  private final DispatchOperations.Snapshotter snapshotter;
  private final DispatchOperations.Listener listener;
  private final DispatchOperations.EventSink events;
  private final ShellExec shell;

  public BuildDispatch(
      ProjectLoader projects,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      Supplier<MessageStore> messageStore,
      LaunchAdmission admission,
      RunReservation runReservation,
      RunLauncher runLauncher,
      DispatchOperations.Snapshotter snapshotter,
      DispatchOperations.Listener listener,
      DispatchOperations.EventSink events,
      ShellExec shell) {
    this.projects = projects;
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.runStore = runStore;
    this.messageStore = messageStore;
    this.admission = admission;
    this.runReservation = runReservation;
    this.runLauncher = runLauncher;
    this.snapshotter = snapshotter;
    this.listener = listener;
    this.events = events;
    this.shell = shell;
  }

  /** Outcome of {@link #resolveSpec}: the chosen spec, and whether {@code restart} reset it. */
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

  /** What the claim/branch phase produced: the snapshot label and whether a branch was set up. */
  private record PreparedClaim(String snapshot, boolean branchCreated) {}

  /**
   * Executes one dispatch: resolve the spec (honoring {@code restart}), enforce {@link
   * DispatchPolicy}, refuse while an ad-hoc agent is live, atomically reserve the run against the
   * target repo set (the binding concurrency gate), then claim the spec {@code in_progress} with
   * its resolved repos and branch persisted, publish the lifecycle events, snapshot and branch as
   * configured, launch, and arm the guardrail watcher. Every refusal fires before any mutation.
   */
  public DispatchOperations.Outcome dispatch(
      String project, DispatchOperations.Request request, Actor actor, String localHandle) {
    var loaded = projects.loadRunning(project);
    if (!request.mode().equals("background") && !request.mode().equals("foreground")) {
      throw new ApiException(
          ErrorCode.INVALID_MODE, "Dispatch mode must be background or foreground.");
    }
    if (Strings.isBlank(localHandle)) {
      throw refusal(DispatchPolicy.nodeHandleUnset());
    }
    admission.requireTrustedRoster(localHandle);
    if (loaded.config().agent() == null) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED, "No agent configured in sail.yaml's agent block.");
    }

    var specs = specStore.projectSpecs(project);
    var resolution = resolveSpec(specs, request.specId(), request.restart(), actor, localHandle);
    if (resolution.spec() == null) {
      return new DispatchOperations.NoSpecs();
    }
    var nextSpec = resolution.spec();

    var targetRepos = DispatchRepos.resolve(loaded.config(), nextSpec, request.repos());
    var taskSpec = DispatchRepos.withTargetRepos(nextSpec, targetRepos);
    var branch = BranchPolicy.branchName(loaded.config(), nextSpec);
    var specBody = specStore.getContent(nextSpec.id()).map(SpecStore.SpecContent::body).orElse("");
    var messages = messageStore.get();
    var room =
        messages == null
            ? List.<MessageStore.MessageRow>of()
            : messages.list(nextSpec.id(), null, 20);
    var built =
        AgentTaskPrompt.build(taskSpec, specBody.isBlank() ? nextSpec.title() : specBody, room);
    var task = built.prompt();
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
      return new DispatchOperations.Dispatched(
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
    var credential =
        reserveRun(
            runId,
            project,
            nextSpec.id(),
            localHandle,
            dispatchOwner(localHandle),
            taskSpec.repos(),
            agentType,
            branch,
            task,
            unit,
            loaded.config());
    try {
      seedRoomDelivery(runId, built.renderedMessages());
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
          runLauncher.launchAgent(
              project,
              loaded.config(),
              targetRepos,
              task,
              branch,
              background,
              taskSpec,
              agentType,
              unit,
              runId,
              credential);
      var status =
          runLauncher.finishLaunch(
              new RunLauncher.RunContext(
                  project, unit, runId, nextSpec.id(), agentType, Lane.BUILD.wire(), background),
              launch);
      return new DispatchOperations.Dispatched(
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
      runReservation.releaseIfAbsent(runId, project, unit);
      throw e;
    }
  }

  /**
   * Picks the spec to dispatch. Without {@code specId}, the next ready spec assigned to this box's
   * FDE (or none) — unless {@code restart} is set, which {@link RestartResolution} refuses because
   * a restart must name its target. With {@code specId}, the spec must exist and pass {@link
   * DispatchPolicy}; {@link RestartResolution} then decides how its status is treated. Pure: every
   * refusal fires before any mutation, so a refused caller can never reset a status.
   */
  static SpecResolution resolveSpec(
      List<Spec> specs, String specId, boolean restart, Actor actor, String localHandle) {
    var spec = Strings.isBlank(specId) ? null : SpecCatalog.findById(specs, specId);
    if (spec == null) {
      if (RestartResolution.decide(specId, null, restart)
          instanceof RestartResolution.Refused refused) {
        throw refusal(refused);
      }
      var next = SpecCatalog.nextReadyAssignedTo(specs, localHandle);
      if (next == null) {
        return SpecResolution.none();
      }
      LaunchAdmission.requireAllowed(actor, next, localHandle);
      return SpecResolution.of(next);
    }
    LaunchAdmission.requireAllowed(actor, spec, localHandle);
    return switch (RestartResolution.decide(specId, spec, restart)) {
      case RestartResolution.Refused refused -> throw refusal(refused);
      case RestartResolution.NotRestarted ignored -> {
        if (!SpecCatalog.isReady(specs, spec)) {
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
   * Refuses the dry-run dispatch when a local run of this project is still {@code running} with a
   * reserved repo set intersecting the target's — the {@link DispatchGate} decision over run rows
   * only. This read-only check serves the dry lane, which must not reserve; a live dispatch is
   * gated atomically inside {@link #reserveRun} instead.
   */
  private void requireNoRepoOverlap(
      String project, String localHandle, String specId, List<String> targetRepos) {
    DispatchGate.decide(
            specId, Lane.BUILD.wire(), targetRepos, runningLocalRuns(project, localHandle))
        .ifPresent(
            conflict -> {
              throw RunReservation.overlapRefusal(conflict);
            });
  }

  /**
   * The project's runs this box is executing right now, each with the repo set its dispatch
   * reserved. Uses the same {@link DispatchOperations#ownsLiveAgent} reading as the live
   * reservation, so the dry lane counts a mid-stop run as occupying its repos. A box that keeps no
   * run aggregate has nothing to consult and allows the dispatch.
   */
  private List<DispatchGate.RunningRun> runningLocalRuns(String project, String localHandle) {
    if (runStore == null) {
      return List.of();
    }
    return runStore.listForProject(project).stream()
        .filter(DispatchOperations::ownsLiveAgent)
        .filter(run -> run.ownedBy(localHandle))
        .map(run -> new DispatchGate.RunningRun(run.id(), run.specId(), run.role(), run.repos()))
        .toList();
  }

  /**
   * Atomically reserves the dispatch as a {@code running} run stamped with this box's handle and
   * the target repo set, returning the run's bearer credential. A run store is absent only on boxes
   * that keep no run aggregate, which have nothing to reserve against.
   */
  private String reserveRun(
      String runId,
      String project,
      String specId,
      String node,
      String owner,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    if (runStore == null) {
      return null;
    }
    return runReservation.reserve(
        runId,
        project,
        specId,
        node,
        owner,
        Lane.BUILD.wire(),
        repos,
        agentType,
        branch,
        task,
        unit,
        config);
  }

  /**
   * The FDE a dispatched run acts for: the box's handle, which {@link DispatchPolicy} has already
   * matched to the spec's assignee. An admin dispatching on another FDE's box initiates the run but
   * never becomes its authorization owner.
   */
  private static String dispatchOwner(String localHandle) {
    return localHandle;
  }

  private void seedRoomDelivery(String runId, List<MessageStore.MessageRow> rendered) {
    if (runStore == null || rendered.isEmpty()) {
      return;
    }
    runStore.markDelivered(runId, rendered.stream().map(MessageStore.MessageRow::id).toList());
  }

  private void publish(String project, String specId, String type, Map<String, Object> data) {
    events.publish(Event.of(project, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }

  private ShellExec.Result exec(List<String> command) {
    try {
      return shell.exec(command);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed.", e);
    }
  }

  private static ApiException refusal(DispatchDecision.Refused refused) {
    return new ApiException(refused.code(), refused.message(), refused.fix());
  }

  private static ApiException refusal(RestartResolution.Refused refused) {
    return new ApiException(refused.code(), refused.message(), refused.fix());
  }
}
