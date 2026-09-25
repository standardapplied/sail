/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class ChangeLogTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ChangeLog log;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    log = new ChangeLog(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void aLocalMutationRecordsNoPeer() {
    log.append("spec", "a", "1-x", "local", false, "{}");

    assertNull(log.history("spec", "a").getFirst().peer());
  }

  @Test
  void aRevisionAppendedUnderASyncPeerRecordsThatPeerAsItsProvenance() {
    Actor.run(
        Actor.sync("sumesh", Role.MEMBER),
        () -> log.append("spec", "a", "1-x", "uday", "sync", false, "{}"));

    var entry = log.history("spec", "a").getFirst();
    assertEquals("sumesh", entry.peer());
    assertEquals("sync", entry.origin());
    assertEquals(
        "uday", entry.actor(), "actor stays the offered author; peer names the box that pushed");
  }

  @Test
  void thePeerBindingIsScopedAndDoesNotLeakToLaterAppends() {
    Actor.run(
        Actor.sync("sumesh", Role.MEMBER),
        () -> log.append("spec", "a", "1-x", "sync", false, "{}"));
    log.append("spec", "b", "1-y", "local", false, "{}");

    assertEquals("sumesh", log.history("spec", "a").getFirst().peer());
    assertNull(log.history("spec", "b").getFirst().peer());
  }

  @Test
  void aLocalRevisionIsAuthoredByTheBoundActorWhateverItOffers() {
    log.append("spec", "a", "1-x", "someone-else", "local", false, "{}");

    assertEquals("uday", log.history("spec", "a").getFirst().actor());
  }

  @Test
  void thisBoxsMachineryAuthorsAsSail() {
    Actor.run(Actor.system(), () -> log.append("spec", "a", "1-x", "local", false, "{}"));

    var entry = log.history("spec", "a").getFirst();
    assertEquals("sail", entry.actor());
    assertNull(entry.peer());
  }

  @Test
  void aPushThatOffersNoAuthorIsAuthoredByThePushingFde() {
    Actor.run(
        Actor.sync("sumesh", Role.MEMBER),
        () -> log.append("spec", "a", "1-x", "sync", false, "{}"));

    assertEquals("sumesh", log.history("spec", "a").getFirst().actor());
  }

  @Test
  void adoptionRecordsTheAuthorMainRecordedWithMainAsThePeer() {
    Actor.run(Actor.main(), () -> log.append("spec", "a", "1-x", "sumesh", "sync", false, "{}"));
    Actor.run(Actor.main(), () -> log.append("spec", "b", "1-y", "sync", false, "{}"));

    var adopted = log.history("spec", "a").getFirst();
    assertEquals("sumesh", adopted.actor());
    assertEquals("main", adopted.peer());
    assertEquals("main", log.history("spec", "b").getFirst().actor());
  }

  @Test
  void anErasureIsRecordedByTheBoundActor() {
    log.append("spec", "a", "1-x", "local", false, "{}");
    Actor.run(Actor.sync("sumesh", Role.MEMBER), () -> log.erase("spec", "a", "2-x", "sync"));

    var erasure = log.erasure("spec", "a").orElseThrow();
    assertEquals("sumesh", erasure.actor());
    assertEquals("sumesh", erasure.peer());
  }

  @Test
  void historyIsOrderedBySequenceAndScopedToTheEntity() {
    log.append("spec", "a", "1-x", "local", false, "{}");
    log.append("spec", "b", "1-y", "local", false, "{}");
    log.append("spec", "a", "2-z", "local", false, "{}");

    var historyA = log.history("spec", "a");
    assertEquals(2, historyA.size());
    assertTrue(historyA.get(0).seq() < historyA.get(1).seq());
    assertEquals("1-x", historyA.get(0).rev());
    assertEquals("2-z", historyA.get(1).rev());
    assertEquals(1, log.history("spec", "b").size());
  }

  @Test
  void atFindsAnExactRevisionAndIsEmptyOtherwise() {
    log.append("spec", "a", "1-x", "local", false, "{\"k\":1}");

    var found = log.at("spec", "a", "1-x").orElseThrow();
    assertEquals("uday", found.actor());
    assertEquals("{\"k\":1}", found.snapshot());
    assertFalse(found.deleted());
    assertTrue(log.at("spec", "a", "9-z").isEmpty());
  }

  @Test
  void deletedFlagRoundTrips() {
    log.append("spec", "a", "3-x", "local", true, "{}");
    assertTrue(log.history("spec", "a").getFirst().deleted());
  }

  @Test
  void historyIsEmptyForAnUnknownEntity() {
    assertTrue(log.history("spec", "ghost").isEmpty());
  }

  @Test
  void anAppendMovesTheEntitysHeadAndTheTypesHighWater() {
    log.append("spec", "a", "1-x", "local", false, "{}");
    log.append("spec", "b", "1-y", "local", false, "{}");
    log.append("spec", "a", "2-z", "local", false, "{}");

    assertEquals("2-z", log.head("spec", "a").orElseThrow().rev());
    assertEquals("1-y", log.head("spec", "b").orElseThrow().rev());
    assertEquals(3L, log.maxSeq("spec"));
    assertEquals(0L, log.maxSeq("file"));
    assertTrue(log.head("spec", "missing").isEmpty());
  }

  @Test
  void headsAfterPagesEachEntityOnceAtItsLatestChangeInSeqOrder() {
    log.append("spec", "a", "1-x", "local", false, "{}");
    log.append("spec", "b", "1-y", "local", false, "{}");
    log.append("spec", "a", "2-z", "local", false, "{}");
    log.append("file", "f", "1-f", "local", false, "{}");

    var all = log.headsAfter("spec", 0, 10);
    assertEquals(List.of("b", "a"), all.stream().map(ChangeLog.Head::entityId).toList());
    assertEquals(List.of(2L, 3L), all.stream().map(ChangeLog.Head::seq).toList());
    assertEquals(
        List.of("b"), log.headsAfter("spec", 0, 1).stream().map(ChangeLog.Head::entityId).toList());
    assertEquals(
        List.of("a"),
        log.headsAfter("spec", 2, 10).stream().map(ChangeLog.Head::entityId).toList());
    assertTrue(log.headsAfter("spec", 3, 10).isEmpty());
  }

  @Test
  void localTombstonesNameOnlyDeletionsThisBoxDecided() {
    log.append("spec", "mine", "1-x", "local", false, "{}");
    log.append("spec", "mine", "2-x", "local", true, "{}");
    log.append("spec", "theirs", "1-y", "sync", true, "{}");
    log.append("spec", "revived", "1-z", "local", true, "{}");
    log.append("spec", "revived", "2-z", "local", false, "{}");

    assertEquals(Set.of("mine"), log.localTombstones("spec"));
  }

  @Test
  void aSeedPageRunsTheIncrementalPagesPlanAndNeverScansTheLog() {
    db.transaction(
        () -> {
          for (var i = 0; i < 200_000; i++) {
            db.execute(
                "INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, deleted,"
                    + " snapshot) VALUES ('spec', ?, ?, 't', 'local', 0, '{}')",
                "spec-" + (i % 5_000),
                (i / 5_000 + 1) + "-r");
          }
          db.execute(
              "INSERT INTO change_heads (entity_type, entity_id, seq) SELECT entity_type,"
                  + " entity_id, MAX(seq) FROM change_log GROUP BY entity_type, entity_id");
        });
    var seed = plan(0);
    var incremental = plan(199_990);

    assertEquals(seed, incremental);
    assertTrue(seed.contains("idx_change_heads_seq"), seed);
    assertFalse(seed.toLowerCase().contains("scan change_log"), seed);
    assertEquals(2_000, log.headsAfter("spec", 0, 2_000).size());
    assertEquals(200_000L, log.maxSeq("spec"));
  }

  private String plan(long since) {
    return String.join(
        "\n",
        db.query(
            "EXPLAIN QUERY PLAN SELECT seq, entity_id FROM change_heads WHERE entity_type = ? AND"
                + " seq > ? ORDER BY seq LIMIT ?",
            row -> row.text(3),
            "spec",
            since,
            2_000));
  }
}
