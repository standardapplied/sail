/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private String newRun(String project, String specId) {
    return store.create(
        DateTimeUtils.newId().toString(),
        project,
        specId,
        "node-a",
        "build",
        "claude-code",
        "feat/x",
        "do it",
        1234,
        5678,
        "/home/dev/.sail/runs/r/agent.log");
  }

  @Test
  void createAndFindRun() {
    var id = newRun("backend", "auth");

    var run = store.findById(id).orElseThrow();
    assertEquals("backend", run.project());
    assertEquals("auth", run.specId());
    assertEquals("node-a", run.node());
    assertEquals("build", run.role());
    assertEquals("claude-code", run.agent());
    assertEquals("feat/x", run.branch());
    assertEquals("do it", run.task());
    assertEquals(1234, run.pid());
    assertEquals(5678, run.watcherPid());
    assertEquals("running", run.status());
    assertEquals("/home/dev/.sail/runs/r/agent.log", run.logPath());
    assertNotNull(run.startedAt());
    assertNull(run.completedAt());
  }

  @Test
  void createWithNullOptionalFields() {
    var id =
        store.create(
            DateTimeUtils.newId().toString(),
            "backend",
            null,
            "node-a",
            "build",
            "codex",
            null,
            null,
            null,
            null,
            null);

    var run = store.findById(id).orElseThrow();
    assertNull(run.specId());
    assertNull(run.branch());
    assertNull(run.task());
    assertNull(run.pid());
    assertNull(run.watcherPid());
    assertNull(run.exitCode());
    assertNull(run.logPath());
  }

  @Test
  void completeRun() {
    var id = newRun("backend", "auth");
    store.complete(id, "completed", 0);

    var run = store.findById(id).orElseThrow();
    assertEquals("completed", run.status());
    assertNotNull(run.completedAt());
    assertEquals(0, run.exitCode());
  }

  @Test
  void completeRecordsANonZeroExitCode() {
    var id = newRun("backend", "auth");
    store.complete(id, "stopped", 137);

    assertEquals(137, store.findById(id).orElseThrow().exitCode());
  }

  @Test
  void recordExitCodeStampsAnAlreadyFinishedRun() {
    var id = newRun("backend", "auth");
    store.complete(id, "stopped", null);
    store.recordExitCode(id, 42);

    assertEquals(42, store.findById(id).orElseThrow().exitCode());
  }

  @Test
  void latestForProjectReturnsNewest() {
    newRun("backend", "spec-1");
    newRun("backend", "spec-2");
    newRun("other", "spec-3");

    assertEquals("spec-2", store.latestForProject("backend").orElseThrow().specId());
    assertTrue(store.latestForProject("nonexistent").isEmpty());
  }

  @Test
  void runningForProjectFindsActiveRun() {
    var id1 = newRun("backend", "spec-1");
    store.complete(id1, "completed", 0);
    newRun("backend", "spec-2");

    var running = store.runningForProject("backend").orElseThrow();
    assertEquals("spec-2", running.specId());
    assertEquals("running", running.status());
  }

  @Test
  void runningForProjectReturnsEmptyWhenAllCompleted() {
    var id = newRun("backend", "spec-1");
    store.complete(id, "completed", 0);

    assertTrue(store.runningForProject("backend").isEmpty());
  }

  @Test
  void listForProjectReturnsNewestFirst() {
    newRun("backend", "spec-1");
    newRun("backend", "spec-2");
    newRun("other", "spec-3");

    var runs = store.listForProject("backend");
    assertEquals(2, runs.size());
    assertEquals("spec-2", runs.get(0).specId());
    assertEquals("spec-1", runs.get(1).specId());
  }

  @Test
  void listForSpecReturnsRunsAcrossProjects() {
    newRun("backend", "auth");
    newRun("frontend", "auth");
    newRun("backend", "payment");

    assertEquals(2, store.listForSpec("auth").size());
  }

  @Test
  void listNarrowsByProjectAndSpec() {
    newRun("backend", "auth");
    newRun("backend", "payment");
    newRun("frontend", "auth");

    assertEquals(3, store.list(null, null).size());
    assertEquals(2, store.list("backend", null).size());
    assertEquals(2, store.list(null, "auth").size());
    assertEquals(1, store.list("backend", "auth").size());
  }

  @Test
  void findByIdReturnsEmptyForUnknown() {
    assertTrue(store.findById("nonexistent").isEmpty());
  }

  @Test
  void mutationsJournalComparableRevisions() {
    var id = newRun("backend", "auth");

    assertTrue(store.syncEntityIds().contains(id));
    var running = store.comparableSnapshot(id);
    assertEquals("running", running.get("status"));
    assertNull(running.get("id"), "the surrogate id is excluded from the comparable snapshot");
    var rev1 = store.latestRev(id);

    store.complete(id, "completed", 0);

    assertNotEquals(rev1, store.latestRev(id));
    assertEquals("completed", store.comparableSnapshot(id).get("status"));
    assertEquals("running", store.comparableAtRev(id, rev1).get("status"));
  }

  @Test
  void applyRevisionAdoptsMainsStateAtItsExactRevAsTheBase() {
    var id = newRun("backend", "auth");
    var snapshot = store.comparableSnapshot(id);
    var rev = store.latestRev(id);

    var other = freshStore("adopt.db");
    other.applyRevision(id, snapshot, rev);

    assertEquals(rev, other.latestRev(id));
    assertEquals(rev, other.baseRevOf(id));
    assertEquals("auth", other.findById(id).orElseThrow().specId());
    assertEquals("node-a", other.findById(id).orElseThrow().node());
  }

  @Test
  void commitAcceptsWhenExpectedRevMatchesAndRejectsWhenStale() {
    var other = freshStore("commit.db");
    other.applyRevision("r1", base(), "rev-base");

    var accepted = other.commitRevision("r1", moved(), "rev-base");
    assertInstanceOf(PushOutcome.Accepted.class, accepted);

    var stale = other.commitRevision("r1", moved(), "rev-base");
    assertInstanceOf(PushOutcome.Stale.class, stale);
  }

  @Test
  void applyRevisionNullAdoptsADeletion() {
    var id = newRun("backend", "auth");
    var rev = store.latestRev(id);

    store.applyRevision(id, null, rev + "-del");

    assertTrue(store.findById(id).isEmpty());
    assertNull(store.comparableSnapshot(id));
  }

  @Test
  void backfillNodeStampsRowsMissingANode() {
    db.execute(
        "INSERT INTO runs (id, project, spec_id, agent, status, started_at)"
            + " VALUES ('legacy', 'acme', 'auth', 'claude-code', 'completed', '2026-01-01')");
    assertNull(store.findById("legacy").orElseThrow().node());

    assertEquals(1, store.backfillNode("mady"));
    assertEquals(0, store.backfillNode("mady"), "idempotent");
    assertEquals(0, store.backfillNode(null), "a blank handle stamps nothing");

    assertEquals("mady", store.findById("legacy").orElseThrow().node());
    assertTrue(store.syncEntityIds().contains("legacy"), "the stamped row is journaled");
  }

  @Test
  void backfillRevisionsMakesAnUnjournaledRunSyncable() {
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, agent, status, started_at)"
            + " VALUES ('legacy', 'acme', 'auth', 'node-a', 'claude-code', 'completed', '2026-01-01')");
    assertFalse(store.syncEntityIds().contains("legacy"));

    assertEquals(1, store.backfillRevisions());
    assertEquals(0, store.backfillRevisions(), "idempotent");

    assertTrue(store.syncEntityIds().contains("legacy"));
  }

  @Test
  void resolveConflictTakeTheirsAdoptsRemote() {
    var other = freshStore("resolve.db");
    other.applyRevision("r1", base(), "rev-base");

    other.resolveConflict("r1", theirs(), theirs());

    assertEquals("completed", other.findById("r1").orElseThrow().status());
  }

  private RunStore freshStore(String file) {
    var freshDb = Sqlite.open(tempDir.resolve(file));
    new SchemaManager(freshDb).migrate();
    return new RunStore(freshDb);
  }

  private static java.util.Map<String, Object> base() {
    return java.util.Map.of(
        "project",
        "backend",
        "spec_id",
        "auth",
        "node",
        "node-a",
        "role",
        "build",
        "agent",
        "claude-code",
        "status",
        "running");
  }

  private static java.util.Map<String, Object> moved() {
    return java.util.Map.of(
        "project",
        "backend",
        "spec_id",
        "auth",
        "node",
        "node-a",
        "role",
        "build",
        "agent",
        "claude-code",
        "status",
        "completed");
  }

  private static java.util.Map<String, Object> theirs() {
    return moved();
  }
}
