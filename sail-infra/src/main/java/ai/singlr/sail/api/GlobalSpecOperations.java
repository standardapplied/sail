/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.PrincipalHandle;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
  private final BooleanSupplier authoritative;

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
    this(specStore, reviewStore, eventBus, runStore, rooms, () -> true);
  }

  /**
   * {@code authoritative} says whether this box resolves the findings a shipped follow-up was
   * drafted from: main and a box that syncs nobody do; a node leaves them to main, which resolves
   * them when the transition commits there ({@code ShippedFollowUps}).
   */
  GlobalSpecOperations(
      SpecStore specStore,
      ReviewStore reviewStore,
      EventBus eventBus,
      RunStore runStore,
      Supplier<RoomStore> rooms,
      BooleanSupplier authoritative) {
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.eventBus = eventBus;
    this.runStore = runStore;
    this.rooms = rooms;
    this.authoritative = authoritative;
  }

  GlobalSpecsListResponse list(SpecStore.SpecFilter filter) {
    requireStore();
    var specs = listed(filter).stream().map(this::viewOf).toList();
    return new GlobalSpecsListResponse(specs, specs.size());
  }

  /** The specs {@code filter} lists that the reader may see; a filter it cannot read is refused. */
  private List<SpecStore.SpecRow> listed(SpecStore.SpecFilter filter) {
    try {
      return readable(filter);
    } catch (IllegalArgumentException e) {
      throw ApiException.of(ErrorCode.INVALID_REQUEST, e);
    }
  }

  /** The specs matching {@code filter} that the reader may see. */
  List<SpecStore.SpecRow> readable(SpecStore.SpecFilter filter) {
    return ReadFilter.readable(Actor.reader(), specStore.list(filter), SpecStore.SpecRow::project);
  }

  GlobalSpecDetailResponse get(String specId) {
    requireStore();
    return detail(findOrThrow(specId), null);
  }

  /**
   * The detail of spec {@code specId} carrying its measures, as {@link #measures} computes them on
   * the box {@code box} names.
   */
  GlobalSpecDetailResponse measured(String specId, String box) {
    requireMeasured();
    var row = findOrThrow(specId);
    return detail(row, measuresOf(List.of(row), box).getFirst());
  }

  private GlobalSpecDetailResponse detail(SpecStore.SpecRow row, SpecMeasures measures) {
    var content = specStore.getContent(row.id()).orElse(null);
    return new GlobalSpecDetailResponse(
        viewOf(row),
        content != null ? content.body() : null,
        content != null ? content.plan() : null,
        openFindingCount(row.id()),
        latestRun(row.id()),
        measures,
        null);
  }

  /**
   * What spec {@code specId}'s change took, as the box {@code box} names records it ({@link
   * SpecMeasures}): a spec the reader may not read is one that is not there (M13).
   */
  SpecMeasures measures(String specId, String box) {
    requireMeasured();
    return measuresOf(List.of(findOrThrow(specId)), box).getFirst();
  }

  /**
   * The measures of every spec {@code filter} lists that the reader sees, done within {@code
   * window}, read in as many statements however many specs it lists.
   */
  SpecMeasuresList listMeasures(
      SpecStore.SpecFilter filter, SpecMeasuresList.Window window, String box) {
    requireMeasured();
    return SpecMeasuresList.of(measuresOf(listed(filter), box), window);
  }

  /**
   * The rows of {@code specs} the reader sees, measured, one read of each kind for all of them:
   * their revisions on this box, every entry compacted away or filed where the reader does not read
   * a gap; their reviews and the findings of those it sees; their runs, as {@code GET
   * /v1/runs?spec=} lists one spec's; and, on a box that authors erasures, its latest erasure of a
   * run by retention. A review or run the reader may not see is not counted and is not counted as
   * none either. Only reads.
   */
  private List<SpecMeasures> measuresOf(List<SpecStore.SpecRow> specs, String box) {
    var reader = Actor.reader();
    var ids = specs.stream().map(SpecStore.SpecRow::id).toList();
    var history = byKey(specStore.histories(ids), ChangeLog.Entry::entityId);
    var compacted = byKey(specStore.compacted(ids), ChangeLog.Head::entityId);
    var everyReview = reviewStore.reviewsForSpecs(ids);
    var filed =
        reviewStore.projectsOf(everyReview.stream().map(ReviewStore.ReviewRow::id).toList());
    var seenReviews =
        ReadFilter.readable(reader, everyReview, review -> filed.get(review.id())).stream()
            .map(ReviewStore.ReviewRow::id)
            .collect(Collectors.toSet());
    var findings = reviewStore.findingsForReviews(seenReviews);
    var reviews = byKey(everyReview, ReviewStore.ReviewRow::specId);
    var everyRun = byKey(runStore.listForConversations(ids), RunStore.RunRow::conversationId);
    var erasures =
        authoritative.getAsBoolean()
            ? new MeasureRows.RunErasures(true, runStore.lastErasure(SpecPruner.RETENTION))
            : MeasureRows.RunErasures.UNTOLD;
    var sees = ReadFilter.<ChangeLog.Entry>readable(reader, GlobalSpecOperations::projectOf);
    return specs.stream()
        .map(
            spec -> {
              var id = spec.id();
              var held = history.getOrDefault(id, List.of());
              var gaps = new HashSet<Long>();
              compacted.getOrDefault(id, List.of()).forEach(dropped -> gaps.add(dropped.seq()));
              held.stream().filter(sees.negate()).map(ChangeLog.Entry::seq).forEach(gaps::add);
              var ofSpec = reviews.getOrDefault(id, List.of());
              var seen =
                  ofSpec.stream().filter(review -> seenReviews.contains(review.id())).toList();
              var runs = everyRun.getOrDefault(id, List.of());
              var seenRuns = ReadFilter.readable(reader, runs, RunStore.RunRow::project);
              return SpecMeasures.of(
                  new MeasureRows(
                      id,
                      spec.status(),
                      box,
                      held.stream().filter(sees).toList(),
                      gaps,
                      new MeasureRows.Seen<>(seen, seen.size() < ofSpec.size()),
                      seen.stream()
                          .flatMap(review -> findings.getOrDefault(review.id(), List.of()).stream())
                          .toList(),
                      new MeasureRows.Seen<>(seenRuns, seenRuns.size() < runs.size()),
                      erasures));
            })
        .toList();
  }

  private static <T> Map<String, List<T>> byKey(List<T> rows, Function<T, String> key) {
    return rows.stream().collect(Collectors.groupingBy(key));
  }

  private void requireMeasured() {
    requireStore();
    if (runStore == null) {
      throw SailOperations.storeUnavailable("Run store");
    }
    if (reviewStore == null) {
      throw SailOperations.storeUnavailable("Review store");
    }
  }

  /**
   * The spec's most recent run the reader may see as a compact summary (id, node, status, exit
   * code), or null when there is none. A run is of the project it ran in, which a spec moved since
   * is no longer of. Lets a client gate its "open logs" button on provenance up front — one call,
   * no second round-trip to find where the run executed.
   */
  private RunSummary latestRun(String specId) {
    if (runStore == null) {
      return null;
    }
    return ReadFilter.readable(
            Actor.reader(), runStore.listForSpec(specId), RunStore.RunRow::project)
        .stream()
        .findFirst()
        .map(RunSummary::from)
        .orElse(null);
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
          "Pass --project <name>, or set a current project with project switch.");
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
            null,
            null,
            null,
            request.dependsOn(),
            request.repos());
    var created = specStore.atomically(() -> birth(row, request));
    publishBoardUpdated(created.project(), created.id(), principal(actor.handle()));
    return new GlobalSpecCreatedResponse(viewOf(created));
  }

  /**
   * The birth itself, one transaction on the connection every room and spec write shares: the
   * home-room admission, the row, and the identity mint commit together or not at all. A room
   * already holding the spec's own id is the home it is born in: whether it may be — the room, and
   * the conversation in it, become the spec's — is the spec rule's, decided as the row is
   * journaled, here as on main, and the spec then adopts that room and mints none. A room outside
   * the caller's scope is never one to be born into, and is refused before anything is said of it.
   */
  private SpecStore.SpecRow birth(SpecStore.SpecRow row, SpecCreateRequest request) {
    var home =
        Strings.isNotBlank(request.roomId())
            ? requireHomeRoom(request.roomId())
            : identityRoom(request.id());
    if (home != null) {
      requireSameProject(home, row);
    }
    if (specStore.findById(request.id()).isPresent()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Spec '" + request.id() + "' already exists.",
          Remedy.of(
              "Pick another spec id, or edit it with: ", Remedy.sail("spec update", request.id())));
    }
    var attached = row.withRoomId(home != null ? home.id() : request.id());
    specStore.create(
        attached,
        Objects.requireNonNullElse(request.body(), ""),
        Objects.requireNonNullElse(request.plan(), ""));
    var born = specStore.findById(request.id()).orElseThrow();
    if (home == null) {
      mintIdentityRoom(born);
    }
    return born;
  }

  /**
   * The room a spec is explicitly born into — a brainstorm's {@code SAIL_ROOM_ID}, an epic's room —
   * must already exist, and be one the caller may see: a room outside its scope is the room that
   * was not found. A spec with a home room mints no identity room: the room it was born in is its
   * conversation.
   */
  private RoomStore.RoomRow requireHomeRoom(String roomId) {
    NameValidator.requireValidSpecId(roomId);
    var store = rooms.get();
    if (store == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "This box keeps no rooms, so a spec cannot be born in room '" + roomId + "'.",
          Remedy.of("Start the server with '", Remedy.sail("server start"), "' or omit the room."));
    }
    return ReadFilter.readable(Actor.reader(), store.findById(roomId), RoomStore.RoomRow::project)
        .orElseThrow(() -> Refusals.roomNotFound(roomId));
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
    var wake =
        request.wake() == null || rooms.get() == null ? null : new Wake(validWake(request.wake()));
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
    var result = specStore.atomically(() -> write(existing, updated, wake));
    if (result.status() != existing.status()) {
      publishStatusChanged(
          result.project(), specId, existing.status(), result.status(), principal(actor.handle()));
    } else {
      publishBoardUpdated(result.project(), specId, principal(actor.handle()));
    }
    return new GlobalSpecUpdatedResponse(viewOf(result));
  }

  /**
   * The update itself, one transaction: the row, its wake on the room it lives in, and, on a move
   * into or out of {@code done}, the findings that move resolves. Each write is decided as it is
   * journaled, so a refusal of any of them leaves none.
   */
  private SpecStore.SpecRow write(
      SpecStore.SpecRow existing, SpecStore.SpecRow updated, Wake wake) {
    var wasDone = existing.status() == SpecStatus.DONE;
    var isDone = updated.status() == SpecStatus.DONE;
    if (wasDone && !isDone) {
      catchUpOnShippedFollowUps();
    }
    specStore.update(updated);
    if (wake != null) {
      writeWake(updated, wake.value());
    }
    if (isDone && !wasDone && reviewStore != null) {
      catchUpOnShippedFollowUps();
      reviewStore.resolveShippedFindings(updated.id());
    }
    return specStore.findById(updated.id()).orElseThrow();
  }

  /**
   * The row as a wire view, wake and roster decorated from its room — the fields' only home — when
   * the reader may see that room. A room is of its own project, which a spec moved since has left:
   * such a spec reads as one whose room has not arrived.
   */
  private GlobalSpecView viewOf(SpecStore.SpecRow row) {
    var store = rooms.get();
    var room =
        store == null
            ? null
            : ReadFilter.readable(
                    Actor.reader(),
                    store.findById(row.roomIdOrIdentity()),
                    RoomStore.RoomRow::project)
                .orElse(null);
    return GlobalSpecView.from(row, room);
  }

  /**
   * The room already holding spec id {@code specId}, which a spec born under it adopts. One outside
   * the caller's scope is refused as the spec rule refuses a room that is not the caller's to
   * adopt: the id is taken, and nothing else is said of what holds it.
   */
  private RoomStore.RoomRow identityRoom(String specId) {
    var store = rooms.get();
    var held = store == null ? null : store.findById(specId).orElse(null);
    if (held != null && !ReadFilter.reads(Actor.reader(), held::project)) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Room '" + specId + "' already exists, and a spec's id is reserved for its own room.",
          "Pick another spec id.");
    }
    return held;
  }

  /**
   * Mints the spec's identity room — same id, the conversation surface every spec gets — when this
   * box keeps a room aggregate. A strict insert, never an ensure: the birth found no room on the id
   * before the row was written, so one there now was never the rule's to decide, and the birth
   * fails loudly rather than adopt it. Membership writes still {@code ensureFor} defensively, so a
   * box without the aggregate here loses nothing.
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
   * Writes an explicit wake edit onto the spec's home room — the one home the wake mode has, and
   * the same room messages, roster, and the spec view read. For a spec living in another room it is
   * a revision of that room, which the room rule decides as it is journaled. A room the caller may
   * not see — one a spec moved into the caller's scope left behind — is the room that was not
   * found, before the journal can say its id is taken.
   */
  private void writeWake(SpecStore.SpecRow updated, String wake) {
    var store = rooms.get();
    var roomId = updated.roomIdOrIdentity();
    var held = store.findById(roomId);
    var room =
        held.isPresent()
            ? ReadFilter.readable(Actor.reader(), held, RoomStore.RoomRow::project)
                .orElseThrow(() -> Refusals.roomNotFound(roomId))
            : store.ensureFor(roomId, updated.project(), updated.title(), updated.assignee(), null);
    if (!Objects.equals(room.wake(), wake)) {
      store.updateWake(roomId, wake);
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

  /**
   * Deletes a spec, and the identity room it minted when the caller may see it: one a spec moved
   * into the caller's scope left in another project stays there, for an admin or retention.
   */
  GlobalSpecDeletedResponse delete(String specId) {
    requireStore();
    var existing = findOrThrow(specId);
    var store = rooms.get();
    var mintedItsRoom = existing.roomIdOrIdentity().equals(specId);
    specStore.atomically(
        () -> {
          if (existing.status() == SpecStatus.DONE) {
            catchUpOnShippedFollowUps();
          }
          specStore.delete(specId);
          if (store != null
              && mintedItsRoom
              && ReadFilter.readable(
                      Actor.reader(), store.findById(specId), RoomStore.RoomRow::project)
                  .isPresent()) {
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
    var content = specStore.getContent(specId).orElse(new SpecStore.SpecContent("", ""));
    return new GlobalSpecContentResponse(specId, content.body(), content.plan());
  }

  GlobalSpecContentResponse setContent(String specId, SpecContentRequest request) {
    requireStore();
    var existing = findOrThrow(specId);
    specStore.setContent(
        specId,
        Objects.requireNonNullElse(request.body(), ""),
        Objects.requireNonNullElse(request.plan(), ""));
    var content = specStore.getContent(specId).orElseThrow();
    publishBoardUpdated(existing.project(), specId, Event.SAIL_AGENT);
    return new GlobalSpecContentResponse(specId, content.body(), content.plan());
  }

  /** The board of {@code project}, or of every project, counted from the specs the reader sees. */
  GlobalBoardResponse board(String project) {
    requireStore();
    return new GlobalBoardResponse(
        specStore.board(project, Actor.reader()), doneOpenFindings(project));
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
    return readable(new SpecStore.SpecFilter(project, SpecStatus.DONE.wire(), null, null, null))
        .stream()
        .mapToInt(spec -> openFindingCount(spec.id()))
        .sum();
  }

  /**
   * The open findings {@code specId} shipped with, counted only where the reader may see the review
   * that holds them: one filed under another project counts for nothing here.
   */
  private int openFindingCount(String specId) {
    if (reviewStore == null) {
      return 0;
    }
    return ReadFilter.readable(
            Actor.reader(), reviewStore.latestReviewForSpec(specId), reviewStore::projectOf)
        .map(seen -> reviewStore.openFindingsAfterPass(specId).size())
        .orElse(0);
  }

  /**
   * The revisions of {@code specId} the reader may see. Each is of the project its snapshot names,
   * so a spec that moved between projects shows a reader the revisions made in theirs, and one that
   * kept no snapshot, as a tombstone this box only heard of does, is of no known project.
   */
  GlobalSpecHistoryResponse history(String specId) {
    requireStore();
    return GlobalSpecHistoryResponse.from(
        specId,
        ReadFilter.readable(
            Actor.reader(), specStore.history(specId), GlobalSpecOperations::projectOf));
  }

  private static String projectOf(ChangeLog.Entry revision) {
    var json = revision.snapshot();
    return Strings.isBlank(json) ? null : Snapshots.text(YamlUtil.parseMap(json), "project");
  }

  /**
   * A restore is a revision of the spec, decided by the spec rule as it is journaled: a historical
   * snapshot carries the assignee, so one that changes it is a reassignment the claim rule decides,
   * and no assignee routes around the admin-only reassign rule by restoring a revision owned by
   * someone else.
   */
  GlobalSpecRestoredResponse restore(String specId, SpecRestoreRequest request) {
    requireStore();
    var existing = restorable(specId);
    if (request.rev() == null || request.rev().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "rev is required.");
    }
    validAssignee(Snapshots.text(revision(specId, request.rev()), "assignee"));
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
   * with its history, and the refusal says so; an id never recorded is not found, and neither is
   * one whose head is of a project outside the caller's scope, whatever became of it.
   */
  private SpecStore.LastKnown restorable(String specId) {
    var head =
        ReadFilter.readable(
                Actor.reader(),
                specStore.head(specId),
                found -> specStore.headProject(specId).orElse(null))
            .orElseThrow(() -> Refusals.specNotFound(specId));
    if (head.kind() == ChangeLog.Kind.ERASURE) {
      throw new ApiException(ErrorCode.SPEC_PRUNED, specStore.pruned(specId));
    }
    return specStore.lastKnown(specId).orElseThrow();
  }

  /**
   * Revision {@code rev} of spec {@code specId} as the spec rule reads it. One made in a project
   * outside the caller's scope, which the history hides from it, is one its history does not hold.
   */
  private Map<String, Object> revision(String specId, String rev) {
    return ReadFilter.readable(
            Actor.reader(),
            Optional.ofNullable(specStore.comparableAtRev(specId, rev)),
            revision -> Snapshots.text(revision, "project"))
        .orElseThrow(
            () -> ApiException.of(ErrorCode.INVALID_REQUEST, specStore.unrestorable(specId, rev)));
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

  /**
   * The spec an operation addresses, to read or to change: one outside the caller's scope is
   * answered as one that is not there.
   */
  private SpecStore.SpecRow findOrThrow(String specId) {
    return ReadFilter.readable(
            Actor.reader(), specStore.findById(specId), SpecStore.SpecRow::project)
        .orElseThrow(() -> Refusals.specNotFound(specId));
  }

  /**
   * Fixes the findings of every shipped follow-up, as this box's machinery: after a transition into
   * {@code done}, for the follow-up itself, and before a done spec leaves {@code done} or is
   * deleted here, so a resolution an earlier run lost lands while the follow-up still reads as
   * shipped. Only an authoritative box writes them.
   */
  private void catchUpOnShippedFollowUps() {
    if (reviewStore != null && authoritative.getAsBoolean()) {
      Actor.run(Actor.system(), reviewStore::resolveFindingsOfShippedFollowUps);
    }
  }

  private void requireStore() {
    if (specStore == null) {
      throw SailOperations.storeUnavailable("Spec store");
    }
  }

  /**
   * An assignee is an FDE handle, or blank for anyone to claim — never a run's principal, whose
   * shape ({@link PrincipalHandle#matches}) no FDE handle has. A handle this box's roster does not
   * know yet is accepted: a node may not have synced a new FDE.
   */
  private static String validAssignee(String assignee) {
    if (PrincipalHandle.matches(assignee)) {
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
      throw ApiException.of(ErrorCode.INVALID_REQUEST, e);
    }
  }

  private static String validReasoning(String reasoningEffort) {
    try {
      return Spec.validatedReasoningEffort(reasoningEffort);
    } catch (IllegalArgumentException e) {
      throw ApiException.of(ErrorCode.INVALID_REQUEST, e);
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
      throw ApiException.of(ErrorCode.INVALID_REQUEST, e);
    }
  }
}
