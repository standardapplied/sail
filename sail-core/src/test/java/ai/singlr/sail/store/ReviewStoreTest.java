/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  private void createSpec(String id) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "test-project",
            "Follow-up",
            SpecStatus.DRAFT,
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

  private Finding addOpenFinding(String stageId, Finding.Severity severity, String title) {
    var finding =
        Finding.create(
            severity,
            Finding.Category.SECURITY,
            "src/Auth.java",
            10,
            12,
            title,
            "Description",
            "Evidence",
            new Finding.Suggestion("bad", "good", "why"),
            0.8);
    store.addFinding(stageId, finding);
    return finding;
  }

  @Test
  void theAggregateSnapshotRoundTripsOntoAFreshBoxAsCountsWithoutFindingRows() {
    var reviewId = store.createReview("auth", 2);
    var stageId = store.createStage(reviewId, "security", "agent");
    store.startStage(stageId, "codex");
    addOpenFinding(stageId, Finding.Severity.HIGH, "One");
    addOpenFinding(stageId, Finding.Severity.HIGH, "Two");
    addOpenFinding(stageId, Finding.Severity.MEDIUM, "Three");
    store.completeStage(stageId, "failed");
    store.updateReviewStatus(reviewId, "failed");

    var snapshot = store.comparableSnapshot(reviewId);
    var rev = store.latestRev(reviewId);

    var mainDb = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(mainDb).migrate();
    var main = new ReviewStore(mainDb);
    main.applyRevision(reviewId, snapshot, rev);

    var review = main.findReview(reviewId).orElseThrow();
    assertEquals("failed", review.status());
    assertEquals(2, review.iteration());
    var stages = main.stagesForReview(reviewId);
    assertEquals(1, stages.size());
    assertEquals("failed", stages.getFirst().status());
    assertEquals("codex", stages.getFirst().reviewer());
    var counts = main.findingCountsForStage(stages.getFirst().id());
    assertEquals(2, counts.get("HIGH"));
    assertEquals(1, counts.get("MEDIUM"));
    assertTrue(
        main.findingsForStage(stages.getFirst().id()).isEmpty(),
        "finding rows stay on the executing node; only counts replicate");
    assertEquals(rev, main.latestRev(reviewId));
    mainDb.close();
  }

  @Test
  void linkSourceFindingsRecordsAndReturnsIds() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    var finding = addOpenFinding(stageId, Finding.Severity.HIGH, "Issue");
    createSpec("auth-followup");

    store.linkSourceFindings("auth-followup", List.of(finding.id()));
    store.linkSourceFindings("auth-followup", List.of(finding.id()));

    assertEquals(List.of(finding.id()), store.sourceFindingIds("auth-followup"));
  }

  @Test
  void resolveSourceFindingsMarksOnlyLinkedOpenFindingsFixed() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    var linked = addOpenFinding(stageId, Finding.Severity.HIGH, "Linked");
    var dismissed = addOpenFinding(stageId, Finding.Severity.LOW, "Dismissed");
    var unlinked = addOpenFinding(stageId, Finding.Severity.MEDIUM, "Unlinked");
    store.resolveFinding(dismissed.id(), Finding.Resolution.DISMISSED);
    createSpec("auth-followup");
    store.linkSourceFindings("auth-followup", List.of(linked.id(), dismissed.id()));

    assertEquals(1, store.resolveSourceFindings("auth-followup"));

    var byId = store.findingsForReview(reviewId);
    assertEquals(
        Finding.Resolution.FIXED,
        byId.stream()
            .filter(f -> f.id().equals(linked.id()))
            .findFirst()
            .orElseThrow()
            .resolution());
    assertEquals(
        Finding.Resolution.DISMISSED,
        byId.stream()
            .filter(f -> f.id().equals(dismissed.id()))
            .findFirst()
            .orElseThrow()
            .resolution());
    assertEquals(
        Finding.Resolution.OPEN,
        byId.stream()
            .filter(f -> f.id().equals(unlinked.id()))
            .findFirst()
            .orElseThrow()
            .resolution());
  }

  @Test
  void openFindingsAfterPassReturnsOpenFindingsOfLatestPassedReview() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    var open = addOpenFinding(stageId, Finding.Severity.HIGH, "Open");
    var fixed = addOpenFinding(stageId, Finding.Severity.LOW, "Fixed");
    store.resolveFinding(fixed.id(), Finding.Resolution.FIXED);
    store.updateReviewStatus(reviewId, "passed");

    var findings = store.openFindingsAfterPass("auth");
    assertEquals(1, findings.size());
    assertEquals(open.id(), findings.getFirst().id());
  }

  @Test
  void openFindingsAfterPassEmptyWhenLatestReviewNotPassed() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    addOpenFinding(stageId, Finding.Severity.HIGH, "Open");
    store.updateReviewStatus(reviewId, "failed");

    assertTrue(store.openFindingsAfterPass("auth").isEmpty());
  }

  @Test
  void openFindingsAfterPassIgnoresSupersededReviews() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    addOpenFinding(stageId, Finding.Severity.HIGH, "Open");
    store.updateReviewStatus(reviewId, "passed");
    store.supersedeForSpec("auth");

    assertTrue(store.openFindingsAfterPass("auth").isEmpty());
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

  @Test
  void carryForwardCreatesALinkedRowAndLeavesThePredecessorOpen() {
    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var original = addOpenFinding(stage1, Finding.Severity.HIGH, "Stubborn high");
    var r2 = store.createReview("auth", 2);
    var stage2 = store.createStage(r2, "security", "agent");

    var carried = store.carryForward(stage2, original, "still repros via the seed window");

    assertEquals(original.id(), carried.carriedFrom());
    assertTrue(!carried.id().equals(original.id()), "a carried row is a fresh identity");
    assertEquals(
        Finding.Resolution.OPEN, store.findFinding(original.id()).orElseThrow().resolution());
    var stored = store.findingsForStage(stage2).getFirst();
    assertEquals("Stubborn high", stored.title());
    assertEquals(
        "still repros via the seed window",
        stored.carryEvidence(),
        "the ruling's evidence persists on the carried row");
    assertNull(
        store.findFinding(original.id()).orElseThrow().carryEvidence(),
        "the predecessor is history and is never rewritten");
  }

  @Test
  void eachReCarryStoresTheLatestRulingsEvidenceAndTheChainKeepsTheOlderOnes() {
    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var original = addOpenFinding(stage1, Finding.Severity.HIGH, "Stubborn high");
    var r2 = store.createReview("auth", 2);
    var stage2 = store.createStage(r2, "security", "agent");
    var second = store.carryForward(stage2, original, "first scenario");
    var r3 = store.createReview("auth", 3);
    var stage3 = store.createStage(r3, "security", "agent");

    var third = store.carryForward(stage3, second, "sharper second scenario");

    assertEquals(
        "sharper second scenario",
        store.findFinding(third.id()).orElseThrow().carryEvidence(),
        "the newest ruling's explanation is the actionable one");
    assertEquals(
        "first scenario",
        store.findFinding(second.id()).orElseThrow().carryEvidence(),
        "the chain walk preserves every prior ruling's evidence");
  }

  @Test
  void findingChainWalksTheWholeLineageNewestFirst() {
    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var original = addOpenFinding(stage1, Finding.Severity.HIGH, "Aging high");
    var r2 = store.createReview("auth", 2);
    var stage2 = store.createStage(r2, "security", "agent");
    var second = store.carryForward(stage2, original, null);
    var r3 = store.createReview("auth", 3);
    var stage3 = store.createStage(r3, "security", "agent");
    var third = store.carryForward(stage3, second, null);

    var chain = store.findingChain(third.id());

    assertEquals(
        List.of(third.id(), second.id(), original.id()),
        chain.stream().map(Finding::id).toList(),
        "one finding aging across iterations is one chain walk");
    assertEquals(2, store.findingAge(third.id()));
    assertEquals(1, store.findingAge(second.id()));
    assertEquals(0, store.findingAge(original.id()));
  }

  @Test
  void carryForwardFindingsReturnsThePreviousFailedReviewsOpenFindings() {
    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var open = addOpenFinding(stage1, Finding.Severity.HIGH, "Open high");
    var fixed = addOpenFinding(stage1, Finding.Severity.MEDIUM, "Already fixed");
    store.resolveFinding(fixed.id(), Finding.Resolution.FIXED, "commit abc");
    store.completeStage(stage1, "failed");
    store.updateReviewStatus(r1, "failed");
    var r2 = store.createReview("auth", 2);
    store.createStage(r2, "security", "agent");

    var carryable = store.carryForwardFindings("auth", r2, "security");

    assertEquals(List.of(open.id()), carryable.stream().map(Finding::id).toList());
  }

  @Test
  void carryForwardIsScopedToTheStageThatEmittedTheFinding() {
    var r1 = store.createReview("auth", 1);
    var security = store.createStage(r1, "security", "agent");
    var correctness = store.createStage(r1, "correctness", "agent");
    var securityFinding = addOpenFinding(security, Finding.Severity.HIGH, "Token leak");
    var correctnessFinding = addOpenFinding(correctness, Finding.Severity.HIGH, "Off by one");
    store.completeStage(security, "failed");
    store.completeStage(correctness, "failed");
    store.updateReviewStatus(r1, "failed");
    var r2 = store.createReview("auth", 2);
    store.createStage(r2, "security", "agent");
    store.createStage(r2, "correctness", "agent");

    assertEquals(
        List.of(securityFinding.id()),
        store.carryForwardFindings("auth", r2, "security").stream().map(Finding::id).toList(),
        "each stage re-judges only its own findings, under its own gate");
    assertEquals(
        List.of(correctnessFinding.id()),
        store.carryForwardFindings("auth", r2, "correctness").stream().map(Finding::id).toList());
  }

  @Test
  void carryForwardFindingsSkipsErroredReviewsAndAlreadyCarriedRows() {
    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var open = addOpenFinding(stage1, Finding.Severity.HIGH, "Open high");
    store.completeStage(stage1, "failed");
    store.updateReviewStatus(r1, "failed");
    var errored = store.createReview("auth", 2);
    store.failReviewWithError(errored, "quota exceeded");
    var r3 = store.createReview("auth", 2);
    var stage3 = store.createStage(r3, "security", "agent");

    assertEquals(
        List.of(open.id()),
        store.carryForwardFindings("auth", r3, "security").stream().map(Finding::id).toList(),
        "an errored review has no verdict; the carry set comes from the last real one");

    store.carryForward(stage3, open, null);
    assertTrue(
        store.carryForwardFindings("auth", r3, "security").isEmpty(),
        "a finding already re-attached to this review is not carried twice");
  }

  @Test
  void aStageCompletedBeforeALaterStageErroredStillCarriesItsFindings() {
    var r1 = store.createReview("auth", 1);
    var security1 = store.createStage(r1, "security", "agent");
    var blocker = addOpenFinding(security1, Finding.Severity.HIGH, "Token leak");
    store.completeStage(security1, "failed");
    var correctness1 = store.createStage(r1, "correctness", "agent");
    store.completeStage(correctness1, "failed", "agent runner crashed");
    store.failReviewWithError(r1, "agent runner crashed");

    var r2 = store.createReview("auth", 1);
    store.createStage(r2, "security", "agent");

    assertEquals(
        List.of(blocker.id()),
        store.carryForwardFindings("auth", r2, "security").stream().map(Finding::id).toList(),
        "validity is per stage: a stage that completed before a later stage errored the review"
            + " keeps its findings in the carry set");
  }

  @Test
  void carryForwardReachesPastAReviewWhereAnEarlierStageFailedBeforeThisOneRan() {
    var r1 = store.createReview("auth", 1);
    var security1 = store.createStage(r1, "security", "agent");
    store.completeStage(security1, "passed");
    var correctness1 = store.createStage(r1, "correctness", "agent");
    var blocker = addOpenFinding(correctness1, Finding.Severity.HIGH, "Off by one");
    store.completeStage(correctness1, "failed");
    store.updateReviewStatus(r1, "failed");

    var r2 = store.createReview("auth", 2);
    var security2 = store.createStage(r2, "security", "agent");
    var securityBlocker = addOpenFinding(security2, Finding.Severity.CRITICAL, "Token leak");
    store.completeStage(security2, "failed");
    store.createStage(r2, "correctness", "agent");
    store.updateReviewStatus(r2, "failed");

    var r3 = store.createReview("auth", 3);
    store.createStage(r3, "security", "agent");
    store.createStage(r3, "correctness", "agent");

    assertEquals(
        List.of(blocker.id()),
        store.carryForwardFindings("auth", r3, "correctness").stream().map(Finding::id).toList(),
        "a stage that never ran in the latest failed review still carries its open findings"
            + " from the last review where it did run");
    assertEquals(
        List.of(securityBlocker.id()),
        store.carryForwardFindings("auth", r3, "security").stream().map(Finding::id).toList());
  }

  @Test
  void carryForwardFindingsIsEmptyWhenNoPriorFailedReviewExists() {
    var r1 = store.createReview("auth", 1);
    store.createStage(r1, "security", "agent");

    assertTrue(store.carryForwardFindings("auth", r1, "security").isEmpty());
  }

  @Test
  void applyStageResultAppliesRulingsAndNewFindingsTogether() {
    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var fixed = addOpenFinding(stage1, Finding.Severity.HIGH, "Now fixed");
    var stubborn = addOpenFinding(stage1, Finding.Severity.MEDIUM, "Still there");
    store.completeStage(stage1, "failed");
    store.updateReviewStatus(r1, "failed");
    var r2 = store.createReview("auth", 2);
    var stage2 = store.createStage(r2, "security", "agent");
    var fresh =
        Finding.create(
            Finding.Severity.LOW,
            Finding.Category.LOGIC,
            "b.java",
            1,
            1,
            "New issue",
            "",
            "",
            null,
            0.6);

    store.applyStageResult(
        stage2,
        List.of(
            new ReviewStore.StageRuling(fixed, Finding.Resolution.FIXED, "commit abc"),
            new ReviewStore.StageRuling(
                stubborn, Finding.Resolution.OPEN, "the seed window still races")),
        List.of(fresh));

    var resolved = store.findFinding(fixed.id()).orElseThrow();
    assertEquals(Finding.Resolution.FIXED, resolved.resolution());
    assertEquals("commit abc", resolved.resolutionEvidence());
    assertNull(resolved.carryEvidence(), "a resolving ruling stores its evidence as resolution");
    var stageFindings = store.findingsForStage(stage2);
    assertEquals(2, stageFindings.size());
    var carried =
        stageFindings.stream().filter(f -> f.carriedFrom() != null).findFirst().orElseThrow();
    assertEquals(stubborn.id(), carried.carriedFrom());
    assertEquals(
        "the seed window still races",
        carried.carryEvidence(),
        "the still_open ruling's evidence travels on the carried row");
  }

  @Test
  void applyStageResultRollsBackRulingsWhenAFindingInsertFails() {
    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var blocker = addOpenFinding(stage1, Finding.Severity.HIGH, "Blocker");
    store.completeStage(stage1, "failed");
    store.updateReviewStatus(r1, "failed");
    var r2 = store.createReview("auth", 2);
    var stage2 = store.createStage(r2, "security", "agent");
    var colliding =
        new Finding(
            blocker.id(),
            Finding.Severity.CRITICAL,
            Finding.Category.SECURITY,
            "b.java",
            1,
            1,
            "Colliding id",
            "",
            "",
            null,
            0.9,
            Finding.Resolution.OPEN,
            null,
            null,
            null);

    assertThrows(
        RuntimeException.class,
        () ->
            store.applyStageResult(
                stage2,
                List.of(new ReviewStore.StageRuling(blocker, Finding.Resolution.FIXED, "claimed")),
                List.of(colliding)));

    assertEquals(
        Finding.Resolution.OPEN,
        store.findFinding(blocker.id()).orElseThrow().resolution(),
        "a stage result that fails to commit fully must not retire any carried finding");
    assertTrue(store.findingsForStage(stage2).isEmpty());
    assertEquals(
        List.of(blocker.id()),
        store.carryForwardFindings("auth", r2, "security").stream().map(Finding::id).toList(),
        "the retry still sees the finding open and carries it");
  }

  @Test
  void resolveFindingRecordsEvidenceForTheResolution() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    var finding = addOpenFinding(stageId, Finding.Severity.HIGH, "Ruled fixed");

    store.resolveFinding(finding.id(), Finding.Resolution.FIXED, "commit abc parameterizes it");

    var resolved = store.findFinding(finding.id()).orElseThrow();
    assertEquals(Finding.Resolution.FIXED, resolved.resolution());
    assertEquals("commit abc parameterizes it", resolved.resolutionEvidence());
  }

  @Test
  void disputedFindingsSpansTheAttemptAndIgnoresSupersededHistory() {
    var stale = store.createReview("auth", 1);
    var staleStage = store.createStage(stale, "security", "agent");
    var oldDispute = addOpenFinding(staleStage, Finding.Severity.HIGH, "Old attempt dispute");
    store.resolveFinding(oldDispute.id(), Finding.Resolution.DISPUTED, "old argument");
    store.supersedeForSpec("auth");

    var r1 = store.createReview("auth", 1);
    var stage1 = store.createStage(r1, "security", "agent");
    var disputed = addOpenFinding(stage1, Finding.Severity.HIGH, "Wrong finding");
    store.resolveFinding(disputed.id(), Finding.Resolution.DISPUTED, "cap enforced upstream");
    addOpenFinding(stage1, Finding.Severity.LOW, "Still open");

    var found = store.disputedFindings("auth");

    assertEquals(List.of(disputed.id()), found.stream().map(Finding::id).toList());
    assertEquals("cap enforced upstream", found.getFirst().resolutionEvidence());
  }

  @Test
  void openFindingsAfterPassIgnoresFixedAndDisputed() {
    var reviewId = store.createReview("auth", 1);
    var stageId = store.createStage(reviewId, "security", "agent");
    var open = addOpenFinding(stageId, Finding.Severity.LOW, "Genuinely open");
    var fixed = addOpenFinding(stageId, Finding.Severity.HIGH, "Fixed");
    var disputed = addOpenFinding(stageId, Finding.Severity.MEDIUM, "Disputed");
    store.resolveFinding(fixed.id(), Finding.Resolution.FIXED, "commit abc");
    store.resolveFinding(disputed.id(), Finding.Resolution.DISPUTED, "argued");
    store.updateReviewStatus(reviewId, "passed");

    assertEquals(
        List.of(open.id()),
        store.openFindingsAfterPass("auth").stream().map(Finding::id).toList(),
        "open-after-pass means genuinely unresolved, not accumulated history");
  }

  @Test
  void anErroredReviewRecordsWhyAndIsDistinguishableFromAVerdict() {
    var review = store.createReview("auth", 1);
    var stage = store.createStage(review, "security", "agent");

    store.completeStage(stage, "failed", "Quota exceeded");
    store.failReviewWithError(review, "Quota exceeded");

    var reviewRow = store.findReview(review).orElseThrow();
    assertEquals("failed", reviewRow.status());
    assertTrue(reviewRow.errored());
    assertEquals("Quota exceeded", reviewRow.error());
    assertEquals("Quota exceeded", store.findStage(stage).orElseThrow().error());
    var gateFailed = store.createReview("auth", 2);
    store.updateReviewStatus(gateFailed, "failed");
    assertTrue(
        !store.findReview(gateFailed).orElseThrow().errored(),
        "a gate failure carries no error; only infrastructure failures do");
  }

  @Test
  void supersedeForSpecClosesPriorAttemptsSoIterationsRestartOnRedispatch() {
    var first = store.createReview("auth", 2);
    store.updateReviewStatus(first, "escalated");
    var second = store.createReview("auth", 3);
    store.updateReviewStatus(second, "running");

    var superseded = store.supersedeForSpec("auth");

    assertEquals(2, superseded);
    assertTrue(store.findReview(first).orElseThrow().superseded());
    assertTrue(store.findReview(second).orElseThrow().superseded());
    assertEquals(
        "escalated",
        store.findReview(first).orElseThrow().status(),
        "supersession is lineage metadata; what happened stays recorded");
    assertEquals(0, store.supersedeForSpec("auth"), "idempotent on a second call");
    assertTrue(
        store.latestReviewForSpec("auth").isEmpty(),
        "superseded rows are history, not pipeline state — the current attempt starts fresh");
    assertEquals(2, store.reviewsForSpec("auth").size(), "history stays queryable");
  }

  @Test
  void failOrphanedRunningSweepsInterruptedReviewsSoTheyCannotWedgeTheSpec() {
    var interrupted = store.createReview("auth", 1);
    store.updateReviewStatus(interrupted, "running");
    var finished = store.createReview("auth", 2);
    store.updateReviewStatus(finished, "passed");

    var swept = store.failOrphanedRunning();

    assertEquals(1, swept, "exactly the interrupted review is swept");
    assertEquals(
        "failed",
        store.findReview(interrupted).orElseThrow().status(),
        "a 'running' review cannot survive a restart; left as-is it silently blocks every"
            + " future review for the spec");
    assertEquals(
        "passed", store.findReview(finished).orElseThrow().status(), "terminal rows untouched");
    assertEquals(0, store.failOrphanedRunning(), "idempotent: a second sweep finds nothing");
  }
}
