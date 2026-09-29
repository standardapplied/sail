/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.RunTracker;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A change of this box's sync identity, as {@code sail host config set sync-handle}, {@code sail
 * join} and {@code sail host sync --as-main} make it: refused while a run main holds under the old
 * handle would be stranded, and once written, every run main has not taken is stamped as the box's.
 */
class HandleChangeTest {

  private static final SyncConfig STANDALONE = SyncConfig.unset();
  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);
  private static final Actor UDAY = Actor.sync("uday", Role.MEMBER);
  private static final SyncOperations.Channels UNREACHABLE =
      target -> {
        throw new IOException("ssh: connect to host main: Connection refused");
      };

  @TempDir Path dir;
  private Path dbPath;
  private Sqlite db;
  private RunStore runs;
  private SyncBox main;
  private final AtomicBoolean written = new AtomicBoolean();

  @BeforeEach
  void setUp() {
    dbPath = dir.resolve("box.db");
    db = Sqlite.open(dbPath);
    new SchemaManager(db).migrate();
    runs = new RunStore(db);
    main = new SyncBox("main");
  }

  @AfterEach
  void tearDown() {
    main.close();
    db.close();
  }

  private static SyncConfig node(String handle) {
    return new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", handle, "box");
  }

  private static SyncConfig main(String handle) {
    return new SyncConfig(SyncConfig.ROLE_MAIN, null, handle, "box");
  }

  private List<String> apply(SyncConfig before, SyncConfig after) throws Exception {
    return apply(before, after, target -> PipedSyncChannel.to(main.server(ADA)));
  }

  private List<String> apply(SyncConfig before, SyncConfig after, SyncOperations.Channels channels)
      throws Exception {
    return HandleChange.apply(dbPath, before, after, channels, () -> written.set(true));
  }

  private String run(String handle, String project) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs.reserveDispatch(
                id, project, "s", handle, "build", List.of(), "claude-code", "b", "t", "/l", "u"));
    return id;
  }

  private void acknowledge(String id) {
    Acting.system(
        () -> runs.applyRevision(id, runs.comparableSnapshot(id), "9-main-" + System.nanoTime()));
  }

  private void finish(String id) {
    Acting.system(() -> runs.complete(id, "completed", 0));
  }

  @Test
  void aStandaloneBoxStampsEveryRunItMadeWhenItTakesAHandle() throws Exception {
    var finished = run(null, "p");
    finish(finished);
    var live = run(null, "q");

    assertEquals(List.of(finished, live), apply(STANDALONE, node("ada"), UNREACHABLE));

    assertTrue(written.get());
    for (var id : List.of(finished, live)) {
      assertEquals("ada", runs.findById(id).orElseThrow().node());
      assertEquals("ada", runs.findById(id).orElseThrow().owner());
    }
  }

  @Test
  void aNodesHandleChangeWaitsForTheRunsMainHoldsUnderTheOldHandle() throws Exception {
    var held = run("ada", "p");
    acknowledge(held);
    var unheld = run("ada", "q");

    var live = assertThrows(IllegalStateException.class, () -> apply(node("ada"), node("uday")));
    assertTrue(live.getMessage().contains(held), live.getMessage());
    assertTrue(live.getMessage().contains("from 'ada' to 'uday'"), live.getMessage());
    assertTrue(live.getMessage().contains("run 'sail sync'"), live.getMessage());
    assertFalse(live.getMessage().contains(unheld), "a run main never took is re-stamped");
    assertFalse(written.get(), "nothing is written");
    finish(held);
    assertThrows(IllegalStateException.class, () -> apply(node("ada"), node("uday")));
    assertEquals("ada", runs.findById(unheld).orElseThrow().node(), "nothing is stamped");

    acknowledge(held);
    assertEquals(List.of(unheld), apply(node("ada"), node("uday")));

    assertTrue(written.get());
    assertEquals("ada", runs.findById(held).orElseThrow().node(), "main holds it under 'ada'");
    assertEquals("uday", runs.findById(unheld).orElseThrow().owner());
  }

  @Test
  void mainsHandleChangeWaitsForTheRunsItExecutesAndStampsNone() throws Exception {
    var live = run("ada", "p");

    var refused = assertThrows(IllegalStateException.class, () -> apply(main("ada"), main("uday")));
    assertTrue(refused.getMessage().contains(live), refused.getMessage());
    assertFalse(refused.getMessage().contains("sail sync"), refused.getMessage());
    finish(live);

    assertEquals(List.of(), apply(main("ada"), main("uday")), "main's runs are its own already");
    assertEquals("ada", runs.findById(live).orElseThrow().node());
  }

  @Test
  void aBoxBecomingMainStampsItsUnstampedRunsAndTheyFinishLocally() throws Exception {
    var unstamped = run(null, "p");
    var stamped = run("ada", "q");
    var standalone = new SyncConfig(null, null, "ada", "box");

    assertEquals(List.of(unstamped), apply(standalone, main("ada")));

    try (var scheduler = new SyncScheduler(() -> {}, Duration.ofMillis(1), Duration.ofMillis(1))) {
      var tracker = new RunTracker(runs, scheduler, () -> "ada");
      for (var id : List.of(unstamped, stamped)) {
        tracker.onEvent(
            Event.of(
                "p",
                "s",
                Event.WellKnownTypes.AGENT_SESSION_COMPLETED,
                "claude-code",
                "host",
                Map.of(Event.WellKnownData.RUN_ID, id)));
      }
    }
    assertEquals("completed", runs.findById(unstamped).orElseThrow().status());
    assertEquals("completed", runs.findById(stamped).orElseThrow().status());
  }

  @Test
  void aRunReservedWhileTheIdentityIsWrittenIsStampedForTheNewOne() throws Exception {
    var during = new AtomicReference<String>();

    var stamped =
        HandleChange.apply(
            dbPath, node("ada"), node("uday"), UNREACHABLE, () -> during.set(run("ada", "p")));

    assertEquals(List.of(during.get()), stamped);
    assertEquals("uday", runs.findById(during.get()).orElseThrow().owner());
  }

  @Test
  void aChangeThatLeavesTheHandleAloneOnlyWrites() throws Exception {
    var unstamped = run(null, "p");

    assertEquals(List.of(), apply(node("ada"), node(" ada ")));

    assertTrue(written.get());
    assertEquals(null, runs.findById(unstamped).orElseThrow().node());
  }

  @Test
  void aBoxWithNoDatabaseOnlyWrites() throws Exception {
    assertEquals(
        List.of(),
        HandleChange.apply(
            dir.resolve("absent.db"),
            STANDALONE,
            node("ada"),
            UNREACHABLE,
            () -> written.set(true)));
    assertTrue(written.get());
  }

  @Test
  void aRunMainTookWhoseAnswerWasLostIsHeldUnderTheOldHandleAndNeverReStamped() throws Exception {
    try (var ada = new SyncBox(dir, "box").syncsAs(ADA)) {
      var live = run("ada", "p");
      SyncBox.pushLosingTheAnswer(main, ada);
      assertTrue(new RunStore(main.db).findById(live).isPresent(), "main took it");
      assertEquals(null, runs.baseRevOf(live), "but this box never heard");

      var refused =
          assertThrows(IllegalStateException.class, () -> apply(node("ada"), node("uday")));
      assertTrue(refused.getMessage().contains(live), refused.getMessage());
      assertFalse(written.get(), "nothing is written");
      assertEquals("ada", runs.findById(live).orElseThrow().node(), "nor re-stamped");

      finish(live);
      SyncBox.quiesce(main, ada);
      assertEquals(List.of(), apply(node("ada"), node("uday")));
      assertEquals("completed", new RunStore(main.db).findById(live).orElseThrow().status());
      assertEquals("ada", runs.findById(live).orElseThrow().node());
    }
  }

  @Test
  void aRunMainTookAndMovedOnWhoseAnswerWasLostStillHoldsTheOldHandle() throws Exception {
    try (var ada = new SyncBox(dir, "box").syncsAs(ADA)) {
      var held = run("ada", "p");
      finish(held);
      SyncBox.pushLosingTheAnswer(main, ada);
      Acting.system(
          () -> new RunStore(main.db).recordSession(held, "main-session", "claude", "/t"));

      var refused =
          assertThrows(IllegalStateException.class, () -> apply(node("ada"), node("uday")));

      assertTrue(refused.getMessage().contains(held), refused.getMessage());
      assertFalse(written.get(), "nothing is written");
      assertEquals(null, runs.baseRevOf(held), "main moved on: not acknowledged over it");
      assertEquals("ada", runs.findById(held).orElseThrow().node(), "nor re-stamped");
    }
  }

  @Test
  void aRoundStartedWhileTheHandleChangesWaitsForItAndOffersTheRunUnderTheNewHandle()
      throws Exception {
    var unheld = run("ada", "p");
    var opened = new AtomicInteger();
    var syncing =
        new SyncOperations(
            db,
            "box",
            dir.resolve("projects"),
            () -> node("uday"),
            target -> {
              opened.incrementAndGet();
              return PipedSyncChannel.to(main.server(UDAY));
            });
    var failure = new AtomicReference<Throwable>();
    var round = new AtomicReference<Thread>();

    var stamped =
        apply(
            node("ada"),
            node("uday"),
            target -> {
              round.set(
                  Thread.ofVirtual()
                      .start(
                          () -> {
                            try {
                              syncing.sync(new SyncRequest(null));
                            } catch (Throwable e) {
                              failure.set(e);
                            }
                          }));
              FileMutexTest.awaitParked(round.get());
              assertEquals(0, opened.get(), "no round runs while the handle changes");
              return PipedSyncChannel.to(main.server(ADA));
            });
    round.get().join();

    assertEquals(null, failure.get());
    assertEquals(List.of(unheld), stamped);
    assertEquals(1, opened.get());
    assertEquals("uday", new RunStore(main.db).findById(unheld).orElseThrow().node());
  }

  @Test
  void aNodeThatCannotAskMainWhichRunsItTookChangesNothing() throws Exception {
    var unheld = run("ada", "p");

    var refused =
        assertThrows(
            IllegalStateException.class, () -> apply(node("ada"), node("uday"), UNREACHABLE));

    assertTrue(refused.getMessage().contains("from 'ada' to 'uday'"), refused.getMessage());
    assertTrue(refused.getMessage().contains("Connection refused"), refused.getMessage());
    assertFalse(written.get(), "nothing is written");
    assertEquals("ada", runs.findById(unheld).orElseThrow().node(), "nor re-stamped");
  }
}
