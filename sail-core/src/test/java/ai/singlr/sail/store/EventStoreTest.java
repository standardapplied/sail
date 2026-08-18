/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private EventStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new EventStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private EventStore.EventRow event(String type, String project, String specId) {
    return new EventStore.EventRow(
        0, "2026-05-27T00:00:00Z", type, project, specId, "sail", "host1", "{}");
  }

  @Test
  void insertReturnsAutoIncrementId() {
    var id1 = store.insert(event("spec_dispatched", "backend", "auth"));
    var id2 = store.insert(event("agent_session_started", "backend", "auth"));
    assertTrue(id1 > 0);
    assertTrue(id2 > id1);
  }

  @Test
  void recentReturnsEventsInDescendingOrder() {
    store.insert(event("first", "p", null));
    store.insert(event("second", "p", null));
    store.insert(event("third", "p", null));

    var recent = store.recent(2);
    assertEquals(2, recent.size());
    assertEquals("third", recent.get(0).type());
    assertEquals("second", recent.get(1).type());
  }

  @Test
  void recentWithLimitExceedingCountReturnsAll() {
    store.insert(event("only", "p", null));

    var recent = store.recent(100);
    assertEquals(1, recent.size());
    assertEquals("only", recent.getFirst().type());
  }

  @Test
  void forSpecReturnsMatchingEventsInAscendingOrder() {
    store.insert(event("dispatched", "backend", "auth"));
    store.insert(event("started", "backend", "auth"));
    store.insert(event("dispatched", "backend", "payment"));
    store.insert(event("stopped", "backend", "auth"));

    var events = store.forSpec("auth");
    assertEquals(3, events.size());
    assertEquals("dispatched", events.get(0).type());
    assertEquals("started", events.get(1).type());
    assertEquals("stopped", events.get(2).type());
  }

  @Test
  void forSpecReturnsEmptyForUnknownSpec() {
    assertTrue(store.forSpec("nonexistent").isEmpty());
  }

  @Test
  void boundedForSpecScopesAndOrdersAscending() {
    store.insert(event("dispatched", "backend", "auth"));
    store.insert(event("dispatched", "backend", "payment"));
    store.insert(event("started", "backend", "auth"));
    store.insert(event("stopped", "backend", "auth"));

    var events = store.forSpec("auth", null, 10, Set.of());
    assertEquals(3, events.size());
    assertEquals("dispatched", events.get(0).type());
    assertEquals("started", events.get(1).type());
    assertEquals("stopped", events.get(2).type());
  }

  @Test
  void boundedForSpecWithoutCursorReturnsNewestWindowOldestFirst() {
    store.insert(event("first", "backend", "auth"));
    store.insert(event("second", "backend", "auth"));
    store.insert(event("third", "backend", "auth"));

    var events = store.forSpec("auth", null, 2, Set.of());
    assertEquals(2, events.size());
    assertEquals("second", events.get(0).type());
    assertEquals("third", events.get(1).type());
  }

  @Test
  void boundedForSpecCursorIsExclusive() {
    store.insert(event("first", "backend", "auth"));
    var cursor = store.insert(event("second", "backend", "auth"));
    store.insert(event("noise", "backend", "payment"));
    store.insert(event("third", "backend", "auth"));

    var events = store.forSpec("auth", cursor, 10, Set.of());
    assertEquals(1, events.size());
    assertEquals("third", events.getFirst().type());
    assertTrue(store.forSpec("auth", events.getFirst().id(), 10, Set.of()).isEmpty());
  }

  @Test
  void boundedForSpecCursorRespectsLimitAscending() {
    var cursor = store.insert(event("seed", "backend", "auth"));
    store.insert(event("a", "backend", "auth"));
    store.insert(event("b", "backend", "auth"));
    store.insert(event("c", "backend", "auth"));

    var events = store.forSpec("auth", cursor, 2, Set.of());
    assertEquals(2, events.size());
    assertEquals("a", events.get(0).type());
    assertEquals("b", events.get(1).type());
  }

  @Test
  void boundedForSpecExcludesTypesBeforeApplyingTheLimit() {
    store.insert(event("record_one", "backend", "auth"));
    for (var i = 0; i < 5; i++) {
      store.insert(event("agent_log_chunk", "backend", "auth"));
    }
    store.insert(event("record_two", "backend", "auth"));

    var events = store.forSpec("auth", null, 2, Set.of("agent_log_chunk"));
    assertEquals(2, events.size());
    assertEquals("record_one", events.get(0).type());
    assertEquals("record_two", events.get(1).type());
  }

  @Test
  void boundedForSpecReturnsEmptyForUnknownSpec() {
    store.insert(event("dispatched", "backend", "auth"));
    assertTrue(store.forSpec("nonexistent", null, 10, Set.of()).isEmpty());
  }

  @Test
  void boundedForSpecRejectsNonPositiveLimit() {
    assertThrows(IllegalArgumentException.class, () -> store.forSpec("auth", null, 0, Set.of()));
  }

  @Test
  void boundedForSpecUsesTheSpecIndexNotATableScan() {
    var plans =
        List.of(
            "EXPLAIN QUERY PLAN SELECT id FROM events WHERE spec_id = ? AND id > ?"
                + " AND type NOT IN (?) ORDER BY id ASC LIMIT ?",
            "EXPLAIN QUERY PLAN SELECT id FROM events WHERE spec_id = ?"
                + " AND type NOT IN (?) ORDER BY id DESC LIMIT ?");
    var indexed = db.query(plans.get(0), row -> row.text(3), "auth", 1L, "agent_log_chunk", 10);
    var newest = db.query(plans.get(1), row -> row.text(3), "auth", "agent_log_chunk", 10);
    for (var detail : List.of(indexed.getFirst(), newest.getFirst())) {
      assertTrue(
          detail.contains("USING INDEX idx_events_spec"),
          "expected idx_events_spec range scan, got: " + detail);
      assertFalse(detail.startsWith("SCAN"), "combined predicate must not table-scan: " + detail);
    }
  }

  @Test
  void forSpecAndTypeFiltersOnBothAndKeepsInsertionOrder() {
    store.insert(event("stopped", "backend", "auth"));
    store.insert(event("started", "backend", "auth"));
    store.insert(event("stopped", "backend", "payment"));
    store.insert(event("stopped", "backend", "auth"));

    var events = store.forSpecAndType("auth", "stopped");
    assertEquals(2, events.size());
    assertTrue(events.getFirst().id() < events.getLast().id());
    assertTrue(store.forSpecAndType("auth", "missing").isEmpty());
  }

  @Test
  void sinceReturnsEventsAfterGivenId() {
    var id1 = store.insert(event("first", "p", null));
    var id2 = store.insert(event("second", "p", null));
    store.insert(event("third", "p", null));

    var events = store.since(id1, 10);
    assertEquals(2, events.size());
    assertEquals("second", events.get(0).type());
    assertEquals("third", events.get(1).type());
  }

  @Test
  void sinceRespectsLimit() {
    store.insert(event("a", "p", null));
    var afterFirst = store.insert(event("b", "p", null));
    store.insert(event("c", "p", null));
    store.insert(event("d", "p", null));

    var events = store.since(0, 2);
    assertEquals(2, events.size());
  }

  @Test
  void statsReturnsTotalAndPerTypeCounts() {
    store.insert(event("spec_dispatched", "p", null));
    store.insert(event("spec_dispatched", "p", null));
    store.insert(event("agent_session_started", "p", null));

    var stats = store.stats();
    assertEquals(3L, stats.get("total"));
    assertEquals(2L, stats.get("spec_dispatched"));
    assertEquals(1L, stats.get("agent_session_started"));
  }

  @Test
  void statsReturnsZeroTotalWhenEmpty() {
    var stats = store.stats();
    assertEquals(0L, stats.get("total"));
  }

  @Test
  void eventRowPreservesAllFields() {
    store.insert(
        new EventStore.EventRow(
            0,
            "2026-05-27T12:34:56Z",
            "custom_type",
            "my-project",
            "my-spec",
            "claude-code",
            "bare-metal-1",
            "{\"key\":\"value\"}"));

    var events = store.recent(1);
    assertEquals(1, events.size());
    var row = events.getFirst();
    assertEquals("2026-05-27T12:34:56Z", row.timestamp());
    assertEquals("custom_type", row.type());
    assertEquals("my-project", row.project());
    assertEquals("my-spec", row.specId());
    assertEquals("claude-code", row.agent());
    assertEquals("bare-metal-1", row.host());
    assertEquals("{\"key\":\"value\"}", row.data());
  }

  @Test
  void nullFieldsHandledCorrectly() {
    store.insert(
        new EventStore.EventRow(0, "2026-05-27T00:00:00Z", "test", null, null, null, "h", "{}"));

    var events = store.recent(1);
    assertEquals(1, events.size());
    assertNull(events.getFirst().project());
    assertNull(events.getFirst().specId());
    assertNull(events.getFirst().agent());
  }

  @Test
  void pruneBeforeDeletesOnlyMatchingOldTypesAcrossBatches() {
    for (var i = 0; i < 5; i++) {
      store.insert(event("telemetry", "p", null));
    }
    store.insert(event("record", "p", null));
    store.insert(
        new EventStore.EventRow(
            0, "2099-01-01T00:00:00Z", "telemetry", "p", null, "sail", "h", "{}"));

    assertEquals(5, store.pruneBefore("2026-06-01T00:00:00Z", Set.of("telemetry"), 2));
    assertEquals(2, store.recent(10).size());
    assertTrue(store.recent(10).stream().anyMatch(row -> row.type().equals("record")));
    assertEquals(0, store.pruneBefore("2099-12-01T00:00:00Z", Set.of(), 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.pruneBefore("2099-12-01T00:00:00Z", Set.of("telemetry"), 0));
  }
}
