/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Global (control-plane) spec CRUD against the {@link SpecStore}. Pure database operations, split
 * out of {@code SailOperations} so the spec-store domain lives in one focused, fully-testable
 * class. Methods return their response value and throw {@link ApiException} on failure; the caller
 * wraps them in a {@code Result}.
 */
final class GlobalSpecOperations {

  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final EventBus eventBus;
  private final RunStore runStore;
  private final Supplier<RoomStore> rooms;

  GlobalSpecOperations(SpecStore specStore) {
    this(specStore, null, null);
  }

  GlobalSpecOperations(SpecStore specStore, ReviewStore reviewStore) {
    this(specStore, reviewStore, null);
  }

  GlobalSpecOperations(SpecStore specStore, ReviewStore reviewStore, EventBus eventBus) {
    this(specStore, reviewStore, eventBus, null);
  }

  GlobalSpecOperations(
      SpecStore specStore, ReviewStore reviewStore, EventBus eventBus, RunStore runStore) {
    this(specStore, reviewStore, eventBus, runStore, () -> null);
  }

  GlobalSpecOperations(
      SpecStore specStore,
      ReviewStore reviewStore,
      EventBus eventBus,
      RunStore runStore,
      Supplier<RoomStore> rooms) {
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.eventBus = eventBus;
    this.runStore = runStore;
    this.rooms = rooms;
  }

  GlobalSpecsListResponse list(SpecStore.SpecFilter filter) {
    requireStore();
    try {
      var specs = specStore.list(filter).stream().map(this::viewOf).toList();
      return new GlobalSpecsListResponse(specs, specs.size());
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  GlobalSpecDetailResponse get(String specId) {
    requireStore();
    var row = findOrThrow(specId);
    var content = specStore.getContent(specId).orElse(null);
    return new GlobalSpecDetailResponse(
        viewOf(row),
        content != null ? content.body() : null,
        content != null ? content.plan() : null,
        openFindingCount(specId),
        latestRun(specId));
  }

  /**
   * The spec's most recent run as a compact summary (id, node, status, exit code), or null when it
   * has never been dispatched. Lets a client gate its "open logs" button on provenance up front —
   * one call, no second round-trip to find where the run executed.
   */
  private RunSummary latestRun(String specId) {
    if (runStore == null) {
      return null;
    }
    return runStore.listForSpec(specId).stream().findFirst().map(RunSummary::from).orElse(null);
  }

  GlobalSpecCreatedResponse create(SpecCreateRequest request, Actor actor) {
    requireStore();
    Objects.requireNonNull(actor, "spec creation needs the authenticated actor");
    if (request.id() == null || request.id().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "spec id is required.");
    }
    if (request.title() == null || request.title().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "spec title is required.");
    }
    NameValidator.requireValidSpecId(request.id());
    if (request.project() == null || request.project().isBlank()) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "spec project is required.",
          "Pass --project <name> or run from a directory containing sail.yaml.");
    }
    var assignee = Strings.isBlank(request.assignee()) ? request.createdBy() : request.assignee();
    reserveIdentityRoom(request.id());
    var home = Strings.isNotBlank(request.roomId()) ? requireHomeRoom(request.roomId()) : null;
    var roomId = home != null ? home.id() : request.id();
    if (home != null) {
      admitIntoRoom(home, request.project(), actor);
    }
    var row =
        new SpecStore.SpecRow(
            request.id(),
            request.project(),
            request.title(),
            parseStatus(request.status(), SpecStatus.PENDING),
            assignee,
            request.agent(),
            validModel(request.model()),
            validReasoning(request.reasoningEffort()),
            request.branch(),
            request.priority(),
            request.createdBy(),
            "",
            "",
            request.createdBy(),
            request.dependsOn(),
            request.repos());
    specStore.create(row.withRoomId(roomId));
    if (request.body() != null || request.plan() != null) {
      specStore.setContent(
          request.id(),
          Objects.requireNonNullElse(request.body(), ""),
          Objects.requireNonNullElse(request.plan(), ""));
    }
    var created = specStore.findById(request.id()).orElseThrow();
    if (home == null) {
      mintIdentityRoom(created);
    }
    publishBoardUpdated(created.project(), created.id(), principal(request.createdBy()));
    return new GlobalSpecCreatedResponse(viewOf(created));
  }

  /**
   * The room a spec is explicitly born into — a brainstorm's {@code SAIL_ROOM_ID}, an epic's room —
   * must already exist. A spec with a home room mints no identity room: the room it was born in is
   * its conversation.
   */
  private RoomStore.RoomRow requireHomeRoom(String roomId) {
    NameValidator.requireValidSpecId(roomId);
    var store = rooms.get();
    if (store == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "This box keeps no rooms, so a spec cannot be born in room '" + roomId + "'.",
          "Start the server with 'sail server start' or omit the room.");
    }
    return store
        .findById(roomId)
        .orElseThrow(
            () ->
                new ApiException(ErrorCode.ROOM_NOT_FOUND, "Room '" + roomId + "' was not found."));
  }

  /**
   * A spec's id is reserved for the identity room it mints: a room already sitting on that id is
   * somebody's room, and letting the spec bind to it would leave deletion unable to tell the room
   * the spec owns from one it merely borrowed. Refusing here is what makes {@code room_id == id} an
   * exact record of "this spec minted this room" — the one fact {@link #delete} relies on.
   */
  private void reserveIdentityRoom(String specId) {
    var store = rooms.get();
    if (store != null && store.findById(specId).isPresent()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Room '" + specId + "' already exists, and a spec's id is reserved for its own room.",
          "Pick another spec id, or pass --room " + specId + " to be born in that room.");
    }
  }

  /**
   * Binding a spec to an existing room hands the spec's owner every membership write that room
   * takes — engage rewrites its roster — so it needs the room's own post right: the room's assignee
   * (or creator when unassigned) or an admin, in the room's project.
   */
  private static void admitIntoRoom(RoomStore.RoomRow room, String project, Actor actor) {
    if (!Objects.equals(room.project(), project)) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "Room '"
              + room.id()
              + "' belongs to project '"
              + room.project()
              + "', not '"
              + project
              + "'.",
          "A spec is born only into a room of its own project.");
    }
    SpecPolicy.post(actor, room.id(), room.assignee(), room.createdBy()).enforce();
  }

  GlobalSpecUpdatedResponse update(String specId, SpecUpdateRequest request, Actor actor) {
    requireStore();
    var existing = findOrThrow(specId);
    authorizeUpdate(actor, existing, request);
    guardReassignment(specId, existing, request);
    var updated =
        new SpecStore.SpecRow(
            specId,
            request.project() != null ? request.project() : existing.project(),
            request.title() != null ? request.title() : existing.title(),
            parseStatus(request.status(), existing.status()),
            request.assignee() != null ? request.assignee() : existing.assignee(),
            request.agent() != null ? request.agent() : existing.agent(),
            request.model() != null ? validModel(request.model()) : existing.model(),
            request.reasoningEffort() != null
                ? validReasoning(request.reasoningEffort())
                : existing.reasoningEffort(),
            request.branch() != null ? request.branch() : existing.branch(),
            request.priority() != null ? request.priority() : existing.priority(),
            existing.createdBy(),
            existing.createdAt(),
            existing.updatedAt(),
            request.updatedBy(),
            request.dependsOn() != null ? request.dependsOn() : existing.dependsOn(),
            request.repos() != null ? request.repos() : existing.repos(),
            existing.roomIdOrIdentity());
    specStore.update(updated);
    writeWake(updated, request);
    if (updated.status() == SpecStatus.DONE
        && existing.status() != SpecStatus.DONE
        && reviewStore != null) {
      reviewStore.resolveSourceFindings(specId);
      reviewStore.resolveShippedFindings(specId);
    }
    var result = specStore.findById(specId).orElseThrow();
    if (result.status() != existing.status()) {
      publishStatusChanged(
          result.project(),
          specId,
          existing.status(),
          result.status(),
          principal(request.updatedBy()));
    } else {
      publishBoardUpdated(result.project(), specId, principal(request.updatedBy()));
    }
    return new GlobalSpecUpdatedResponse(viewOf(result));
  }

  /**
   * The resource-scoped gate for an update: a request that changes the assignee is a reassignment
   * (admin-only, or a member self-claiming an unassigned spec); any other edit is governed by the
   * general mutate policy (assignee or admin, creator or admin when unassigned). Runs before the
   * status-based claim lock so identity is validated first.
   */
  /**
   * Mints the spec's identity room — same id, the conversation surface every spec gets — when this
   * box keeps a room aggregate. Membership writes {@code ensureFor} the room defensively, so a box
   * without the aggregate here loses nothing; this keeps the room's birth beside the spec's.
   */
  /** The row as a wire view, wake and roster decorated from its room — the fields' only home. */
  private GlobalSpecView viewOf(SpecStore.SpecRow row) {
    var store = rooms.get();
    return GlobalSpecView.from(
        row, store == null ? null : store.findById(row.roomIdOrIdentity()).orElse(null));
  }

  private void mintIdentityRoom(SpecStore.SpecRow spec) {
    var store = rooms.get();
    if (store == null) {
      return;
    }
    store.ensureFor(
        spec.roomIdOrIdentity(),
        spec.project(),
        spec.title(),
        spec.assignee(),
        null,
        spec.createdBy());
  }

  /**
   * Writes an explicit wake edit onto the spec's home room — the one home the wake mode has, and
   * the same room messages, roster, and the spec view read. The spec update door keeps accepting
   * {@code wake} so the CLI's {@code spec update --wake} still works; the value lands only on the
   * room.
   */
  private void writeWake(SpecStore.SpecRow updated, SpecUpdateRequest request) {
    var store = rooms.get();
    if (store == null || request.wake() == null) {
      return;
    }
    var wake = validWake(request.wake());
    var roomId = updated.roomIdOrIdentity();
    var room =
        store.ensureFor(
            roomId,
            updated.project(),
            updated.title(),
            updated.assignee(),
            null,
            request.updatedBy());
    if (!Objects.equals(room.wake(), wake)) {
      store.updateWake(roomId, wake, request.updatedBy());
    }
  }

  private static void authorizeUpdate(
      Actor actor, SpecStore.SpecRow existing, SpecUpdateRequest request) {
    var reassigning = request.assignee() != null && !request.assignee().equals(existing.assignee());
    if (reassigning) {
      SpecPolicy.reassign(actor, existing.id(), existing.assignee(), request.assignee()).enforce();
    } else {
      SpecPolicy.mutate(actor, existing.id(), existing.assignee(), existing.createdBy()).enforce();
    }
  }

  private static void guardReassignment(
      String specId, SpecStore.SpecRow existing, SpecUpdateRequest request) {
    var stealingClaim =
        request.assignee() != null
            && existing.assignee() != null
            && !request.assignee().equals(existing.assignee());
    if (stealingClaim && !existing.status().isReassignable() && !request.force()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Spec '"
              + specId
              + "' is "
              + existing.status().wire()
              + " (dispatched) and assigned to '"
              + existing.assignee()
              + "'.",
          "Its claim is locked. Pass --force to reassign it anyway.");
    }
  }

  GlobalSpecDeletedResponse delete(String specId, Actor actor) {
    requireStore();
    var existing = findOrThrow(specId);
    SpecPolicy.mutate(actor, existing.id(), existing.assignee(), existing.createdBy()).enforce();
    var store = rooms.get();
    var mintedItsRoom = existing.roomIdOrIdentity().equals(specId);
    specStore.atomically(
        () -> {
          specStore.delete(specId);
          if (store != null && mintedItsRoom) {
            store.delete(specId);
          }
          return null;
        });
    publishBoardUpdated(existing.project(), specId, Event.SAIL_AGENT);
    return new GlobalSpecDeletedResponse(specId);
  }

  GlobalSpecContentResponse content(String specId) {
    requireStore();
    findOrThrow(specId);
    var content = specStore.getContent(specId).orElse(new SpecStore.SpecContent("", "", ""));
    return new GlobalSpecContentResponse(specId, content.body(), content.plan());
  }

  GlobalSpecContentResponse setContent(String specId, SpecContentRequest request, Actor actor) {
    requireStore();
    var existing = findOrThrow(specId);
    SpecPolicy.mutate(actor, existing.id(), existing.assignee(), existing.createdBy()).enforce();
    specStore.setContent(
        specId,
        Objects.requireNonNullElse(request.body(), ""),
        Objects.requireNonNullElse(request.plan(), ""));
    var content = specStore.getContent(specId).orElseThrow();
    publishBoardUpdated(existing.project(), specId, Event.SAIL_AGENT);
    return new GlobalSpecContentResponse(specId, content.body(), content.plan());
  }

  GlobalBoardResponse board(String project) {
    requireStore();
    return new GlobalBoardResponse(specStore.board(project), doneOpenFindings(project));
  }

  /**
   * Open findings still attached to {@code done} specs — residual work the gate let ship. Shown
   * next to the board's done column so completion-with-residue is distinguishable from clean
   * completion.
   */
  private int doneOpenFindings(String project) {
    if (reviewStore == null) {
      return 0;
    }
    return specStore
        .list(new SpecStore.SpecFilter(project, SpecStatus.DONE.wire(), null, null, null))
        .stream()
        .mapToInt(spec -> openFindingCount(spec.id()))
        .sum();
  }

  private int openFindingCount(String specId) {
    return reviewStore == null ? 0 : reviewStore.openFindingsAfterPass(specId).size();
  }

  GlobalSpecHistoryResponse history(String specId) {
    requireStore();
    return GlobalSpecHistoryResponse.from(specId, specStore.history(specId));
  }

  /**
   * A historical snapshot carries the assignee, so a restore that changes it is a reassignment in
   * disguise and must clear {@link SpecPolicy#reassign} on top of the plain mutation gate —
   * otherwise an assignee could route around the admin-only reassign rule by restoring a revision
   * owned by someone else.
   */
  GlobalSpecRestoredResponse restore(String specId, SpecRestoreRequest request, Actor actor) {
    requireStore();
    var existing = findOrThrow(specId);
    SpecPolicy.mutate(actor, existing.id(), existing.assignee(), existing.createdBy()).enforce();
    if (request.rev() == null || request.rev().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "rev is required.");
    }
    var targetAssignee = revisionAssignee(specId, request.rev());
    if (!Objects.equals(existing.assignee(), targetAssignee)) {
      SpecPolicy.reassign(actor, existing.id(), existing.assignee(), targetAssignee).enforce();
    }
    specStore.restore(specId, request.rev());
    var row = specStore.findById(specId).orElseThrow();
    publishBoardUpdated(row.project(), specId, Event.SAIL_AGENT);
    return new GlobalSpecRestoredResponse(viewOf(row), request.rev());
  }

  private String revisionAssignee(String specId, String rev) {
    var entry =
        specStore.history(specId).stream()
            .filter(candidate -> rev.equals(candidate.rev()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "No revision '" + rev + "' recorded for spec '" + specId + "'."));
    return Objects.toString(YamlUtil.parseMap(entry.snapshot()).get("assignee"), null);
  }

  private void publishStatusChanged(
      String project, String specId, SpecStatus from, SpecStatus to, String principal) {
    if (eventBus == null) {
      return;
    }
    eventBus.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.SPEC_STATUS_CHANGED,
            principal,
            HostInfo.hostname(),
            Map.of("from", from.wire(), "to", to.wire())));
  }

  private void publishBoardUpdated(String project, String specId, String principal) {
    if (eventBus == null) {
      return;
    }
    eventBus.publish(
        Event.of(
            project, specId, Event.WellKnownTypes.BOARD_UPDATED, principal, HostInfo.hostname()));
  }

  private static String principal(String actor) {
    return Strings.isNotBlank(actor) ? actor : Event.SAIL_AGENT;
  }

  private SpecStore.SpecRow findOrThrow(String specId) {
    return specStore
        .findById(specId)
        .orElseThrow(
            () ->
                new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
  }

  private void requireStore() {
    if (specStore == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "Spec store not available. Start the server with 'sail server start'.");
    }
  }

  private static String validModel(String model) {
    try {
      return Spec.validatedModel(model);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  private static String validReasoning(String reasoningEffort) {
    try {
      return Spec.validatedReasoningEffort(reasoningEffort);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  private static final Set<String> WAKE_MODES = Set.of("on", "mention", "off");

  /** The wake vocabulary is deliberately tiny; an empty string clears the mode back to default. */
  private static String validWake(String wake) {
    if (wake.isBlank()) {
      return null;
    }
    if (!WAKE_MODES.contains(wake)) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "wake must be on, mention, or off (got '" + wake + "'); an empty value clears it.");
    }
    return wake;
  }

  private static SpecStatus parseStatus(String value, SpecStatus fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      return SpecStatus.fromWire(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }
}
