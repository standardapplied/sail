/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Snapshots;
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

  private ProjectDefinition definitionOn(Box box, String name) {
    return box.projects.findByName(name).orElseThrow().definition();
  }

  @Test
  void aViewersPushOfAProjectIsDeniedAndMainKeepsItsDefinition() {
    main.projects.upsert(TestDefinitions.described("zenith", "A1"));
    var viewer = Actor.sync("ada", Role.VIEWER);
    var rev = main.replica.currentRev("zenith");
    var edit = new LinkedHashMap<>(main.replica.current("zenith"));
    edit.put("description", "\"A2\"");

    var edited = Actor.call(viewer, () -> main.replica.commit("zenith", edit, rev));
    var deleted = Actor.call(viewer, () -> main.replica.commit("zenith", null, rev));

    assertInstanceOf(CommitOutcome.Denied.class, edited);
    assertInstanceOf(CommitOutcome.Denied.class, deleted);
    assertEquals("A1", definitionOn(main, "zenith").description());
    assertEquals(rev, main.replica.currentRev("zenith"));

    assertConverged();
  }

  @Test
  void aProjectCreatedOnMainLandsOnEveryNode() {
    main.projects.upsert(TestDefinitions.yaml("name: zenith\nimage: ubuntu/24.04\n"));

    sync(node);
    assertEquals(
        TestDefinitions.yaml("name: zenith\nimage: ubuntu/24.04\n"), definitionOn(node, "zenith"));

    sync(other);
    assertEquals(
        TestDefinitions.yaml("name: zenith\nimage: ubuntu/24.04\n"), definitionOn(other, "zenith"));

    assertConverged();
  }

  @Test
  void aProjectCreatedOnANodePushesToMainAndOtherNodes() {
    node.projects.upsert(TestDefinitions.described("zenith", "from-node"));

    sync(node);
    assertEquals("from-node", definitionOn(main, "zenith").description());

    sync(other);
    assertEquals("from-node", definitionOn(other, "zenith").description());

    assertConverged();
  }

  @Test
  void editsToDifferentProjectsAutoConvergeWithoutConflict() {
    node.projects.upsert(TestDefinitions.described("zenith", "A1"));
    sync(node);
    sync(other);

    node.projects.upsert(TestDefinitions.described("zenith", "A2"));
    other.projects.upsert(TestDefinitions.described("beta", "B1"));

    sync(node);
    sync(other);
    sync(node);

    assertEquals("A2", definitionOn(node, "zenith").description());
    assertEquals("B1", definitionOn(node, "beta").description());
    assertEquals("B1", definitionOn(other, "beta").description());
    assertTrue(node.conflicts.pending().isEmpty());
    assertTrue(other.conflicts.pending().isEmpty());

    assertConverged();
  }

  @Test
  void editsToTheSameProjectConflictAndLeaveTheLocalCopyUntouched() {
    node.projects.upsert(TestDefinitions.described("zenith", "v1"));
    sync(node);
    sync(other);

    node.projects.upsert(TestDefinitions.described("zenith", "from-node"));
    other.projects.upsert(TestDefinitions.described("zenith", "from-other"));

    sync(node);
    var report = engine.reconcile(other.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertEquals("from-node", definitionOn(main, "zenith").description());
    var pending = other.conflicts.pendingFor("project", "zenith");
    assertEquals(List.of("description"), pending.orElseThrow().fields());
    assertEquals(
        "from-other", definitionOn(other, "zenith").description(), "local copy is left untouched");

    resolveTakingMains(other, "project", "zenith");

    assertConverged();
  }

  @Test
  void aDeleteOnOneBoxPropagates() {
    node.projects.upsert(TestDefinitions.described("zenith", "x"));
    sync(node);
    sync(other);

    assertTrue(node.projects.delete("zenith"));
    sync(node);
    assertTrue(main.projects.findByName("zenith").isEmpty());

    sync(other);
    assertTrue(other.projects.findByName("zenith").isEmpty());

    assertConverged();
  }

  @Test
  void aStaleCommitOnTheProjectReplicaIsRejected() {
    main.projects.upsert(TestDefinitions.described("zenith", "AAA"));
    sync(node);

    var outcome = node.replica.commit("zenith", Map.of("description", "\"BBB\""), "9-stale");

    assertInstanceOf(CommitOutcome.Rejected.class, outcome);
    assertEquals("AAA", definitionOn(node, "zenith").description());

    assertConverged();
  }

  @Test
  void aRenameOnMainDeletesAStaleUnbasedNodeCopyInsteadOfResurrectingIt() {
    main.projects.upsert(TestDefinitions.named("p"));
    node.projects.upsert(TestDefinitions.named("p"));

    main.projects.rename("p", "q", TestDefinitions.named("q"));
    sync(node);

    assertTrue(
        node.projects.findByName("p").isEmpty(), "the stale unbased copy adopts main's deletion");
    assertEquals(TestDefinitions.named("q"), definitionOn(node, "q"), "the new name propagates");
    assertTrue(main.projects.findByName("p").isEmpty(), "the node never resurrected p on main");

    assertConverged();
  }

  @Test
  void aRenameOnMainRemovesASyncedProjectFromNodesWithoutConflict() {
    main.projects.upsert(TestDefinitions.named("p"));
    sync(node);

    main.projects.rename("p", "q", TestDefinitions.named("q"));
    sync(node);

    assertTrue(node.projects.findByName("p").isEmpty());
    assertEquals(TestDefinitions.named("q"), definitionOn(node, "q"));
    assertTrue(node.conflicts.pending().isEmpty(), "a clean rename is a pull, not a conflict");

    assertConverged();
  }

  @Test
  void aRenameOnANodePushesBothTheDeletionAndTheNewNameToMain() {
    node.projects.upsert(TestDefinitions.named("p"));
    node.projects.rename("p", "q", TestDefinitions.named("q"));

    sync(node);

    assertTrue(main.projects.findByName("p").isEmpty(), "main receives the deletion");
    assertEquals(TestDefinitions.named("q"), definitionOn(main, "q"), "main receives the new name");

    assertConverged();
  }

  @Test
  void aRenameDuringTheCommitOfAnEditKeepsItsBlockSoAStaleCopyNeverResurrects() {
    main.projects.upsert(TestDefinitions.named("p"));
    sync(node);
    node.projects.upsert(TestDefinitions.described("p", "edited"));
    var racing = new ScriptedMain(main.replica);
    racing.onFirstCommit = () -> node.projects.rename("p", "q", TestDefinitions.named("q"));

    engine.reconcile(node.replica, racing);

    assertTrue(
        Snapshots.isDeletionMark(node.projects.currentForSync("p")),
        "the rename's block survives on the node");
    assertTrue(Snapshots.isDeletionMark(main.projects.currentForSync("p")), "and reaches main");
    other.projects.upsert(TestDefinitions.named("p"));
    sync(other);
    assertTrue(main.projects.findByName("p").isEmpty(), "a stale copy never resurrects p");

    assertConverged();
  }

  @Test
  void aRenameAfterADeletionWhoseAnswerIsLostKeepsItsBlockSoAStaleCopyNeverResurrects()
      throws IOException {
    main.projects.upsert(TestDefinitions.named("p"));
    sync(node);
    Acting.as(node.id, () -> node.projects.delete("p"));
    try (var mainBox = opened(main);
        var nodeBox = opened(node).syncsAs(Actor.sync(node.id, Role.ADMIN))) {
      SyncBox.pushLosingTheAnswer(mainBox, nodeBox, "project");
      assertFalse(
          Snapshots.isDeletionMark(main.projects.currentForSync("p")),
          "main took a plain deletion");
      Acting.as(
          node.id,
          () -> {
            node.projects.upsert(TestDefinitions.named("p"));
            node.projects.rename("p", "q", TestDefinitions.named("q"));
          });

      SyncBox.quiesce(mainBox, nodeBox);
    }

    assertTrue(
        Snapshots.isDeletionMark(main.projects.currentForSync("p")),
        "the later rename's block reaches main");
    other.projects.upsert(TestDefinitions.named("p"));
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
