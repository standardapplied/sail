/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissedStopReconcilerTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private SessionStore sessionStore;
  private EventBus bus;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("recon.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    reviewStore = new ReviewStore(db);
    sessionStore = new SessionStore(db);
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    if (db != null) db.close();
  }

  private void createInProgressSpec(String id) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "test-project",
            "Test spec",
            SpecStatus.IN_PROGRESS,
            null,
            "claude-code",
            null,
            null,
            "feat/test",
            0,
            null,
            "",
            "",
            null,
            List.of(),
            List.of()));
  }

  private void finishedSession(String specId, String status, Integer exitCode) {
    var id = sessionStore.create("test-project", specId, "claude-code", "feat/test", "task", 1);
    sessionStore.complete(id, status, exitCode);
  }

  private void subscribeController(CountDownLatch latch) {
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
                "max_iterations",
                3,
                "stages",
                List.of(
                    Map.of(
                        "name",
                        "security",
                        "type",
                        "agent",
                        "agent",
                        "codex",
                        "gate",
                        "no_critical"))));
    var controller =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> config,
            p -> "codex",
            (p, a, pr) -> "[]",
            bus,
            new DirectExecutorService());
    bus.subscribe(BusTesting.latching(controller, latch));
  }

  @Test
  void replaysACleanMissedStopAndDrivesTheSpecToDone() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    var replayed = new MissedStopReconciler(specStore, sessionStore, bus).reconcile();

    assertEquals(1, replayed);
    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void replaysAMissedStopWithNoRecordedExitCode() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "completed", null);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    var replayed = new MissedStopReconciler(specStore, sessionStore, bus).reconcile();

    assertEquals(1, replayed);
    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void replaysACrashedMissedStopAndLeavesTheSpecInProgress() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 137);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    new MissedStopReconciler(specStore, sessionStore, bus).reconcile();

    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void doesNothingWhenTheSessionIsStillRunning() {
    createInProgressSpec("auth");
    sessionStore.create("test-project", "auth", "claude-code", "feat/test", "task", 1);

    var replayed = new MissedStopReconciler(specStore, sessionStore, bus).reconcile();

    assertEquals(0, replayed);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void stopEventCarriesExitCodeAndStartupSource() {
    var spec =
        new SpecStore.SpecRow(
            "auth",
            "test-project",
            "T",
            SpecStatus.IN_PROGRESS,
            null,
            "codex",
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());

    var event = MissedStopReconciler.stopEvent(new MissedStops.Replay(spec, 137));

    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertEquals("auth", event.spec());
    assertEquals("codex", event.agent());
    assertEquals(137, event.data().get("exit_code"));
    assertEquals("startup-reconcile", event.data().get("source"));
  }

  @Test
  void stopEventOmitsExitCodeWhenUnknown() {
    var spec =
        new SpecStore.SpecRow(
            "auth",
            "test-project",
            "T",
            SpecStatus.IN_PROGRESS,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());

    var event = MissedStopReconciler.stopEvent(new MissedStops.Replay(spec, null));

    assertEquals(Event.SAIL_AGENT, event.agent());
    assertTrue(event.data().get("exit_code") == null);
    assertEquals("startup-reconcile", event.data().get("source"));
  }
}
