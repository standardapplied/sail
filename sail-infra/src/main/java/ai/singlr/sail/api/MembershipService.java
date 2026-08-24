/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The room-membership lane: seats an agent as a member of a spec's room (so the wake reactor
 * answers every human message with a chat turn until dismissed) and removes it again. Membership is
 * pure room state — the authoritative copy is the room row's roster (a JSON array of members), and
 * the spec row's legacy {@code engagement} column is dual-written for every reader that has not yet
 * moved to the room, until the retire brick drops it. This service launches nothing and reserves no
 * container; its only side effect beyond the two writes is an optional engage-time rollback
 * snapshot a full membership may pay for.
 *
 * <p>The roster seats many members by schema; this surface currently seats one standing agent per
 * room, so adding a member replaces the previous one exactly as engagement always did. Waker-box
 * election stays on the <em>spec's</em> assignee — work-item ownership — until rooms can exist
 * without a spec.
 */
public final class MembershipService {

  private static final Duration SNAPSHOT_TIMEOUT = Duration.ofHours(1);

  private final SpecStore specStore;
  private final Supplier<RoomStore> rooms;
  private final ProjectLoader projects;
  private final LaunchAdmission admission;
  private final DispatchOperations.EventSink events;
  private final ShellExec shell;

  public MembershipService(
      SpecStore specStore,
      Supplier<RoomStore> rooms,
      ProjectLoader projects,
      LaunchAdmission admission,
      DispatchOperations.EventSink events,
      ShellExec shell) {
    this.specStore = specStore;
    this.rooms = Objects.requireNonNull(rooms, "rooms");
    this.projects = projects;
    this.admission = admission;
    this.events = events;
    this.shell = shell;
  }

  /** A room's conversation-side state: the effective wake mode and the standing member. */
  public record RoomState(String wake, Engagement standing) {}

  /**
   * The conversation-side state a spec's room runs under — the room row when one exists (the
   * authoritative home), the spec's legacy columns otherwise. A present room is authoritative for
   * the standing member even when its roster is empty; only a missing row falls back, so a
   * dismissal recorded on the room can never be resurrected by a stale spec column.
   */
  public static RoomState stateOf(RoomStore rooms, SpecStore.SpecRow spec) {
    var room = rooms == null ? null : rooms.findById(spec.roomIdOrIdentity()).orElse(null);
    if (room == null) {
      return new RoomState(spec.wake(), legacyRosterOf(spec).standing());
    }
    var wake = room.wake() != null ? room.wake() : spec.wake();
    return new RoomState(wake, Roster.fromJson(room.roster()).standing());
  }

  /**
   * The spec's legacy engagement column read as a one-member roster — the fallback every room-first
   * reader uses when no room row exists for pre-decouple data.
   */
  public static Roster legacyRosterOf(SpecStore.SpecRow spec) {
    var legacy = Engagement.fromJson(spec.engagement());
    return legacy == null ? Roster.EMPTY : Roster.solo(legacy);
  }

  /**
   * The members of {@code roomId}'s roster. A present room row is the authoritative home — a
   * chat-only room needs no spec; a missing row falls back to a spec's legacy column so a
   * pre-decouple box's data still reads.
   */
  public java.util.List<Engagement> members(String roomId) {
    var store = rooms.get();
    var room = store == null ? null : store.findById(roomId).orElse(null);
    if (room != null) {
      return Roster.fromJson(room.roster()).members();
    }
    var spec =
        specStore
            .findById(roomId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.ROOM_NOT_FOUND, "Room '" + roomId + "' was not found."));
    return legacyRosterOf(spec).members();
  }

  /** A prepared membership: the snapshot label a full mode will pay, and the deferred half. */
  public record EngageLaunch(String agent, String mode, String snapshot, Runnable completion) {}

  /**
   * Seats an agent in {@code specId}'s room: records the member on the room row's roster (synced,
   * atomic — one JSON array) and dual-writes the spec's legacy engagement column, so the wake
   * reactor answers every human message with a chat turn until a human dismisses the member. Mode
   * {@code full} is the default; {@code read-only} is the explicit narrow choice, offered only
   * where the harness enforces it. A full membership may take one engage-time rollback snapshot
   * (never per turn), but the default is none — on the {@code dir} backend a snapshot is a slow
   * full filesystem copy, so the rollback point is opt-in ({@code takeSnapshot}) and the per-turn
   * repo reservation remains the standing guard. A requested snapshot runs off the request thread
   * ({@code completion}) because a {@code dir}-backend snapshot would blow the HTTP timeout; the
   * membership is then persisted only after the snapshot succeeds — the payment precedes the access
   * — and a failure publishes {@code spec_engage_failed} into the room instead of seating anyone.
   * Requires the dispatch tier on the spec, exactly like an invite.
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
    requireRooms();
    var agentCli = LaunchAdmission.resolveAgent(agentYamlName);
    Engagement member;
    try {
      member =
          Engagement.of(
              agentCli.yamlName(),
              mode,
              LaunchAdmission.validateModel(model),
              DateTimeUtils.now().toString());
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.BAD_REQUEST, e.getMessage());
    }
    if (!member.full() && !agentCli.supportsRoomLane()) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          agentCli.readOnlyInviteRefusal(),
          "Engage " + agentCli.yamlName() + " with full access instead.");
    }
    var project = spec.project();
    projects.loadRunning(project);
    admission.requireInstalled(agentCli, project);
    if (!member.full() || !takeSnapshot) {
      persistMembership(specId, member, actor);
      publishEngaged(project, specId, member, "");
      return new EngageLaunch(member.agent(), member.mode(), "", null);
    }
    var label = "engage-" + DateTimeUtils.newId();
    Runnable completion = () -> completeEngage(project, specId, member, label, actor);
    return new EngageLaunch(member.agent(), member.mode(), label, completion);
  }

  private void completeEngage(
      String project, String specId, Engagement member, String label, Actor actor) {
    try {
      try {
        new SnapshotManager(shell).create(project, label, SNAPSHOT_TIMEOUT);
      } catch (Exception e) {
        throw new ApiException(
            ErrorCode.SNAPSHOT_FAILED,
            "Failed to create the engage snapshot, so the membership does not take effect.",
            "Check the host's snapshot capacity (incus storage) and retry.",
            e);
      }
      publish(project, specId, Event.WellKnownTypes.SNAPSHOT_CREATED, Map.of("label", label));
      persistMembership(specId, member, actor);
      publishEngaged(project, specId, member, label);
    } catch (RuntimeException e) {
      var data = new LinkedHashMap<String, Object>();
      data.put("agent", member.agent());
      data.put("label", label);
      data.put("error", Objects.requireNonNullElse(e.getMessage(), e.toString()));
      publish(project, specId, Event.WellKnownTypes.SPEC_ENGAGE_FAILED, data);
    }
  }

  /** Dismisses the room's standing member. Idempotent: dismissing an empty room is a no-op. */
  public String disengage(String specId, Actor actor, String localHandle) {
    var spec =
        specStore
            .findById(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
    LaunchAdmission.requireAllowed(actor, spec.toSpec(), localHandle);
    var standing = stateOf(rooms.get(), spec).standing();
    if (standing == null) {
      return null;
    }
    writeRoster(spec, Roster.EMPTY, actor);
    publish(
        spec.project(),
        specId,
        Event.WellKnownTypes.SPEC_DISENGAGED,
        Map.of("agent", standing.agent(), "mode", standing.mode()));
    return standing.agent();
  }

  private void persistMembership(String specId, Engagement member, Actor actor) {
    var current =
        specStore
            .findById(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND,
                        "Spec '" + specId + "' vanished while engaging."));
    writeRoster(current, Roster.solo(member), actor);
  }

  private void writeRoster(SpecStore.SpecRow spec, Roster roster, Actor actor) {
    var store = requireRooms();
    var handle = actor == null ? spec.updatedBy() : actor.handle();
    var standing = roster.standing();
    specStore.atomically(
        () -> {
          store.ensureFor(
              spec.roomIdOrIdentity(),
              spec.project(),
              spec.title(),
              spec.assignee(),
              spec.wake(),
              handle);
          store.updateRoster(spec.roomIdOrIdentity(), roster.toJson(), handle);
          specStore.updateEngagement(spec.id(), standing == null ? null : standing.toJson());
          return null;
        });
  }

  private RoomStore requireRooms() {
    var store = rooms.get();
    if (store == null) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box keeps no room aggregate, so membership cannot be recorded.",
          "Wire the room store (useRooms) on this box's operations.");
    }
    return store;
  }

  private void publishEngaged(String project, String specId, Engagement member, String label) {
    var data = new LinkedHashMap<String, Object>();
    data.put("agent", member.agent());
    data.put("mode", member.mode());
    if (!Strings.isBlank(label)) {
      data.put("label", label);
    }
    publish(project, specId, Event.WellKnownTypes.SPEC_ENGAGED, data);
  }

  private void publish(String project, String specId, String type, Map<String, Object> data) {
    events.publish(Event.of(project, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }
}
