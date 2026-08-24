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
import ai.singlr.sail.engine.RoomWakePrompt;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The room-wake lane — the chat lane. A human message posted to a spec's room while no run is live
 * wakes the agent as a real {@code room}-role run that answers in the room. A thin lane over the
 * three launch seams: reserve through {@link RunReservation} (an empty repo set — the gate
 * serializes it only against runs of its own spec), launch through {@link RunLauncher}, and — read
 * only — capture the {@link RoomCommitGuard} baseline so a worktree-writing chat surfaces. The spec
 * is never claimed, no branch is checked out, no snapshot is taken; a chat owns no working tree,
 * and the launch is harness-restricted, never full-permission unless a human engaged the agent in
 * full mode. When the spec's latest run on this node recorded a resumable session for the chosen
 * agent, the launch resumes that conversation with the wake prompt as its next turn.
 */
public final class RoomWakeLauncher {

  private final ProjectLoader projects;
  private final SpecStore specStore;
  private final Supplier<RoomStore> roomStore;
  private final Supplier<MessageStore> messageStore;
  private final RunStore runStore;
  private final RunReservation runReservation;
  private final RunLauncher runLauncher;
  private final RoomCommitGuard roomCommitGuard;

  public RoomWakeLauncher(
      ProjectLoader projects,
      SpecStore specStore,
      Supplier<RoomStore> roomStore,
      Supplier<MessageStore> messageStore,
      RunStore runStore,
      RunReservation runReservation,
      RunLauncher runLauncher,
      RoomCommitGuard roomCommitGuard) {
    this.projects = projects;
    this.specStore = specStore;
    this.roomStore = roomStore;
    this.messageStore = messageStore;
    this.runStore = runStore;
    this.runReservation = runReservation;
    this.runLauncher = runLauncher;
    this.roomCommitGuard = roomCommitGuard;
  }

  /** Launches a room wake for {@code specId}, returning the run id. */
  public String wake(String project, String specId, String localHandle) {
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
    var engagement = MembershipService.stateOf(roomStore.get(), spec).standing();
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
    var messages = messageStore.get();
    var room =
        messages == null
            ? List.<MessageStore.MessageRow>of()
            : messages.list(spec.roomIdOrIdentity(), null, 20);
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
    var credential =
        runReservation.reserve(
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
            unit,
            config);
    try {
      seedRoomDelivery(runId, built.renderedMessages());
      if (!full) {
        roomCommitGuard.captureRoomBaseline(project, config, runId);
      }
      var model =
          engagement != null && engagement.model() != null ? engagement.model() : spec.model();
      var workDir =
          full
              ? AgentSession.launchWorkDir(config.sshUser(), targetRepos)
              : "/home/" + config.sshUser() + "/workspace";
      var launch =
          runLauncher.launchSession(
              new RunLauncher.LaunchSpec(
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
      runLauncher.finishLaunch(
          new RunLauncher.RunContext(project, unit, runId, specId, agentType, role, true), launch);
      return runId;
    } catch (RuntimeException e) {
      runReservation.releaseIfAbsent(runId, project, unit);
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

  private void seedRoomDelivery(String runId, List<MessageStore.MessageRow> rendered) {
    if (runStore == null || rendered.isEmpty()) {
      return;
    }
    runStore.markDelivered(runId, rendered.stream().map(MessageStore.MessageRow::id).toList());
  }
}
