/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Run metadata reconciles up to main and on to every other box through the same {@link SyncEngine}
 * as specs — "sumesh's agent started/finished" reaches main and every reader box learns which node
 * ran spec X. Runs are single-writer: only the executing node mutates its own runs, so a reader box
 * that pulled a run never pushes a change back and the round stays conflict-free.
 */
class RunSyncTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private Box main;
  private Box node;
  private Box other;

  private final class Box implements AutoCloseable {
    final Sqlite db;
    final RunStore runs;
    final SyncConflicts conflicts;
    final RunReplica replica;

    Box(String id) {
      this.db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      this.runs = new RunStore(db);
      this.conflicts = new SyncConflicts(db);
      this.replica = new RunReplica(id, id, runs, new ChangeLog(db), conflicts, new SyncState(db));
    }

    @Override
    public void close() {
      db.close();
    }
  }

  @BeforeEach
  void setUp() {
    main = new Box("main");
    node = new Box("node");
    other = new Box("other");
  }

  @AfterEach
  void tearDown() {
    other.close();
    node.close();
    main.close();
  }

  private void sync(Box box) {
    engine.reconcile(box.replica, main.replica);
  }

  private String startRun(Box box, String node) {
    var id = DateTimeUtils.newId().toString();
    return box.runs.create(
        id,
        "backend",
        "auth",
        node,
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        123,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
  }

  @Test
  void aRunStartedOnANodePushesToMainAndOtherNodes() {
    var id = startRun(node, "node");

    sync(node);
    assertEquals("node", main.runs.findById(id).orElseThrow().node());
    assertEquals("running", main.runs.findById(id).orElseThrow().status());

    sync(other);
    assertEquals("node", other.runs.findById(id).orElseThrow().node());
    assertEquals("running", other.runs.findById(id).orElseThrow().status());
  }

  @Test
  void aReviewRunReplicatesAsMetadataButRemainsOwnedByItsExecutingNode() {
    var id = DateTimeUtils.newId().toString();
    node.runs.createReview(
        id,
        "backend",
        "auth",
        "node",
        "codex",
        "feat/auth",
        "review it",
        "/home/dev/.sail/runs/" + id + "/review.log",
        "sail-review-" + id);

    sync(node);
    sync(other);

    var replicated = other.runs.findById(id).orElseThrow();
    assertEquals("review", replicated.role());
    assertEquals("node", replicated.node());
    assertEquals("running", replicated.status());
    assertFalse(other.replica.mayPush(id));
  }

  @Test
  void aLifecycleTransitionOnTheOwningNodePropagates() {
    var id = startRun(node, "node");
    sync(node);
    sync(other);

    node.runs.complete(id, "completed", 0);
    sync(node);
    assertEquals("completed", main.runs.findById(id).orElseThrow().status());

    sync(other);
    assertEquals("completed", other.runs.findById(id).orElseThrow().status());
    assertEquals(0, other.runs.findById(id).orElseThrow().exitCode());
  }

  @Test
  void aReplicaMayPushOnlyTheRunsItExecuted() {
    var mine = startRun(node, "node");
    var foreign = startRun(node, "other");
    var stampless = startRun(node, "");

    assertTrue(node.replica.mayPush(mine), "a node may push a run it executed");
    assertFalse(node.replica.mayPush(foreign), "a node must not push a run another node executed");
    assertTrue(
        node.replica.mayPush(stampless),
        "a stampless pre-upgrade run is left to main's ownership guard, not denied here");
  }

  @Test
  void aReaderNeverPushesADivergedForeignRunAndAdoptsMainsVersion() {
    var id = startRun(node, "node");
    sync(node);
    sync(other);

    other.runs.complete(id, "stopped", 0);

    sync(other);

    assertEquals(
        "running",
        main.runs.findById(id).orElseThrow().status(),
        "a reader box cannot clobber main's run with a change it did not author");
    assertEquals(
        "running",
        other.runs.findById(id).orElseThrow().status(),
        "the reader discards its illegitimate change and adopts main's authoritative version");
  }

  @Test
  void runsFromTwoDifferentNodesBothLandEverywhereWithoutConflict() {
    var fromNode = startRun(node, "node");
    var fromOther = startRun(other, "other");

    sync(node);
    sync(other);
    sync(node);

    assertEquals("node", main.runs.findById(fromNode).orElseThrow().node());
    assertEquals("other", main.runs.findById(fromOther).orElseThrow().node());
    assertEquals("other", node.runs.findById(fromOther).orElseThrow().node());
    assertTrue(node.conflicts.pending().isEmpty());
    assertTrue(other.conflicts.pending().isEmpty());
  }

  @Test
  void aReaderBoxNeverPushesAForeignRunAndStaysConflictFree() {
    var id = startRun(node, "node");
    sync(node);
    sync(other);

    var mainRevAfterPull = main.runs.latestRev(id);
    var report = engine.reconcile(other.replica, main.replica);

    assertEquals(0, report.pushed(), "a reader box pushes nothing for a foreign run");
    assertEquals(0, report.conflicts());
    assertEquals(
        mainRevAfterPull, main.runs.latestRev(id), "main's row is untouched by the reader's round");
  }
}
