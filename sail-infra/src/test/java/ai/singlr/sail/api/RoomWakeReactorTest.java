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
import ai.singlr.sail.store.RoomStore;
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
  private RoomStore roomStore;
  private RunStore runStore;
  private MessageStore messageStore;
  private RecordingLauncher launcher;
  private final AtomicReference<String> handle = new AtomicReference<>("uday");
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-08-11T12:00:00Z"));

  private static final class RecordingLauncher
      implements RoomWakeReactor.Waker, RoomWakeReactor.Guard {
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
    roomStore = new RoomStore(db);
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
        roomStore,
        runStore,
        messages,
        handle::get,
        launcher,
        launcher,
        Duration.ZERO,
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

  private void engage(String id, String agent, String mode) {
    var spec = specStore.findById(id).orElseThrow();
    var member = ai.singlr.sail.config.Engagement.of(agent, mode, null, now.get().toString());
    specStore.update(spec.withEngagement(member.toJson()));
    var room =
        roomStore.ensureFor(id, spec.project(), spec.title(), spec.assignee(), spec.wake(), "uday");
    roomStore.update(
        new RoomStore.RoomRow(
            room.id(),
            room.project(),
            room.title(),
            room.assignee(),
            room.wake(),
            ai.singlr.sail.config.Roster.solo(member).toJson(),
            room.createdBy(),
            room.createdAt(),
            null,
            "uday"));
  }

  private String chatRun(String specId, String role, String status, Instant startedAt) {
    var id = DateTimeUtils.newId().toString();
    runStore.create(
        id,
        "acme",
        specId,
        "uday",
        "uday",
        role,
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + id);
    db.execute("UPDATE runs SET started_at = ? WHERE id = ?", startedAt.toString(), id);
    if (!"running".equals(status)) {
      runStore.complete(id, status, 0);
    }
    return id;
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
        reactor
            .filter()
            .test(Event.of("acme", "auth", Event.WellKnownTypes.AGENT_PRESENCE, "u", "h")));
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
            roomStore,
            runStore,
            messageStore,
            handle::get,
            launcher,
            launcher,
            Duration.ZERO,
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
  void anEngagedDraftRoomWakesWithNoDispatchHistoryAndNoAssignedWakeMode() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");

    reactor().onEvent(message("chat", "uday", "hello"));

    assertEquals(List.of("acme/chat"), launcher.woken);
  }

  @Test
  void anEngagedRoomIgnoresTheCooldown() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");
    var run = chatRun("chat", "room", "completed", now.get().minus(Duration.ofSeconds(90)));
    finishAt(run, now.get().minusSeconds(30));

    reactor().onEvent(message("chat", "uday", "and another thing"));

    assertEquals(List.of("acme/chat"), launcher.woken);
  }

  @Test
  void aLiveChatTurnSuppressesTheEngagedWakeButALiveBuildDoesNot() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");
    chatRun("chat", "room", "running", now.get());

    reactor().onEvent(message("chat", "uday", "while you think"));
    assertTrue(launcher.woken.isEmpty(), "the relay owns delivery to a live chat turn");

    seed("busy", "in_progress", "uday", null);
    engage("busy", "claude-code", "read-only");
    buildRun("busy", "running");

    reactor().onEvent(message("busy", "uday", "how is it going"));
    assertEquals(List.of("acme/busy"), launcher.woken, "a build never silences an engaged room");
  }

  @Test
  void agentPostsNeverWakeAnEngagedRoom() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");

    reactor().onEvent(message("chat", "claude/room-1", "my own answer"));
    reactor().onEvent(message("chat", "sail", "narration"));

    assertTrue(launcher.woken.isEmpty());
  }

  @Test
  void anEngagedRoomUsesTheShortDebounce() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");
    seed("plain", "in_progress", "uday", "on");
    var paused = new ArrayList<Duration>();
    var reactor =
        new RoomWakeReactor(
            specStore,
            roomStore,
            runStore,
            messageStore,
            handle::get,
            launcher,
            launcher,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            RoomWakeReactor.COOLDOWN,
            new DirectExecutorService(),
            paused::add,
            now::get);

    reactor.onEvent(message("chat", "uday", "hi"));
    reactor.onEvent(message("plain", "uday", "hi"));

    assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)), paused);
  }

  @Test
  void aStopRefiresTheTurnAMessageOwedFromTheTurnsTail() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");
    var run = chatRun("chat", "room", "completed", now.get().minus(Duration.ofMinutes(2)));
    messageStore.append("chat", "uday", "landed after your last relay check", null);

    reactor().onEvent(roomStop("chat", run));

    assertEquals(List.of("acme/chat"), launcher.woken);
    assertEquals(List.of("acme/" + run), launcher.guarded, "the read-only guard still runs");
  }

  @Test
  void aStopWithNothingOwedStaysQuiet() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");
    messageStore.append("chat", "uday", "answered already", null);
    var run = chatRun("chat", "room", "completed", DateTimeUtils.now().plusSeconds(60));

    reactor().onEvent(roomStop("chat", run));

    assertTrue(launcher.woken.isEmpty(), "the turn started after the newest human message");
  }

  @Test
  void aBuildStopFreesADeferredFullTurn() {
    seed("chat", "draft", "uday", null);
    engage("chat", "codex", "full");
    messageStore.append("chat", "uday", "please add the diagram", null);
    var build = buildRun("chat", "completed");
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    data.put(Event.WellKnownData.RUN_ID, build);
    data.put(Event.WellKnownData.RUN_ROLE, "build");
    var stop =
        Event.of(
            "acme",
            "chat",
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "claude-code",
            "host",
            data);

    reactor().onEvent(stop);

    assertEquals(List.of("acme/chat"), launcher.woken);
    assertTrue(launcher.guarded.isEmpty(), "a build stop is not the chat guard's business");
  }

  @Test
  void aStopOnAnUnengagedSpecNeverRefires() {
    seed("plain", "in_progress", "uday", "on");
    var run = chatRun("plain", "room", "completed", now.get().minus(Duration.ofMinutes(2)));
    messageStore.append("plain", "uday", "owed but not engaged", null);

    reactor().onEvent(roomStop("plain", run));

    assertTrue(
        launcher.woken.isEmpty(), "the stop edge is the engaged loop's; wake keeps its own rules");
  }

  @Test
  void theSweepWakesOwedEngagedRoomsAndSkipsForeignOrQuietOnes() {
    seed("owed", "draft", "uday", null);
    engage("owed", "claude-code", "full");
    messageStore.append("owed", "uday", "anyone there?", null);

    seed("foreign", "draft", "someone-else", null);
    engage("foreign", "claude-code", "full");
    messageStore.append("foreign", "uday", "not this box's job", null);

    seed("quiet", "draft", "uday", null);
    engage("quiet", "claude-code", "full");

    reactor().sweepEngagedRooms();

    assertEquals(List.of("acme/owed"), launcher.woken);
  }

  @Test
  void aSweepFailureOnOneRoomNeverStopsTheOthers() {
    seed("first", "draft", "uday", null);
    engage("first", "claude-code", "full");
    messageStore.append("first", "uday", "hello", null);
    seed("second", "draft", "uday", null);
    engage("second", "claude-code", "full");
    messageStore.append("second", "uday", "hello too", null);
    launcher.failWith = new RuntimeException("container offline");

    var reactor = reactor();
    reactor.sweepEngagedRooms();
    assertTrue(launcher.woken.isEmpty(), "both wakes failed loudly");

    launcher.failWith = null;
    reactor.sweepEngagedRooms();
    assertEquals(2, launcher.woken.size(), "the next sweep retries both");
  }

  @Test
  void theOwedCheckSurvivesNullStoresCorruptTimestampsAndMultiMessageRooms() {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");
    var run = chatRun("chat", "room", "completed", now.get().minus(Duration.ofMinutes(2)));
    messageStore.append("chat", "uday", "first", null);
    var second = messageStore.append("chat", "uday", "second", null);

    reactor(new DirectExecutorService(), null).onEvent(roomStop("chat", run));
    assertTrue(launcher.woken.isEmpty(), "no message store, no owed check");

    db.execute("UPDATE spec_messages SET created_at = 'garbage' WHERE id = ?", second.id());
    db.execute("UPDATE spec_messages SET created_at = 'garbage' WHERE spec_id = 'chat'");
    reactor().onEvent(roomStop("chat", run));
    assertTrue(launcher.woken.isEmpty(), "an unparseable timestamp is never owed");

    db.execute(
        "UPDATE spec_messages SET created_at = ? WHERE spec_id = 'chat'", now.get().toString());
    reactor().onEvent(roomStop("chat", run));
    assertEquals(List.of("acme/chat"), launcher.woken, "the newest of several messages decides");
  }

  @Test
  void aSweepOverABrokenMessageStoreLogsAndMovesOn(@TempDir Path other) {
    seed("chat", "draft", "uday", null);
    engage("chat", "claude-code", "full");
    var dead = Sqlite.open(other.resolve("dead.db"));
    new SchemaManager(dead).migrate();
    var brokenMessages = new MessageStore(dead);
    dead.close();

    reactor(new DirectExecutorService(), brokenMessages).sweepEngagedRooms();

    assertTrue(launcher.woken.isEmpty());
  }

  @Test
  void theSweepPassArmsAndClosesWithoutFiringEarly() {
    var reactor = reactor();
    reactor.startSweep(Duration.ofHours(1));
    reactor.close();

    assertTrue(launcher.woken.isEmpty());
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
  void aReadOnlyInviteStopOnAnOwnedRunTriggersTheCommitGuard() {
    seed("auth", "done", "uday", null);
    var runId = DateTimeUtils.newId().toString();
    runStore.create(
        runId,
        "acme",
        "auth",
        "uday",
        "uday",
        "invite",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    var stop = new LinkedHashMap<String, Object>();
    stop.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    stop.put(Event.WellKnownData.RUN_ID, runId);
    stop.put(Event.WellKnownData.RUN_ROLE, "invite");

    reactor()
        .onEvent(
            Event.of(
                "acme",
                "auth",
                Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                "claude-code",
                "host",
                stop));

    assertEquals(
        List.of("acme/" + runId),
        launcher.guarded,
        "a read-only invite carries the same worktree-digest guard as a wake");
  }

  @Test
  void aFullInviteStopNeverGuardsItMayLegitimatelyChangeCode() {
    seed("auth", "done", "uday", null);
    var runId = DateTimeUtils.newId().toString();
    runStore.create(
        runId,
        "acme",
        "auth",
        "uday",
        "uday",
        "invite-full",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    var stop = new LinkedHashMap<String, Object>();
    stop.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    stop.put(Event.WellKnownData.RUN_ID, runId);
    stop.put(Event.WellKnownData.RUN_ROLE, "invite-full");

    reactor()
        .onEvent(
            Event.of(
                "acme",
                "auth",
                Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                "claude-code",
                "host",
                stop));

    assertTrue(launcher.guarded.isEmpty());
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
        new RoomWakeReactor(
            specStore, roomStore, runStore, messageStore, handle::get, launcher, launcher)) {
      assertEquals("room-wake", reactor.name());
    }
  }

  @Test
  void constructorRejectsMissingCollaborators() {
    assertThrows(
        NullPointerException.class,
        () ->
            new RoomWakeReactor(
                null, roomStore, runStore, messageStore, handle::get, launcher, launcher));
    assertThrows(
        NullPointerException.class,
        () ->
            new RoomWakeReactor(
                specStore, roomStore, runStore, messageStore, handle::get, null, launcher));
  }

  @Test
  void aMemberRecordedOnlyOnTheRoomRowWakesTheAgent() {
    seed("auth", "in_progress", "uday", "off");
    roomStore.ensureFor("auth", "acme", "auth", "uday", "off", "uday");
    roomStore.update(
        new RoomStore.RoomRow(
            "auth",
            "acme",
            "auth",
            "uday",
            "off",
            "[{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}]",
            "uday",
            null,
            null,
            "uday"));

    var reactor = reactor();
    reactor.onEvent(message("auth", "uday", "hello"));

    assertEquals(1, launcher.woken.size(), "the room row is the authoritative membership home");
  }

  @Test
  void aDismissalRecordedOnTheRoomWinsOverAStaleSpecColumn() {
    seed("auth", "in_progress", "uday", "off");
    engage("auth", "claude-code", "full");
    var room = roomStore.findById("auth").orElseThrow();
    roomStore.update(
        new RoomStore.RoomRow(
            room.id(),
            room.project(),
            room.title(),
            room.assignee(),
            room.wake(),
            null,
            room.createdBy(),
            room.createdAt(),
            null,
            "uday"));

    var reactor = reactor();
    reactor.onEvent(message("auth", "uday", "hello"));

    assertTrue(
        launcher.woken.isEmpty(),
        "a present room with an empty roster is authoritative — no stale-column resurrection");
  }

  @Test
  void theWakeModeStoredOnTheRoomRowGovernsTheDecision() {
    seed("auth", "in_progress", "uday", "off");
    roomStore.ensureFor("auth", "acme", "auth", "uday", "on", "uday");

    var reactor = reactor();
    reactor.onEvent(message("auth", "uday", "hello"));

    assertEquals(1, launcher.woken.size(), "the room's wake mode overrides the spec column");
  }
}
