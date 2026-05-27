/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.*;

import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTrackerTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SessionStore sessionStore;
  private SessionTracker tracker;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    sessionStore = new SessionStore(db);
    tracker = new SessionTracker(sessionStore);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void nameReturnsSessionTracker() {
    assertEquals("session-tracker", tracker.name());
  }

  @Test
  void filterAcceptsSessionEvents() {
    assertTrue(
        tracker
            .filter()
            .test(Event.of("p", "s", Event.WellKnownTypes.AGENT_SESSION_STARTED, "a", "h")));
    assertTrue(
        tracker
            .filter()
            .test(Event.of("p", "s", Event.WellKnownTypes.AGENT_SESSION_STOPPED, "a", "h")));
    assertTrue(
        tracker
            .filter()
            .test(Event.of("p", "s", Event.WellKnownTypes.AGENT_SESSION_COMPLETED, "a", "h")));
  }

  @Test
  void filterRejectsUnrelatedEvents() {
    assertFalse(tracker.filter().test(Event.of("p", "s", "spec_dispatched", "a", "h")));
  }

  @Test
  void startedCreatesRunningSession() {
    var event =
        Event.of(
            "backend",
            "auth",
            Event.WellKnownTypes.AGENT_SESSION_STARTED,
            "claude-code",
            "host",
            Map.of("pid", 1234, "branch", "feat/auth", "task", "implement OAuth"));

    tracker.onEvent(event);

    var session = sessionStore.latestForProject("backend");
    assertTrue(session.isPresent());
    assertEquals("backend", session.get().project());
    assertEquals("auth", session.get().specId());
    assertEquals("claude-code", session.get().agent());
    assertEquals("feat/auth", session.get().branch());
    assertEquals("implement OAuth", session.get().task());
    assertEquals(1234, session.get().pid());
    assertEquals("running", session.get().status());
  }

  @Test
  void stoppedCompletesSession() {
    tracker.onEvent(
        Event.of(
            "backend", "auth", Event.WellKnownTypes.AGENT_SESSION_STARTED, "claude-code", "host"));

    tracker.onEvent(
        Event.of(
            "backend", "auth", Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host"));

    var session = sessionStore.latestForProject("backend").orElseThrow();
    assertEquals("stopped", session.status());
    assertNotNull(session.completedAt());
  }

  @Test
  void completedSetsCompletedStatus() {
    tracker.onEvent(
        Event.of("backend", null, Event.WellKnownTypes.AGENT_SESSION_STARTED, "codex", "host"));

    tracker.onEvent(
        Event.of("backend", null, Event.WellKnownTypes.AGENT_SESSION_COMPLETED, "codex", "host"));

    var session = sessionStore.latestForProject("backend").orElseThrow();
    assertEquals("completed", session.status());
  }

  @Test
  void stoppedWithoutStartedFallsBackToRunningQuery() {
    var id = sessionStore.create("backend", null, "claude-code", null, null, null);

    tracker.onEvent(
        Event.of(
            "backend", null, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host"));

    var session = sessionStore.findById(id).orElseThrow();
    assertEquals("stopped", session.status());
  }

  @Test
  void startedWithStringPid() {
    tracker.onEvent(
        Event.of(
            "proj",
            null,
            Event.WellKnownTypes.AGENT_SESSION_STARTED,
            "codex",
            "host",
            Map.of("pid", "9999")));

    var session = sessionStore.latestForProject("proj").orElseThrow();
    assertEquals(9999, session.pid());
  }

  @Test
  void startedWithInvalidPid() {
    tracker.onEvent(
        Event.of(
            "proj",
            null,
            Event.WellKnownTypes.AGENT_SESSION_STARTED,
            "codex",
            "host",
            Map.of("pid", "not-a-number")));

    var session = sessionStore.latestForProject("proj").orElseThrow();
    assertNull(session.pid());
  }

  @Test
  void startedWithMinimalData() {
    tracker.onEvent(
        Event.of("proj", null, Event.WellKnownTypes.AGENT_SESSION_STARTED, "codex", "host"));

    var session = sessionStore.latestForProject("proj").orElseThrow();
    assertNull(session.specId());
    assertNull(session.pid());
    assertNull(session.task());
  }

  @Test
  void multiplSessionsPerProject() {
    tracker.onEvent(
        Event.of("backend", "s1", Event.WellKnownTypes.AGENT_SESSION_STARTED, "claude-code", "h"));
    tracker.onEvent(
        Event.of("backend", "s1", Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "h"));
    tracker.onEvent(
        Event.of("backend", "s2", Event.WellKnownTypes.AGENT_SESSION_STARTED, "codex", "h"));

    var sessions = sessionStore.listForProject("backend");
    assertEquals(2, sessions.size());
    assertEquals("running", sessions.get(0).status());
    assertEquals("stopped", sessions.get(1).status());
  }

  @Test
  void exceptionInHandlerDoesNotPropagate() {
    db.close();
    db = null;
    assertDoesNotThrow(
        () ->
            tracker.onEvent(
                Event.of(
                    "p", null, Event.WellKnownTypes.AGENT_SESSION_STARTED, "claude-code", "h")));
  }

  @Test
  void integrationWithEventBus() throws Exception {
    try (var bus = new EventBus()) {
      bus.subscribe(tracker);
      bus.publish(
          Event.of(
              "backend",
              "auth",
              Event.WellKnownTypes.AGENT_SESSION_STARTED,
              "claude-code",
              "host",
              Map.of("pid", 42)));

      Thread.sleep(100);

      var session = sessionStore.latestForProject("backend");
      assertTrue(session.isPresent());
      assertEquals(42, session.get().pid());
    }
  }
}
