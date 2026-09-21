/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
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
 * Shared project files reconcile bidirectionally through the same {@link SyncEngine} as specs: a
 * file created on one box propagates to the other, two FDEs editing <em>different</em> files
 * auto-converge, and two editing the <em>same</em> file conflict on its content with the local copy
 * left untouched — exactly the per-file granularity the design buys.
 */
class FileSyncTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private Box main;
  private Box node;
  private Box other;

  private final class Box implements AutoCloseable {
    final Sqlite db;
    final FileStore files;
    final SyncConflicts conflicts;
    final StoreReplica replica;

    Box(String id) {
      this.db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      this.files = new FileStore(db);
      this.conflicts = new SyncConflicts(db);
      this.replica = new StoreReplica(id, files, new ChangeLog(db), conflicts, new SyncState(db));
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
    SyncBox.round(main.db, box.db, "file");
  }

  @Test
  void aFileCreatedOnOneBoxPropagatesToTheOther() {
    ai.singlr.sail.store.ContentFixtures.put(node.files, "acme", "scripts/deploy.sh", "ZGVwbG95");

    sync(node);
    assertEquals(
        "ZGVwbG95",
        ai.singlr.sail.store.ContentFixtures.text(main.files, "acme", "scripts/deploy.sh"));

    sync(other);
    assertEquals(
        "ZGVwbG95",
        ai.singlr.sail.store.ContentFixtures.text(other.files, "acme", "scripts/deploy.sh"));
  }

  @Test
  void editsToDifferentFilesAutoConvergeWithoutConflict() {
    ai.singlr.sail.store.ContentFixtures.put(node.files, "acme", "a.txt", "AAA");
    sync(node);
    sync(other);

    ai.singlr.sail.store.ContentFixtures.put(node.files, "acme", "a.txt", "AAA2");
    ai.singlr.sail.store.ContentFixtures.put(other.files, "acme", "b.txt", "BBB");

    sync(node);
    sync(other);
    sync(node);

    assertEquals("AAA2", ai.singlr.sail.store.ContentFixtures.text(node.files, "acme", "a.txt"));
    assertEquals("BBB", ai.singlr.sail.store.ContentFixtures.text(node.files, "acme", "b.txt"));
    assertEquals("BBB", ai.singlr.sail.store.ContentFixtures.text(other.files, "acme", "b.txt"));
    assertTrue(node.conflicts.pending().isEmpty());
    assertTrue(other.conflicts.pending().isEmpty());
  }

  @Test
  void editsToTheSameFileConflictAndLeaveTheLocalCopyUntouched() {
    ai.singlr.sail.store.ContentFixtures.put(node.files, "acme", "shared.conf", "v1");
    sync(node);
    sync(other);

    ai.singlr.sail.store.ContentFixtures.put(node.files, "acme", "shared.conf", "from-node");
    ai.singlr.sail.store.ContentFixtures.put(other.files, "acme", "shared.conf", "from-other");

    sync(node);
    var report = sync2(other);

    assertEquals(1, report.conflicts());
    assertEquals(
        "from-node", ai.singlr.sail.store.ContentFixtures.text(main.files, "acme", "shared.conf"));
    var pending = other.conflicts.pendingFor("file", FileStore.idOf("acme", "shared.conf"));
    assertEquals(List.of("content"), pending.orElseThrow().fields());
    assertEquals(
        "from-other",
        ai.singlr.sail.store.ContentFixtures.text(other.files, "acme", "shared.conf"));
  }

  @Test
  void twoBoxesCreatingTheSameFilePathConflictWithNoCommonBase() {
    ai.singlr.sail.store.ContentFixtures.put(node.files, "acme", "shared.conf", "from-node");
    ai.singlr.sail.store.ContentFixtures.put(other.files, "acme", "shared.conf", "from-other");

    sync(node);
    var report = sync2(other);

    assertEquals(1, report.conflicts());
    assertEquals(
        List.of("content"),
        other
            .conflicts
            .pendingFor("file", FileStore.idOf("acme", "shared.conf"))
            .orElseThrow()
            .fields());
  }

  @Test
  void aDeleteOnOneBoxPropagates() {
    ai.singlr.sail.store.ContentFixtures.put(node.files, "acme", "old.txt", "x");
    sync(node);
    sync(other);

    assertTrue(node.files.delete("acme", "old.txt"));
    sync(node);
    assertTrue(main.files.find("acme", "old.txt").isEmpty());

    sync(other);
    assertTrue(other.files.find("acme", "old.txt").isEmpty());
  }

  @Test
  void aStaleCommitOnTheFileReplicaIsRejected() {
    var id = FileStore.idOf("acme", "a.txt");
    node.files.applyRevision(id, java.util.Map.of("content", "AAA"), "1-base");

    var outcome = node.replica.commit(id, java.util.Map.of("content", "BBB"), "9-stale");

    assertInstanceOf(CommitOutcome.Rejected.class, outcome);
    assertEquals("AAA", ai.singlr.sail.store.ContentFixtures.text(node.files, "acme", "a.txt"));
  }

  private SyncEngine.Report sync2(Box box) {
    return SyncBox.round(main.db, box.db, "file");
  }
}
