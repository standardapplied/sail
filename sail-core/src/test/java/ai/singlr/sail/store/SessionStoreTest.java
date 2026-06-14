/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SessionStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new SessionStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void createAndFindSession() {
    var id = store.create("backend", "auth", "claude-code", "feat/auth", "implement OAuth", 1234);

    var session = store.findById(id);
    assertTrue(session.isPresent());
    assertEquals("backend", session.get().project());
    assertEquals("auth", session.get().specId());
    assertEquals("claude-code", session.get().agent());
    assertEquals("feat/auth", session.get().branch());
    assertEquals("implement OAuth", session.get().task());
    assertEquals(1234, session.get().pid());
    assertEquals("running", session.get().status());
    assertNotNull(session.get().startedAt());
    assertNull(session.get().completedAt());
  }

  @Test
  void createWithNullOptionalFields() {
    var id = store.create("backend", null, "codex", null, null, null);

    var session = store.findById(id).orElseThrow();
    assertNull(session.specId());
    assertNull(session.branch());
    assertNull(session.task());
    assertNull(session.pid());
  }

  @Test
  void completeSession() {
    var id = store.create("backend", "auth", "claude-code", null, null, null);
    store.complete(id, "completed");

    var session = store.findById(id).orElseThrow();
    assertEquals("completed", session.status());
    assertNotNull(session.completedAt());
  }

  @Test
  void latestForProjectReturnsNewest() {
    store.create("backend", "spec-1", "claude-code", null, null, null);
    store.create("backend", "spec-2", "claude-code", null, null, null);
    store.create("other-project", "spec-3", "codex", null, null, null);

    var latest = store.latestForProject("backend");
    assertTrue(latest.isPresent());
    assertEquals("spec-2", latest.get().specId());
  }

  @Test
  void latestForProjectReturnsEmptyWhenNone() {
    assertTrue(store.latestForProject("nonexistent").isEmpty());
  }

  @Test
  void runningForProjectFindsActiveSession() {
    var id1 = store.create("backend", "spec-1", "claude-code", null, null, null);
    store.complete(id1, "completed");
    store.create("backend", "spec-2", "claude-code", null, null, null);

    var running = store.runningForProject("backend");
    assertTrue(running.isPresent());
    assertEquals("spec-2", running.get().specId());
    assertEquals("running", running.get().status());
  }

  @Test
  void runningForProjectReturnsEmptyWhenAllCompleted() {
    var id = store.create("backend", "spec-1", "claude-code", null, null, null);
    store.complete(id, "completed");

    assertTrue(store.runningForProject("backend").isEmpty());
  }

  @Test
  void listForProjectReturnsNewestFirst() {
    store.create("backend", "spec-1", "claude-code", null, null, null);
    store.create("backend", "spec-2", "codex", null, null, null);
    store.create("other", "spec-3", "claude-code", null, null, null);

    var sessions = store.listForProject("backend");
    assertEquals(2, sessions.size());
    assertEquals("spec-2", sessions.get(0).specId());
    assertEquals("spec-1", sessions.get(1).specId());
  }

  @Test
  void listForSpecReturnsSessions() {
    store.create("backend", "auth", "claude-code", null, null, null);
    store.create("frontend", "auth", "codex", null, null, null);
    store.create("backend", "payment", "claude-code", null, null, null);

    var sessions = store.listForSpec("auth");
    assertEquals(2, sessions.size());
  }

  @Test
  void findByIdReturnsEmptyForUnknown() {
    assertTrue(store.findById("nonexistent").isEmpty());
  }

  @Test
  void multipleStatusTransitions() {
    var id = store.create("backend", null, "claude-code", null, null, null);
    assertEquals("running", store.findById(id).orElseThrow().status());

    store.complete(id, "stopped");
    assertEquals("stopped", store.findById(id).orElseThrow().status());
  }
}
