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
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WorkPrompt;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
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
  private final WorkBranch workBranch;

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
    this.workBranch = new WorkBranch(shell, listener);
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
   * target repo set (the binding concurrency gate) and, in the same transaction, claim the spec
   * {@code in_progress} with its resolved repos and branch persisted, then publish the lifecycle
   * events, snapshot and branch as configured, launch, and arm the guardrail watcher. Every refusal
   * fires before any mutation.
   */
  public DispatchOperations.Outcome dispatch(
      String project, DispatchOperations.Request request, String localHandle) {
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
    var resolution =
        resolveSpec(
            specs,
            request.specId(),
            request.restart(),
            localHandle,
            spec -> earlierReviewsInScope(spec.id()));
    if (resolution.spec() == null) {
      return new DispatchOperations.NoSpecs();
    }
    var nextSpec = resolution.spec();
    if (!earlierReviewsInScope(nextSpec.id())) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Dispatching spec '"
              + nextSpec.id()
              + "' would close a review outside your project scope.",
          "Ask an admin to dispatch it.");
    }

    var targetRepos = DispatchRepos.resolve(loaded.config(), nextSpec, request.repos());
    var taskSpec = DispatchRepos.withTargetRepos(nextSpec, targetRepos);
    var branch = BranchPolicy.branchName(loaded.config(), nextSpec);
    var specBody = specStore.getContent(nextSpec.id()).map(SpecStore.SpecContent::body).orElse("");
    var messages = messageStore.get();
    var room =
        messages == null
            ? List.<MessageStore.MessageRow>of()
            : messages.list(nextSpec.id(), null, 20);
    var agentType = taskSpec.agent() != null ? taskSpec.agent() : loaded.config().agent().type();
    if (Strings.isBlank(agentType)) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "Project '" + project + "' names no agent; set agent.type or the spec's agent.");
    }
    LaunchAdmission.resolveAgent(agentType);
    var built =
        WorkPrompt.build(
            taskSpec, specBody.isBlank() ? nextSpec.title() : specBody, room, List.of());
    var task = built.prompt();

    Runnable claim = () -> claim(resolution, taskSpec, branch);
    if (request.dryRun()) {
      requireNoRepoOverlap(project, localHandle, taskSpec.id(), taskSpec.repos());
      commit(claim);
      var prepared =
          prepare(
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
            taskSpec.repos(),
            agentType,
            branch,
            task,
            unit,
            loaded.config(),
            claim);
    try {
      seedRoomDelivery(runId, built.renderedMessages());
      var prepared =
          prepare(
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
      List<Spec> specs, String specId, boolean restart, String localHandle) {
    return resolveSpec(specs, specId, restart, localHandle, spec -> true);
  }

  /**
   * {@link #resolveSpec(List, String, boolean, String)}, picking the next ready spec among those
   * {@code dispatchable} admits when none is named; a named spec is resolved whatever it says.
   */
  static SpecResolution resolveSpec(
      List<Spec> specs,
      String specId,
      boolean restart,
      String localHandle,
      Predicate<Spec> dispatchable) {
    var spec = Strings.isBlank(specId) ? null : SpecCatalog.findById(specs, specId);
    if (spec == null) {
      if (RestartResolution.decide(specId, null, restart)
          instanceof RestartResolution.Refused refused) {
        throw refusal(refused);
      }
      var next = SpecCatalog.nextReadyAssignedTo(specs, localHandle, dispatchable);
      if (next == null) {
        return SpecResolution.none();
      }
      LaunchAdmission.requireAllowed(next, localHandle);
      return SpecResolution.of(next);
    }
    LaunchAdmission.requireAllowed(spec, localHandle);
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
   * Whether every open review of the spec's earlier attempts is filed inside the caller's scope. A
   * dispatch closes them all ({@link ReviewStore#supersedeForSpec}), or the loop would read the
   * last of them as this attempt's; so a named spec with one left in the project it moved from is
   * refused whole, naming none of it, and an unnamed pick passes such a spec over.
   */
  private boolean earlierReviewsInScope(String specId) {
    if (reviewStore == null) {
      return true;
    }
    var earlier =
        reviewStore.reviewsForSpec(specId).stream().filter(review -> !review.superseded()).toList();
    return Scoped.rows(Actor.reader(), earlier, reviewStore::projectOf).size() == earlier.size();
  }

  /**
   * The claim, identical on the dry and live lanes: reset a restarted spec, claim it {@code
   * in_progress} with its resolved repos and branch, and supersede stale reviews. On the live lane
   * it commits in the transaction that reserves the run ({@link #reserveRun}), so a claim the
   * journal refuses leaves no run, and two concurrent dispatches can never both mutate spec state.
   */
  private void claim(SpecResolution resolution, Spec taskSpec, String branch) {
    if (resolution.restarted()) {
      specStore.updateStatus(taskSpec.id(), SpecStatus.PENDING);
    }
    specStore.updateReposAndStatus(taskSpec.id(), taskSpec.repos(), SpecStatus.IN_PROGRESS, branch);
    if (reviewStore != null) {
      reviewStore.supersedeForSpec(taskSpec.id());
    }
  }

  /** Commits {@code claim} on its own, where no run is reserved with it: one transaction. */
  private void commit(Runnable claim) {
    specStore.atomically(
        () -> {
          claim.run();
          return null;
        });
  }

  /**
   * What follows a claim that landed, identical on the dry and live lanes: publish the lifecycle
   * events, snapshot as configured, and check out the work branch, racing no other dispatch's
   * checkouts in a shared repo.
   */
  private PreparedClaim prepare(
      String project,
      SailYaml config,
      List<SailYaml.Repo> targetRepos,
      SpecResolution resolution,
      Spec taskSpec,
      String branch,
      String task,
      String mode) {
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
        workBranch.checkout(project, config, targetRepos, branch, resolution.restarted());
    return new PreparedClaim(snapshot, branchCreated);
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
   * the target repo set, and commits {@code claim} with it, returning the run's bearer credential.
   * A run store is absent only on boxes that keep no run aggregate, which have nothing to reserve
   * against and claim on their own.
   */
  private String reserveRun(
      String runId,
      String project,
      String specId,
      String boxHandle,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config,
      Runnable claim) {
    if (runStore == null) {
      commit(claim);
      return null;
    }
    return runReservation.reserve(
        runId,
        project,
        specId,
        null,
        boxHandle,
        Lane.BUILD.wire(),
        repos,
        agentType,
        branch,
        task,
        unit,
        config,
        claim);
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

  private static ApiException refusal(DispatchDecision.Refused refused) {
    return new ApiException(refused.code(), refused.message(), refused.fix());
  }

  private static ApiException refusal(RestartResolution.Refused refused) {
    return new ApiException(refused.code(), refused.message(), refused.fix());
  }
}
