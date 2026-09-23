/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * History is bounded by one local rule: the newest {@link ChangeLog#HISTORY_REVISIONS} entries of
 * an entity, its synced base, and every tombstone and erasure, compacted only outside a transaction
 * under the exclusive retention lease.
 */
class CompactionTest {

  private Sqlite db;
  private SpecStore specs;
  private ChangeLog changes;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    changes = new ChangeLog(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void theNewestRevisionsOfEachEntityAreKeptAndTheRestDropped() {
    edits("busy", 30);
    edits("quiet", 5);

    var collected = new BlobStore(db).gc(BlobStore.Compaction.ALL, false);

    assertEquals(11, collected.compacted(), "31 entries, 20 kept");
    assertEquals(ChangeLog.HISTORY_REVISIONS, changes.history("spec", "busy").size());
    assertEquals(6, changes.history("spec", "quiet").size(), "a short history is untouched");
    assertEquals(
        "busy 30",
        specs.findById("busy").orElseThrow().title(),
        "the live row and its head are never compacted");
    assertEquals(
        changes.head("spec", "busy").orElseThrow().seq(),
        changes.history("spec", "busy").getLast().seq());
  }

  @Test
  void theSyncedBaseTombstonesAndErasuresSurviveCompaction() {
    specs.create(spec("based"));
    var base = specs.latestRev("based");
    db.execute("UPDATE specs SET base_rev = ? WHERE id = 'based'", base);
    for (var i = 1; i <= 30; i++) {
      specs.update(titled("based", "edit " + i));
    }
    edits("gone", 10);
    specs.delete("gone");
    edits("gone", 25);
    var erasure = new Erasure(db);
    edits("erased", 3);
    erasure.erase(List.of(new Erasure.Target("spec", "erased")), "uday", "local");

    new BlobStore(db).gc(BlobStore.Compaction.ALL, false);

    assertTrue(
        changes.at("spec", "based", base).isPresent(), "the merge base a node reconciles against");
    assertEquals(
        ChangeLog.HISTORY_REVISIONS + 1,
        changes.history("spec", "based").size(),
        "the newest twenty and the base");
    assertEquals(
        1,
        changes.history("spec", "gone").stream()
            .filter(entry -> entry.kind() == ChangeLog.Kind.TOMBSTONE)
            .count(),
        "a tombstone is kept however old");
    assertEquals(
        List.of(ChangeLog.Kind.ERASURE),
        changes.history("spec", "erased").stream().map(ChangeLog.Entry::kind).toList());
  }

  @Test
  void anOpenConflictsBaseSurvivesCompaction() {
    specs.create(spec("parked"));
    var base = specs.latestRev("parked");
    db.execute("UPDATE specs SET base_rev = ? WHERE id = 'parked'", base);
    new SyncConflicts(db).record("spec", "parked", "{}", "{}", "{}", List.of("title"));
    for (var i = 1; i <= 25; i++) {
      specs.update(titled("parked", "mine " + i));
    }

    new BlobStore(db).gc(BlobStore.Compaction.of("spec", List.of("parked")), false);

    assertTrue(changes.at("spec", "parked", base).isPresent());
  }

  @Test
  void compactionIsRefusedInsideATransactionScope() {
    edits("busy", 30);

    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> db.transaction(() -> new BlobStore(db).gc(BlobStore.Compaction.ALL, false)));

    assertTrue(refused.getMessage().contains("before opening a transaction"));
    assertEquals(31, changes.history("spec", "busy").size());
  }

  @Test
  void nothingToCompactAndNothingAskedCollectsNothing() {
    edits("quiet", 3);
    var orphan = new BlobStore(db).putText("unreferenced");

    var collected = new BlobStore(db).gc(BlobStore.Compaction.of("spec", List.of("quiet")), false);

    assertEquals(BlobStore.Collected.NONE, collected);
    assertTrue(new BlobStore(db).has(orphan), "no collection ran, so nothing was freed");
  }

  @Test
  void compactingARevisionIsWhatLetsItsContentGo() {
    specs.create(spec("doc"));
    specs.setContent("doc", "the first body, soon history", "");
    var first =
        db.queryOne("SELECT body_hash FROM specs WHERE id = 'doc'", r -> r.text(0)).orElseThrow();
    for (var i = 1; i <= 25; i++) {
      specs.setContent("doc", "body " + i, "");
    }
    assertEquals(0, new BlobStore(db).gc(BlobStore.Compaction.NONE, true).freed());
    assertTrue(new BlobStore(db).has(first), "history still names the first body");

    var collected = new BlobStore(db).gc(BlobStore.Compaction.of("spec", List.of("doc")), false);

    assertTrue(collected.compacted() > 0);
    assertTrue(collected.freed() > 0, "compacting anything collects");
    assertTrue(!new BlobStore(db).has(first), "the first body went with its revision");
  }

  @Test
  void aNodeWhoseCheckpointPredatesMainsCompactionStillConverges(@TempDir Path dir) {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      main.specs.create(spec("busy"));
      SyncBox.round(main.db, node.db, "spec");
      for (var i = 1; i <= 30; i++) {
        main.specs.update(titled("busy", "main " + i));
      }
      assertTrue(new BlobStore(main.db).gc(BlobStore.Compaction.ALL, false).compacted() > 0);

      var report = SyncBox.round(main.db, node.db, "spec");

      assertEquals(1, report.pulled());
      assertEquals(0, report.conflicts());
      assertEquals("main 30", node.specs.findById("busy").orElseThrow().title());
      assertEquals(main.specs.latestRev("busy"), node.specs.latestRev("busy"));
    }
  }

  @Test
  void aNodeCompactsWhatItsRoundTouchedKeepingTheBaseItSyncedFrom(@TempDir Path dir) {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      main.specs.create(spec("busy"));
      SyncBox.round(main.db, node.db, "spec");
      var base = node.specs.baseRevOf("busy");
      for (var i = 1; i <= 30; i++) {
        node.specs.update(titled("busy", "node " + i));
      }
      assertEquals(31, new ChangeLog(node.db).history("spec", "busy").size());

      SyncBox.round(main.db, node.db, "spec");

      var history = new ChangeLog(node.db).history("spec", "busy");
      assertTrue(history.size() <= ChangeLog.HISTORY_REVISIONS + 1, history.size() + " entries");
      assertEquals("node 30", main.specs.findById("busy").orElseThrow().title());
      assertEquals(
          node.specs.latestRev("busy"), node.specs.baseRevOf("busy"), "the push was adopted");
      assertTrue(base != null);
    }
  }

  private void edits(String id, int count) {
    if (specs.findById(id).isEmpty()) {
      specs.create(spec(id));
    }
    for (var i = 1; i <= count; i++) {
      specs.update(titled(id, id + " " + i));
    }
  }

  private static SpecStore.SpecRow spec(String id) {
    return titled(id, id);
  }

  private static SpecStore.SpecRow titled(String id, String title) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.PENDING,
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
}
