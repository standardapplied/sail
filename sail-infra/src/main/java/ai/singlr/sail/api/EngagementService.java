/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The room-engagement lane: puts an agent in a spec's room (so the wake reactor answers every human
 * message with a chat turn until dismissed) and takes it out again. Engagement is pure room state —
 * one JSON value on the spec row — so this service launches nothing and reserves no container; its
 * only side effect beyond the spec write is an optional engage-time rollback snapshot a full
 * engagement may pay for. Kept apart from the launch lanes so the room/spec surface can evolve
 * without touching dispatch.
 */
public final class EngagementService {

  private static final Duration SNAPSHOT_TIMEOUT = Duration.ofHours(1);

  private final SpecStore specStore;
  private final ProjectLoader projects;
  private final LaunchAdmission admission;
  private final DispatchOperations.EventSink events;
  private final ShellExec shell;

  public EngagementService(
      SpecStore specStore,
      ProjectLoader projects,
      LaunchAdmission admission,
      DispatchOperations.EventSink events,
      ShellExec shell) {
    this.specStore = specStore;
    this.projects = projects;
    this.admission = admission;
    this.events = events;
    this.shell = shell;
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
    LaunchAdmission.requireAllowed(actor, spec.toSpec(), localHandle);
    admission.requireTrustedRoster(localHandle);
    var agentCli = LaunchAdmission.resolveAgent(agentYamlName);
    Engagement engagement;
    try {
      engagement =
          Engagement.of(
              agentCli.yamlName(),
              mode,
              LaunchAdmission.validateModel(model),
              DateTimeUtils.now().toString());
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
    admission.requireInstalled(agentCli, project);
    if (!engagement.full() || !takeSnapshot) {
      persistEngagement(specId, engagement);
      publishEngaged(project, specId, engagement, "");
      return new EngageLaunch(engagement.agent(), engagement.mode(), "", null);
    }
    var label = "engage-" + DateTimeUtils.newId();
    Runnable completion = () -> completeEngage(project, specId, engagement, label);
    return new EngageLaunch(engagement.agent(), engagement.mode(), label, completion);
  }

  private void completeEngage(String project, String specId, Engagement engagement, String label) {
    try {
      try {
        new SnapshotManager(shell).create(project, label, SNAPSHOT_TIMEOUT);
      } catch (Exception e) {
        throw new ApiException(
            ErrorCode.SNAPSHOT_FAILED,
            "Failed to create the engage snapshot, so the engagement does not take effect.",
            "Check the host's snapshot capacity (incus storage) and retry.",
            e);
      }
      publish(project, specId, Event.WellKnownTypes.SNAPSHOT_CREATED, Map.of("label", label));
      persistEngagement(specId, engagement);
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
    LaunchAdmission.requireAllowed(actor, spec.toSpec(), localHandle);
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

  private void persistEngagement(String specId, Engagement engagement) {
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

  private void publish(String project, String specId, String type, Map<String, Object> data) {
    events.publish(Event.of(project, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }
}
