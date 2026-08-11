/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.MessageStore;
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
  private final RunStore runStore;
  private final MessageStore messageStore;
  private final Supplier<String> localHandle;
  private final Waker waker;
  private final Guard guard;
  private final Duration debounce;
  private final Duration cooldown;
  private final ExecutorService executor;
  private final Delay delay;
  private final Supplier<Instant> clock;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();

  public RoomWakeReactor(
      SpecStore specStore,
      RunStore runStore,
      MessageStore messageStore,
      Supplier<String> localHandle,
      Waker waker,
      Guard guard) {
    this(
        specStore,
        runStore,
        messageStore,
        localHandle,
        waker,
        guard,
        DEBOUNCE,
        COOLDOWN,
        Executors.newVirtualThreadPerTaskExecutor(),
        Thread::sleep,
        DateTimeUtils::now);
  }

  RoomWakeReactor(
      SpecStore specStore,
      RunStore runStore,
      MessageStore messageStore,
      Supplier<String> localHandle,
      Waker waker,
      Guard guard,
      Duration debounce,
      Duration cooldown,
      ExecutorService executor,
      Delay delay,
      Supplier<Instant> clock) {
    this.specStore = Objects.requireNonNull(specStore, "specStore");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.messageStore = messageStore;
    this.localHandle = Objects.requireNonNull(localHandle, "localHandle");
    this.waker = Objects.requireNonNull(waker, "waker");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.debounce = debounce;
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
    if (spec == null || !ownsSpec(spec)) {
      return;
    }
    var message = messageOf(event);
    if (!RoomWakePolicy.shouldWake(
        spec.wake(), dispatchedAtLeastOnce(specId), message.author(), message.body())) {
      return;
    }
    if (!pending.add(specId)) {
      return;
    }
    executor.execute(() -> debounceThenFire(specId, message));
  }

  private void debounceThenFire(String specId, Message message) {
    try {
      delay.pause(debounce);
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
      if (spec == null || !ownsSpec(spec)) {
        return;
      }
      if (!RoomWakePolicy.shouldWake(
          spec.wake(), dispatchedAtLeastOnce(specId), message.author(), message.body())) {
        return;
      }
      var runs = runStore.listForSpec(specId);
      if (runs.stream().anyMatch(run -> LIVE_STATUSES.contains(run.status()))) {
        return;
      }
      if (withinCooldown(runs)) {
        return;
      }
      waker.wake(spec.project(), specId);
    } catch (Exception e) {
      System.err.println("room-wake: wake of spec " + specId + " failed: " + e.getMessage());
    }
  }

  private void handleStop(Event event) throws Exception {
    if (!Event.WellKnownData.RUN_ROLE_ROOM.equals(event.data().get(Event.WellKnownData.RUN_ROLE))) {
      return;
    }
    var runId = Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null);
    if (Strings.isBlank(runId)) {
      return;
    }
    var run = runStore.findById(runId).orElse(null);
    if (run == null || !SailOperations.ownsRun(run.node(), localHandle.get())) {
      return;
    }
    guard.guard(run.project(), runId);
  }

  private boolean ownsSpec(SpecStore.SpecRow spec) {
    var handle = localHandle.get();
    return Strings.isNotBlank(handle) && handle.equals(spec.assignee());
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

  @Override
  public void close() {
    executor.close();
  }
}
