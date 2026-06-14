/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * In-process two-node harness: each {@link SyncBox} is its own SQLite DB with a full set of stores.
 * Two nodes sync to a shared main, proving convergence, propagation, and conflict surfacing end to
 * end through the real {@link SyncEngine} — no transport.
 */
class TwoNodeSyncTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private SyncBox main;
  private SyncBox nodeA;
  private SyncBox nodeB;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    nodeA = new SyncBox(tempDir, "A");
    nodeB = new SyncBox(tempDir, "B");
  }

  @AfterEach
  void tearDown() {
    nodeB.close();
    nodeA.close();
    main.close();
  }

  private SpecStore.SpecRow spec(String id, String title, String status) {
    return SyncBox.spec(id, title, status);
  }

  private void syncToMain(SyncBox node) {
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
  void deletingASpecThatNeverReachedMainConvergesQuietly() {
    nodeA.specs.create(spec("local", "Local only", "pending"));
    nodeA.specs.delete("local");

    var report = engine.reconcile(nodeA.replica, main.replica);

    assertEquals(0, report.total());
    assertTrue(main.specs.findById("local").isEmpty());
  }

  private SpecStore.SpecRow specBy(String id, String title, String author) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire("pending"),
        null,
        null,
        null,
        null,
        null,
        0,
        author,
        "",
        "",
        author,
        List.of(),
        List.of());
  }

  @Test
  void theAuthorOfASyncedSpecPropagatesInsteadOfBecomingSync() {
    nodeA.specs.create(specBy("auth", "Auth", "ada"));

    syncToMain(nodeA);
    assertEquals(
        "ada",
        main.specs.findById("auth").orElseThrow().updatedBy(),
        "main attributes the row to its real author, not 'sync'");

    syncToMain(nodeB);
    assertEquals(
        "ada",
        nodeB.specs.findById("auth").orElseThrow().updatedBy(),
        "the author reaches the other node too");
  }

  @Test
  void aSyncedEditCarriesTheEditorNotTheOriginalAuthor() {
    nodeA.specs.create(specBy("auth", "Auth", "ada"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeB.specs.update(specBy("auth", "Auth, revised", "bob"));
    syncToMain(nodeB);
    syncToMain(nodeA);

    assertEquals("bob", main.specs.findById("auth").orElseThrow().updatedBy());
    assertEquals("bob", nodeA.specs.findById("auth").orElseThrow().updatedBy());
  }

  @Test
  void checkpointAdvancesToMainHighWaterMark() {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);

    assertEquals(main.replica.maxSeq(), nodeA.syncState.checkpoint("main"));
    assertTrue(main.replica.maxSeq() > 0);
  }
}
