/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.*;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * In-process two-node harness: each "box" is its own SQLite DB with a full set of stores. Two nodes
 * sync to a shared main, proving convergence, propagation, and conflict surfacing end to end
 * through the real {@link SyncEngine} — no transport.
 */
class TwoNodeSyncTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private Box main;
  private Box nodeA;
  private Box nodeB;

  private final class Box implements AutoCloseable {
    final String id;
    final Sqlite db;
    final SpecStore specs;
    final SyncConflicts conflicts;
    final SpecReplica replica;

    Box(String id) {
      this.id = id;
      this.db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      this.specs = new SpecStore(db);
      this.conflicts = new SyncConflicts(db);
      this.replica = new SpecReplica(id, specs, new ChangeLog(db), conflicts, new SyncState(db));
    }

    @Override
    public void close() {
      db.close();
    }
  }

  @BeforeEach
  void setUp() {
    main = new Box("main");
    nodeA = new Box("A");
    nodeB = new Box("B");
  }

  @AfterEach
  void tearDown() {
    nodeB.close();
    nodeA.close();
    main.close();
  }

  private SpecStore.SpecRow spec(String id, String title, String status) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire(status),
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  private void syncToMain(Box node) {
    engine.reconcile(node.replica, main.replica);
  }

  @Test
  void aLocalCreatePropagatesToMainAndThenToTheOtherNode() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));

    syncToMain(nodeA);
    assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());

    syncToMain(nodeB);
    assertEquals("Auth", nodeB.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void disjointFieldEditsOnTwoNodesAutoMergeWithoutConflict() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.updateStatus("auth", SpecStatus.fromWire("in_progress"));
    nodeB.specs.setContent("auth", "node B body", "");

    syncToMain(nodeA);
    syncToMain(nodeB);
    syncToMain(nodeA);

    var a = nodeA.specs.findById("auth").orElseThrow();
    var b = nodeB.specs.findById("auth").orElseThrow();
    assertEquals("in_progress", a.status().wire());
    assertEquals("in_progress", b.status().wire());
    assertEquals("node B body", nodeA.specs.getContent("auth").orElseThrow().body());
    assertEquals("node B body", nodeB.specs.getContent("auth").orElseThrow().body());
    assertTrue(nodeA.conflicts.pending().isEmpty());
    assertTrue(nodeB.conflicts.pending().isEmpty());
  }

  @Test
  void sameFieldEditsOnTwoNodesConflictOnTheSecondAndLeaveItsWorkUntouched() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.update(spec("auth", "Title from A", "pending"));
    nodeB.specs.update(spec("auth", "Title from B", "pending"));

    syncToMain(nodeA);
    syncToMain(nodeB);

    assertEquals("Title from A", main.specs.findById("auth").orElseThrow().title());
    var pending = nodeB.conflicts.pending();
    assertEquals(1, pending.size());
    assertEquals(List.of("title"), pending.getFirst().fields());
    assertEquals(
        "Title from B",
        nodeB.specs.findById("auth").orElseThrow().title(),
        "node B's work is left untouched while the conflict is open");
  }

  @Test
  void aLocalDeletePropagatesToMainAndTheOtherNode() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.delete("auth");
    syncToMain(nodeA);
    assertTrue(main.specs.findById("auth").isEmpty());

    syncToMain(nodeB);
    assertTrue(nodeB.specs.findById("auth").isEmpty());
  }

  @Test
  void deleteOnOneNodeVersusEditOnAnotherConflicts() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.delete("auth");
    nodeB.specs.update(spec("auth", "Edited by B", "pending"));

    syncToMain(nodeA);
    syncToMain(nodeB);

    assertTrue(main.specs.findById("auth").isEmpty(), "A's delete reached main first");
    var pending = nodeB.conflicts.pending();
    assertEquals(1, pending.size());
    assertEquals(List.of("<deleted>"), pending.getFirst().fields());
  }

  @Test
  void reSyncingWithNoChangesConvergesAndDoesNothing() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);

    var second = engine.reconcile(nodeA.replica, main.replica);

    assertEquals(0, second.total());
  }

  @Test
  void anInterruptedRoundReRunsIdempotently() {
    nodeA.specs.create(spec("one", "One", "pending"));
    nodeA.specs.create(spec("two", "Two", "pending"));

    engine.reconcile(nodeA.replica, main.replica);
    var rerun = engine.reconcile(nodeA.replica, main.replica);

    assertEquals(0, rerun.total());
    assertEquals(2, main.specs.list(SpecStore.SpecFilter.all()).size());
  }

  @Test
  void checkpointAdvancesToMainHighWaterMark() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);

    assertEquals(main.replica.maxSeq(), new SyncState(nodeA.db).checkpoint("main"));
    assertTrue(main.replica.maxSeq() > 0);
  }
}
