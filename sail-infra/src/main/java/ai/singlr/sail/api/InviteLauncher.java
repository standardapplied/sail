/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.InvitePrompt;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The invite lane — a human explicitly puts an agent in a spec's room in full mode (read-only
 * invites are superseded by engagement). Reserving is synchronous, but the snapshot payment and the
 * launch are deferred off the request thread through the returned {@link
 * DispatchOperations.InviteLaunch}'s completion — a {@code dir}-backend snapshot is a slow full
 * copy that would blow the client timeout. A thin lane over the three seams: authorize through
 * {@link LaunchAdmission}, reserve through {@link RunReservation}, launch through {@link
 * RunLauncher}; on failure the reservation is released and the room learns through a {@code
 * snapshot_created} event carrying an error, since there is no caller left to throw to.
 */
public final class InviteLauncher {

  private static final Duration SNAPSHOT_TIMEOUT = Duration.ofHours(1);

  private final SpecStore specStore;
  private final ProjectLoader projects;
  private final Supplier<MessageStore> messageStore;
  private final RunStore runStore;
  private final LaunchAdmission admission;
  private final RunReservation runReservation;
  private final RunLauncher runLauncher;
  private final DispatchOperations.EventSink events;
  private final ShellExec shell;

  public InviteLauncher(
      SpecStore specStore,
      ProjectLoader projects,
      Supplier<MessageStore> messageStore,
      RunStore runStore,
      LaunchAdmission admission,
      RunReservation runReservation,
      RunLauncher runLauncher,
      DispatchOperations.EventSink events,
      ShellExec shell) {
    this.specStore = specStore;
    this.projects = projects;
    this.messageStore = messageStore;
    this.runStore = runStore;
    this.admission = admission;
    this.runReservation = runReservation;
    this.runLauncher = runLauncher;
    this.events = events;
    this.shell = shell;
  }

  /**
   * Accepts an invite: authorize the actor, reserve the run synchronously, and return an {@link
   * DispatchOperations.InviteLaunch} whose {@code completion} pays the pre-launch snapshot (unless
   * {@code takeSnapshot} waives it) and launches — off the request thread. Requires the dispatch
   * tier on the spec, exactly like a dispatch.
   */
  public DispatchOperations.InviteLaunch start(
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
    LaunchAdmission.requireAllowed(actor, spec.toSpec(), localHandle);
    admission.requireTrustedRoster(localHandle);
    var agentCli = LaunchAdmission.resolveAgent(agentYamlName);
    var inviteModel = LaunchAdmission.validateModel(model);
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
    admission.requireInstalled(agentCli, project);
    var body = specStore.getContent(specId).map(SpecStore.SpecContent::body).orElse("");
    var messages = messageStore.get();
    var room =
        messages == null
            ? List.<MessageStore.MessageRow>of()
            : messages.list(spec.roomIdOrIdentity(), null, 20);
    var built = InvitePrompt.build(spec, body.isBlank() ? spec.title() : body, room);
    var task = built.prompt();
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(runId);
    var role = DispatchGate.FULL_INVITE_ROLE;
    var targetRepos = DispatchRepos.resolve(config, spec.toSpec(), List.of());
    var repoPaths = targetRepos.stream().map(SailYaml.Repo::path).toList();
    var branch = Objects.toString(spec.branch(), "");
    var owner = Strings.isBlank(spec.assignee()) ? localHandle : spec.assignee();
    var credential =
        runReservation.reserve(
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
      runReservation.releaseIfAbsent(runId, project, unit);
      throw e;
    }
    var principal = runStore.findById(runId).map(RunStore.RunRow::principal).orElse("");
    var snapshot = takeSnapshot ? "invite-" + runId : "";
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
    return new DispatchOperations.InviteLaunch(runId, principal, full, snapshot, completion);
  }

  /**
   * The deferred half of an invite, run off the request thread: take the pre-launch snapshot, then
   * launch the session. The reservation is already held. A snapshot or launch failure releases it
   * and publishes {@code snapshot_created} carrying an {@code error} — there is no caller left to
   * throw to, so the room learns the invite failed through the stream.
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
          runLauncher.launchSession(
              new RunLauncher.LaunchSpec(
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
      runLauncher.finishLaunch(
          new RunLauncher.RunContext(project, unit, runId, specId, agentCli.yamlName(), role, true),
          launch);
    } catch (RuntimeException e) {
      runReservation.releaseIfAbsent(runId, project, unit);
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
   * The full invite's mandatory pre-launch snapshot, labeled {@code invite-<runId>} so rollback is
   * one visible step. Published with the spec id — unlike the container-scoped dispatch snapshot —
   * so the {@code snapshot_created} event renders in the room the invite was made from. Failure
   * aborts the invite: the snapshot is the payment for full access, and a YOLO session with no
   * rollback point must not launch.
   */
  private String inviteSnapshot(String project, String specId, String runId) {
    var label = "invite-" + runId;
    try {
      new SnapshotManager(shell).create(project, label, SNAPSHOT_TIMEOUT);
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

  private void seedRoomDelivery(String runId, List<MessageStore.MessageRow> rendered) {
    if (runStore == null || rendered.isEmpty()) {
      return;
    }
    runStore.markDelivered(runId, rendered.stream().map(MessageStore.MessageRow::id).toList());
  }

  private void publish(String project, String specId, String type, Map<String, Object> data) {
    events.publish(Event.of(project, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }
}
