/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
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

  private static final Duration INVITE_SNAPSHOT_TIMEOUT = Duration.ofHours(1);

  /** One dispatch invocation, lane-agnostic. */
  public record Request(
      String specId, String mode, boolean dryRun, List<String> repos, boolean restart) {}

  /**
   * One ad-hoc session launch — {@code sail agent run --task}. No spec, no policy: the operator
   * supplies the task directly, and the session reserves the whole container. {@code branch} is the
   * already-checked-out work branch (or null), {@code path} an optional workspace subdirectory.
   */
  public record AdhocRequest(
      String task, String branch, String path, boolean background, boolean dryRun) {}

  /**
   * A launched ad-hoc session: its minted run id and, live launches only, the probed session,
   * foreground exit code, and watcher. A dry run carries the run id it would have used and nothing
   * else.
   */
  public record AdhocSession(
      String runId,
      AgentSession.SessionInfo session,
      Integer exitCode,
      Optional<WatcherSpawner.Spawned> watcher) {}

  /**
   * One accepted invite: the run it reserved, the principal it posts under, its mode, and — full
   * mode only — the label of the pre-launch snapshot ({@code ""} for read only). {@code completion}
   * runs the deferred work — the snapshot (full) and the launch — off the request thread, so the
   * caller returns immediately (a dir-backend snapshot is a slow full copy that would blow the
   * client timeout and, if the request were force-killed mid-copy, leave the container in Error).
   * The reservation is already held when this record exists; {@code completion} releases it and
   * publishes a failure event if the snapshot or launch fails.
   */
  public record InviteLaunch(
      String runId, String principal, boolean full, String snapshot, Runnable completion) {}

  /**
   * Container preparation that must not run until the whole-container reservation is won — the
   * pre-launch snapshot and the work-branch checkout. A refused reservation means another agent
   * owns the container, so no workspace mutation may precede it; a preparation failure is a launch
   * failure and releases the reservation through the same path.
   */
  @FunctionalInterface
  public interface AdhocPreparer {
    AdhocPreparer NONE = () -> {};

    void prepare() throws Exception;
  }

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

    default void sailSetupUpdated(boolean updated) {}
  }

  private final ShellExec shell;
  private final String file;
  private final ProjectLoader projects;
  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final RunStore runStore;
  private final LaunchAdmission admission;
  private final MembershipService membership;
  private final RoomCommitGuard roomCommitGuard;
  private final RunLauncher runLauncher;
  private final RunReservation runReservation;
  private final AdhocRunner adhocRunner;
  private final RoomWakeLauncher roomWakeLauncher;
  private final InviteLauncher inviteLauncher;
  private final BuildDispatch buildDispatch;
  private MessageStore messageStore;
  private RoomStore roomStore;
  private final SessionYield sessionYield;
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
   * passing {@code null} is an explicit decision, not a default. {@code sessionYield} is never
   * null: every production lane passes the pty host seam, so no launch path can silently skip
   * yielding a resumed conversation; a lane with no host passes {@link SessionYield#NONE}.
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
      Listener listener,
      SessionYield sessionYield) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.file = Objects.requireNonNull(file, "file");
    this.projects = new ProjectLoader(shell, file);
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.runStore = runStore;
    this.admission = new LaunchAdmission(shell, fdeStore);
    this.events = Objects.requireNonNull(events, "events");
    this.watcherSpawner = Objects.requireNonNull(watcherSpawner, "watcherSpawner");
    this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.sessionYield = Objects.requireNonNull(sessionYield, "sessionYield");
    this.membership =
        new MembershipService(specStore, () -> roomStore, projects, admission, this.events, shell);
    this.roomCommitGuard = new RoomCommitGuard(runStore, projects, this.events, shell);
    this.runLauncher =
        new RunLauncher(shell, file, launcher, listener, watcherSpawner, runStore, this.events);
    this.runReservation = new RunReservation(runStore, shell, listener, sessionYield);
    this.adhocRunner = new AdhocRunner(projects, runLauncher, runReservation, runStore, listener);
    this.roomWakeLauncher =
        new RoomWakeLauncher(
            projects,
            specStore,
            () -> roomStore,
            () -> messageStore,
            runStore,
            runReservation,
            runLauncher,
            roomCommitGuard);
    this.inviteLauncher =
        new InviteLauncher(
            specStore,
            projects,
            () -> messageStore,
            runStore,
            admission,
            runReservation,
            runLauncher,
            this.events,
            shell);
    this.buildDispatch =
        new BuildDispatch(
            projects,
            specStore,
            reviewStore,
            runStore,
            () -> messageStore,
            admission,
            runReservation,
            runLauncher,
            snapshotter,
            listener,
            this.events,
            shell);
  }

  public DispatchOperations useMessages(MessageStore messages) {
    this.messageStore = Objects.requireNonNull(messages, "messages");
    return this;
  }

  /** Wires the room store — the authoritative home of membership state; returns {@code this}. */
  public DispatchOperations useRooms(RoomStore rooms) {
    this.roomStore = Objects.requireNonNull(rooms, "rooms");
    return this;
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
    return buildDispatch.dispatch(project, request, actor, localHandle);
  }

  /**
   * Launches one ad-hoc agent session — the {@code sail agent run --task} lane — as a first-class
   * run: a minted run id, {@code role='adhoc'} with no spec, a run-scoped unit and file set, and a
   * whole-container reservation through the same {@link RunStore#reserveDispatch} transaction that
   * gates dispatches, so an ad-hoc session and a dispatched agent are mutually exclusive by the one
   * atomic mechanism. Background launches get the same run-addressed guardrail watcher as
   * dispatches. No spec means no policy: unlike dispatch, a blank node handle is allowed — the
   * reservation is stamped with whatever identity the box has, exactly like the run rows it gates
   * against. The {@code preparer} runs strictly after the reservation is won and before anything is
   * staged or launched, so a refused launch leaves the workspace untouched. A dry run mints the id
   * and announces the launch command but reserves, prepares, writes, and executes nothing.
   */
  public AdhocSession startAdhoc(String project, AdhocRequest request, String localHandle) {
    return startAdhoc(project, request, localHandle, AdhocPreparer.NONE);
  }

  public AdhocSession startAdhoc(
      String project, AdhocRequest request, String localHandle, AdhocPreparer preparer) {
    return adhocRunner.start(project, request, localHandle, preparer);
  }

  /**
   * Launches a room wake — the chat lane: a real run minted through the same reservation machinery
   * as dispatch, role {@code room} with an empty repo set (the gate serializes it only against runs
   * of its own spec), principal {@code <agent>/room-<runId>}, run credential, watcher, and
   * guardrail ceiling. The spec is never claimed, no branch is checked out, no snapshot is taken —
   * a chat owns no working tree, and the launch is harness-restricted, never full-permission
   * (Claude only; an agent without an enforceable read-only session declines here, before anything
   * is reserved). When the spec's latest run on this node recorded a resumable session for the
   * chosen agent, the launch resumes that conversation with the wake prompt as its next turn;
   * otherwise a fresh session is primed like dispatch (spec body + room tail). The prompt's
   * rendered messages seed the run's delivery ledger, and each target repo's launch state is
   * recorded host-side so {@link #guardRoomRun} can tell whether the chat somehow changed a tree.
   * Returns the run id.
   */
  public String startRoomRun(String project, String specId, String localHandle) {
    return roomWakeLauncher.wake(project, specId, localHandle);
  }

  /**
   * Launches an invited agent into {@code specId}'s room — the explicit lane beside the wake lane's
   * automatic one: a human chose the agent, the model, and the mode, so the human's choice decides
   * the contract. Read only is the room lane verbatim under a new role ({@code invite}): viewer
   * credential, harness tool cut, no repo reservation (the gate lets it run alongside anything, its
   * own spec's live build included), worktree-digest guard. Full ({@code invite-full}) is the
   * member credential a dispatched agent holds, bought with two structural payments: the repo
   * reservation (reserved like a build, so one writer per repo always holds — a held reservation
   * refuses the invite with the same vocabulary as a dispatch conflict) and a mandatory pre-launch
   * snapshot labeled {@code invite-<runId>}, published into the room as {@code snapshot_created}; a
   * failed snapshot aborts the launch loudly. Neither mode claims the spec, checks out a branch, or
   * triggers the review pipeline on stop — the review loop stays anchored to dispatch. Inviting
   * requires the same tier as dispatching on the spec, checked via {@link DispatchPolicy}. Always a
   * fresh session: the point of an invite is a new participant.
   */
  public InviteLaunch startInvite(
      String specId,
      String agentYamlName,
      boolean full,
      String model,
      Actor actor,
      String localHandle) {
    return startInvite(specId, agentYamlName, full, true, model, actor, localHandle);
  }

  /**
   * As {@link #startInvite(String, String, boolean, String, Actor, String)}, but {@code
   * takeSnapshot} may waive the pre-launch snapshot on a full invite. Skipping trades the rollback
   * point for an instant launch — the escape hatch for the {@code dir} backend, where a snapshot is
   * a slow full filesystem copy. Read only never snapshots, so the flag is a no-op there.
   */
  public InviteLaunch startInvite(
      String specId,
      String agentYamlName,
      boolean full,
      boolean takeSnapshot,
      String model,
      Actor actor,
      String localHandle) {
    return inviteLauncher.start(
        specId, agentYamlName, full, takeSnapshot, model, actor, localHandle);
  }

  /**
   * Delegates to {@link EngagementService}, the launch-free room-engagement lane. Kept on the
   * dispatch surface so callers reach engagement and dispatch through one operations object.
   */
  public MembershipService.EngageLaunch engage(
      String specId,
      String agentYamlName,
      String mode,
      String model,
      boolean takeSnapshot,
      Actor actor,
      String localHandle) {
    return membership.engage(specId, agentYamlName, mode, model, takeSnapshot, actor, localHandle);
  }

  /** Delegates to {@link EngagementService}: dismisses the room's engaged agent. */
  public String disengage(String specId, Actor actor, String localHandle) {
    return membership.disengage(specId, actor, localHandle);
  }

  /** The members of {@code roomId}'s roster, room-first. */
  public List<ai.singlr.sail.config.Engagement> roomMembers(String roomId) {
    return membership.members(roomId);
  }

  /**
   * Delegates to {@link RoomCommitGuard}: the read-only room contract's backstop, run when a room
   * run stops. Kept on the dispatch surface so the server and the wake reactor reach it here.
   */
  public void guardRoomRun(String project, String runId) {
    roomCommitGuard.guardRoomRun(project, runId);
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
   * Whether a run may still own a live agent process, so its run-scoped files (the pid file the
   * stop's kill reads, the log an operator is following) must survive retention. A {@code stopping}
   * run is mid-stop, not terminal: pruning its directory would turn {@code killAgent} into a no-op
   * that can never verify, wedging the claim until the process exits on its own.
   */
  static boolean ownsLiveAgent(RunStore.RunRow run) {
    return "running".equals(run.status()) || StopOperations.STOPPING.equals(run.status());
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
}
