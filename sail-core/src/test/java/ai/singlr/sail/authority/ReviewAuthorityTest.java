/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.identity.Actors.ADMIN;
import static ai.singlr.sail.identity.Actors.ADMIN_SYNC;
import static ai.singlr.sail.identity.Actors.AGENT;
import static ai.singlr.sail.identity.Actors.MACHINE;
import static ai.singlr.sail.identity.Actors.MAIN;
import static ai.singlr.sail.identity.Actors.OTHER;
import static ai.singlr.sail.identity.Actors.OTHERS_AGENT;
import static ai.singlr.sail.identity.Actors.OTHER_API;
import static ai.singlr.sail.identity.Actors.OTHER_SYNC;
import static ai.singlr.sail.identity.Actors.OWNER;
import static ai.singlr.sail.identity.Actors.OWNER_API;
import static ai.singlr.sail.identity.Actors.OWNER_CLI;
import static ai.singlr.sail.identity.Actors.OWNER_SYNC;
import static ai.singlr.sail.identity.Actors.ROOM;
import static ai.singlr.sail.identity.Actors.SYSTEM;
import static ai.singlr.sail.identity.Actors.VIEWER;
import static ai.singlr.sail.identity.Actors.VIEWER_SYNC;
import static ai.singlr.sail.identity.Actors.projection;
import static ai.singlr.sail.identity.Actors.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Refusal.Kind;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.ReviewStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The review rule's matrix: a review is its spec owner's or an admin's to create, record a verdict
 * on or delete, on every lane, and it never moves to another spec.
 */
class ReviewAuthorityTest {

  private static final String REVIEW = "019fee00-0000-7000-8000-0000000000d1";
  private static final Map<String, Object> RUNNING =
      projection("spec_id", "auth", "iteration", 1, "status", "running", "stages", List.of());

  private static final Map<String, Object> MARKED = Map.of("_actor", OWNER);

  private Board board;
  private ReviewAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    board.spec("auth", "auth", OWNER, OTHER);
    rule = new ReviewAuthority(board.db);
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(
      String name, Actor actor, Map<String, Object> held, Map<String, Object> next, Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> matrix() {
    var verdict = with(RUNNING, "status", "passed");
    var rows = Stream.<Case>builder();
    for (var verb : List.of("create", "verdict", "delete")) {
      Map<String, Object> held = "create".equals(verb) ? null : RUNNING;
      Map<String, Object> next =
          switch (verb) {
            case "create" -> RUNNING;
            case "verdict" -> verdict;
            default -> null;
          };
      for (var actor : List.of(ADMIN, OWNER_API, OWNER_CLI, OWNER_SYNC, ADMIN_SYNC, MAIN, SYSTEM)) {
        rows.add(new Case(verb + " by " + actor, actor, held, next, null));
      }
      rows.add(new Case(verb + " by the owner's agent", AGENT, held, next, Kind.LANE_FORBIDDEN));
      rows.add(new Case(verb + " by another member", OTHER_API, held, next, Kind.NOT_OWNER));
      rows.add(
          new Case(verb + " by another's agent", OTHERS_AGENT, held, next, Kind.LANE_FORBIDDEN));
      rows.add(new Case(verb + " by a machine credential", MACHINE, held, next, Kind.NOT_OWNER));
      rows.add(new Case(verb + " pushed by another", OTHER_SYNC, held, next, Kind.NOT_OWNER));
      rows.add(new Case(verb + " by a viewer", VIEWER, held, next, Kind.READ_ONLY));
      rows.add(new Case(verb + " pushed by a viewer", VIEWER_SYNC, held, next, Kind.READ_ONLY));
      rows.add(new Case(verb + " by a room principal", ROOM, held, next, Kind.READ_ONLY));
    }
    rows.add(
        new Case("a deletion of a review this box holds no head of", OTHER_API, null, null, null));
    var specless = with(RUNNING, "spec_id", null);
    rows.add(new Case("an admin writes a review of no spec", ADMIN, null, specless, null));
    rows.add(
        new Case("a review of no spec is no member's", OTHER_API, null, specless, Kind.NOT_OWNER));
    rows.add(
        new Case(
            "an admin cannot move a review to another spec",
            ADMIN,
            RUNNING,
            with(RUNNING, "spec_id", "other"),
            Kind.FIXED));
    rows.add(
        new Case(
            "a viewer is told its role before a review's fixed spec",
            VIEWER,
            RUNNING,
            with(RUNNING, "spec_id", "other"),
            Kind.READ_ONLY));
    rows.add(
        new Case(
            "a push cannot move a review to another spec",
            OWNER_SYNC,
            RUNNING,
            with(RUNNING, "spec_id", "other"),
            Kind.FIXED));
    rows.add(
        new Case(
            "another member is told whose it is before the spec it would move to",
            OTHER_API,
            RUNNING,
            with(RUNNING, "spec_id", "other"),
            Kind.NOT_OWNER));
    rows.add(
        new Case(
            "a verdict authored by another FDE",
            OWNER_SYNC,
            RUNNING,
            with(verdict, "_actor", OTHER),
            Kind.NOT_AUTHOR));
    rows.add(
        new Case(
            "a verdict authored by the pipeline",
            OWNER_SYNC,
            RUNNING,
            with(verdict, "_actor", "sail"),
            null));
    rows.add(
        new Case(
            "a review of a spec no one here holds is an admin's",
            OWNER_API,
            with(RUNNING, "spec_id", "unknown"),
            with(verdict, "spec_id", "unknown"),
            Kind.NOT_OWNER));
    rows.add(
        new Case(
            "an admin decides a review of an unknown spec",
            ADMIN,
            with(RUNNING, "spec_id", "unknown"),
            with(verdict, "spec_id", "unknown"),
            null));
    return rows.build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), REVIEW, row.held(), row.next()).map(Refusal::kind));
  }

  /**
   * A review is approved, and a push of one is told whose it is before who it may not write as; a
   * viewer is told it may not approve reviews.
   */
  @Test
  void aReviewIsTheSpecOwnersToApproveSaidBeforeWhoAPushWritesAs() {
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Review " + REVIEW + " is for spec 'auth', owned by 'ada', not you.",
                "Only ada or an admin may approve or dismiss it.")),
        rule.decide(OTHER_SYNC, REVIEW, null, with(RUNNING, "_actor", OWNER)));
    assertEquals(
        Optional.of(Refusal.readOnly("approve reviews")),
        rule.decide(VIEWER, REVIEW, RUNNING, with(RUNNING, "status", "passed")));
  }

  /**
   * A scoped member's marked deletion of a review this box holds no head of, asked as the journal
   * and main's commit ask it, behind the scope rule: it names no project, so the scope rule lets it
   * through, and it is decided as main decided it, a review of no spec, so no one's but an admin's,
   * never thrown as a write outside the actor's audience.
   */
  @Test
  void aScopedMembersMarkedDeletionOfAReviewThisBoxHoldsNoHeadOfIsAnAdminsAsOnMain() {
    var scoped = ProjectScope.of(List.of("acme"));
    var noOnes =
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Review rv-x names no spec, so it has no owner.",
                "Only an admin may approve or dismiss it."));
    var scoping = new ScopeRule(new ChangeLog(board.db), "review").before(rule);

    assertEquals(noOnes, scoping.decide(OWNER_API.withScope(scoped), "rv-x", null, MARKED));
    assertEquals(noOnes, scoping.decide(OWNER_SYNC.withScope(scoped), "rv-x", null, MARKED));
    assertEquals(Optional.empty(), scoping.decide(ADMIN.withScope(scoped), "rv-x", null, MARKED));
  }

  /**
   * The same deletion pushed to main, of a review whose row main holds with no head — the one such
   * deletion main's commit decides rather than takes as changing nothing — is denied there as main
   * denied it.
   */
  @Test
  void mainsCommitDeniesAScopedNodesMarkedDeletionOfAReviewItHoldsNoHeadOf() {
    var reviews = new ReviewStore(board.db);
    var id = Acting.system(() -> reviews.createReview("auth", 1));
    board.db.execute("DELETE FROM change_heads WHERE entity_type = 'review' AND entity_id = ?", id);

    var outcome =
        Actor.call(
            OWNER_SYNC.withScope(ProjectScope.of(List.of("acme"))),
            () -> reviews.commitRevision(id, MARKED, null, reviews.authority()));

    assertEquals(
        "Review " + id + " names no spec, so it has no owner.",
        assertInstanceOf(PushOutcome.Denied.class, outcome).reason());
  }

  @Test
  void aReviewCarriesOnlyItsOwnStagesEvenForAnAdmin() {
    var theirs = "019fee00-0000-7000-8000-0000000000d2";
    board.db.execute(
        "INSERT INTO reviews (id, spec_id, iteration, status, created_at) VALUES (?, 'auth', 1,"
            + " 'running', 'now')",
        REVIEW);
    board.db.execute(
        "INSERT INTO reviews (id, spec_id, iteration, status, created_at) VALUES (?, 'auth', 1,"
            + " 'running', 'now')",
        theirs);
    for (var stage : List.of(List.of("s-own", REVIEW), List.of("s-theirs", theirs))) {
      board.db.execute(
          "INSERT INTO review_stages (id, review_id, name, stage_type, status) VALUES (?, ?,"
              + " 'security', 'agent', 'running')",
          stage.get(0),
          stage.get(1));
    }
    var own = with(RUNNING, "stages", List.of(Map.of("id", "s-own"), Map.of("id", "s-new")));

    assertEquals(Optional.empty(), rule.decide(OWNER_SYNC, REVIEW, RUNNING, own));
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.FIXED,
                "Stage s-theirs belongs to review " + theirs + ", not review " + REVIEW + ".",
                "A stage is set when its review creates it and never moves to another review.")),
        rule.decide(
            ADMIN_SYNC,
            REVIEW,
            RUNNING,
            with(RUNNING, "stages", List.of(Map.of("id", "s-theirs")))));
  }

  @Test
  void refusalsSpeakTheTextsClientsSee() {
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "Review " + REVIEW + " is for spec 'auth', owned by 'ada', not you.",
            "Only ada or an admin may approve or dismiss it."),
        rule.decide(OTHER_API, REVIEW, RUNNING, RUNNING).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "Review " + REVIEW + " is for spec 'unknown', which has no owner.",
            "Only the spec's owner or an admin may approve or dismiss it."),
        rule.decide(
                OTHER_API,
                REVIEW,
                with(RUNNING, "spec_id", "unknown"),
                with(RUNNING, "spec_id", "unknown"))
            .orElseThrow());
  }
}
