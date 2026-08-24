/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The standing-member reactor: a human message posted to a spec's room while no run is live wakes
 * the agent as a new, fully-guarded {@code room}-role run that answers in the room. Subscribed to
 * {@code spec_message_posted} on every box, but it fires only on the dispatch-owning one — the box
 * whose handle equals the spec's assignee — so the fleet has exactly one waker per spec, and a
 * sync-arrived message (a Mast reply landing on main and syncing over) counts the same as a
 * locally-posted one.
 *
 * <p>Timing is deliberately dumb — no content heuristics. A {@link #DEBOUNCE} window batches the
 * triggering message with any that follow (they all ride the wake prompt, which reads the room at
 * launch). A {@link #COOLDOWN} after any run finish suppresses the thank-you refire and covers the
 * review loop's inter-iteration gaps; a live run of the spec — any role, any box — suppresses the
 * wake outright, because the relay and the stop-gate last look own delivery to a live run. The
 * launch itself rides the same atomic reservation as dispatch, so a race that slips past these
 * checks is refused by the gate, never doubled.
 *
 * <p>The reactor also hears {@code room}-role stop signals: a wake turn that somehow moved a repo
 * is a guardrail event, and {@link Guard#guard} performs that check for runs this box owns.
 *
 * <p>Failures are logged and swallowed — a broken wake must never take the bus down — and every
 * launch failure surfaces through the launcher's own refusal messages.
 */
public final class RoomWakeReactor implements EventSubscriber, AutoCloseable {

  /** How long a triggering message waits so the messages right behind it ride the same wake. */
  public static final Duration DEBOUNCE = Duration.ofSeconds(30);

  /**
   * The engaged room's debounce: an agent someone deliberately put in the room answers promptly, so
   * the batching window shrinks to what still catches a quick follow-up keystroke.
   */
  public static final Duration ENGAGED_DEBOUNCE = Duration.ofSeconds(5);

  /** How long after any run finish the room stays quiet — no thank-you refire, no loop sniping. */
  public static final Duration COOLDOWN = Duration.ofMinutes(10);

  /** The wake seam, implemented by the dispatch machinery's {@code startRoomRun}. */
  @FunctionalInterface
  public interface Waker {
    void wake(String project, String specId) throws Exception;
  }

  /** The commit-guard seam, implemented by the dispatch machinery's {@code guardRoomRun}. */
  @FunctionalInterface
  public interface Guard {
    void guard(String project, String runId) throws Exception;
  }

  /** The debounce pause, injectable so tests never sleep. */
  @FunctionalInterface
  public interface Delay {
    void pause(Duration duration) throws InterruptedException;
  }

  private static final Set<String> LIVE_STATUSES = Set.of("running", "stopping");

  private final SpecStore specStore;
  private final RoomStore roomStore;
  private final RunStore runStore;
  private final MessageStore messageStore;
  private final Supplier<String> localHandle;
  private final Waker waker;
  private final Guard guard;
  private final Duration debounce;
  private final Duration engagedDebounce;
  private final Duration cooldown;
  private final ExecutorService executor;
  private final Delay delay;
  private final Supplier<Instant> clock;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final PeriodicPass sweepPass =
      new PeriodicPass("room-engagement", this::sweepEngagedRooms);

  public RoomWakeReactor(
      SpecStore specStore,
      RoomStore roomStore,
      RunStore runStore,
      MessageStore messageStore,
      Supplier<String> localHandle,
      Waker waker,
      Guard guard) {
    this(
        specStore,
        roomStore,
        runStore,
        messageStore,
        localHandle,
        waker,
        guard,
        DEBOUNCE,
        ENGAGED_DEBOUNCE,
        COOLDOWN,
        Executors.newVirtualThreadPerTaskExecutor(),
        Thread::sleep,
        DateTimeUtils::now);
  }

  RoomWakeReactor(
      SpecStore specStore,
      RoomStore roomStore,
      RunStore runStore,
      MessageStore messageStore,
      Supplier<String> localHandle,
      Waker waker,
      Guard guard,
      Duration debounce,
      Duration engagedDebounce,
      Duration cooldown,
      ExecutorService executor,
      Delay delay,
      Supplier<Instant> clock) {
    this.specStore = Objects.requireNonNull(specStore, "specStore");
    this.roomStore = Objects.requireNonNull(roomStore, "roomStore");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.messageStore = messageStore;
    this.localHandle = Objects.requireNonNull(localHandle, "localHandle");
    this.waker = Objects.requireNonNull(waker, "waker");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.debounce = debounce;
    this.engagedDebounce = engagedDebounce;
    this.cooldown = cooldown;
    this.executor = executor;
    this.delay = delay;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "room-wake";
  }

  @Override
  public Predicate<Event> filter() {
    return e ->
        (Event.WellKnownTypes.SPEC_MESSAGE_POSTED.equals(e.type())
                || Event.WellKnownTypes.AGENT_SESSION_STOPPED.equals(e.type()))
            && e.spec() != null
            && !e.spec().isBlank();
  }

  @Override
  public void onEvent(Event event) {
    try {
      if (Event.WellKnownTypes.SPEC_MESSAGE_POSTED.equals(event.type())) {
        handleMessage(event);
      } else {
        handleStop(event);
      }
    } catch (Exception e) {
      System.err.println(
          "room-wake: failed to process "
              + event.type()
              + " for spec "
              + event.spec()
              + ": "
              + e.getMessage());
    }
  }

  private void handleMessage(Event event) {
    var specId = event.spec();
    var spec = specStore.findById(specId).orElse(null);
    if (spec == null || !spec.assignedTo(localHandle.get())) {
      return;
    }
    var state = MembershipService.stateOf(roomStore, spec);
    var engaged = state.standing() != null;
    var message = messageOf(event);
    if (!RoomWakePolicy.shouldWake(
        state.wake(), dispatchedAtLeastOnce(specId), engaged, message.author(), message.body())) {
      return;
    }
    schedule(specId, message, engaged);
  }

  private void schedule(String specId, Message message, boolean engaged) {
    if (!pending.add(specId)) {
      return;
    }
    executor.execute(() -> debounceThenFire(specId, message, engaged ? engagedDebounce : debounce));
  }

  private void debounceThenFire(String specId, Message message, Duration pause) {
    try {
      delay.pause(pause);
      fire(specId, message);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      pending.remove(specId);
    }
  }

  private void fire(String specId, Message message) {
    try {
      var spec = specStore.findById(specId).orElse(null);
      if (spec == null || !spec.assignedTo(localHandle.get())) {
        return;
      }
      var state = MembershipService.stateOf(roomStore, spec);
      var engaged = state.standing() != null;
      if (!RoomWakePolicy.shouldWake(
          state.wake(), dispatchedAtLeastOnce(specId), engaged, message.author(), message.body())) {
        return;
      }
      var runs = runStore.listForSpec(specId);
      if (engaged) {
        if (runs.stream().anyMatch(run -> LIVE_STATUSES.contains(run.status()) && run.chatRole())) {
          return;
        }
      } else {
        if (runs.stream().anyMatch(run -> LIVE_STATUSES.contains(run.status()))) {
          return;
        }
        if (withinCooldown(runs)) {
          return;
        }
      }
      waker.wake(spec.project(), specId);
    } catch (Exception e) {
      System.err.println("room-wake: wake of spec " + specId + " failed: " + e.getMessage());
    }
  }

  private void handleStop(Event event) throws Exception {
    guardChatStop(event);
    refireOwedTurn(event.spec());
  }

  private void guardChatStop(Event event) throws Exception {
    var role = event.data().get(Event.WellKnownData.RUN_ROLE);
    if (!Event.WellKnownData.RUN_ROLE_ROOM.equals(role)
        && !Event.WellKnownData.RUN_ROLE_INVITE.equals(role)) {
      return;
    }
    var runId = Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null);
    if (Strings.isBlank(runId)) {
      return;
    }
    var run = runStore.findById(runId).orElse(null);
    if (run == null || !run.ownedBy(localHandle.get())) {
      return;
    }
    guard.guard(run.project(), runId);
  }

  /**
   * The stop edge of the engaged loop: a message that landed in a turn's tail — after the relay's
   * last check — or a full turn deferred behind a build's repo claim gets its turn when any run of
   * the spec stops. The owed check is derived, never bookkept: the newest human message arrived
   * after the newest chat turn started, so no turn has read it.
   */
  private void refireOwedTurn(String specId) {
    var spec = specStore.findById(specId).orElse(null);
    if (spec == null
        || !spec.assignedTo(localHandle.get())
        || MembershipService.stateOf(roomStore, spec).standing() == null) {
      return;
    }
    var owed = owedMessage(spec);
    if (owed == null) {
      return;
    }
    schedule(specId, owed, true);
  }

  /** The newest human message no chat turn has started after, or {@code null} when none is owed. */
  private Message owedMessage(SpecStore.SpecRow spec) {
    if (messageStore == null) {
      return null;
    }
    var newestHuman =
        messageStore.list(spec.roomIdOrIdentity(), null, 20).stream()
            .filter(row -> RoomWakePolicy.humanAuthor(row.author()))
            .reduce((first, second) -> second)
            .orElse(null);
    if (newestHuman == null) {
      return null;
    }
    var humanAt = parseInstant(newestHuman.createdAt());
    if (humanAt == null) {
      return null;
    }
    var newestChatStart =
        runStore.listForSpec(spec.id()).stream()
            .filter(RunStore.RunRow::chatRole)
            .map(RunStore.RunRow::startedAt)
            .filter(Strings::isNotBlank)
            .map(RoomWakeReactor::parseInstant)
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(Instant.MIN);
    return humanAt.isAfter(newestChatStart)
        ? new Message(newestHuman.author(), newestHuman.body())
        : null;
  }

  /**
   * The engaged loop's safety net, run by a periodic pass: any engaged room this box owns whose
   * newest human message no chat turn has answered gets its turn scheduled — a crashed debounce, a
   * lost event, or a turn deferred behind a build must never strand a conversation.
   */
  public void sweepEngagedRooms() {
    var engaged = new java.util.LinkedHashSet<String>();
    roomStore.listEngaged().forEach(room -> engaged.add(room.id()));
    specStore.listEngaged().forEach(spec -> engaged.add(spec.id()));
    for (var id : engaged) {
      try {
        var spec = specStore.findById(id).orElse(null);
        if (spec != null && spec.assignedTo(localHandle.get())) {
          refireOwedTurn(spec.id());
        }
      } catch (RuntimeException e) {
        System.err.println(
            "room-wake: engagement sweep of room " + id + " failed: " + e.getMessage());
      }
    }
  }

  private boolean dispatchedAtLeastOnce(String specId) {
    return runStore.listForSpec(specId).stream().anyMatch(RunStore.RunRow::buildRole);
  }

  private boolean withinCooldown(java.util.List<RunStore.RunRow> runs) {
    var now = clock.get();
    return runs.stream()
        .map(RunStore.RunRow::completedAt)
        .filter(Strings::isNotBlank)
        .map(RoomWakeReactor::parseInstant)
        .filter(Objects::nonNull)
        .anyMatch(completedAt -> Duration.between(completedAt, now).compareTo(cooldown) < 0);
  }

  private static Instant parseInstant(String value) {
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      return null;
    }
  }

  private record Message(String author, String body) {}

  /**
   * The triggering message's author and body — the store row when it is available (the body drives
   * {@code mention} matching), the event's author and preview otherwise, so a box without a message
   * store still gates on authorship.
   */
  private Message messageOf(Event event) {
    var messageId = Objects.toString(event.data().get("message_id"), null);
    if (messageStore != null && messageId != null) {
      var row = messageStore.findById(messageId).orElse(null);
      if (row != null) {
        return new Message(row.author(), row.body());
      }
    }
    return new Message(event.agent(), Objects.toString(event.data().get("preview"), ""));
  }

  /** Arms the engagement sweep at the given cadence — the safety net behind the event edges. */
  public void startSweep(Duration interval) {
    sweepPass.start(interval);
  }

  @Override
  public void close() {
    sweepPass.close();
    executor.close();
  }
}
