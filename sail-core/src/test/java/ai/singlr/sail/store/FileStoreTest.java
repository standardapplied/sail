/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FileStore files;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private String id(String path) {
    return FileStore.idOf("acme", path);
  }

  @Test
  void putAndFindAndList() {
    files.put("acme", "a.txt", "AAA");
    files.put("acme", "dir/b.txt", "BBB");

    assertEquals("AAA", files.find("acme", "a.txt").orElseThrow().content());
    assertEquals(
        List.of("a.txt", "dir/b.txt"),
        files.list("acme").stream().map(FileStore.FileRow::path).toList());
  }

  @Test
  void deleteReturnsFalseWhenAbsentAndTrueWhenPresent() {
    assertFalse(files.delete("acme", "missing"));
    files.put("acme", "a.txt", "AAA");
    assertTrue(files.delete("acme", "a.txt"));
    assertTrue(files.find("acme", "a.txt").isEmpty());
  }

  @Test
  void comparableSnapshotAndAtRev() {
    files.put("acme", "a.txt", "AAA");
    var rev = files.latestRev(id("a.txt"));

    assertEquals("AAA", files.comparableSnapshot(id("a.txt")).get("content"));
    assertEquals("AAA", files.comparableAtRev(id("a.txt"), rev).get("content"));
    assertNull(files.comparableAtRev(id("a.txt"), null));
    assertNull(files.comparableSnapshot(id("missing")));
  }

  @Test
  void baseRevOfRecoversTheTombstoneBaseForADeletedFile() {
    files.applyRevision(id("a.txt"), Map.of("content", "AAA"), "1-base");
    assertEquals("1-base", files.baseRevOf(id("a.txt")));

    files.delete("acme", "a.txt");
    assertEquals("1-base", files.baseRevOf(id("a.txt")), "base recovered from the tombstone");
  }

  @Test
  void commitAcceptsWhenExpectedRevMatches() {
    files.applyRevision(id("a.txt"), Map.of("content", "AAA"), "1-base");

    var outcome = files.commitRevision(id("a.txt"), Map.of("content", "BBB"), "1-base");

    assertInstanceOf(PushOutcome.Accepted.class, outcome);
    assertEquals("BBB", files.find("acme", "a.txt").orElseThrow().content());
  }

  @Test
  void commitRejectsAStalePushAndLeavesTheFileUntouched() {
    files.applyRevision(id("a.txt"), Map.of("content", "AAA"), "1-base");

    var outcome = files.commitRevision(id("a.txt"), Map.of("content", "BBB"), "9-stale");

    var stale = assertInstanceOf(PushOutcome.Stale.class, outcome);
    assertEquals("1-base", stale.currentRev());
    assertEquals("AAA", stale.currentSnapshot().get("content"));
    assertEquals("AAA", files.find("acme", "a.txt").orElseThrow().content());
  }

  @Test
  void committingADeleteOfAnAbsentFileIsANoOpAccept() {
    assertInstanceOf(PushOutcome.Accepted.class, files.commitRevision(id("ghost"), null, null));
    assertTrue(files.find("acme", "ghost").isEmpty());
  }

  @Test
  void comparableAtRevWithABlankRevIsNull() {
    assertNull(files.comparableAtRev(id("a.txt"), "  "));
  }

  @Test
  void baseRevOfADeletedLocalFileIsNull() {
    files.put("acme", "a.txt", "AAA");
    files.delete("acme", "a.txt");
    assertNull(files.baseRevOf(id("a.txt")), "a locally-created file has no synced base");
  }

  @Test
  void aSnapshotMissingContentIsRejected() {
    var snapshot = new java.util.HashMap<String, Object>();
    snapshot.put("content", null);
    assertThrows(RuntimeException.class, () -> files.applyRevision(id("a.txt"), snapshot, "1-x"));
  }

  @Test
  void idsForProjectIncludesTombstonedFiles() {
    files.put("acme", "a.txt", "AAA");
    files.put("acme", "dir/b.txt", "BBB");
    files.put("globex", "c.txt", "CCC");
    files.delete("acme", "a.txt");

    assertEquals(
        List.of("acme/a.txt", "acme/dir/b.txt"),
        files.idsForProject("acme").stream().sorted().toList());
  }

  @Test
  void projectsWithFilesSpansEveryProjectTouched() {
    files.put("acme", "a.txt", "AAA");
    files.put("globex", "c.txt", "CCC");
    files.delete("globex", "c.txt");

    assertEquals(java.util.Set.of("acme", "globex"), files.projectsWithFiles());
  }

  @Test
  void isKnownContentRecognizesAnyRevisionThisBoxWrote() {
    files.put("acme", "a.txt", "v1");
    files.put("acme", "a.txt", "v2");

    assertTrue(files.isKnownContent(id("a.txt"), "v1"), "a superseded revision is still ours");
    assertTrue(files.isKnownContent(id("a.txt"), "v2"));
    assertFalse(files.isKnownContent(id("a.txt"), "a local human edit"));
  }

  @Test
  void resolveTakeTheirsAdoptsMainAndCannotReRaise() {
    files.put("acme", "a.txt", "mine");

    var rev =
        files.resolveConflict(
            id("a.txt"), Map.of("content", "theirs"), Map.of("content", "theirs"));

    assertEquals("theirs", files.find("acme", "a.txt").orElseThrow().content());
    assertEquals(rev, files.baseRevOf(id("a.txt")), "base now equals theirs, so no re-raise");
  }

  @Test
  void resolveKeepMineRebasesOntoTheirsAndPushesMineForward() {
    files.put("acme", "a.txt", "mine");

    files.resolveConflict(id("a.txt"), Map.of("content", "mine"), Map.of("content", "theirs"));

    assertEquals("mine", files.find("acme", "a.txt").orElseThrow().content());
    assertTrue(files.isKnownContent(id("a.txt"), "theirs"), "theirs is journaled as the base");
  }

  @Test
  void resolveTakeTheirsWhereTheirsIsADeleteRemovesTheRow() {
    files.put("acme", "a.txt", "mine");

    files.resolveConflict(id("a.txt"), null, null);

    assertTrue(files.find("acme", "a.txt").isEmpty());
  }

  @Test
  void resolveKeepMineWhereTheirsIsADeleteRestoresMine() {
    files.applyRevision(id("a.txt"), Map.of("content", "base"), "1-base");
    files.put("acme", "a.txt", "mine");

    files.resolveConflict(id("a.txt"), Map.of("content", "mine"), null);

    assertEquals("mine", files.find("acme", "a.txt").orElseThrow().content());
  }

  @Test
  void resolveDeleteMineWhereTheirsEditsTombstonesTheRow() {
    files.applyRevision(id("a.txt"), Map.of("content", "base"), "1-base");

    files.resolveConflict(id("a.txt"), null, Map.of("content", "theirs"));

    assertTrue(files.find("acme", "a.txt").isEmpty());
  }
}
