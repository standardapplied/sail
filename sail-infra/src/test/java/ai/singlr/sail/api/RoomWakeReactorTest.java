/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wake reactor's whole routing table, driven synchronously: a same-thread executor and a
 * zero-length debounce make every decision observable without a sleep, and a manual executor makes
 * the debounce batching itself observable.
 */
class RoomWakeReactorTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private RunStore runStore;
  private MessageStore messageStore;
  private RecordingLauncher launcher;
  private final AtomicReference<String> handle = new AtomicReference<>("uday");
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-08-11T12:00:00Z"));

  private static final class RecordingLauncher implements RoomWakeReactor.Launcher {
    final List<String> woken = new ArrayList<>();
    final List<String> guarded = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public void wake(String project, String specId) {
      if (failWith != null) {
        throw failWith;
      }
      woken.add(project + "/" + specId);
    }

    @Override
    public void guard(String project, String runId) {
      if (failWith != null) {
        throw failWith;
      }
      guarded.add(project + "/" + runId);
    }
  }

  private static final class ManualExecutorService
      extends java.util.concurrent.AbstractExecutorService {
    final Deque<Runnable> queued = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      queued.add(command);
    }

    void drain() {
      while (!queued.isEmpty()) {
        queued.pop().run();
      }
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
      return true;
    }
  }

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    runStore = new RunStore(db);
    messageStore = new MessageStore(db);
    launcher = new RecordingLauncher();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private RoomWakeReactor reactor() {
    return reactor(new DirectExecutorService(), messageStore);
  }

  private RoomWakeReactor reactor(java.util.concurrent.ExecutorService executor) {
    return reactor(executor, messageStore);
  }

  private RoomWakeReactor reactor(
      java.util.concurrent.ExecutorService executor, MessageStore messages) {
    return new RoomWakeReactor(
        specStore,
        runStore,
        messages,
        handle::get,
        launcher,
        Duration.ZERO,
        RoomWakeReactor.COOLDOWN,
        executor,
        duration -> {},
        now::get);
  }

  private void seed(String id, String status, String assignee, String wake) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            id,
            SpecStatus.fromWire(status),
            assignee,
            null,
            null,
            null,
            null,
            0,
            "uday",
            "",
            "",
            null,
            List.of(),
            List.of(),
            wake));
  }

  private String buildRun(String specId, String status) {
    var id = DateTimeUtils.newId().toString();
    runStore.create(
        id,
        "acme",
        specId,
        "uday",
        "uday",
        "build",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + id);
    if (!"running".equals(status)) {
      if ("stopping".equals(status)) {
        runStore.transition(id, "running", "stopping");
      } else {
        runStore.complete(id, status, 0);
      }
    }
    return id;
  }

  private void finishAt(String runId, Instant completedAt) {
    db.execute("UPDATE runs SET completed_at = ? WHERE id = ?", completedAt.toString(), runId);
  }

  private Event message(String specId, String author, String body) {
    var row = messageStore.append(specId, author, body, null);
    return Event.of(
        "acme",
        specId,
        Event.WellKnownTypes.SPEC_MESSAGE_POSTED,
        author,
        "host",
        Map.of("message_id", row.id(), "preview", body));
  }

  private Event roomStop(String specId, String runId) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    data.put(Event.WellKnownData.RUN_ID, runId);
    data.put(Event.WellKnownData.RUN_ROLE, "room");
    return Event.of(
        "acme", specId, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host", data);
  }

  private void ageOutEveryFinish(String specId) {
    for (var run : runStore.listForSpec(specId)) {
      if (run.completedAt() != null) {
        finishAt(run.id(), now.get().minus(Duration.ofHours(2)));
      }
    }
  }

  @Test
  void nameAndFilterAdmitMessagesAndStopsWithASpec() {
    var reactor = reactor();
    assertEquals("room-wake", reactor.name());
    assertTrue(reactor.filter().test(message("auth", "uday", "hi")));
    seed("auth", "done", "uday", null);
    assertTrue(reactor.filter().test(roomStop("auth", DateTimeUtils.newId().toString())));
    assertFalse(
        reactor
            .filter()
            .test(Event.of("acme", null, Event.WellKnownTypes.SPEC_MESSAGE_POSTED, "u", "h")));
    assertFalse(
        reactor.filter().test(Event.of("acme", "auth", Event.WellKnownTypes.HEARTBEAT, "u", "h")));
  }

  @Test
  void aHumanMessageWakesADispatchedSpecWithNoLiveRun() {
    seed("auth", "done", "uday", null);
    var run = buildRun("auth", "completed");
    finishAt(run, now.get().minus(Duration.ofHours(1)));

    reactor().onEvent(message("auth", "uday", "what did you ship?"));

    assertEquals(List.of("acme/auth"), launcher.woken);
  }

  @Test
  void agentAndSailAuthoredMessagesNeverWake() {
    seed("auth", "done", "uday", null);
    ageOutEveryFinish("auth");
    buildRun("auth", "completed");
    ageOutEveryFinish("auth");
    var reactor = reactor();

    reactor.onEvent(message("auth", "sail", "Review passed."));
    reactor.onEvent(message("auth", "claude/room-1", "answered in the room"));

    assertTrue(launcher.woken.isEmpty(), "no storm loops: only humans wake the agent");
  }

  @Test
  void theWakeModeMatrixHolds() {
    seed("on-spec", "done", "uday", "on");
    seed("mention-spec", "done", "uday", "mention");
    seed("off-spec", "done", "uday", "off");
    seed("default-spec", "done", "uday", null);
    var reactor = reactor();

    reactor.onEvent(message("on-spec", "uday", "never dispatched but explicitly on"));
    reactor.onEvent(message("mention-spec", "uday", "no mention here"));
    reactor.onEvent(message("mention-spec", "uday", "hey @agent, look"));
    reactor.onEvent(message("off-spec", "uday", "@agent please"));
    reactor.onEvent(message("default-spec", "uday", "hello?"));

    assertEquals(
        List.of("acme/on-spec", "acme/mention-spec"),
        launcher.woken,
        "explicit on wakes, mention needs @agent, off never, unset+undispatched never");
  }

  @Test
  void onlyTheDispatchOwningBoxWakes() {
    seed("auth", "done", "mady", null);
    buildRun("auth", "completed");
    ageOutEveryFinish("auth");
    var reactor = reactor();

    reactor.onEvent(message("auth", "uday", "hello"));
    assertTrue(launcher.woken.isEmpty(), "assignee mady owns the wake, not this box");

    handle.set("");
    reactor.onEvent(message("auth", "uday", "hello again"));
    assertTrue(launcher.woken.isEmpty(), "a box with no handle wakes nothing");
  }

  @Test
  void aLiveRunSuppressesTheWakeWhateverItsRole() {
    seed("auth", "in_progress", "uday", "on");
    buildRun("auth", "running");
    var reactor = reactor();

    reactor.onEvent(message("auth", "uday", "steer left"));
    assertTrue(launcher.woken.isEmpty(), "the relay owns delivery to a live run");

    seed("stopping-spec", "in_progress", "uday", "on");
    buildRun("stopping-spec", "stopping");
    reactor.onEvent(message("stopping-spec", "uday", "hello"));
    assertTrue(launcher.woken.isEmpty(), "a mid-stop run still counts as live");
  }

  @Test
  void theCooldownAfterAnyFinishSuppressesAndThenExpires() {
    seed("auth", "awaiting_merge", "uday", null);
    var run = buildRun("auth", "completed");
    finishAt(run, now.get().minus(Duration.ofMinutes(5)));
    var reactor = reactor();

    reactor.onEvent(message("auth", "uday", "thanks!"));
    assertTrue(launcher.woken.isEmpty(), "the thank-you refire is exactly what cooldown prevents");

    now.set(now.get().plus(Duration.ofMinutes(6)));
    reactor.onEvent(message("auth", "uday", "actually, one question"));
    assertEquals(List.of("acme/auth"), launcher.woken);
  }

  @Test
  void aGarbageCompletedAtNeverSuppressesTheWake() {
    seed("auth", "done", "uday", null);
    var run = buildRun("auth", "completed");
    db.execute("UPDATE runs SET completed_at = 'not-a-timestamp' WHERE id = ?", run);

    reactor().onEvent(message("auth", "uday", "hello"));

    assertEquals(List.of("acme/auth"), launcher.woken, "an unreadable finish reads as no cooldown");
  }

  @Test
  void theWakeFiresOnParkedAndDoneSpecs() {
    for (var status : List.of("review", "awaiting_merge", "done")) {
      var specId = status + "-spec";
      seed(specId, status, "uday", null);
      buildRun(specId, "completed");
      ageOutEveryFinish(specId);

      reactor().onEvent(message(specId, "uday", "what is it stuck on?"));

      assertTrue(launcher.woken.contains("acme/" + specId), "wake must fire on " + status);
    }
  }

  @Test
  void theDebounceBatchesMessagesIntoOneWake() {
    seed("auth", "done", "uday", null);
    buildRun("auth", "completed");
    ageOutEveryFinish("auth");
    var executor = new ManualExecutorService();
    var reactor = reactor(executor);

    reactor.onEvent(message("auth", "uday", "first"));
    reactor.onEvent(message("auth", "uday", "second, right behind"));
    assertEquals(1, executor.queued.size(), "the second message rides the pending wake");

    executor.drain();
    assertEquals(List.of("acme/auth"), launcher.woken);

    reactor.onEvent(message("auth", "uday", "a later conversation"));
    executor.drain();
    assertEquals(List.of("acme/auth", "acme/auth"), launcher.woken, "the window re-arms");
  }

  @Test
  void theFireRecheckDropsAWakeWhoseConditionsChangedDuringTheDebounce() {
    seed("auth", "done", "uday", null);
    buildRun("auth", "completed");
    ageOutEveryFinish("auth");
    var executor = new ManualExecutorService();
    var reactor = reactor(executor);

    reactor.onEvent(message("auth", "uday", "hello"));
    specStore.update(specStore.findById("auth").orElseThrow().withWake("off"));
    executor.drain();
    assertTrue(launcher.woken.isEmpty(), "a mode flipped off mid-debounce wins");

    specStore.update(specStore.findById("auth").orElseThrow().withWake("on"));
    reactor.onEvent(message("auth", "uday", "hello again"));
    buildRun("auth", "running");
    executor.drain();
    assertTrue(launcher.woken.isEmpty(), "a dispatch that started mid-debounce wins");
  }

  @Test
  void aSpecDeletedDuringTheDebounceIsDroppedAtFireTime() {
    seed("auth", "done", "uday", null);
    buildRun("auth", "completed");
    ageOutEveryFinish("auth");
    var executor = new ManualExecutorService();
    var reactor = reactor(executor);

    reactor.onEvent(message("auth", "uday", "hello"));
    specStore.delete("auth");
    executor.drain();

    assertTrue(launcher.woken.isEmpty(), "a spec that vanished mid-debounce wakes nothing");
  }

  @Test
  void aSyncArrivedMessageWakesTheOwningBoxTheSame() {
    seed("auth", "done", "uday", null);
    buildRun("auth", "completed");
    ageOutEveryFinish("auth");
    var row = messageStore.append("auth", "uday", "from another box", null);
    var data = new LinkedHashMap<String, Object>();
    data.put("message_id", row.id());
    data.put("preview", "from another box");
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_SYNC);

    reactor()
        .onEvent(
            Event.of(
                "acme", "auth", Event.WellKnownTypes.SPEC_MESSAGE_POSTED, "uday", "main", data));

    assertEquals(List.of("acme/auth"), launcher.woken);
  }

  @Test
  void theEventItselfCarriesEnoughWhenTheStoreHasNoRow() {
    seed("auth", "done", "uday", "mention");
    buildRun("auth", "completed");
    ageOutEveryFinish("auth");
    var data = Map.<String, Object>of("preview", "please look @agent");

    reactor(new DirectExecutorService(), null)
        .onEvent(
            Event.of(
                "acme", "auth", Event.WellKnownTypes.SPEC_MESSAGE_POSTED, "uday", "host", data));

    assertEquals(List.of("acme/auth"), launcher.woken, "author and preview fall back to the event");
  }

  @Test
  void anUnknownSpecOrMissingMessageRowIsQuietlyIgnored() {
    var reactor = reactor();
    reactor.onEvent(
        Event.of("acme", "ghost", Event.WellKnownTypes.SPEC_MESSAGE_POSTED, "uday", "host"));
    assertTrue(launcher.woken.isEmpty());

    seed("auth", "done", "uday", "on");
    reactor.onEvent(
        Event.of("acme", "auth", Event.WellKnownTypes.SPEC_MESSAGE_POSTED, "uday", "host"));
    assertEquals(
        List.of("acme/auth"), launcher.woken, "no message row: the event's author decides");
  }

  @Test
  void aFailingLauncherIsLoggedAndSwallowed() {
    seed("auth", "done", "uday", "on");
    launcher.failWith = new IllegalStateException("container down");

    var reactor = reactor();
    reactor.onEvent(message("auth", "uday", "hello"));

    assertTrue(launcher.woken.isEmpty());
  }

  @Test
  void aBrokenStoreNeverTakesTheBusDown() {
    seed("auth", "done", "uday", "on");
    var event = message("auth", "uday", "hello");
    db.close();

    reactor().onEvent(event);

    db = Sqlite.open(tempDir.resolve("test.db"));
  }

  @Test
  void anInterruptedDebounceAbandonsTheWakeQuietly() {
    seed("auth", "done", "uday", "on");
    var reactor =
        new RoomWakeReactor(
            specStore,
            runStore,
            messageStore,
            handle::get,
            launcher,
            Duration.ZERO,
            RoomWakeReactor.COOLDOWN,
            new DirectExecutorService(),
            duration -> {
              throw new InterruptedException("shutdown");
            },
            now::get);

    reactor.onEvent(message("auth", "uday", "hello"));

    assertTrue(launcher.woken.isEmpty());
    assertTrue(Thread.interrupted(), "the interrupt flag is restored, then cleared here");
  }

  @Test
  void aRoomStopOnAnOwnedRunTriggersTheCommitGuard() {
    seed("auth", "done", "uday", null);
    var runId = DateTimeUtils.newId().toString();
    runStore.create(
        runId,
        "acme",
        "auth",
        "uday",
        "uday",
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);

    reactor().onEvent(roomStop("auth", runId));

    assertEquals(List.of("acme/" + runId), launcher.guarded);
  }

  @Test
  void stopsThatAreNotThisBoxsRoomRunsNeverGuard() {
    seed("auth", "done", "uday", null);
    var reactor = reactor();

    var buildStop = new LinkedHashMap<String, Object>();
    buildStop.put(Event.WellKnownData.RUN_ID, DateTimeUtils.newId().toString());
    buildStop.put(Event.WellKnownData.RUN_ROLE, "build");
    reactor.onEvent(
        Event.of(
            "acme",
            "auth",
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "claude-code",
            "host",
            buildStop));

    reactor.onEvent(roomStop("auth", DateTimeUtils.newId().toString()));

    var foreignId = DateTimeUtils.newId().toString();
    runStore.create(
        foreignId,
        "acme",
        "auth",
        "mady",
        "mady",
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + foreignId);
    reactor.onEvent(roomStop("auth", foreignId));

    var blankRunId = new LinkedHashMap<String, Object>();
    blankRunId.put(Event.WellKnownData.RUN_ROLE, "room");
    reactor.onEvent(
        Event.of(
            "acme",
            "auth",
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "claude-code",
            "host",
            blankRunId));

    assertTrue(launcher.guarded.isEmpty());
  }

  @Test
  void theBusDeliversAPostedMessageToTheReactorEndToEnd() throws Exception {
    seed("auth", "done", "uday", "on");
    var latch = new java.util.concurrent.CountDownLatch(1);
    var reactor = reactor();
    try (var bus = new EventBus()) {
      bus.subscribe(BusTesting.latching(reactor, latch));
      bus.publish(message("auth", "uday", "hello over the bus"));
      BusTesting.awaitDelivery(latch);
    }
    assertEquals(List.of("acme/auth"), launcher.woken);
  }

  @Test
  void theProductionConstructorWiresTheRealDefaults() {
    assertEquals(Duration.ofSeconds(30), RoomWakeReactor.DEBOUNCE);
    assertEquals(Duration.ofMinutes(10), RoomWakeReactor.COOLDOWN);
    try (var reactor =
        new RoomWakeReactor(specStore, runStore, messageStore, handle::get, launcher)) {
      assertEquals("room-wake", reactor.name());
    }
  }

  @Test
  void constructorRejectsMissingCollaborators() {
    assertThrows(
        NullPointerException.class,
        () -> new RoomWakeReactor(null, runStore, messageStore, handle::get, launcher));
    assertThrows(
        NullPointerException.class,
        () -> new RoomWakeReactor(specStore, runStore, messageStore, handle::get, null));
  }
}
