/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A box with finding rows from before findings were a review's content folds them into one revision
 * per review, once, resumably, and every reader returns what it returned before.
 */
@ActingAs
class ReviewFindingsMigrationTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ReviewStore store;
  private ChangeLog log;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("legacy.db"));
    new SchemaManager(db).migrate();
    store = new ReviewStore(db);
    log = new ChangeLog(db);
    var specs = new SpecStore(db);
    for (var id : List.of("auth", "auth-followup")) {
      specs.create(
          new SpecStore.SpecRow(
              id,
              "acme",
              id,
              SpecStatus.PENDING,
              null,
              null,
              null,
              null,
              null,
              0,
              null,
              "",
              "",
              null,
              List.of(),
              List.of()));
    }
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  /** A review journaled before findings were content, with box-local finding rows. */
  private String legacyReview(String id, int findings, String followup) {
    db.execute(
        "INSERT INTO reviews (id, spec_id, iteration, status, created_at) VALUES (?, 'auth', 1,"
            + " 'failed', 't0')",
        id);
    db.execute(
        "INSERT INTO review_stages (id, review_id, name, stage_type, status) VALUES (?, ?,"
            + " 'security', 'agent', 'failed')",
        id + "-stage",
        id);
    for (var i = 0; i < findings; i++) {
      db.execute(
          """
          INSERT INTO review_findings (id, stage_id, severity, category, file, line_start,
              line_end, title, description, evidence, suggestion_before, suggestion_after,
              suggestion_rationale, confidence, resolution, resolution_evidence, carried_from,
              carry_evidence, followup)
          VALUES (?, ?, 'HIGH', 'SECURITY', 'A.java', 1, 2, ?, 'd', 'e', 'a', 'b', 'c', 0.9,
              'OPEN', NULL, NULL, NULL, ?)""",
          id + "-f" + i,
          id + "-stage",
          "issue " + i,
          followup);
    }
    db.transaction(() -> store.recordRevision(id, "local"));
    return id;
  }

  private List<DataMigrator.Run> migrate() {
    return new DataMigrator(db, List.of(new ReviewFindingsMigration()))
        .run(
            ProjectRegistry.loadFromDisk(tempDir.resolve("no-projects")),
            DataMigration.Prompter.NON_INTERACTIVE);
  }

  private String findingsHash(String reviewId) {
    return db.queryOne(
            "SELECT COALESCE(findings_hash, '') FROM reviews WHERE id = ?",
            row -> row.text(0),
            reviewId)
        .filter(hash -> !hash.isBlank())
        .orElse(null);
  }

  @Test
  void foldsEachLegacyReviewOnceAsTheSystemAndEveryReaderReturnsWhatItReturnedBefore() {
    var withFindings = legacyReview("r1", 2, "auth-followup");
    var withoutFindings = legacyReview("r2", 0, null);
    var contentAlready = store.createReview("auth", 2);
    store.addFinding(store.createStage(contentAlready, "security", "agent"), finding());
    var before =
        Map.of(
            "findings", store.findingsForReview(withFindings),
            "open", store.openFindingsForReview(withFindings),
            "counts", store.findingCountsForStage(withFindings + "-stage"),
            "links", store.sourceFindingIds("auth-followup"),
            "chain", store.findingChain(withFindings + "-f1"));
    var heads =
        Map.of(
            withoutFindings, log.head("review", withoutFindings).orElseThrow().seq(),
            contentAlready, log.head("review", contentAlready).orElseThrow().seq());

    var first = migrate();
    var folded = log.head("review", withFindings).orElseThrow();
    var second = migrate();

    assertEquals(1, first.getFirst().report().applied());
    assertTrue(second.getFirst().alreadyApplied());
    assertEquals(
        before,
        Map.of(
            "findings", store.findingsForReview(withFindings),
            "open", store.openFindingsForReview(withFindings),
            "counts", store.findingCountsForStage(withFindings + "-stage"),
            "links", store.sourceFindingIds("auth-followup"),
            "chain", store.findingChain(withFindings + "-f1")));
    assertEquals(2, log.history("review", withFindings).size(), "one revision, folded once");
    assertEquals(Actor.system().handle(), folded.actor());
    assertEquals("migration", folded.origin());
    assertTrue(folded.snapshot().contains(findingsHash(withFindings)));
    assertTrue(new BlobStore(db).text(findingsHash(withFindings)).contains("auth-followup"));
    assertEquals(folded.seq(), log.head("review", withFindings).orElseThrow().seq());
    assertEquals(
        heads,
        Map.of(
            withoutFindings, log.head("review", withoutFindings).orElseThrow().seq(),
            contentAlready, log.head("review", contentAlready).orElseThrow().seq()),
        "a review without findings, or already with content, is left alone");
    assertNull(findingsHash(withoutFindings));
    assertEquals(
        store.comparableSnapshot(withFindings).get("findings_hash"), findingsHash(withFindings));
  }

  @Test
  void resumesWhereAnInterruptedRunStopped() {
    var done = legacyReview("r1", 1, null);
    var pending = legacyReview("r2", 1, null);
    db.transaction(() -> store.foldLegacyFindings(done));
    var doneHead = log.head("review", done).orElseThrow().seq();

    var run = migrate();

    assertEquals(1, run.getFirst().report().applied(), "only the review not yet folded");
    assertEquals(doneHead, log.head("review", done).orElseThrow().seq());
    assertNotNull(findingsHash(pending));
    assertEquals(2, log.history("review", pending).size());
  }

  @Test
  void aReviewOfAPrunedSpecIsLeftForItsErasureAndTheRestStillFold() {
    var orphan = legacyReview("r-orphan", 1, null);
    var folds = legacyReview("r1", 1, null);
    db.execute("UPDATE reviews SET spec_id = 'gone' WHERE id = ?", orphan);
    log.erase("spec", "gone", "erased", "local");

    var run = migrate().getFirst();

    assertEquals(1, run.report().applied());
    assertEquals(1, run.report().skipped());
    assertTrue(run.report().notes().getLast().contains(orphan), run.report().notes().toString());
    assertNotNull(findingsHash(folds));
    assertNull(findingsHash(orphan));
    assertEquals(
        1, store.findingsForReview(orphan).size(), "the orphan's rows are left as they are");
    assertTrue(migrate().getFirst().alreadyApplied(), "the migration is recorded as applied");
    assertFalse(db.transaction(() -> store.foldLegacyFindings(folds)), "already content");
    assertFalse(db.transaction(() -> store.foldLegacyFindings("no-such-review")));
  }

  private static Finding finding() {
    return Finding.create(
        Finding.Severity.HIGH,
        Finding.Category.SECURITY,
        "A.java",
        1,
        2,
        "issue",
        "desc",
        "evidence",
        new Finding.Suggestion("a", "b", "c"),
        0.9);
  }
}
