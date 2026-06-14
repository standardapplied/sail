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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecStoreAuditPersisterTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private EventStore eventStore;
  private SpecStoreAuditPersister persister;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    eventStore = new EventStore(db);
    persister = new SpecStoreAuditPersister(eventStore);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void nameReturnsSqliteAuditPersister() {
    assertEquals("sqlite-audit-persister", persister.name());
  }

  @Test
  void filterAcceptsAllEvents() {
    var event = Event.of("proj", "spec-1", "test_type", "sail", "host");
    assertTrue(persister.filter().test(event));
  }

  @Test
  void onEventPersistsToDatabase() {
    var event = Event.of("backend", "auth", "spec_dispatched", "sail", "host1", Map.of("k", "v"));
    persister.onEvent(event);

    var stored = eventStore.recent(1);
    assertEquals(1, stored.size());
    assertEquals("spec_dispatched", stored.getFirst().type());
    assertEquals("backend", stored.getFirst().project());
    assertEquals("auth", stored.getFirst().specId());
    assertEquals("sail", stored.getFirst().agent());
    assertEquals("host1", stored.getFirst().host());
  }

  @Test
  void onEventPersistsMultipleEvents() {
    persister.onEvent(Event.of("p", "s1", "started", "sail", "h"));
    persister.onEvent(Event.of("p", "s1", "stopped", "sail", "h"));
    persister.onEvent(Event.of("p", "s2", "started", "codex", "h"));

    var stored = eventStore.recent(10);
    assertEquals(3, stored.size());
  }

  @Test
  void recentReturnsMappedEvents() {
    persister.onEvent(Event.of("proj", "spec", "test_event", "claude", "host"));

    var recent = persister.recent(1);
    assertEquals(1, recent.size());
    assertEquals("test_event", recent.getFirst().type());
    assertEquals("proj", recent.getFirst().project());
  }

  @Test
  void onEventSwallowsExceptionsGracefully() {
    db.close();
    persister.onEvent(Event.of("proj", null, "should_fail", "sail", "host"));
    db = null;
  }

  @Test
  void integrationWithEventBus() throws Exception {
    try (var bus = new EventBus()) {
      bus.subscribe(persister);

      var event = Event.of("proj", null, "server_started", "sail", "host");
      bus.publish(event);

      Thread.sleep(100);

      var stored = eventStore.recent(1);
      assertEquals(1, stored.size());
      assertEquals("server_started", stored.getFirst().type());
    }
  }
}
