/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Project definitions reconcile bidirectionally through the same {@link SyncEngine} as specs and
 * files: a project created on main lands on every node ("pull a project from main" = sync), two
 * boxes touching <em>different</em> projects auto-converge, and two editing the <em>same</em>
 * project's definition conflict with the local copy left untouched.
 */
@ActingAs
class ProjectSyncTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private Box main;
  private Box node;
  private Box other;

  private final class Box implements AutoCloseable {
    final String id;
    final Sqlite db;
    final ProjectStore projects;
    final SyncConflicts conflicts;
    final StoreReplica replica;

    Box(String id) {
      this.id = id;
      this.db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      this.projects = new ProjectStore(db);
      this.conflicts = new SyncConflicts(db);
      this.replica =
          new StoreReplica(id, projects, new ChangeLog(db), conflicts, new SyncState(db));
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

  private String definitionOn(Box box, String name) {
    return box.projects.findByName(name).orElseThrow().definition();
  }

  @Test
  void aViewersPushOfAProjectIsDeniedAndMainKeepsItsDefinition() {
    main.projects.upsert("acme", "A1");
    var viewer = Actor.sync("ada", Role.VIEWER);
    var rev = main.replica.currentRev("acme");
    var edit = new LinkedHashMap<>(main.replica.current("acme"));
    edit.put("definition", "A2");

    var edited = Actor.call(viewer, () -> main.replica.commit("acme", edit, rev));
    var deleted = Actor.call(viewer, () -> main.replica.commit("acme", null, rev));

    assertInstanceOf(CommitOutcome.Denied.class, edited);
    assertInstanceOf(CommitOutcome.Denied.class, deleted);
    assertEquals("A1", definitionOn(main, "acme"));
    assertEquals(rev, main.replica.currentRev("acme"));

    assertConverged();
  }

  @Test
  void aProjectCreatedOnMainLandsOnEveryNode() {
    main.projects.upsert("acme", "name: acme\nimage: ubuntu/24.04\n");

    sync(node);
    assertEquals("name: acme\nimage: ubuntu/24.04\n", definitionOn(node, "acme"));

    sync(other);
    assertEquals("name: acme\nimage: ubuntu/24.04\n", definitionOn(other, "acme"));

    assertConverged();
  }

  @Test
  void aProjectCreatedOnANodePushesToMainAndOtherNodes() {
    node.projects.upsert("acme", "from-node");

    sync(node);
    assertEquals("from-node", definitionOn(main, "acme"));

    sync(other);
    assertEquals("from-node", definitionOn(other, "acme"));

    assertConverged();
  }

  @Test
  void editsToDifferentProjectsAutoConvergeWithoutConflict() {
    node.projects.upsert("acme", "A1");
    sync(node);
    sync(other);

    node.projects.upsert("acme", "A2");
    other.projects.upsert("beta", "B1");

    sync(node);
    sync(other);
    sync(node);

    assertEquals("A2", definitionOn(node, "acme"));
    assertEquals("B1", definitionOn(node, "beta"));
    assertEquals("B1", definitionOn(other, "beta"));
    assertTrue(node.conflicts.pending().isEmpty());
    assertTrue(other.conflicts.pending().isEmpty());

    assertConverged();
  }

  @Test
  void editsToTheSameProjectConflictAndLeaveTheLocalCopyUntouched() {
    node.projects.upsert("acme", "v1");
    sync(node);
    sync(other);

    node.projects.upsert("acme", "from-node");
    other.projects.upsert("acme", "from-other");

    sync(node);
    var report = engine.reconcile(other.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertEquals("from-node", definitionOn(main, "acme"));
    var pending = other.conflicts.pendingFor("project", "acme");
    assertEquals(List.of("definition"), pending.orElseThrow().fields());
    assertEquals("from-other", definitionOn(other, "acme"), "local copy is left untouched");

    resolveTakingMains(other, "project", "acme");

    assertConverged();
  }

  @Test
  void aDeleteOnOneBoxPropagates() {
    node.projects.upsert("acme", "x");
    sync(node);
    sync(other);

    assertTrue(node.projects.delete("acme"));
    sync(node);
    assertTrue(main.projects.findByName("acme").isEmpty());

    sync(other);
    assertTrue(other.projects.findByName("acme").isEmpty());

    assertConverged();
  }

  @Test
  void aStaleCommitOnTheProjectReplicaIsRejected() {
    main.projects.upsert("acme", "AAA");
    sync(node);

    var outcome = node.replica.commit("acme", Map.of("definition", "BBB"), "9-stale");

    assertInstanceOf(CommitOutcome.Rejected.class, outcome);
    assertEquals("AAA", definitionOn(node, "acme"));

    assertConverged();
  }

  @Test
  void aRenameOnMainDeletesAStaleUnbasedNodeCopyInsteadOfResurrectingIt() {
    main.projects.upsert("p", "name: p\n");
    node.projects.upsert("p", "name: p\n");

    main.projects.rename("p", "q", "name: q\n");
    sync(node);

    assertTrue(
        node.projects.findByName("p").isEmpty(), "the stale unbased copy adopts main's deletion");
    assertEquals("name: q\n", definitionOn(node, "q"), "the new name propagates");
    assertTrue(main.projects.findByName("p").isEmpty(), "the node never resurrected p on main");

    assertConverged();
  }

  @Test
  void aRenameOnMainRemovesASyncedProjectFromNodesWithoutConflict() {
    main.projects.upsert("p", "name: p\n");
    sync(node);

    main.projects.rename("p", "q", "name: q\n");
    sync(node);

    assertTrue(node.projects.findByName("p").isEmpty());
    assertEquals("name: q\n", definitionOn(node, "q"));
    assertTrue(node.conflicts.pending().isEmpty(), "a clean rename is a pull, not a conflict");

    assertConverged();
  }

  @Test
  void aRenameOnANodePushesBothTheDeletionAndTheNewNameToMain() {
    node.projects.upsert("p", "name: p\n");
    node.projects.rename("p", "q", "name: q\n");

    sync(node);

    assertTrue(main.projects.findByName("p").isEmpty(), "main receives the deletion");
    assertEquals("name: q\n", definitionOn(main, "q"), "main receives the new name");

    assertConverged();
  }

  @Test
  void aRenameDuringTheCommitOfAnEditKeepsItsBlockSoAStaleCopyNeverResurrects() {
    main.projects.upsert("p", "name: p\n");
    sync(node);
    node.projects.upsert("p", "name: p\nedited: true\n");
    var racing = new ScriptedMain(main.replica);
    racing.onFirstCommit = () -> node.projects.rename("p", "q", "name: q\n");

    engine.reconcile(node.replica, racing);

    assertTrue(node.projects.blocksResurrection("p"), "the rename's block survives on the node");
    assertTrue(main.projects.blocksResurrection("p"), "and reaches main");
    other.projects.upsert("p", "name: p\n");
    sync(other);
    assertTrue(main.projects.findByName("p").isEmpty(), "a stale copy never resurrects p");

    assertConverged();
  }

  @Test
  void aRenameAfterADeletionWhoseAnswerIsLostKeepsItsBlockSoAStaleCopyNeverResurrects()
      throws IOException {
    main.projects.upsert("p", "name: p\n");
    sync(node);
    Acting.as(node.id, () -> node.projects.delete("p"));
    try (var mainBox = opened(main);
        var nodeBox = opened(node)) {
      SyncBox.pushLosingTheAnswer(mainBox, nodeBox, "project");
      assertFalse(main.projects.blocksResurrection("p"), "main took a plain deletion");
      Acting.as(
          node.id,
          () -> {
            node.projects.upsert("p", "name: p\n");
            node.projects.rename("p", "q", "name: q\n");
          });

      SyncBox.quiesce(mainBox, nodeBox);
    }

    assertTrue(main.projects.blocksResurrection("p"), "the later rename's block reaches main");
    other.projects.upsert("p", "name: p\n");
    sync(other);
    assertTrue(main.projects.findByName("p").isEmpty(), "a stale copy never resurrects p");
    assertConverged();
  }

  private void assertConverged() {
    try (var mainBox = opened(main);
        var nodeBox = opened(node);
        var otherBox = opened(other)) {
      SyncBox.assertConverged(mainBox, nodeBox, otherBox);
    }
  }

  private SyncBox opened(Box box) {
    return SyncBox.opening(tempDir.resolve(box.id + ".db"), box.id);
  }

  private static void resolveTakingMains(Box box, String type, String id) {
    var conflict = box.conflicts.pendingFor(type, id).orElseThrow();
    var theirs = conflict.theirs();
    var rev =
        Acting.as(
            box.id,
            () ->
                SyncedEntities.require(type)
                    .resolver(box.db)
                    .resolveConflict(id, theirs.snapshot(), theirs));
    box.conflicts.resolve(conflict.id(), rev);
  }
}
