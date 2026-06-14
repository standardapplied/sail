/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ReviewStore store;
  private SpecStore specStore;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new ReviewStore(db);
    specStore = new SpecStore(db);
    specStore.create(
        new SpecStore.SpecRow(
            "auth",
            "test-project",
            "OAuth flow",
            SpecStatus.IN_PROGRESS,
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

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void createAndFindReview() {
    var id = store.createReview("auth", 1);
    var review = store.findReview(id);

    assertTrue(review.isPresent());
    assertEquals("auth", review.get().specId());
    assertEquals(1, review.get().iteration());
    assertEquals("pending", review.get().status());
  }

  @Test
  void latestReviewForSpec() {
    store.createReview("auth", 1);
    store.createReview("auth", 2);

    var latest = store.latestReviewForSpec("auth");
    assertTrue(latest.isPresent());
    assertEquals(2, latest.get().iteration());
  }

  @Test
  void reviewsForSpecReturnsInOrder() {
    store.createReview("auth", 1);
    store.createReview("auth", 2);
    store.createReview("auth", 3);

    var reviews = store.reviewsForSpec("auth");
    assertEquals(3, reviews.size());
    assertEquals(1, reviews.get(0).iteration());
    assertEquals(3, reviews.get(2).iteration());
  }

  @Test
  void updateReviewStatusSetsCompletedAtForTerminalStates() {
    var id = store.createReview("auth", 1);
    store.updateReviewStatus(id, "passed");

    var review = store.findReview(id).orElseThrow();
    assertEquals("passed", review.status());
    assertNotNull(review.completedAt());
  }

  @Test
  void updateReviewStatusRunningDoesNotSetCompletedAt() {
    var id = store.createReview("auth", 1);
    store.updateReviewStatus(id, "running");

    var review = store.findReview(id).orElseThrow();
    assertEquals("running", review.status());
    assertNull(review.completedAt());
  }

  @Test
  void createAndFindStage() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");

    var stage = store.findStage(stageId);
    assertTrue(stage.isPresent());
    assertEquals("security", stage.get().name());
    assertEquals("agent", stage.get().stageType());
    assertEquals("pending", stage.get().status());
  }

  @Test
  void stagesForReviewReturnsInInsertionOrder() {
    var reviewId = store.createReview("auth", 1);
    store.createStage(reviewId, "security", "agent");
    store.createStage(reviewId, "correctness", "agent");
    store.createStage(reviewId, "human", "human");

    var stages = store.stagesForReview(reviewId);
    assertEquals(3, stages.size());
    assertEquals("security", stages.get(0).name());
    assertEquals("correctness", stages.get(1).name());
    assertEquals("human", stages.get(2).name());
  }

  @Test
  void startAndCompleteStage() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");

    store.startStage(stageId, "codex");
    var running = store.findStage(stageId).orElseThrow();
    assertEquals("running", running.status());
    assertEquals("codex", running.reviewer());
    assertNotNull(running.startedAt());

    store.completeStage(stageId, "passed");
    var completed = store.findStage(stageId).orElseThrow();
    assertEquals("passed", completed.status());
    assertNotNull(completed.completedAt());
  }

  @Test
  void addAndQueryFindings() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");

    var finding =
        Finding.create(
            Finding.Severity.CRITICAL,
            Finding.Category.SECURITY,
            "src/main/Auth.java",
            42,
            42,
            "SQL injection in query builder",
            "User input directly concatenated into SQL query string.",
            "Input flows from request.getParam() to db.execute() without sanitization.",
            new Finding.Suggestion(
                "db.execute(\"SELECT * FROM users WHERE id = \" + userId)",
                "db.execute(\"SELECT * FROM users WHERE id = ?\", userId)",
                "Use parameterized queries to prevent SQL injection."),
            0.95);

    store.addFinding(stageId, finding);

    var findings = store.findingsForStage(stageId);
    assertEquals(1, findings.size());

    var stored = findings.getFirst();
    assertEquals(Finding.Severity.CRITICAL, stored.severity());
    assertEquals(Finding.Category.SECURITY, stored.category());
    assertEquals("src/main/Auth.java", stored.file());
    assertEquals(42, stored.lineStart());
    assertEquals("SQL injection in query builder", stored.title());
    assertEquals(
        "Use parameterized queries to prevent SQL injection.", stored.suggestion().rationale());
    assertEquals(0.95, stored.confidence(), 0.001);
    assertEquals(Finding.Resolution.OPEN, stored.resolution());
  }

  @Test
  void findingsOrderedBySeverity() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");

    store.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.LOW,
            Finding.Category.SECURITY,
            "a.java",
            1,
            1,
            "Low issue",
            "",
            "",
            null,
            0.5));
    store.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.CRITICAL,
            Finding.Category.SECURITY,
            "b.java",
            1,
            1,
            "Critical issue",
            "",
            "",
            null,
            0.9));
    store.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "c.java",
            1,
            1,
            "High issue",
            "",
            "",
            null,
            0.8));

    var findings = store.findingsForStage(stageId);
    assertEquals(Finding.Severity.CRITICAL, findings.get(0).severity());
    assertEquals(Finding.Severity.HIGH, findings.get(1).severity());
    assertEquals(Finding.Severity.LOW, findings.get(2).severity());
  }

  @Test
  void findingsForReviewSpansStages() {
    var reviewId = store.createReview("auth", 1);
    var stage1 = store.createStage(reviewId, "security", "agent");
    var stage2 = store.createStage(reviewId, "correctness", "agent");

    store.addFinding(
        stage1,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "a.java",
            1,
            1,
            "Security issue",
            "",
            "",
            null,
            0.8));
    store.addFinding(
        stage2,
        Finding.create(
            Finding.Severity.MEDIUM,
            Finding.Category.LOGIC,
            "b.java",
            1,
            1,
            "Logic issue",
            "",
            "",
            null,
            0.7));

    var allFindings = store.findingsForReview(reviewId);
    assertEquals(2, allFindings.size());
  }

  @Test
  void resolveFinding() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    var finding =
        Finding.create(
            Finding.Severity.MEDIUM,
            Finding.Category.LOGIC,
            "a.java",
            1,
            1,
            "Issue",
            "",
            "",
            null,
            0.5);
    store.addFinding(stageId, finding);

    store.resolveFinding(finding.id(), Finding.Resolution.FIXED);

    var resolved = store.findingsForStage(stageId).getFirst();
    assertEquals(Finding.Resolution.FIXED, resolved.resolution());
  }

  @Test
  void openFindingsExcludesResolved() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");

    var openFinding =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "a.java",
            1,
            1,
            "Open",
            "",
            "",
            null,
            0.8);
    var fixedFinding =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "b.java",
            1,
            1,
            "Fixed",
            "",
            "",
            null,
            0.8);

    store.addFinding(stageId, openFinding);
    store.addFinding(stageId, fixedFinding);
    store.resolveFinding(fixedFinding.id(), Finding.Resolution.FIXED);

    var open = store.openFindingsForReview(reviewId);
    assertEquals(1, open.size());
    assertEquals("Open", open.getFirst().title());
  }

  @Test
  void deleteReviewCascadesToStagesAndFindings() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    store.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.LOW,
            Finding.Category.LOGIC,
            "a.java",
            1,
            1,
            "Issue",
            "",
            "",
            null,
            0.5));

    db.execute("DELETE FROM reviews WHERE id = ?", reviewId);

    assertTrue(store.stagesForReview(reviewId).isEmpty());
    assertTrue(store.findingsForStage(stageId).isEmpty());
  }
}
