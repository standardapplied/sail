/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sync protocol in isolation, driven through a minimal {@code widget} entity so the journal's
 * behavior is asserted directly rather than only through a store: rev minting, adopting an
 * authoritative revision, the compare-and-set commit (accept on a matching expected rev, reject as
 * stale otherwise), conflict resolution (take-remote vs keep-local), and {@code base_rev} recovery
 * from a tombstone after a local delete.
 */
class RevisionJournalTest {

  @TempDir Path tempDir;

  private Sqlite db;
  private RevisionJournal journal;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("journal.db"));
    new SchemaManager(db).migrate();
    db.execute(
        "CREATE TABLE widgets (id TEXT PRIMARY KEY, value TEXT, updated_by TEXT, rev TEXT,"
            + " base_rev TEXT)");
    journal = new RevisionJournal(db, new ChangeLog(db), new WidgetSchema(db));
  }

  private void createWidget(String id, String value, String author) {
    db.transaction(
        () -> {
          db.execute(
              "INSERT INTO widgets (id, value, updated_by) VALUES (?, ?, ?)", id, value, author);
          journal.recordRevision(id, "local", false);
          return null;
        });
  }

  private static Map<String, Object> snapshot(String value, String actor) {
    var m = new LinkedHashMap<String, Object>();
    m.put("value", value);
    m.put(Snapshots.ACTOR, actor);
    return m;
  }

  @Test
  void recordRevisionMintsARevAndJournalsTheState() {
    createWidget("w1", "hello", "uday");

    assertEquals(journal.revOf("w1"), journal.latestRev("w1"));
    assertEquals("hello", journal.comparableSnapshot("w1").get("value"));
    assertEquals("uday", journal.comparableSnapshot("w1").get(Snapshots.ACTOR));
    assertTrue(journal.entityIds().contains("w1"));
  }

  @Test
  void applyRevisionAdoptsRemoteAtItsExactRevAndSetsTheBase() {
    journal.applyRevision("w2", snapshot("remote", "bob"), "5-abc");

    assertEquals("remote", journal.comparableSnapshot("w2").get("value"));
    assertEquals("bob", journal.comparableSnapshot("w2").get(Snapshots.ACTOR));
    assertEquals("5-abc", journal.latestRev("w2"));
    assertEquals("5-abc", journal.baseRevOf("w2"));
  }

  @Test
  void applyRevisionWithNullDeletesAndRecoversBaseFromTheTombstone() {
    journal.applyRevision("w3", snapshot("v", "bob"), "3-aaa");

    journal.applyRevision("w3", null, "4-bbb");

    assertFalse(new WidgetSchema(db).exists("w3"));
    assertEquals("4-bbb", journal.latestRev("w3"));
    assertEquals("3-aaa", journal.baseRevOf("w3"));
    assertNull(journal.comparableSnapshot("w3"));
    assertTrue(journal.entityIds().contains("w3"));
  }

  @Test
  void commitRevisionAcceptsWhenTheExpectedRevMatches() {
    createWidget("w4", "one", "uday");
    var current = journal.latestRev("w4");

    var outcome = journal.commitRevision("w4", snapshot("two", "uday"), current);

    var accepted = assertInstanceOf(PushOutcome.Accepted.class, outcome);
    assertNotEquals(current, accepted.rev());
    assertEquals("two", journal.comparableSnapshot("w4").get("value"));
  }

  @Test
  void commitRevisionRejectsAStaleExpectedRevWithoutOverwriting() {
    createWidget("w5", "kept", "uday");
    var current = journal.latestRev("w5");

    var outcome = journal.commitRevision("w5", snapshot("clobber", "bob"), "0-stale");

    var stale = assertInstanceOf(PushOutcome.Stale.class, outcome);
    assertEquals(current, stale.currentRev());
    assertEquals("kept", journal.comparableSnapshot("w5").get("value"));
  }

  @Test
  void commitRevisionForABrandNewEntityExpectsNull() {
    var outcome = journal.commitRevision("w6", snapshot("fresh", "uday"), null);

    assertInstanceOf(PushOutcome.Accepted.class, outcome);
    assertEquals("fresh", journal.comparableSnapshot("w6").get("value"));
  }

  @Test
  void resolveConflictTakingRemoteAdoptsTheBaseAndKeepsNoForwardEdit() {
    createWidget("w7", "mine", "uday");
    var remote = snapshot("theirs", "bob");

    var rev = journal.resolveConflict("w7", remote, remote);

    assertEquals("theirs", journal.comparableSnapshot("w7").get("value"));
    assertEquals(rev, journal.baseRevOf("w7"));
    assertEquals(rev, journal.latestRev("w7"));
  }

  @Test
  void resolveConflictKeepingLocalRebasesOntoRemoteThenWritesTheChosenState() {
    createWidget("w8", "original", "uday");
    var remote = snapshot("theirs", "bob");
    var chosen = snapshot("mine-wins", "uday");

    var rev = journal.resolveConflict("w8", chosen, remote);

    assertEquals("mine-wins", journal.comparableSnapshot("w8").get("value"));
    assertEquals(rev, journal.latestRev("w8"));
    assertNotEquals(rev, journal.baseRevOf("w8"));
  }

  @Test
  void resolveConflictWithBothSidesDeletedTombstonesTheRow() {
    createWidget("w9", "doomed", "uday");

    journal.resolveConflict("w9", null, null);

    assertFalse(new WidgetSchema(db).exists("w9"));
    assertNull(journal.comparableSnapshot("w9"));
  }

  @Test
  void comparableAtRevReadsTheHistoricalStateNotTheCurrentOne() {
    createWidget("w10", "first", "uday");
    var firstRev = journal.latestRev("w10");
    db.transaction(
        () -> {
          db.execute("UPDATE widgets SET value = ? WHERE id = ?", "second", "w10");
          journal.recordRevision("w10", "local", false);
          return null;
        });

    assertEquals("first", journal.comparableAtRev("w10", firstRev).get("value"));
    assertEquals("second", journal.comparableSnapshot("w10").get("value"));
    assertNull(journal.comparableAtRev("w10", null));
  }

  private static final class WidgetSchema implements EntitySchema {

    private final Sqlite db;

    WidgetSchema(Sqlite db) {
      this.db = db;
    }

    @Override
    public String entityType() {
      return "widget";
    }

    @Override
    public String table() {
      return "widgets";
    }

    @Override
    public boolean exists(String id) {
      return db.queryOne("SELECT 1 FROM widgets WHERE id = ?", row -> row.integer(0), id)
          .isPresent();
    }

    @Override
    public Map<String, Object> snapshotMap(String id) {
      return db.queryOne(
              "SELECT id, value, updated_by FROM widgets WHERE id = ?",
              row -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", row.text(0));
                m.put("value", row.text(1));
                m.put("updated_by", row.text(2));
                return m;
              },
              id)
          .orElse(null);
    }

    @Override
    public String author(String id) {
      return db.queryOne("SELECT updated_by FROM widgets WHERE id = ?", row -> row.text(0), id)
          .orElse(null);
    }

    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      var value = Snapshots.text(snapshot, "value");
      var author = Snapshots.actor(snapshot);
      if (exists(id)) {
        db.execute("UPDATE widgets SET value = ?, updated_by = ? WHERE id = ?", value, author, id);
      } else {
        db.execute(
            "INSERT INTO widgets (id, value, updated_by) VALUES (?, ?, ?)", id, value, author);
      }
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      var m = new LinkedHashMap<String, Object>();
      if (full.containsKey("value")) {
        m.put("value", full.get("value"));
      }
      var author = full.get("updated_by");
      if (author != null) {
        m.put(Snapshots.ACTOR, author);
      }
      return m;
    }

    @Override
    public void deleteRow(String id) {
      db.execute("DELETE FROM widgets WHERE id = ?", id);
    }
  }
}
