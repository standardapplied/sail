/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyEventBridgeTest {

  @TempDir Path dir;
  private Sqlite db;
  private EventStore events;

  private final ConcurrentLinkedQueue<Event> seen = new ConcurrentLinkedQueue<>();

  private EventSubscriber collector(CountDownLatch latch) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "collector";
      }

      @Override
      public Predicate<Event> filter() {
        return event -> true;
      }

      @Override
      public void onEvent(Event event) {
        seen.add(event);
        latch.countDown();
      }
    };
  }

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("test.db"));
    new SchemaManager(db).migrate();
    events = new EventStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private long insert(String type, String project, String specId, String data) {
    return events.insert(
        new EventStore.EventRow(
            0, "2026-09-01T12:00:00Z", type, project, specId, "uday", "box-1", data));
  }

  @Test
  void publishesOnlyNewPtyFactsWithTheirStoredIdentityAndPayload() throws Exception {
    insert(
        Event.WellKnownTypes.PTY_SESSION_STARTED, "acme", "design-talk", "{\"session\": \"old\"}");
    try (var bus = new EventBus()) {
      var bridge = new PtyEventBridge(events, bus);
      var latch = new CountDownLatch(2);
      bus.subscribe(collector(latch));

      insert(Event.WellKnownTypes.SPEC_DISPATCHED, "acme", "s1", "{}");
      insert(
          Event.WellKnownTypes.PTY_SESSION_STARTED,
          "acme",
          "design-talk",
          "{\"session\": \"brainstorm\", \"room_id\": \"design-talk\"}");
      insert(Event.WellKnownTypes.PTY_SESSION_ENDED, "acme", null, "{\"reason\": \"exited(0)\"}");

      assertEquals(2, bridge.publishNewRows(), "only the pty facts newer than the cursor go live");
      BusTesting.awaitDelivery(latch);

      var types = seen.stream().map(Event::type).toList();
      assertEquals(
          List.of(Event.WellKnownTypes.PTY_SESSION_STARTED, Event.WellKnownTypes.PTY_SESSION_ENDED),
          types,
          "rows persisted before the bridge existed are never replayed, foreign types never bridge");
      var started = seen.stream().findFirst().orElseThrow();
      assertEquals("acme", started.project());
      assertEquals("design-talk", started.spec());
      assertEquals("uday", started.agent());
      assertEquals("box-1", started.host());
      assertEquals("brainstorm", started.data().get("session"));
      assertTrue(started.id() > 0, "the bus stamps a live id");

      assertEquals(0, bridge.publishNewRows(), "a second pass finds nothing new");
    }
  }

  @Test
  void aFullBatchKeepsDrainingUntilTheTableIsCaughtUp() throws Exception {
    try (var bus = new EventBus()) {
      var bridge = new PtyEventBridge(events, bus, 2);
      var latch = new CountDownLatch(5);
      bus.subscribe(collector(latch));
      for (var i = 0; i < 5; i++) {
        insert(
            Event.WellKnownTypes.PTY_SESSION_ATTACHED,
            "acme",
            null,
            "{\"session\": \"s" + i + "\"}");
      }

      assertEquals(5, bridge.publishNewRows());
      BusTesting.awaitDelivery(latch);
      assertEquals(5, seen.size());
    }
  }

  @Test
  void anUntranslatableRowIsSkippedOnceNotAStalledCursor() {
    try (var bus = new EventBus()) {
      var bridge = new PtyEventBridge(events, bus);
      insert(Event.WellKnownTypes.PTY_SESSION_STARTED, "", null, "{}");

      assertEquals(0, bridge.publishNewRows(), "a blank-project row cannot become a bus event");
      assertEquals(0, bridge.publishNewRows(), "and it is never retried");

      var latch = new CountDownLatch(1);
      bus.subscribe(collector(latch));
      insert(Event.WellKnownTypes.PTY_SESSION_STARTED, "acme", null, "{\"session\": \"next\"}");
      assertEquals(1, bridge.publishNewRows(), "the lane keeps flowing past the bad row");
    }
  }

  @Test
  void thePersisterAndTheBridgeCannotFeedEachOther() throws Exception {
    try (var bus = new EventBus()) {
      var bridge = new PtyEventBridge(events, bus);
      var latch = new CountDownLatch(1);
      bus.subscribe(BusTesting.latching(new SpecStoreAuditPersister(events), latch));
      insert(Event.WellKnownTypes.PTY_SESSION_STARTED, "acme", null, "{\"session\": \"once\"}");

      assertEquals(1, bridge.publishNewRows());
      Thread.sleep(150);

      assertEquals(1, events.recent(10).size(), "the persister never re-persists a bridged fact");
      assertEquals(1, latch.getCount(), "the persister's filter refused it before delivery");
      assertEquals(0, bridge.publishNewRows(), "so the bridge has nothing new to see");
    }
  }

  @Test
  void startBridgesOnItsOwnCadenceAndCloseStopsIt() throws Exception {
    try (var bus = new EventBus()) {
      var latch = new CountDownLatch(1);
      bus.subscribe(collector(latch));
      try (var bridge = new PtyEventBridge(events, bus)) {
        bridge.start(Duration.ofMillis(20));
        insert(Event.WellKnownTypes.PTY_SESSION_STARTED, "acme", null, "{\"session\": \"timed\"}");
        BusTesting.awaitDelivery(latch);
      }
      assertEquals(1, seen.size());
    }
  }
}
