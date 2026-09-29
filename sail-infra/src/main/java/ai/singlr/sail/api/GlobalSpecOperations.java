/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import java.util.LinkedHashMap;
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

  GlobalSpecCreatedResponse create(SpecCreateRequest request) {
    var actor = Actor.current();
    requireStore();
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
    var row =
        new SpecStore.SpecRow(
            request.id(),
            request.project(),
            request.title(),
            parseStatus(request.status(), SpecStatus.PENDING),
            validAssignee(request.assignee()),
            request.agent(),
            validModel(request.model()),
            validReasoning(request.reasoningEffort()),
            request.branch(),
            request.priority(),
            null,
            "",
            "",
            null,
            request.dependsOn(),
            request.repos());
    var created = specStore.atomically(() -> birth(row, request));
    publishBoardUpdated(created.project(), created.id(), principal(actor.handle()));
    return new GlobalSpecCreatedResponse(viewOf(created));
  }

  /**
   * The birth itself, one transaction on the connection every room and spec write shares: the id
   * reservation, the home-room admission, the row, and the identity mint commit together or not at
   * all. That is what keeps {@code room_id == id} an exact record of "this spec minted this room" —
   * a room landing on the id mid-birth cannot slip between the check and the mint.
   */
  private SpecStore.SpecRow birth(SpecStore.SpecRow row, SpecCreateRequest request) {
    reserveIdentityRoom(request.id());
    var home = Strings.isNotBlank(request.roomId()) ? requireHomeRoom(request.roomId()) : null;
    if (home != null) {
      requireSameProject(home, row);
    }
    var attached = row.withRoomId(home != null ? home.id() : request.id());
    authorize(attached.id(), null, projection(attached.roomIdOrIdentity(), attached.assignee()));
    specStore.create(attached);
    if (request.body() != null || request.plan() != null) {
      specStore.setContent(
          request.id(),
          Objects.requireNonNullElse(request.body(), ""),
          Objects.requireNonNullElse(request.plan(), ""));
    }
    var born = specStore.findById(request.id()).orElseThrow();
    if (home == null) {
      mintIdentityRoom(born);
    }
    return born;
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
   * exact record of "this spec minted this room" — the one fact {@link #delete} relies on. Runs
   * inside the birth transaction, and {@link #mintIdentityRoom} inserts strictly rather than
   * ensuring, so a room that lands on the id anyway fails the birth instead of being borrowed.
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
   * A spec is born only into a room of its own project. Binding it hands the spec's owner a voice
   * there and every membership write the room takes, so the spec rule then asks for the room's post
   * right, and a named assignee is a claim into that room.
   */
  private static void requireSameProject(RoomStore.RoomRow room, SpecStore.SpecRow spec) {
    var project = spec.project();
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
  }

  GlobalSpecUpdatedResponse update(String specId, SpecUpdateRequest request) {
    var actor = Actor.current();
    requireStore();
    var assignee = request.assignee() == null ? null : validAssignee(request.assignee());
    var existing = findOrThrow(specId);
    var held = specStore.held(specId);
    authorize(specId, held, request.assignee() == null ? held : with(held, "assignee", assignee));
    var wake = request.wake() == null ? null : authorizeWake(existing, request.wake());
    guardReassignment(specId, existing, request);
    var updated =
        new SpecStore.SpecRow(
            specId,
            request.project() != null ? request.project() : existing.project(),
            request.title() != null ? request.title() : existing.title(),
            parseStatus(request.status(), existing.status()),
            request.assignee() != null ? assignee : existing.assignee(),
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
            null,
            request.dependsOn() != null ? request.dependsOn() : existing.dependsOn(),
            request.repos() != null ? request.repos() : existing.repos(),
            existing.roomIdOrIdentity());
    specStore.update(updated);
    if (wake != null) {
      writeWake(updated, wake.value());
    }
    if (updated.status() == SpecStatus.DONE
        && existing.status() != SpecStatus.DONE
        && reviewStore != null) {
      reviewStore.resolveSourceFindings(specId);
      reviewStore.resolveShippedFindings(specId);
    }
    var result = specStore.findById(specId).orElseThrow();
    if (result.status() != existing.status()) {
      publishStatusChanged(
          result.project(), specId, existing.status(), result.status(), principal(actor.handle()));
    } else {
      publishBoardUpdated(result.project(), specId, principal(actor.handle()));
    }
    return new GlobalSpecUpdatedResponse(viewOf(result));
  }

  /** The row as a wire view, wake and roster decorated from its room — the fields' only home. */
  private GlobalSpecView viewOf(SpecStore.SpecRow row) {
    var store = rooms.get();
    return GlobalSpecView.from(
        row, store == null ? null : store.findById(row.roomIdOrIdentity()).orElse(null));
  }

  /**
   * Mints the spec's identity room — same id, the conversation surface every spec gets — when this
   * box keeps a room aggregate. A strict insert, never an ensure: the birth transaction already
   * reserved the id, so a row there now is a foreign room and the birth must fail loudly rather
   * than adopt it. Membership writes still {@code ensureFor} defensively, so a box without the
   * aggregate here loses nothing.
   */
  private void mintIdentityRoom(SpecStore.SpecRow spec) {
    var store = rooms.get();
    if (store == null) {
      return;
    }
    store.create(
        new RoomStore.RoomRow(
            spec.roomIdOrIdentity(),
            spec.project(),
            spec.title(),
            spec.assignee(),
            null,
            null,
            null,
            null,
            null,
            null));
  }

  /** A validated wake edit: the mode to store, null to clear it back to the default. */
  private record Wake(String value) {}

  /**
   * Validates an explicit wake edit and asks the room rule whether the actor may set it on the
   * spec's home room — the room the wake lives on, whoever's it is — before anything is written.
   * The spec update door keeps accepting {@code wake} so the CLI's {@code spec update --wake} still
   * works; the value lands only on the room.
   */
  private Wake authorizeWake(SpecStore.SpecRow spec, String requested) {
    var wake = new Wake(validWake(requested));
    var store = rooms.get();
    if (store != null) {
      var roomId = spec.roomIdOrIdentity();
      var held = store.comparableSnapshot(roomId);
      Refusals.enforce(
          store
              .authority()
              .decide(
                  Actor.current(),
                  roomId,
                  held,
                  held == null ? null : with(held, "wake", wake.value())));
    }
    return wake;
  }

  /**
   * Writes an explicit wake edit onto the spec's home room — the one home the wake mode has, and
   * the same room messages, roster, and the spec view read.
   */
  private void writeWake(SpecStore.SpecRow updated, String wake) {
    var store = rooms.get();
    if (store == null) {
      return;
    }
    var roomId = updated.roomIdOrIdentity();
    var room =
        store.ensureFor(roomId, updated.project(), updated.title(), updated.assignee(), null);
    if (!Objects.equals(room.wake(), wake)) {
      store.updateWake(roomId, wake);
    }
  }

  /** Asks the spec rule whether the bound actor may write {@code next} over {@code held}. */
  private void authorize(String specId, Map<String, Object> held, Map<String, Object> next) {
    Refusals.enforce(specStore.authority().decide(Actor.current(), specId, held, next));
  }

  private static Map<String, Object> projection(String roomId, String assignee) {
    return with(Map.of("room_id", roomId), "assignee", assignee);
  }

  private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
    var next = new LinkedHashMap<>(base);
    next.put(key, value);
    return next;
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

  GlobalSpecDeletedResponse delete(String specId) {
    requireStore();
    var existing = findOrThrow(specId);
    authorize(specId, specStore.held(specId), null);
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

  GlobalSpecContentResponse setContent(String specId, SpecContentRequest request) {
    requireStore();
    var existing = findOrThrow(specId);
    var held = specStore.held(specId);
    authorize(specId, held, held);
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
   * A restore is a revision of the spec, so it is its owner's or an admin's; a historical snapshot
   * carries the assignee, so one that changes it is a reassignment in disguise and must pass the
   * claim rule too — otherwise an assignee could route around the admin-only reassign rule by
   * restoring a revision owned by someone else.
   */
  GlobalSpecRestoredResponse restore(String specId, SpecRestoreRequest request) {
    requireStore();
    var existing = restorable(specId);
    var held = specStore.held(specId);
    authorize(specId, held, held);
    if (request.rev() == null || request.rev().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "rev is required.");
    }
    var revision = revision(specId, request.rev());
    validAssignee(Snapshots.text(revision, "assignee"));
    authorize(specId, held, revision);
    var store = rooms.get();
    specStore.atomically(
        () -> {
          specStore.restore(specId, request.rev());
          if (store != null && !existing.live()) {
            var row = specStore.findById(specId).orElseThrow();
            if (row.roomIdOrIdentity().equals(specId)) {
              store.restoreDeleted(specId);
            }
          }
          return null;
        });
    var row = specStore.findById(specId).orElseThrow();
    publishBoardUpdated(row.project(), specId, Event.SAIL_AGENT);
    return new GlobalSpecRestoredResponse(viewOf(row), request.rev());
  }

  /**
   * The spec a restore targets, live or deleted with its tombstone retained. A pruned spec is gone
   * with its history, and the refusal says so; an id never recorded is not found.
   */
  private SpecStore.LastKnown restorable(String specId) {
    var head =
        specStore
            .head(specId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
    if (head.kind() == ChangeLog.Kind.ERASURE) {
      throw new ApiException(ErrorCode.SPEC_PRUNED, specStore.pruned(specId));
    }
    return specStore.lastKnown(specId).orElseThrow();
  }

  /** Revision {@code rev} of spec {@code specId} as the spec rule reads it. */
  private Map<String, Object> revision(String specId, String rev) {
    var revision = specStore.comparableAtRev(specId, rev);
    if (revision == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, specStore.unrestorable(specId, rev));
    }
    return revision;
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

  /**
   * An assignee is an FDE handle, or blank for anyone to claim — never a run's principal, whose
   * shape ({@link RunStore#isPrincipalHandle}) no FDE handle has. A handle this box's roster does
   * not know yet is accepted: a node may not have synced a new FDE.
   */
  private static String validAssignee(String assignee) {
    if (RunStore.isPrincipalHandle(assignee)) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "Assignee '" + assignee + "' names a run, not an FDE.",
          "Give an FDE handle, or leave it blank for anyone to claim; the agent type goes in"
              + " --agent.");
    }
    return Strings.isBlank(assignee) ? null : assignee;
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
