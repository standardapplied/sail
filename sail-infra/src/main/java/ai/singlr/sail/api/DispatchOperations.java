/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.BranchPolicy;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecCatalog;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentTaskPrompt;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.InvitePrompt;
import ai.singlr.sail.engine.RoomWakePrompt;
import ai.singlr.sail.engine.RunRetention;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
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
  private MessageStore messageStore;
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

  public DispatchOperations useMessages(MessageStore messages) {
    this.messageStore = Objects.requireNonNull(messages, "messages");
    return this;
  }

  /**
   * Seeds the reserved run's delivery ledger with exactly the messages the task prompt rendered in
   * full — by identity, from the same in-memory snapshot the prompt was built from, never
   * re-queried and never range-matched. The prompt is the run's first delivery; anything the budget
   * truncated or omitted, and any message that syncs in at any point — regardless of how its id
   * sorts — has no ledger row and stays owed a full delivery through the relay or the stop gate.
   */
  private void seedRoomDelivery(String runId, List<MessageStore.MessageRow> rendered) {
    if (runStore == null || rendered.isEmpty()) {
      return;
    }
    runStore.markDelivered(runId, rendered.stream().map(MessageStore.MessageRow::id).toList());
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
    var room =
        messageStore == null
            ? List.<MessageStore.MessageRow>of()
            : messageStore.list(nextSpec.id(), null, 20);
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
              runId,
              credential);
      var status = querySession(new AgentSession(shell), project, unit);
      if (!updateRunProcess(runId, project, status, launch.watcher())) {
        throw launchLostToCancel(runId, project, unit);
      }
      if (!background) {
        completeForegroundRun(runId, launch.exitCode());
      }
      if (status != null && status.running()) {
        publishAgentSessionStarted(
            project, nextSpec.id(), agentType, status.pid(), runId, "build", launch.watcher());
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
      releaseIfAbsent(runId, project, unit);
      throw e;
    }
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
    var loaded = projects.loadRunning(project);
    var config = loaded.config();
    var agentType =
        config.agent() != null ? config.agent().type() : AgentCli.CLAUDE_CODE.yamlName();
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(runId);
    var background = request.background();
    var workDir = adhocWorkDir(config, request.path());
    var fullPermissions = adhocFullPermissions(config);
    if (request.dryRun()) {
      listener.launching(
          background,
          launchCommand(
              new LaunchSpec(
                  project,
                  config,
                  workDir,
                  fullPermissions,
                  null,
                  null,
                  "",
                  agentType,
                  request.task(),
                  request.branch(),
                  List.of(),
                  background,
                  unit,
                  runId,
                  null,
                  "adhoc",
                  null)));
      return new AdhocSession(runId, null, null, Optional.empty());
    }
    var credential =
        reserveAdhocRun(
            runId, project, localHandle, agentType, request.branch(), request.task(), unit, config);
    try {
      prepare(preparer);
      var launch =
          launchSession(
              new LaunchSpec(
                  project,
                  config,
                  workDir,
                  fullPermissions,
                  null,
                  null,
                  "",
                  agentType,
                  request.task(),
                  request.branch(),
                  List.of(),
                  background,
                  unit,
                  runId,
                  credential,
                  "adhoc",
                  null));
      var status = querySession(new AgentSession(shell), project, unit);
      if (!updateRunProcess(runId, project, status, launch.watcher())) {
        throw launchLostToCancel(runId, project, unit);
      }
      if (!background) {
        completeForegroundRun(runId, launch.exitCode());
      }
      if (status != null && status.running()) {
        publishAgentSessionStarted(
            project, null, agentType, status.pid(), runId, "adhoc", launch.watcher());
      }
      return new AdhocSession(
          runId, status, background ? null : launch.exitCode(), launch.watcher());
    } catch (RuntimeException e) {
      releaseIfAbsent(runId, project, unit);
      throw e;
    }
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
    var loaded = projects.loadRunning(project);
    var config = loaded.config();
    if (config.agent() == null) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED, "No agent configured in sail.yaml's agent block.");
    }
    if (runStore == null) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box keeps no run aggregate, so a room wake cannot be reserved or tracked.");
    }
    var spec =
        specStore
            .findById(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
    var engagement = Engagement.fromJson(spec.engagement());
    var agentType =
        engagement != null
            ? engagement.agent()
            : spec.agent() != null ? spec.agent() : config.agent().type();
    var full = engagement != null && engagement.full();
    if (!full && !AgentCli.fromYamlName(agentType).supportsRoomLane()) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED,
          "Room wake needs a harness-enforced read-only session, and "
              + agentType
              + " has none inside a sail container: its bubblewrap sandbox needs user namespaces,"
              + " which incus containers block, so its only working mode bypasses all"
              + " restrictions. Set the spec's agent to claude-code to chat in this room, or"
              + " answer from the room directly and dispatch when code should change.");
    }
    var body = specStore.getContent(specId).map(SpecStore.SpecContent::body).orElse("");
    var room =
        messageStore == null
            ? List.<MessageStore.MessageRow>of()
            : messageStore.list(specId, null, 20);
    var resumeSessionId =
        engagement != null
            ? engagedSessionId(specId, agentType, localHandle)
            : resumableSessionId(specId, agentType, localHandle);
    var built =
        RoomWakePrompt.build(
            spec, body.isBlank() ? spec.title() : body, room, resumeSessionId != null, engagement);
    var task = built.prompt();
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(runId);
    var role = full ? DispatchGate.ROOM_FULL_ROLE : DispatchGate.ROOM_ROLE;
    var targetRepos =
        full ? DispatchRepos.resolve(config, spec.toSpec(), List.of()) : List.<SailYaml.Repo>of();
    var repoPaths = targetRepos.stream().map(SailYaml.Repo::path).toList();
    var branch = full ? Objects.toString(spec.branch(), "") : "";
    RunStore.Reservation reservation;
    try {
      reservation =
          runStore.reserveDispatch(
              runId,
              project,
              specId,
              localHandle,
              Strings.isBlank(spec.assignee()) ? localHandle : spec.assignee(),
              role,
              repoPaths,
              agentType,
              Strings.isBlank(branch) ? null : branch,
              task,
              unit.logPath(),
              unit.unitName(),
              configuredMaxDuration(config));
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Failed to record the room wake run.", e);
    }
    if (reservation instanceof RunStore.Reservation.Conflicted conflicted) {
      throw overlapRefusal(conflicted.conflict());
    }
    if (reservation instanceof RunStore.Reservation.LeaseHeld held) {
      throw leaseRefusal(held);
    }
    var credential = ((RunStore.Reservation.Reserved) reservation).credential();
    try {
      seedRoomDelivery(runId, built.renderedMessages());
      if (!full) {
        captureRoomBaseline(project, config, runId);
      }
      var model =
          engagement != null && engagement.model() != null ? engagement.model() : spec.model();
      var workDir =
          full
              ? AgentSession.launchWorkDir(config.sshUser(), targetRepos)
              : "/home/" + config.sshUser() + "/workspace";
      var launch =
          launchSession(
              new LaunchSpec(
                  project,
                  config,
                  workDir,
                  full,
                  model,
                  spec.reasoningEffort(),
                  specId,
                  agentType,
                  task,
                  branch,
                  repoPaths,
                  true,
                  unit,
                  runId,
                  credential,
                  role,
                  resumeSessionId));
      var status = querySession(new AgentSession(shell), project, unit);
      if (!updateRunProcess(runId, project, status, launch.watcher())) {
        throw launchLostToCancel(runId, project, unit);
      }
      if (status != null && status.running()) {
        publishAgentSessionStarted(
            project, specId, agentType, status.pid(), runId, role, launch.watcher());
      }
      return runId;
    } catch (RuntimeException e) {
      releaseIfAbsent(runId, project, unit);
      throw e;
    }
  }

  /**
   * The engagement's own conversation: the newest chat-turn session of the engaged agent this box
   * can resume. A build or invite session never qualifies — a working lane's conversation must not
   * reopen under a chat turn's contract, and the engaged agent's memory is its chat turns.
   */
  private String engagedSessionId(String specId, String agentType, String localHandle) {
    return runStore.listForSpec(specId).stream()
        .filter(RunStore.RunRow::chatRole)
        .filter(run -> run.ownedBy(localHandle))
        .filter(run -> Strings.isNotBlank(run.sessionId()))
        .filter(run -> agentType.equals(run.agent()))
        .filter(run -> AgentCli.isSafeSessionId(run.sessionId()))
        .findFirst()
        .map(RunStore.RunRow::sessionId)
        .orElse(null);
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
    if (runStore == null) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box keeps no run aggregate, so an invite cannot be reserved or tracked.");
    }
    var spec =
        specStore
            .findById(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
    requireAllowed(actor, spec.toSpec(), localHandle);
    requireTrustedRoster(localHandle);
    var agentCli = inviteAgent(agentYamlName);
    var inviteModel = inviteModel(model);
    if (!full) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "Read-only invites are superseded by engagement: engage the agent in the room instead"
              + " (POST /v1/specs/{id}/engage, or 'sail spec engage').",
          "An engaged read-only agent stays in the room and answers every message — the one-shot"
              + " read-only invite offered strictly less.");
    }
    var project = spec.project();
    var loaded = projects.loadRunning(project);
    var config = loaded.config();
    if (config.agent() == null) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED, "No agent configured in sail.yaml's agent block.");
    }
    requireInstalled(agentCli, project);
    var body = specStore.getContent(specId).map(SpecStore.SpecContent::body).orElse("");
    var room =
        messageStore == null
            ? List.<MessageStore.MessageRow>of()
            : messageStore.list(specId, null, 20);
    var built = InvitePrompt.build(spec, body.isBlank() ? spec.title() : body, room);
    var task = built.prompt();
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(runId);
    var role = full ? DispatchGate.FULL_INVITE_ROLE : DispatchGate.READ_ONLY_INVITE_ROLE;
    var targetRepos =
        full ? DispatchRepos.resolve(config, spec.toSpec(), List.of()) : List.<SailYaml.Repo>of();
    var repoPaths = targetRepos.stream().map(SailYaml.Repo::path).toList();
    var branch = full ? Objects.toString(spec.branch(), "") : "";
    var owner = Strings.isBlank(spec.assignee()) ? localHandle : spec.assignee();
    var credential =
        reserve(
            runId,
            project,
            specId,
            localHandle,
            owner,
            role,
            repoPaths,
            agentCli.yamlName(),
            Strings.isBlank(branch) ? null : branch,
            task,
            unit,
            config);
    try {
      seedRoomDelivery(runId, built.renderedMessages());
    } catch (RuntimeException e) {
      releaseIfAbsent(runId, project, unit);
      throw e;
    }
    var principal = runStore.findById(runId).map(RunStore.RunRow::principal).orElse("");
    var snapshot = full && takeSnapshot ? "invite-" + runId : "";
    Runnable completion =
        () ->
            completeInvite(
                project,
                config,
                specId,
                runId,
                unit,
                agentCli,
                inviteModel,
                task,
                branch,
                targetRepos,
                repoPaths,
                credential,
                role,
                snapshot);
    return new InviteLaunch(runId, principal, full, snapshot, completion);
  }

  /**
   * The deferred half of an invite, run off the request thread: take the pre-launch snapshot (full
   * mode) or capture the room baseline (read only), then launch the session. The reservation is
   * already held. A snapshot or launch failure releases it and publishes {@code snapshot_created}
   * carrying an {@code error} — there is no caller left to throw to, so the room learns the invite
   * failed through the stream.
   */
  private void completeInvite(
      String project,
      SailYaml config,
      String specId,
      String runId,
      AgentUnit unit,
      AgentCli agentCli,
      String inviteModel,
      String task,
      String branch,
      List<SailYaml.Repo> targetRepos,
      List<String> repoPaths,
      String credential,
      String role,
      String snapshot) {
    try {
      if (!Strings.isBlank(snapshot)) {
        inviteSnapshot(project, specId, runId);
      }
      var workDir = AgentSession.launchWorkDir(config.sshUser(), targetRepos);
      var launch =
          launchSession(
              new LaunchSpec(
                  project,
                  config,
                  workDir,
                  true,
                  inviteModel,
                  null,
                  specId,
                  agentCli.yamlName(),
                  task,
                  branch,
                  repoPaths,
                  true,
                  unit,
                  runId,
                  credential,
                  role,
                  null));
      var status = querySession(new AgentSession(shell), project, unit);
      if (!updateRunProcess(runId, project, status, launch.watcher())) {
        throw launchLostToCancel(runId, project, unit);
      }
      if (status != null && status.running()) {
        publishAgentSessionStarted(
            project, specId, agentCli.yamlName(), status.pid(), runId, role, launch.watcher());
      }
    } catch (RuntimeException e) {
      releaseIfAbsent(runId, project, unit);
      publishInviteFailed(project, specId, runId, snapshot, e);
    }
  }

  private void publishInviteFailed(
      String project, String specId, String runId, String snapshot, RuntimeException e) {
    var data = new LinkedHashMap<String, Object>();
    data.put("label", snapshot);
    data.put(Event.WellKnownData.RUN_ID, runId);
    data.put("error", Objects.requireNonNullElse(e.getMessage(), e.toString()));
    publish(project, specId, Event.WellKnownTypes.SNAPSHOT_CREATED, data);
  }

  /**
   * Validates the invite's model exactly like a spec write, refusing shell-unsafe values as a
   * client error before any reservation or snapshot — the model rides the agent command through
   * {@code bash -l -c}, so only a single safe token may reach it.
   */
  private static String inviteModel(String model) {
    try {
      return Spec.validatedModel(model);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  /**
   * Refuses the invite before any reservation or snapshot when the chosen agent's binary is not on
   * the container's PATH — sail.yaml's agent block declares what a project apply installed, but the
   * container is the authority on what can actually launch.
   */
  private void requireInstalled(AgentCli agentCli, String project) {
    var found =
        exec(
            ContainerExec.asDevUser(
                project,
                List.of("bash", "-lc", "command -v -- \"$1\"", "bash", agentCli.binaryName())));
    if (!found.ok()) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED,
          "Agent '" + agentCli.yamlName() + "' is not installed in project '" + project + "'.",
          "Add "
              + agentCli.yamlName()
              + " to sail.yaml's agent.install list and run 'sail project apply'.");
    }
  }

  /** Resolves the invite's agent, refusing an unknown or missing name as a client error. */
  private static AgentCli inviteAgent(String agentYamlName) {
    if (Strings.isBlank(agentYamlName)) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "An invite must name the agent to launch.",
          "Pass agent: claude-code or codex.");
    }
    try {
      return AgentCli.fromYamlName(agentYamlName);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.BAD_REQUEST, e.getMessage());
    }
  }

  /** A prepared engagement: the snapshot label a full mode will pay, and the deferred half. */
  public record EngageLaunch(String agent, String mode, String snapshot, Runnable completion) {}

  /**
   * Engages an agent in {@code specId}'s room: records the engagement on the spec row (synced,
   * atomic — one JSON value) so the wake reactor answers every human message with a chat turn until
   * a human disengages. Mode {@code full} is the default; {@code read-only} is the explicit narrow
   * choice, offered only where the harness enforces it. A full engagement may take one engage-time
   * rollback snapshot (never per turn), but the default is none — on the {@code dir} backend a
   * snapshot is a slow full filesystem copy, so the rollback point is opt-in ({@code takeSnapshot})
   * and the per-turn repo reservation remains the standing guard. A requested snapshot runs off the
   * request thread ({@code completion}) because a {@code dir}-backend snapshot would blow the HTTP
   * timeout; the engagement is then persisted only after the snapshot succeeds — the payment
   * precedes the access — and a failure publishes {@code spec_engage_failed} into the room instead
   * of engaging. Requires the dispatch tier on the spec, exactly like an invite.
   */
  public EngageLaunch engage(
      String specId,
      String agentYamlName,
      String mode,
      String model,
      boolean takeSnapshot,
      Actor actor,
      String localHandle) {
    var spec =
        specStore
            .findById(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
    requireAllowed(actor, spec.toSpec(), localHandle);
    requireTrustedRoster(localHandle);
    var agentCli = inviteAgent(agentYamlName);
    Engagement engagement;
    try {
      engagement =
          Engagement.of(
              agentCli.yamlName(), mode, inviteModel(model), DateTimeUtils.now().toString());
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.BAD_REQUEST, e.getMessage());
    }
    if (!engagement.full() && !agentCli.supportsRoomLane()) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          agentCli.readOnlyInviteRefusal(),
          "Engage " + agentCli.yamlName() + " with full access instead.");
    }
    var project = spec.project();
    projects.loadRunning(project);
    requireInstalled(agentCli, project);
    if (!engagement.full() || !takeSnapshot) {
      persistEngagement(specId, engagement, actor);
      publishEngaged(project, specId, engagement, "");
      return new EngageLaunch(engagement.agent(), engagement.mode(), "", null);
    }
    var label = "engage-" + DateTimeUtils.newId();
    Runnable completion = () -> completeEngage(project, specId, engagement, label, actor);
    return new EngageLaunch(engagement.agent(), engagement.mode(), label, completion);
  }

  private void completeEngage(
      String project, String specId, Engagement engagement, String label, Actor actor) {
    try {
      try {
        new SnapshotManager(shell).create(project, label, INVITE_SNAPSHOT_TIMEOUT);
      } catch (Exception e) {
        throw new ApiException(
            ErrorCode.SNAPSHOT_FAILED,
            "Failed to create the engage snapshot, so the engagement does not take effect.",
            "Check the host's snapshot capacity (incus storage) and retry.",
            e);
      }
      publish(project, specId, Event.WellKnownTypes.SNAPSHOT_CREATED, Map.of("label", label));
      persistEngagement(specId, engagement, actor);
      publishEngaged(project, specId, engagement, label);
    } catch (RuntimeException e) {
      var data = new LinkedHashMap<String, Object>();
      data.put("agent", engagement.agent());
      data.put("label", label);
      data.put("error", Objects.requireNonNullElse(e.getMessage(), e.toString()));
      publish(project, specId, Event.WellKnownTypes.SPEC_ENGAGE_FAILED, data);
    }
  }

  /** Dismisses the room's engaged agent. Idempotent: dismissing an empty room is a no-op. */
  public String disengage(String specId, Actor actor, String localHandle) {
    var spec =
        specStore
            .findById(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
    requireAllowed(actor, spec.toSpec(), localHandle);
    var engagement = Engagement.fromJson(spec.engagement());
    if (engagement == null) {
      return null;
    }
    specStore.update(spec.withEngagement(null));
    publish(
        spec.project(),
        specId,
        Event.WellKnownTypes.SPEC_DISENGAGED,
        Map.of("agent", engagement.agent(), "mode", engagement.mode()));
    return engagement.agent();
  }

  private void persistEngagement(String specId, Engagement engagement, Actor actor) {
    var current =
        specStore
            .findById(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND,
                        "Spec '" + specId + "' vanished while engaging."));
    specStore.update(current.withEngagement(engagement.toJson()));
  }

  private void publishEngaged(String project, String specId, Engagement engagement, String label) {
    var data = new LinkedHashMap<String, Object>();
    data.put("agent", engagement.agent());
    data.put("mode", engagement.mode());
    if (!Strings.isBlank(label)) {
      data.put("label", label);
    }
    publish(project, specId, Event.WellKnownTypes.SPEC_ENGAGED, data);
  }

  /**
   * The full invite's mandatory pre-launch snapshot, labeled {@code invite-<runId>} so rollback is
   * one visible step. Published with the spec id — unlike the container-scoped dispatch snapshot —
   * so the {@code snapshot_created} event renders in the room the invite was made from. Failure
   * aborts the invite: the snapshot is the payment for full access, and a YOLO session with no
   * rollback point must not launch.
   */
  private String inviteSnapshot(String project, String specId, String runId) {
    var label = "invite-" + runId;
    try {
      new SnapshotManager(shell).create(project, label, INVITE_SNAPSHOT_TIMEOUT);
    } catch (Exception e) {
      throw new ApiException(
          ErrorCode.SNAPSHOT_FAILED,
          "Failed to create the pre-invite snapshot, so the invite does not launch.",
          "Check the host's snapshot capacity (incus storage) and retry.",
          e);
    }
    publish(
        project,
        specId,
        Event.WellKnownTypes.SNAPSHOT_CREATED,
        Map.of("label", label, Event.WellKnownData.RUN_ID, runId));
    return label;
  }

  /**
   * The spec's most recent conversation this box can actually resume: the latest run that recorded
   * a session id, restricted to runs executed on this node (conversation state lives on the box
   * that ran it; run rows and session ids replicate fleet-wide), to the same agent (a Claude
   * session cannot resume under Codex), and to ids safe to place in an argv — session ids are
   * hook-reported, replicated data.
   */
  private String resumableSessionId(String specId, String agentType, String localHandle) {
    return runStore.listForSpec(specId).stream()
        .filter(run -> run.ownedBy(localHandle))
        .filter(run -> Strings.isNotBlank(run.sessionId()))
        .filter(run -> agentType.equals(run.agent()))
        .filter(run -> AgentCli.isSafeSessionId(run.sessionId()))
        .findFirst()
        .map(RunStore.RunRow::sessionId)
        .orElse(null);
  }

  /**
   * Records each configured repo's launch state — HEAD and a content fingerprint of the worktree
   * (the tracked diff plus each untracked file's object hash, so editing an already-dirty file is
   * as visible as dirtying a clean one) — host-side in the run store before the chat launches, out
   * of the guarded agent's reach: a baseline the chat could edit or delete would gut the guard.
   * Best-effort bookkeeping: a failure degrades the commit guard, never the wake. A repo whose
   * worktree state cannot be read at launch records no fingerprint and is exempt from the dirty
   * check rather than misjudged by it.
   */
  private void captureRoomBaseline(String project, SailYaml config, String runId) {
    if (runStore == null) {
      return;
    }
    try {
      var baseline = new LinkedHashMap<String, Object>();
      for (var repo : config.repos()) {
        var repoDir = "/home/" + config.sshUser() + "/workspace/" + repo.path();
        var head =
            exec(
                ContainerExec.asDevUser(
                    project, List.of("git", "-C", repoDir, "rev-parse", "HEAD")));
        if (!head.ok() || head.stdout().isBlank()) {
          continue;
        }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("head", head.stdout().trim());
        var state = worktreeFingerprint(project, repoDir);
        if (state != null) {
          entry.put("state", state);
        }
        baseline.put(repo.path(), entry);
      }
      if (baseline.isEmpty()) {
        return;
      }
      runStore.saveRoomGuardBaseline(runId, YamlUtil.dumpJson(baseline));
    } catch (RuntimeException e) {
      System.err.println(
          "  [room-wake] Warning: could not record the guard baseline: " + e.getMessage());
    }
  }

  /**
   * A worktree content fingerprint that changes whenever any byte the room contract protects
   * changes: the tracked diff against HEAD (staged and unstaged, binary-safe) plus each untracked
   * file's path and object hash — so editing an already-modified file or an already-untracked file
   * is as visible as dirtying a clean one, which a bare {@code git status --porcelain} digest is
   * blind to. Null when the worktree cannot be read, which exempts the repo from the dirty check.
   */
  private String worktreeFingerprint(String project, String repoDir) {
    var script =
        """
        set -e
        git -C "$1" diff --binary HEAD --
        git -C "$1" ls-files --others --exclude-standard -z | while IFS= read -r -d '' path; do printf '%s\\0' "$path"; git -C "$1" hash-object -- "$path"; done
        """;
    var result =
        exec(ContainerExec.asDevUser(project, List.of("bash", "-c", script, "bash", repoDir)));
    return result.ok() ? digest(result.stdout()) : null;
  }

  /**
   * The read-only contract's backstop, run when a room run stops — defense in depth behind the
   * harness-restricted launch. Any repo whose HEAD moved or whose worktree content changed (the
   * {@link #worktreeFingerprint}, so an uncommitted edit is as loud as a commit) since the wake
   * launched — and that no working run whose execution overlapped the room run's interval reserves,
   * so a concurrent build's work is never misattributed even when that build finished before this
   * guard fired — is published as a loud {@code guardrail_triggered} event. Never a review: the
   * pipeline ignores {@code room} stops structurally, and this guard is how a worktree-writing chat
   * surfaces instead. The baseline lives host-side in the run store, where the guarded agent cannot
   * touch it, and is consumed on first read so a replayed stop checks nothing twice.
   */
  public void guardRoomRun(String project, String runId) {
    if (runStore == null) {
      return;
    }
    var recorded = runStore.consumeRoomGuardBaseline(runId).orElse(null);
    if (recorded == null || recorded.isBlank()) {
      return;
    }
    var run = runStore.findById(runId).orElse(null);
    var specId = run != null ? run.specId() : null;
    var baseline = YamlUtil.parseMap(recorded);
    var roomStarted = run != null ? parseInstant(run.startedAt()) : null;
    var roomNode = run != null ? run.node() : null;
    var guardAt = DateTimeUtils.now();
    var others =
        runStore.listForProject(project).stream()
            .filter(candidate -> !candidate.id().equals(runId))
            .filter(candidate -> !candidate.readOnlyLane())
            .filter(candidate -> sameNode(roomNode, candidate))
            .filter(candidate -> overlapsRoomInterval(candidate, roomStarted, guardAt))
            .toList();
    if (others.stream().anyMatch(candidate -> candidate.repos().isEmpty())) {
      return;
    }
    var reserved =
        others.stream()
            .flatMap(candidate -> candidate.repos().stream())
            .collect(Collectors.toSet());
    var moved = new ArrayList<String>();
    var config = projects.loadRunning(project).config();
    for (var entry : baseline.entrySet()) {
      var repo = entry.getKey();
      if (reserved.contains(repo)) {
        continue;
      }
      var violation = repoViolation(project, config, repo, entry.getValue());
      if (violation != null) {
        moved.add(violation);
      }
    }
    if (moved.isEmpty()) {
      return;
    }
    publish(
        project,
        specId,
        Event.WellKnownTypes.GUARDRAIL_TRIGGERED,
        Map.of(
            "reason",
            "room run "
                + runId
                + " modified "
                + String.join("; ", moved)
                + " — a chat session must never change code",
            "action",
            "recorded; the worktree keeps the changes — review them by hand"));
  }

  /**
   * Whether a run's execution interval could have overlapped the room run's — from the recorded
   * baseline capture at {@code roomStarted} to this guard check at {@code guardAt} — making it a
   * possible author of a repo change the guard observes. A run still live is always a candidate; a
   * completed run is one unless it finished before the room run started, so a build that committed
   * mid-chat and finished first still shields its repos. Unparseable timestamps count as
   * overlapping: the safe failure mode is a quieter guard, never a misattributed one.
   */
  /**
   * A candidate run can only have authored changes the local guard observes if it executed in the
   * same node's shared container. Run rows replicate fleet-wide, so {@code listForProject} returns
   * foreign-node runs too; a build in another node's separate container cannot touch this
   * workspace, and letting it match would silently shield repositories from the guard. When the
   * room run's node is unknown (its row vanished before the guard fired), scope nothing — the
   * conservative posture is a guard that runs, never one a foreign run can suppress.
   */
  private static boolean sameNode(String roomNode, RunStore.RunRow candidate) {
    return roomNode == null || roomNode.equals(candidate.node());
  }

  private static boolean overlapsRoomInterval(
      RunStore.RunRow candidate, Instant roomStarted, Instant guardAt) {
    var started = parseInstant(candidate.startedAt());
    if (started != null && started.isAfter(guardAt)) {
      return false;
    }
    if (ownsLiveAgent(candidate)) {
      return true;
    }
    var completed = parseInstant(candidate.completedAt());
    return roomStarted == null || completed == null || !completed.isBefore(roomStarted);
  }

  private static Instant parseInstant(String value) {
    if (Strings.isBlank(value)) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * One repo's verdict against its recorded baseline: the description of what changed, or null when
   * the repo is untouched or unreadable. A moved HEAD names the committed files; an unmoved HEAD
   * with a changed content fingerprint names the currently-dirty paths.
   */
  @SuppressWarnings("unchecked")
  private String repoViolation(String project, SailYaml config, String repo, Object recorded) {
    if (!(recorded instanceof Map<?, ?> state)) {
      return null;
    }
    var entry = (Map<String, Object>) state;
    var before = Objects.toString(entry.get("head"), "");
    var repoDir = "/home/" + config.sshUser() + "/workspace/" + repo;
    var current =
        exec(ContainerExec.asDevUser(project, List.of("git", "-C", repoDir, "rev-parse", "HEAD")));
    if (!current.ok() || current.stdout().isBlank()) {
      return null;
    }
    var head = current.stdout().trim();
    if (!head.equals(before)) {
      var files =
          exec(
              ContainerExec.asDevUser(
                  project,
                  List.of("git", "-C", repoDir, "diff", "--name-only", before, head, "--")));
      var fileList =
          files.ok() && !files.stdout().isBlank()
              ? String.join(", ", files.stdout().trim().split("\n"))
              : "unknown files";
      return repo + " (" + fileList + ")";
    }
    var recordedState = entry.get("state");
    if (recordedState == null) {
      return null;
    }
    var fingerprint = worktreeFingerprint(project, repoDir);
    if (fingerprint == null || fingerprint.equals(recordedState.toString())) {
      return null;
    }
    var status =
        exec(
            ContainerExec.asDevUser(
                project, List.of("git", "-C", repoDir, "status", "--porcelain")));
    var dirty =
        status.ok()
            ? status
                .stdout()
                .lines()
                .map(line -> line.length() > 3 ? line.substring(3) : line)
                .toList()
            : List.<String>of();
    return repo
        + " (worktree changed: "
        + (dirty.isEmpty() ? "unknown files" : String.join(", ", dirty))
        + ")";
  }

  private static String digest(String value) {
    try {
      var hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void prepare(AdhocPreparer preparer) {
    try {
      preparer.prepare();
    } catch (Exception e) {
      throw new ApiException(
          ErrorCode.AGENT_LAUNCH_FAILED, "Failed to prepare the agent session.", e);
    }
  }

  private static String adhocWorkDir(SailYaml config, String path) {
    var workDir = "/home/" + config.sshUser() + "/workspace";
    return Strings.isBlank(path) ? workDir : workDir + "/" + path;
  }

  private static boolean adhocFullPermissions(SailYaml config) {
    return config.agent() != null
        && config.agent().config() != null
        && "full".equals(config.agent().config().get("permissions"));
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
      requireAllowed(actor, next, localHandle);
      return SpecResolution.of(next);
    }
    requireAllowed(actor, spec, localHandle);
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

  /**
   * Everything one agent launch needs, in one value so the launch seam is a single parameter rather
   * than the 16-way signature the lanes used to spread by hand. The build, ad-hoc, room, and invite
   * lanes differ only in the fields they fill: a build carries a spec id, model, and reasoning
   * effort; an ad-hoc a blank spec id; a room/invite a viewer role and no repo reservation. {@code
   * task}, {@code branch}, and {@code repoPaths} stage the session file and so are unused when only
   * the launch command is built.
   */
  private record LaunchSpec(
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
   * The FDE a dispatched run acts for: the box's handle, which {@link DispatchPolicy} has already
   * matched to the spec's assignee. An admin dispatching on another FDE's box initiates the run but
   * never becomes its authorization owner — the agent must act for the assignee whose spec it
   * builds, not for whoever pressed the button.
   */
  private static String dispatchOwner(String localHandle) {
    return localHandle;
  }

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
   * The one launch sequence both the dispatch and ad-hoc lanes execute: stage the run-scoped task
   * and session files, build the launch command for the run's own unit, run it, and — background
   * only — verify the launch and arm the run-addressed watcher. The lanes differ only in what they
   * pass: a dispatch carries its spec's identity and model, an ad-hoc session a blank spec id.
   */
  private LaunchOutcome launchSession(LaunchSpec s) {
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

  private static List<String> launchCommand(LaunchSpec s) {
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
   * Refuses the dry-run dispatch when a local run of this project is still {@code running} with a
   * reserved repo set intersecting the target's — the {@link DispatchGate} decision over run rows
   * only. This read-only check serves the dry lane, which must not reserve; a live dispatch is
   * gated atomically inside {@link #reserveRun} instead. A row whose agent already died without a
   * stop signal is healed by the missed-stop sweep within its interval, so a stale row can only
   * ever block briefly.
   */
  private void requireNoRepoOverlap(
      String project, String localHandle, String specId, List<String> targetRepos) {
    DispatchGate.decide(specId, "build", targetRepos, runningLocalRuns(project, localHandle))
        .ifPresent(
            conflict -> {
              throw overlapRefusal(conflict);
            });
  }

  /**
   * The refusal when an exclusive container operation (a snapshot restore) holds the container: no
   * run of any role may start into a container that is about to be rolled back.
   */
  private static ApiException leaseRefusal(RunStore.Reservation.LeaseHeld held) {
    return new ApiException(
        ErrorCode.CONFLICT,
        "A snapshot " + held.action() + " is in progress in this container.",
        "Wait for its snapshot_restored event, then retry.");
  }

  private static ApiException overlapRefusal(DispatchGate.Conflict conflict) {
    var run = conflict.run();
    var occupied =
        Strings.isBlank(run.specId())
            ? "Ad-hoc agent run " + run.runId() + " is occupying this container"
            : "Agent run "
                + run.runId()
                + " is already working spec '"
                + run.specId()
                + "' in "
                + (conflict.overlap().isEmpty()
                    ? "this container"
                    : "repo(s) " + conflict.overlap());
    return new ApiException(
        ErrorCode.AGENT_ALREADY_RUNNING,
        occupied + ".",
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
        .filter(run -> run.ownedBy(localHandle))
        .map(run -> new DispatchGate.RunningRun(run.id(), run.specId(), run.role(), run.repos()))
        .toList();
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
   * <p>A foreground dispatch records the same run-scoped unit name even though it launches no
   * systemd service: the run's pid file carries the same identity, so stop, probe, and the
   * missed-stop reconciler address a foreground session exactly like a background one. The
   * foreground run completes when its blocking launcher returns.
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
    return reserve(
        runId, project, specId, node, owner, "build", repos, agentType, branch, task, unit, config);
  }

  /**
   * Reserves an ad-hoc session through the identical transaction: {@code role='adhoc'}, no spec,
   * and an empty repo set — which the gate reads as the whole project, so the session excludes and
   * is excluded by every dispatch atomically. Unlike dispatch, an absent run store is a refusal,
   * not a skip: the reservation is the only thing standing between two agents in one container, and
   * an ad-hoc session without a row would also be invisible to stop, status, and retention.
   */
  private String reserveAdhocRun(
      String runId,
      String project,
      String node,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    if (runStore == null) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box keeps no run aggregate, so an agent session cannot be reserved or tracked.");
    }
    return reserve(
        runId, project, "", node, node, "adhoc", List.of(), agentType, branch, task, unit, config);
  }

  private String reserve(
      String runId,
      String project,
      String specId,
      String node,
      String owner,
      String role,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    RunStore.Reservation reservation;
    try {
      reservation =
          runStore.reserveDispatch(
              runId,
              project,
              specId,
              node,
              owner,
              role,
              repos,
              agentType,
              branch,
              task,
              unit.logPath(),
              unit.unitName(),
              configuredMaxDuration(config));
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Failed to record the dispatch run.", e);
    }
    if (reservation instanceof RunStore.Reservation.Conflicted conflicted) {
      throw overlapRefusal(conflicted.conflict());
    }
    if (reservation instanceof RunStore.Reservation.LeaseHeld held) {
      throw leaseRefusal(held);
    }
    pruneRuns(project);
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  /**
   * The run's configured hard lifetime, bounding its credential: {@code guardrails.max_duration},
   * or null when unset — an unbounded run's credential is revoked by its verified finishers, never
   * by a clock that could expire mid-work.
   */
  private static Duration configuredMaxDuration(SailYaml config) {
    var agent = config.agent();
    if (agent == null || agent.guardrails() == null) {
      return null;
    }
    return Guardrails.parseDuration(agent.guardrails().maxDuration());
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

  /**
   * Stamps the run's process identity once launch has resolved it: the agent pid, its {@code /proc}
   * start-time fingerprint (live processes only — the fingerprint exists to prove a pid still names
   * the process this run launched, so a foreground session that already exited records its pid
   * without one), and the fallback watcher pid.
   *
   * <p>Returns whether the run still owned its launch: the stamp commits only on a {@code running}
   * row, so a {@code false} means an operator's cancel claimed the run during preparation and the
   * caller must tear down whatever it started. A store error stays best-effort bookkeeping (the
   * launch proceeds); only the definitive lost-race answer aborts it.
   */
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

  private Long readStartTicks(String project, int pid) {
    try {
      return new AgentSession(shell).readProcessStartTicks(project, pid);
    } catch (Exception e) {
      return null;
    }
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
  /**
   * Fails a run on a launch error, but only if the run is still {@code running}: a stop that
   * cancelled the run mid-launch, or a watcher that already recorded the real exit, owns the
   * terminal record and must not be overwritten by the launch's cleanup.
   */
  private void failRun(String runId) {
    runBookkeeping(
        "mark run failed " + runId,
        () -> runStore.transition(runId, "running", "failed", (Integer) null));
  }

  /**
   * Releases the run's repo reservation on a launch failure only when the agent is proven absent —
   * probed on the run's own identity (systemd unit and run-scoped pid file), so the check covers
   * background and foreground launches alike. A failure before or during launch leaves no live
   * process, so the run is failed and its repo freed. But once the agent process exists — a
   * background unit that started, a foreground child whose blocking wait threw — a later failure
   * leaves a live agent, and failing the run would free the repo under it and admit an overlapping
   * session. An unprobeable identity is treated as live for the same reason — the missed-stop
   * reconciler releases a genuinely dead run on its next pass.
   */
  private void releaseIfAbsent(String runId, String project, AgentUnit unit) {
    if (agentLive(project, unit)) {
      return;
    }
    failRun(runId);
  }

  private boolean agentLive(String project, AgentUnit unit) {
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
