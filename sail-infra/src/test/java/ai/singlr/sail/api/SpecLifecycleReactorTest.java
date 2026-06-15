/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecLifecycleReactorTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore store;
  private SpecLifecycleReactor reactor;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new SpecStore(db);
    reactor = new SpecLifecycleReactor(store);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private void seed(String id, String status) {
    store.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            id,
            SpecStatus.fromWire(status),
            null,
            null,
            null,
            null,
            null,
            0,
            "me",
            null,
            null,
            "me",
            List.of(),
            List.of()));
  }

  private static Event stopped(String spec) {
    return Event.of(
        "acme", spec, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host");
  }

  @Test
  void constructorRejectsNullStore() {
    assertThrows(NullPointerException.class, () -> new SpecLifecycleReactor(null));
  }

  @Test
  void nameIsSpecLifecycle() {
    assertEquals("spec-lifecycle", reactor.name());
  }

  @Test
  void filterAcceptsOnlyStoppedEventsWithASpec() {
    var filter = reactor.filter();
    assertTrue(filter.test(stopped("auth")));
    assertFalse(
        filter.test(
            Event.of(
                "acme",
                "auth",
                Event.WellKnownTypes.AGENT_SESSION_STARTED,
                "claude-code",
                "host")));
    assertFalse(
        filter.test(
            Event.of(
                "acme",
                "auth",
                Event.WellKnownTypes.AGENT_SESSION_COMPLETED,
                "claude-code",
                "host")));
    assertFalse(
        filter.test(
            Event.of(
                "acme", null, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "h")));
  }

  @Test
  void stoppedTransitionsInProgressToReview() {
    seed("auth", "in_progress");

    reactor.onEvent(stopped("auth"));

    assertEquals(SpecStatus.REVIEW, store.findById("auth").orElseThrow().status());
  }

  @Test
  void stoppedLeavesNonInProgressSpecsUntouched() {
    seed("pending-one", "pending");
    seed("done-one", "done");

    reactor.onEvent(stopped("pending-one"));
    reactor.onEvent(stopped("done-one"));

    assertEquals(SpecStatus.PENDING, store.findById("pending-one").orElseThrow().status());
    assertEquals(SpecStatus.DONE, store.findById("done-one").orElseThrow().status());
  }

  @Test
  void stoppedForAnUnknownSpecIsANoOp() {
    reactor.onEvent(stopped("ghost"));
    assertTrue(store.findById("ghost").isEmpty());
  }

  @Test
  void failureIsSwallowed() {
    seed("auth", "in_progress");
    db.close();

    reactor.onEvent(stopped("auth"));
  }
}
