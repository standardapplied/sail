/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static ai.singlr.sail.sync.SyncFixtures.assign;
import static ai.singlr.sail.sync.SyncFixtures.finding;
import static ai.singlr.sail.sync.SyncFixtures.findingKeptInChangeLog;
import static ai.singlr.sail.sync.SyncFixtures.findings;
import static ai.singlr.sail.sync.SyncFixtures.ownSpec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewFindingsMigration;
import ai.singlr.sail.store.ReviewStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C1, C2 and C4 for reviews over real sessions, main plus two nodes: a review's findings are its
 * synced content, so every box holds the same review, a node adopting main's version takes main's
 * findings exactly, a review main never took leaves only by denial with its findings kept in the
 * change log and announced, and a follow-up's links ride the content through its spec's delete and
 * restore, its shipping fixing the source findings in their review's content — by the box that
 * marks it done when that box is main, by main when a node's done commits there — so the fix
 * outlives the follow-up.
 */
class ReviewFindingsSyncTest {

  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);
  private static final Actor BOB = Actor.sync("bob", Role.MEMBER);

  @TempDir Path dir;
  private SyncBox main;
  private SyncBox ada;
  private SyncBox bob;
  private ReviewStore reviews;

  @BeforeEach
  void setUp() {
    main = new SyncBox(dir, "main");
    ada = new SyncBox(dir, "ada").syncsAs(ADA);
    bob = new SyncBox(dir, "bob").syncsAs(BOB);
    reviews = new ReviewStore(ada.db);
  }

  @AfterEach
  void tearDown() {
    bob.close();
    ada.close();
    main.close();
  }

  private SyncBox.Round round(SyncBox box) {
    return SyncBox.roundSettling(main, box);
  }

  private static List<String> deniedReviews(SyncBox.Round round) {
    return round.types().stream()
        .filter(report -> report.type().equals("review"))
        .flatMap(report -> report.denials().stream())
        .map(SyncSession.Denial::id)
        .toList();
  }

  private static Finding byId(List<Finding> findings, String id) {
    return findings.stream().filter(finding -> finding.id().equals(id)).findFirst().orElseThrow();
  }

  /** A failed first review of {@code specId} on ada's box with findings {@code found}. */
  private String failedReview(String specId, Finding... found) {
    return Acting.system(() -> failedReviewOn(ada, specId, found));
  }

  /** A failed first review of {@code specId} on {@code box}, by the bound actor. */
  private static String failedReviewOn(SyncBox box, String specId, Finding... found) {
    var reviews = new ReviewStore(box.db);
    var reviewId = reviews.createReview(specId, 1);
    reviews.updateReviewStatus(reviewId, "running");
    var stageId = reviews.createStage(reviewId, "security", "agent");
    reviews.startStage(stageId, "codex");
    for (var finding : found) {
      reviews.addFinding(stageId, finding);
    }
    reviews.completeStage(stageId, "failed");
    reviews.updateReviewStatus(reviewId, "failed");
    return reviewId;
  }

  @Test
  void aReviewRunOnAdasNodeAcrossTwoIterationsReachesMainAndBobWithIdenticalFindings() {
    ownSpec(main, "ada", "s", "ada");
    round(ada);
    round(bob);
    var leak = finding("leak");
    var race = finding("race");
    var first = failedReview("s", leak, race);
    round(ada);
    var second =
        Acting.system(
            () -> {
              var reviewId = reviews.createReview("s", 2);
              reviews.updateReviewStatus(reviewId, "running");
              var stageId = reviews.createStage(reviewId, "security", "agent");
              reviews.startStage(stageId, "codex");
              var carried = reviews.carryForwardFindings("s", reviewId, "security");
              assertEquals(2, carried.size());
              var argued = finding("argued");
              reviews.applyStageResult(
                  stageId,
                  List.of(
                      new ReviewStore.StageRuling(
                          byId(carried, leak.id()), Finding.Resolution.OPEN, "still leaks"),
                      new ReviewStore.StageRuling(
                          byId(carried, race.id()), Finding.Resolution.FIXED, "locked now")),
                  List.of(argued));
              reviews.resolveFinding(argued.id(), Finding.Resolution.DISPUTED, "by design");
              reviews.completeStage(stageId, "passed");
              reviews.updateReviewStatus(reviewId, "passed");
              return reviewId;
            });

    SyncBox.assertConvergedWithin(3, main, ada, bob);

    for (var box : List.of(main, bob)) {
      assertEquals(findings(ada, first), findings(box, first), box.id + " first iteration");
      assertEquals(findings(ada, second), findings(box, second), box.id + " second iteration");
    }
    var onBob = findings(bob, second);
    var carried =
        onBob.stream().filter(f -> leak.id().equals(f.carriedFrom())).findFirst().orElseThrow();
    assertEquals("still leaks", carried.carryEvidence());
    assertEquals(Finding.Resolution.FIXED, byId(findings(bob, first), race.id()).resolution());
    assertEquals("locked now", byId(findings(bob, first), race.id()).resolutionEvidence());
    assertEquals(
        Finding.Resolution.DISPUTED,
        onBob.stream()
            .filter(f -> f.title().equals("argued"))
            .findFirst()
            .orElseThrow()
            .resolution());
    var stage = new ReviewStore(main.db).stagesForReview(second).getFirst().id();
    assertEquals(2, new ReviewStore(main.db).findingCountsForStage(stage).get("HIGH"));
  }

  @Test
  void aFindingWhoseTextCarriesControlCharactersLandsOnEveryBoxVerbatim() {
    ownSpec(main, "ada", "s", "ada");
    round(ada);
    round(bob);
    var mojibake = finding("smart \u0091quotes\u0092 and a NEL\u0085 and DEL\u007f");
    var review = failedReview("s", mojibake);

    SyncBox.assertConvergedWithin(2, main, ada, bob);

    for (var box : List.of(main, bob)) {
      assertEquals(mojibake.title(), byId(findings(box, review), mojibake.id()).title(), box.id);
    }
  }

  @Test
  void aReviewDeniedAfterItsSpecWasReassignedAwayIsAdoptedAsMainsVersionExactly() {
    ownSpec(main, "ada", "s", "ada");
    round(ada);
    round(bob);
    var review = Acting.system(() -> reviews.createReview("s", 1));
    round(ada);
    assign(main, "root", "s", "bob");
    var found = finding("leak");
    Acting.system(
        () -> {
          var stageId = reviews.createStage(review, "security", "agent");
          reviews.addFinding(stageId, found);
          reviews.updateReviewStatus(review, "failed");
        });

    var denied = round(ada);

    assertEquals(List.of(review), deniedReviews(denied), "the denial is announced");
    assertEquals("pending", reviews.findReview(review).orElseThrow().status());
    assertEquals(List.of(), findings(ada, review), "ada holds main's findings: none");
    assertEquals(List.of(), reviews.stagesForReview(review));
    assertTrue(findingKeptInChangeLog(ada, review, found.id()), "the finding is recoverable");
    SyncBox.assertConvergedWithin(2, main, ada, bob);
  }

  @Test
  void aNeverAcknowledgedReviewDeniedIsWithdrawnWithItsFindingsKeptAndAnnounced() {
    ownSpec(main, "ada", "s", "ada");
    round(ada);
    var found = finding("leak");
    var review =
        Acting.system(
            () -> {
              var reviewId = reviews.createReview("s", 1);
              reviews.updateReviewStatus(reviewId, "running");
              reviews.addFinding(reviews.createStage(reviewId, "security", "agent"), found);
              return reviewId;
            });
    assign(main, "root", "s", "bob");
    round(ada);
    assertTrue(reviews.findReview(review).isPresent(), "a running review is left to its pipeline");
    Acting.system(() -> reviews.updateReviewStatus(review, "failed"));

    var denied = round(ada);

    assertEquals(List.of(review), deniedReviews(denied), "the withdrawal is announced");
    assertTrue(reviews.findReview(review).isEmpty(), "main never took it: withdrawn");
    var head = new ChangeLog(ada.db).head("review", review).orElseThrow();
    assertEquals(ChangeLog.Kind.TOMBSTONE, head.kind());
    assertTrue(head.withdrawn());
    assertTrue(findingKeptInChangeLog(ada, review, found.id()), "its findings stay recoverable");
    assertTrue(new ReviewStore(main.db).findReview(review).isEmpty());
    SyncBox.assertConvergedWithin(2, main, ada, bob);
  }

  @Test
  void aFollowUpsLinksSurviveItsSpecsDeleteAndRestoreEverywhereAndShippingItFixesTheSource() {
    ownSpec(main, "ada", "fix", "ada");
    round(ada);
    round(bob);
    var found = finding("leak");
    var review = failedReview("fix", found);
    ownSpec(ada, "ada", "followup", "ada");
    Acting.as("ada", () -> reviews.linkSourceFindings("followup", List.of(found.id())));
    round(ada);
    round(bob);
    for (var box : List.of(main, ada, bob)) {
      assertEquals(
          List.of(found.id()), new ReviewStore(box.db).sourceFindingIds("followup"), box.id);
    }

    Acting.as("root", () -> main.specs.delete("followup"));
    round(ada);
    round(bob);
    assertTrue(ada.specs.findById("followup").isEmpty(), "ada adopted the deletion");
    for (var box : List.of(main, ada, bob)) {
      assertEquals(
          List.of(found.id()),
          new ReviewStore(box.db).sourceFindingIds("followup"),
          box.id + " keeps the link through the delete");
      assertEquals(Finding.Resolution.OPEN, byId(findings(box, review), found.id()).resolution());
    }

    var first = main.specs.history("followup").getFirst().rev();
    Acting.as("root", () -> main.specs.restore("followup", first));
    round(ada);
    round(bob);
    Acting.as("root", () -> main.specs.updateStatus("followup", SpecStatus.DONE));
    assertEquals(
        1, Acting.as("root", () -> new ReviewStore(main.db).resolveFindingsOfShippedFollowUps()));
    round(ada);
    round(bob);
    assertFixedEverywhere(review, found.id());

    Acting.as("root", () -> main.specs.updateStatus("followup", SpecStatus.ARCHIVED));
    prune(main, "followup");
    round(ada);
    round(bob);
    assertTrue(bob.specs.findById("followup").isEmpty(), "bob adopted the erasure");
    assertFixedEverywhere(review, found.id());
    SyncBox.assertConvergedWithin(2, main, ada, bob);
  }

  @Test
  void aFollowUpAnotherFdeShipsOnItsNodeFixesTheSourceFindingsOnceMainTakesIt() {
    main.transitions(new ShippedFollowUps(main.db));
    ownSpec(main, "bob", "fix", "bob");
    ownSpec(main, "ada", "followup", "ada");
    round(ada);
    round(bob);
    var found = finding("leak");
    var review = Acting.as("bob", () -> failedReviewOn(bob, "fix", found));
    round(bob);
    round(ada);
    Acting.as(
        "root", () -> new ReviewStore(main.db).linkSourceFindings("followup", List.of(found.id())));
    round(ada);
    round(bob);

    Acting.as("ada", () -> ada.specs.updateStatus("followup", SpecStatus.DONE));
    assertEquals(Finding.Resolution.OPEN, byId(findings(ada, review), found.id()).resolution());
    round(ada);
    assertEquals(
        Finding.Resolution.FIXED,
        byId(findings(main, review), found.id()).resolution(),
        "main resolves the source finding when the follow-up's done commits there");
    round(ada);
    round(bob);

    assertFixedEverywhere(review, found.id());
    SyncBox.assertConvergedWithin(2, main, ada, bob);
  }

  @Test
  void mainFixingASourceFindingWhileItsOwnerRulesOnAnotherMergesInsteadOfParkingAConflict() {
    main.transitions(new ShippedFollowUps(main.db));
    ownSpec(main, "bob", "fix", "bob");
    ownSpec(main, "ada", "followup", "ada");
    round(ada);
    round(bob);
    var leak = finding("leak");
    var other = finding("other");
    var review = Acting.as("bob", () -> failedReviewOn(bob, "fix", leak, other));
    round(bob);
    round(ada);
    Acting.as(
        "root", () -> new ReviewStore(main.db).linkSourceFindings("followup", List.of(leak.id())));
    round(ada);
    round(bob);

    Acting.as(
        "bob",
        () ->
            new ReviewStore(bob.db)
                .resolveFinding(other.id(), Finding.Resolution.DISMISSED, "by design"));
    Acting.as("ada", () -> ada.specs.updateStatus("followup", SpecStatus.DONE));
    round(ada);
    var bobsRound = round(bob);

    assertEquals(
        0,
        bobsRound.types().stream().mapToInt(type -> type.report().conflicts()).sum(),
        "disjoint findings merge");
    assertTrue(bob.conflicts.pendingFor("review", review).isEmpty());
    SyncBox.assertConvergedWithin(2, main, ada, bob);
    for (var box : List.of(main, ada, bob)) {
      assertEquals(
          Finding.Resolution.FIXED, byId(findings(box, review), leak.id()).resolution(), box.id);
      assertEquals(
          Finding.Resolution.DISMISSED,
          byId(findings(box, review), other.id()).resolution(),
          box.id);
    }
  }

  @Test
  void aResolutionMainMissedIsMadeGoodByTheNextSpecTransitionItTakes() {
    ownSpec(main, "bob", "fix", "bob");
    ownSpec(main, "ada", "followup", "ada");
    round(ada);
    round(bob);
    var leak = finding("leak");
    var review = Acting.as("bob", () -> failedReviewOn(bob, "fix", leak));
    round(bob);
    round(ada);
    Acting.as(
        "root", () -> new ReviewStore(main.db).linkSourceFindings("followup", List.of(leak.id())));
    round(ada);
    round(bob);
    Acting.as("ada", () -> ada.specs.updateStatus("followup", SpecStatus.DONE));
    round(ada);
    assertEquals(
        Finding.Resolution.OPEN,
        byId(findings(main, review), leak.id()).resolution(),
        "main's sink missed the done transition");

    main.transitions(new ShippedFollowUps(main.db));
    Acting.as("bob", () -> bob.specs.updateStatus("fix", SpecStatus.IN_PROGRESS));
    round(bob);

    assertEquals(Finding.Resolution.FIXED, byId(findings(main, review), leak.id()).resolution());
    SyncBox.assertConvergedWithin(2, main, ada, bob);
    assertFixedEverywhere(review, leak.id());
  }

  private void assertFixedEverywhere(String review, String findingId) {
    for (var box : List.of(main, ada, bob)) {
      var fixed = byId(findings(box, review), findingId);
      assertEquals(Finding.Resolution.FIXED, fixed.resolution(), box.id);
      assertEquals("fixed by follow-up followup", fixed.resolutionEvidence(), box.id);
      assertEquals(List.of(), new ReviewStore(box.db).openFindingsForReview(review), box.id);
    }
  }

  private static void prune(SyncBox box, String specId) {
    var erasure = new Erasure(box.db);
    Acting.as(
        "root",
        () ->
            erasure.erase(
                erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, specId))), "local"));
  }

  private void foldLegacyFindings(SyncBox box) {
    new DataMigrator(box.db, List.of(new ReviewFindingsMigration()))
        .run(
            ProjectRegistry.loadFromDisk(dir.resolve("no-projects")),
            DataMigration.Prompter.NON_INTERACTIVE);
  }

  /** A review main took from ada before findings were content, with box-local finding rows. */
  private String legacyReview(String specId, String findingId) {
    var review = failedReview(specId);
    round(ada);
    var stage = reviews.stagesForReview(review).getFirst().id();
    ada.db.execute(
        """
        INSERT INTO review_findings (id, stage_id, severity, category, file, line_start, line_end,
            title, description, confidence, resolution)
        VALUES (?, ?, 'HIGH', 'SECURITY', 'A.java', 1, 2, 'legacy', 'd', 0.9, 'OPEN')""",
        findingId,
        stage);
    return review;
  }

  @Test
  void aNodesLegacyFindingsFoldIntoOneRevisionThatTheNextRoundPushesToMainAndOnToBob() {
    ownSpec(main, "ada", "s", "ada");
    round(ada);
    round(bob);
    var review = legacyReview("s", "legacy-finding");
    var before = new ChangeLog(ada.db).history("review", review).size();

    foldLegacyFindings(ada);
    foldLegacyFindings(ada);

    var history = new ChangeLog(ada.db).history("review", review);
    assertEquals(before + 1, history.size(), "one revision, folded once");
    assertEquals(Actor.system().handle(), history.getLast().actor());
    SyncBox.assertConvergedWithin(2, main, ada, bob);
    for (var box : List.of(main, bob)) {
      assertEquals(List.of("legacy-finding"), ids(findings(box, review)), box.id);
    }
  }

  @Test
  void aNodesLegacyFindingsOfASpecNoLongerItsAreDeniedAndKeptInItsChangeLog() {
    ownSpec(main, "ada", "s", "ada");
    round(ada);
    var review = legacyReview("s", "legacy-finding");
    assign(main, "root", "s", "bob");

    foldLegacyFindings(ada);
    var denied = round(ada);

    assertEquals(List.of(review), deniedReviews(denied), "the denial is announced");
    assertEquals(List.of(), findings(ada, review), "ada holds main's version exactly");
    assertTrue(findingKeptInChangeLog(ada, review, "legacy-finding"));
    assertFalse(findingKeptInChangeLog(main, review, "legacy-finding"));
    SyncBox.assertConvergedWithin(2, main, ada, bob);
  }

  private static List<String> ids(List<Finding> findings) {
    return findings.stream().map(Finding::id).toList();
  }
}
