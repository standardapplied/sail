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

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@ActingAs
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
  void reprojectJournalsATombstoneAndAFreshRevisionSoCheckpointedPeersSeeTheMove() {
    ContentFixtures.put(files, "old", "a.txt", "AAA");
    ContentFixtures.put(files, "old", "dir/b.txt", "BBB");
    var log = new ChangeLog(db);
    var checkpoint = log.maxSeq("file");

    files.reproject("old", "renamed");

    assertTrue(files.find("old", "a.txt").isEmpty(), "nothing left under the old project");
    assertEquals("AAA", ContentFixtures.text(files, "renamed", "a.txt"));
    assertEquals("BBB", ContentFixtures.text(files, "renamed", "dir/b.txt"));
    assertTrue(log.head("file", "old/a.txt").orElseThrow().deleted(), "old id tombstoned");
    assertFalse(log.head("file", "renamed/a.txt").orElseThrow().deleted(), "new id live");
    assertEquals(
        List.of("old/a.txt", "old/dir/b.txt", "renamed/a.txt", "renamed/dir/b.txt"),
        log.headsAfter("file", checkpoint, 10).stream()
            .map(ChangeLog.Head::entityId)
            .sorted()
            .toList(),
        "a peer checkpointed before the rename pages both the deletion and the creation");
  }

  @Test
  void reprojectToTheSameNameJournalsNothing() {
    ContentFixtures.put(files, "old", "a.txt", "AAA");
    var before = new ChangeLog(db).maxSeq("file");

    files.reproject("old", "old");

    assertEquals(before, new ChangeLog(db).maxSeq("file"));
  }

  @Test
  void invalidPermissionBitsCannotWrapIntoValidPermissions() {
    var snapshot = new java.util.LinkedHashMap<>(ContentFixtures.snapshot(files, "data"));
    snapshot.put("mode", 0x1_0000_01a4L);
    assertThrows(
        IllegalArgumentException.class,
        () -> files.applyRevision(id("wrapped"), snapshot, "1-test"));
    assertTrue(files.find("acme", "wrapped").isEmpty());
  }

  @Test
  void aRevisionRecordedWithoutAModeIsKnownAtAnyMode() {
    ContentFixtures.put(files, "acme", "legacy.sh", "first");
    var hash = files.find("acme", "legacy.sh").orElseThrow().contentHash();
    var stripped =
        new LinkedHashMap<>(
            YamlUtil.parseMap(
                new ChangeLog(db).head("file", id("legacy.sh")).orElseThrow().snapshot()));
    stripped.remove("mode");
    db.execute(
        "UPDATE change_log SET snapshot = ? WHERE entity_type = 'file' AND entity_id = ?",
        YamlUtil.dumpJson(stripped),
        id("legacy.sh"));

    assertTrue(files.isKnownVersion(id("legacy.sh"), hash, 0664), "umask 002 on the old box");
    assertTrue(files.isKnownVersion(id("legacy.sh"), hash, 0755));
    assertFalse(files.isKnownVersion(id("legacy.sh"), files.blobs().putText("edited"), 0644));
  }

  @Test
  void onlyHistoryWrittenSinceModesAreJournaledRecordsAModeForContent() {
    var journaled = files.blobs().putText("journaled");
    files.put(new FileStore.FileRow("acme", "run.sh", journaled, 9, 0755, "text"));
    ContentFixtures.put(files, "acme", "legacy.sh", "legacy");
    var legacy = files.find("acme", "legacy.sh").orElseThrow().contentHash();
    db.execute(
        "UPDATE change_log SET snapshot = json_remove(snapshot, '$.mode')"
            + " WHERE entity_type = 'file' AND entity_id = ?",
        id("legacy.sh"));

    assertTrue(files.recordsModeFor(id("run.sh"), journaled));
    assertFalse(files.recordsModeFor(id("run.sh"), files.blobs().putText("other")));
    assertFalse(files.recordsModeFor(id("legacy.sh"), legacy));
    assertFalse(files.recordsModeFor(id("absent"), journaled));
  }

  @Test
  void knownVersionsMatchContentAndModeFromTheSameRevisionOfTheSameFile() {
    var first = files.blobs().putText("first");
    var second = files.blobs().putText("second");
    files.put(new FileStore.FileRow("acme", "known", first, 5, 0644, "text"));
    files.put(new FileStore.FileRow("acme", "known", second, 6, 0600, "text"));
    files.delete("acme", "known");

    assertTrue(files.isKnownVersion(id("known"), first, 0644));
    assertTrue(files.isKnownVersion(id("known"), second, 0600));
    assertFalse(files.isKnownVersion(id("known"), first, 0600));
    assertFalse(files.isKnownVersion(id("known"), second, 0644));
    assertFalse(files.isKnownVersion(id("other"), first, 0644));
    assertFalse(files.isKnownVersion(id("known"), files.blobs().putText("unknown"), 0644));
  }

  @Test
  void putAndFindAndList() {
    ContentFixtures.put(files, "acme", "a.txt", "AAA");
    ContentFixtures.put(files, "acme", "dir/b.txt", "BBB");

    assertEquals("AAA", ContentFixtures.text(files, "acme", "a.txt"));
    assertEquals(
        List.of("a.txt", "dir/b.txt"),
        files.list("acme").stream().map(FileStore.FileRow::path).toList());
  }

  @Test
  void deleteReturnsFalseWhenAbsentAndTrueWhenPresent() {
    assertFalse(files.delete("acme", "missing"));
    ContentFixtures.put(files, "acme", "a.txt", "AAA");
    assertTrue(files.delete("acme", "a.txt"));
    assertTrue(files.find("acme", "a.txt").isEmpty());
  }

  @Test
  void comparableSnapshotAndAtRev() {
    ContentFixtures.put(files, "acme", "a.txt", "AAA");
    var rev = files.latestRev(id("a.txt"));

    assertEquals(
        files.blobs().putText("AAA"), files.comparableSnapshot(id("a.txt")).get("content_hash"));
    assertEquals(
        files.blobs().putText("AAA"), files.comparableAtRev(id("a.txt"), rev).get("content_hash"));
    assertNull(files.comparableAtRev(id("a.txt"), null));
    assertNull(files.comparableSnapshot(id("missing")));
  }

  @Test
  void baseRevOfRecoversTheTombstoneBaseForADeletedFile() {
    files.applyRevision(id("a.txt"), ContentFixtures.snapshot(files, "AAA"), "1-base");
    assertEquals("1-base", files.baseRevOf(id("a.txt")));

    files.delete("acme", "a.txt");
    assertEquals("1-base", files.baseRevOf(id("a.txt")), "base recovered from the tombstone");
  }

  @Test
  void commitAcceptsWhenExpectedRevMatches() {
    files.applyRevision(id("a.txt"), ContentFixtures.snapshot(files, "AAA"), "1-base");

    var outcome =
        files.commitRevision(id("a.txt"), ContentFixtures.snapshot(files, "BBB"), "1-base");

    assertInstanceOf(PushOutcome.Accepted.class, outcome);
    assertEquals("BBB", ContentFixtures.text(files, "acme", "a.txt"));
  }

  @Test
  void commitRejectsAStalePushAndLeavesTheFileUntouched() {
    files.applyRevision(id("a.txt"), ContentFixtures.snapshot(files, "AAA"), "1-base");

    var outcome =
        files.commitRevision(id("a.txt"), ContentFixtures.snapshot(files, "BBB"), "9-stale");

    var stale = assertInstanceOf(PushOutcome.Stale.class, outcome);
    assertEquals("1-base", stale.currentRev());
    assertEquals(files.blobs().putText("AAA"), stale.currentSnapshot().get("content_hash"));
    assertEquals("AAA", ContentFixtures.text(files, "acme", "a.txt"));
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
    ContentFixtures.put(files, "acme", "a.txt", "AAA");
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
    ContentFixtures.put(files, "acme", "a.txt", "AAA");
    ContentFixtures.put(files, "acme", "dir/b.txt", "BBB");
    ContentFixtures.put(files, "globex", "c.txt", "CCC");
    files.delete("acme", "a.txt");

    assertEquals(
        List.of("acme/a.txt", "acme/dir/b.txt"),
        files.idsForProject("acme").stream().sorted().toList());
  }

  @Test
  void projectsWithFilesSpansEveryProjectTouched() {
    ContentFixtures.put(files, "acme", "a.txt", "AAA");
    ContentFixtures.put(files, "globex", "c.txt", "CCC");
    files.delete("globex", "c.txt");

    assertEquals(java.util.Set.of("acme", "globex"), files.projectsWithFiles());
  }

  @Test
  void isKnownVersionRecognizesAnyRevisionThisBoxWroteAtItsRecordedMode() {
    ContentFixtures.put(files, "acme", "a.txt", "v1");
    ContentFixtures.put(files, "acme", "a.txt", "v2");
    var recorded = files.find("acme", "a.txt").orElseThrow().mode();

    assertTrue(
        files.isKnownVersion(id("a.txt"), files.blobs().putText("v1"), recorded),
        "a superseded revision is still ours");
    assertTrue(files.isKnownVersion(id("a.txt"), files.blobs().putText("v2"), recorded));
    assertFalse(files.isKnownVersion(id("a.txt"), files.blobs().putText("v2"), recorded ^ 0111));
    assertFalse(
        files.isKnownVersion(id("a.txt"), files.blobs().putText("a local human edit"), recorded));
  }

  @Test
  void resolveTakeTheirsAdoptsMainAndCannotReRaise() {
    ContentFixtures.put(files, "acme", "a.txt", "mine");

    var rev =
        files.resolveConflict(
            id("a.txt"),
            ContentFixtures.snapshot(files, "theirs"),
            ContentFixtures.snapshot(files, "theirs"));

    assertEquals("theirs", ContentFixtures.text(files, "acme", "a.txt"));
    assertEquals(rev, files.baseRevOf(id("a.txt")), "base now equals theirs, so no re-raise");
  }

  @Test
  void resolveKeepMineRebasesOntoTheirsAndPushesMineForward() {
    ContentFixtures.put(files, "acme", "a.txt", "mine");

    files.resolveConflict(
        id("a.txt"),
        ContentFixtures.snapshot(files, "mine"),
        ContentFixtures.snapshot(files, "theirs"));

    assertEquals("mine", ContentFixtures.text(files, "acme", "a.txt"));
    assertTrue(
        files.isKnownVersion(
            id("a.txt"),
            files.blobs().putText("theirs"),
            files.find("acme", "a.txt").orElseThrow().mode()),
        "theirs is journaled as the base");
  }

  @Test
  void resolveTakeTheirsWhereTheirsIsADeleteRemovesTheRow() {
    ContentFixtures.put(files, "acme", "a.txt", "mine");

    files.resolveConflict(id("a.txt"), null, null);

    assertTrue(files.find("acme", "a.txt").isEmpty());
  }

  @Test
  void resolveKeepMineWhereTheirsIsADeleteRestoresMine() {
    files.applyRevision(id("a.txt"), ContentFixtures.snapshot(files, "base"), "1-base");
    ContentFixtures.put(files, "acme", "a.txt", "mine");

    files.resolveConflict(id("a.txt"), ContentFixtures.snapshot(files, "mine"), null);

    assertEquals("mine", ContentFixtures.text(files, "acme", "a.txt"));
  }

  @Test
  void resolveDeleteMineWhereTheirsEditsTombstonesTheRow() {
    files.applyRevision(id("a.txt"), ContentFixtures.snapshot(files, "base"), "1-base");

    files.resolveConflict(id("a.txt"), null, ContentFixtures.snapshot(files, "theirs"));

    assertTrue(files.find("acme", "a.txt").isEmpty());
  }

  @Test
  void concurrentCommitsAgainstTheSameBaseYieldOneWinnerAndOneCleanStale() throws Exception {
    try (var db2 = Sqlite.open(tempDir.resolve("test.db"))) {
      var files2 = new FileStore(db2);
      for (var i = 0; i < 64; i++) {
        var fid = id("race-" + i + ".txt");
        var base = i + "-base";
        files.applyRevision(fid, ContentFixtures.snapshot(files, "BASE"), base);

        var gate = new CyclicBarrier(2);
        var outcomes = new ConcurrentLinkedQueue<PushOutcome>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var a = racer(files, fid, "A", base, gate, outcomes, errors);
        var b = racer(files2, fid, "B", base, gate, outcomes, errors);
        a.start();
        b.start();
        a.join();
        b.join();

        var iteration = i;
        assertTrue(errors.isEmpty(), () -> "iteration " + iteration + " raced into " + errors);
        assertEquals(
            1,
            outcomes.stream().filter(o -> o instanceof PushOutcome.Accepted).count(),
            "exactly one writer wins the race");
        assertEquals(
            1,
            outcomes.stream().filter(o -> o instanceof PushOutcome.Stale).count(),
            "the loser is cleanly rejected as stale, never lost or errored");
      }
    }
  }

  private static Thread racer(
      FileStore store,
      String fid,
      String content,
      String base,
      CyclicBarrier gate,
      ConcurrentLinkedQueue<PushOutcome> outcomes,
      ConcurrentLinkedQueue<Throwable> errors) {
    return new Thread(
        Actor.carrying(
            () -> {
              try {
                gate.await();
                outcomes.add(
                    store.commitRevision(fid, ContentFixtures.snapshot(store, content), base));
              } catch (Throwable t) {
                errors.add(t);
              }
            }));
  }

  @Test
  void aFileRowMustBeOwnerReadable() {
    var hash = files.blobs().putText("x");

    assertThrows(
        IllegalArgumentException.class,
        () -> new FileStore.FileRow("acme", "dark", hash, 1, 0000, "text"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileStore.FileRow("acme", "dark", hash, 1, 0200, "text"));
    assertEquals(0400, new FileStore.FileRow("acme", "lit", hash, 1, 0400, "text").mode());
  }
}
