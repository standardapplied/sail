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
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

  @TempDir Path dir;
  private Path dbPath;
  private Sqlite db;
  private RunStore runs;
  private final AtomicBoolean written = new AtomicBoolean();

  @BeforeEach
  void setUp() {
    dbPath = dir.resolve("sail.db");
    db = Sqlite.open(dbPath);
    new SchemaManager(db).migrate();
    runs = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static SyncConfig node(String handle) {
    return new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", handle, "box");
  }

  private static SyncConfig main(String handle) {
    return new SyncConfig(SyncConfig.ROLE_MAIN, null, handle, "box");
  }

  private List<String> apply(SyncConfig before, SyncConfig after) throws Exception {
    return HandleChange.apply(dbPath, before, after, () -> written.set(true));
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

    assertEquals(List.of(finished, live), apply(STANDALONE, node("ada")));

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
            dir.resolve("absent.db"), STANDALONE, node("ada"), () -> written.set(true)));
    assertTrue(written.get());
  }
}
