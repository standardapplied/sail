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
import org.junit.jupiter.api.Test;
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
      try (var sweeper = new EventRetentionSweeper(events, Duration.ofDays(1))) {
        assertEquals(1, sweeper.sweep());
        sweeper.sweepQuietly();
        sweeper.start();
      }
      assertEquals(1, events.recent(10).size());
      assertEquals(Event.WellKnownTypes.SPEC_MESSAGE_POSTED, events.recent(10).getFirst().type());
    }
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
    return new EventStore.EventRow(0, "2000-01-01T00:00:00Z", type, "p", "s", "sail", "h", "{}");
  }
}
