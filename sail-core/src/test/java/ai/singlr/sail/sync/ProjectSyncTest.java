/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class ProjectSyncTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private Box main;
  private Box node;
  private Box other;

  private final class Box implements AutoCloseable {
    final Sqlite db;
    final ProjectStore projects;
    final SyncConflicts conflicts;
    final ProjectReplica replica;

    Box(String id) {
      this.db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      this.projects = new ProjectStore(db);
      this.conflicts = new SyncConflicts(db);
      this.replica =
          new ProjectReplica(id, projects, new ChangeLog(db), conflicts, new SyncState(db));
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

  private MainReplica entityOnlyMain() {
    return new MainReplica() {
      @Override
      public String id() {
        return main.replica.id();
      }

      @Override
      public Set<String> entityIds() {
        return main.replica.entityIds();
      }

      @Override
      public Map<String, Object> current(String id) {
        return main.replica.current(id);
      }

      @Override
      public String currentRev(String id) {
        return main.replica.currentRev(id);
      }

      @Override
      public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
        return main.replica.commit(id, snapshot, expectedRev);
      }

      @Override
      public long maxSeq() {
        return main.replica.maxSeq();
      }
    };
  }

  private String definitionOn(Box box, String name) {
    return box.projects.findByName(name).orElseThrow().definition();
  }

  @Test
  void aProjectCreatedOnMainLandsOnEveryNode() {
    main.projects.upsert("acme", "name: acme\nimage: ubuntu/24.04\n", "uday");

    sync(node);
    assertEquals("name: acme\nimage: ubuntu/24.04\n", definitionOn(node, "acme"));

    sync(other);
    assertEquals("name: acme\nimage: ubuntu/24.04\n", definitionOn(other, "acme"));
  }

  @Test
  void aProjectCreatedOnANodePushesToMainAndOtherNodes() {
    node.projects.upsert("acme", "from-node", "mady");

    sync(node);
    assertEquals("from-node", definitionOn(main, "acme"));

    sync(other);
    assertEquals("from-node", definitionOn(other, "acme"));
  }

  @Test
  void editsToDifferentProjectsAutoConvergeWithoutConflict() {
    node.projects.upsert("acme", "A1", "uday");
    sync(node);
    sync(other);

    node.projects.upsert("acme", "A2", "uday");
    other.projects.upsert("beta", "B1", "mady");

    sync(node);
    sync(other);
    sync(node);

    assertEquals("A2", definitionOn(node, "acme"));
    assertEquals("B1", definitionOn(node, "beta"));
    assertEquals("B1", definitionOn(other, "beta"));
    assertTrue(node.conflicts.pending().isEmpty());
    assertTrue(other.conflicts.pending().isEmpty());
  }

  @Test
  void editsToTheSameProjectConflictAndLeaveTheLocalCopyUntouched() {
    node.projects.upsert("acme", "v1", "uday");
    sync(node);
    sync(other);

    node.projects.upsert("acme", "from-node", "uday");
    other.projects.upsert("acme", "from-other", "mady");

    sync(node);
    var report = engine.reconcile(other.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertEquals("from-node", definitionOn(main, "acme"));
    var pending = other.conflicts.pendingFor("project", "acme");
    assertEquals(List.of("definition"), pending.orElseThrow().fields());
    assertEquals("from-other", definitionOn(other, "acme"), "local copy is left untouched");
  }

  @Test
  void aDeleteOnOneBoxPropagates() {
    node.projects.upsert("acme", "x", "uday");
    sync(node);
    sync(other);

    assertTrue(node.projects.delete("acme"));
    sync(node);
    assertTrue(main.projects.findByName("acme").isEmpty());

    sync(other);
    assertTrue(other.projects.findByName("acme").isEmpty());
  }

  @Test
  void anAuthoritativeRenameConflictsWithAnUnbasedProjectOfTheSameName() {
    main.projects.upsert("old", "name: old\n", "uday");
    main.projects.rename("old", "renamed", "name: renamed\n");
    node.projects.upsert("old", "name: old\n", "sumesh");

    var report = engine.reconcile(node.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertTrue(main.projects.findByName("old").isEmpty());
    assertEquals("name: old\n", definitionOn(node, "old"));
    assertTrue(node.projects.findByName("renamed").isEmpty());
  }

  @Test
  void aRenameRacingAnUnbasedCreateSurvivesTheStaleCommitRetry() {
    node.projects.upsert("old", "name: old\n", "sumesh");
    MainReplica racingMain =
        new MainReplica() {
          private boolean raced;

          @Override
          public String id() {
            return main.replica.id();
          }

          @Override
          public Set<String> entityIds() {
            return main.replica.entityIds();
          }

          @Override
          public Map<String, Object> current(String id) {
            return main.replica.current(id);
          }

          @Override
          public String currentRev(String id) {
            return main.replica.currentRev(id);
          }

          @Override
          public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
            if (!raced) {
              raced = true;
              main.projects.upsert("old", "name: old\n", "uday");
              main.projects.rename("old", "renamed", "name: renamed\n");
            }
            return main.replica.commit(id, snapshot, expectedRev);
          }

          @Override
          public long maxSeq() {
            return main.replica.maxSeq();
          }
        };

    engine.reconcile(node.replica, racingMain);

    assertTrue(main.projects.findByName("old").isEmpty());
    assertTrue(node.projects.findByName("old").isEmpty());
    assertEquals("name: renamed\n", definitionOn(main, "renamed"));
  }

  @Test
  void aProjectRenamedOnANodePropagatesToMain() {
    main.projects.upsert("old", "name: old\n", "uday");
    sync(node);

    node.projects.rename("old", "renamed", "name: renamed\n");
    var report = engine.reconcile(node.replica, main.replica);

    assertEquals(1, report.pushed());
    assertTrue(main.projects.findByName("old").isEmpty());
    assertEquals("name: renamed\n", definitionOn(main, "renamed"));
    assertTrue(main.projects.blocksResurrection("old"));
  }

  @Test
  void anUnbasedRenameCannotDeleteAnIndependentAuthoritativeProject() {
    main.projects.upsert("old", "owner: main\n", "uday");
    node.projects.upsert("old", "owner: node\n", "sumesh");
    node.projects.rename("old", "renamed", "owner: node\n");

    var report = engine.reconcile(node.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertEquals("owner: main\n", definitionOn(main, "old"));
    assertTrue(node.projects.findByName("old").isEmpty());
    assertTrue(main.projects.findByName("renamed").isEmpty());
  }

  @Test
  void anUnbasedBlockingTombstoneConflictsWithAuthoritativeContent() {
    main.projects.upsert("victim", "owner: main\n", "uday");
    node.projects.upsert("victim", "owner: node\n", "sumesh");
    node.projects.rename("victim", "unrelated", "owner: node\n");

    var report = engine.reconcile(node.replica, entityOnlyMain());

    assertEquals(1, report.conflicts());
    assertEquals("owner: main\n", definitionOn(main, "victim"));
  }

  @Test
  void aRenameConflictsWithABasedConcurrentEditAndPreservesTheEdit() {
    main.projects.upsert("old", "value: base\n", "uday");
    sync(node);
    node.projects.upsert("old", "value: unsynced-node-edit\n", "sumesh");
    main.projects.rename("old", "renamed", "value: base\n");

    var report = engine.reconcile(node.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertEquals("value: unsynced-node-edit\n", definitionOn(node, "old"));
    assertTrue(node.projects.findByName("renamed").isEmpty());
    assertEquals(
        List.of("<deleted>"), node.conflicts.pendingFor("project", "old").orElseThrow().fields());
  }

  @Test
  void aTargetCollisionRejectsTheWholeRenameBeforeDeletingTheSource() {
    main.projects.upsert("p", "name: p\n", "uday");
    sync(node);
    main.projects.upsert("q", "owner: main\n", "uday");
    node.projects.rename("p", "q", "owner: node\n");

    var report = engine.reconcile(node.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertEquals("name: p\n", definitionOn(main, "p"));
    assertEquals("owner: main\n", definitionOn(main, "q"));
  }

  @Test
  void concurrentRenamesDoNotReplicateDuplicateTargets() {
    main.projects.upsert("p", "name: p\n", "uday");
    sync(node);
    main.projects.rename("p", "q", "name: q\n");
    node.projects.rename("p", "r", "name: r\n");

    var report = engine.reconcile(node.replica, main.replica);

    assertEquals(1, report.conflicts());
    assertEquals(List.of("q"), main.projects.list().stream().map(row -> row.name()).toList());
    assertEquals(List.of("r"), node.projects.list().stream().map(row -> row.name()).toList());
  }

  @Test
  void aStaleCommitOnTheProjectReplicaIsRejected() {
    node.projects.applyRevision("acme", Map.of("definition", "AAA"), "1-base");

    var outcome = node.replica.commit("acme", Map.of("definition", "BBB"), "9-stale");

    assertInstanceOf(CommitOutcome.Rejected.class, outcome);
    assertEquals("AAA", definitionOn(node, "acme"));
  }
}
