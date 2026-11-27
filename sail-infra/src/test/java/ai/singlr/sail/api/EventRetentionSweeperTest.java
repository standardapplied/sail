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
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class EventRetentionSweeperTest {

  @TempDir Path tempDir;

  @Test
  void sweepPrunesOldTelemetryAndKeepsRecords() {
    try (var db = Sqlite.open(tempDir.resolve("events.db"))) {
      new SchemaManager(db).migrate();
      var events = new EventStore(db);
      events.insert(row(Event.WellKnownTypes.AGENT_TOOL_STARTED));
      events.insert(row(Event.WellKnownTypes.SPEC_MESSAGE_POSTED));
      try (var sweeper =
          new EventRetentionSweeper(
              events, Duration.ofDays(1), new ScheduledThreadPoolExecutor(1))) {
        assertEquals(1, sweeper.sweep());
        sweeper.sweepQuietly();
        sweeper.start();
      }
      assertEquals(1, events.recent(10).size());
      assertEquals(Event.WellKnownTypes.SPEC_MESSAGE_POSTED, events.recent(10).getFirst().type());
    }
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void closingWaitsForTheSweepInFlightSoTheDatabaseNeverClosesUnderIt() throws Exception {
    try (var db = Sqlite.open(tempDir.resolve("events.db"))) {
      new SchemaManager(db).migrate();
      var events = new EventStore(db);
      events.insert(row(Event.WellKnownTypes.AGENT_TOOL_STARTED));
      var scheduler = new ScheduledThreadPoolExecutor(1);
      var sweeper = new EventRetentionSweeper(events, Duration.ofDays(1), scheduler);
      var holding = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var reader = Thread.ofPlatform().start(() -> db.read(() -> held(holding, release)));
      holding.await();
      var sweeping = new CountDownLatch(1);
      var swept = new AtomicBoolean();
      scheduler.execute(
          () -> {
            sweeping.countDown();
            sweeper.sweepQuietly();
            swept.set(true);
          });
      sweeping.await();

      var sweptWhenClosed = new AtomicBoolean();
      var closer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    sweeper.close();
                    sweptWhenClosed.set(swept.get());
                  });
      while (closer.getState() == Thread.State.RUNNABLE) {
        Thread.onSpinWait();
      }
      release.countDown();
      reader.join();
      closer.join();

      assertTrue(sweptWhenClosed.get(), "the close returned under a sweep still in flight");
      assertEquals(0, events.recent(10).size(), "the sweep in flight pruned whole");
    }
  }

  private static Object held(CountDownLatch holding, CountDownLatch release) {
    holding.countDown();
    try {
      release.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return null;
  }

  @Test
  void defaultConstructorAndQuietFailurePathAreSafe() {
    var db = Sqlite.open(tempDir.resolve("closed.db"));
    new SchemaManager(db).migrate();
    var sweeper = new EventRetentionSweeper(new EventStore(db));
    assertTrue(EventRetentionSweeper.DEFAULT_RETENTION.toDays() > 0);
    assertEquals(5000, EventRetentionSweeper.BATCH_SIZE);
    db.close();
    sweeper.sweepQuietly();
    sweeper.close();
  }

  private static EventStore.EventRow row(String type) {
    return new EventStore.EventRow(
        0, Instant.parse("2000-01-01T00:00:00Z"), type, "p", "s", "sail", "h", "{}");
  }
}
