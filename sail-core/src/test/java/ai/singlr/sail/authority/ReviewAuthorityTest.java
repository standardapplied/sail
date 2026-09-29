/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.authority.Actors.ADMIN;
import static ai.singlr.sail.authority.Actors.ADMIN_SYNC;
import static ai.singlr.sail.authority.Actors.AGENT;
import static ai.singlr.sail.authority.Actors.MACHINE;
import static ai.singlr.sail.authority.Actors.MAIN;
import static ai.singlr.sail.authority.Actors.OTHER;
import static ai.singlr.sail.authority.Actors.OTHERS_AGENT;
import static ai.singlr.sail.authority.Actors.OTHER_API;
import static ai.singlr.sail.authority.Actors.OTHER_SYNC;
import static ai.singlr.sail.authority.Actors.OWNER;
import static ai.singlr.sail.authority.Actors.OWNER_API;
import static ai.singlr.sail.authority.Actors.OWNER_CLI;
import static ai.singlr.sail.authority.Actors.OWNER_SYNC;
import static ai.singlr.sail.authority.Actors.ROOM;
import static ai.singlr.sail.authority.Actors.SYSTEM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static ai.singlr.sail.authority.Actors.projection;
import static ai.singlr.sail.authority.Actors.with;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Actor;
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
      for (var actor :
          List.of(ADMIN, OWNER_API, OWNER_CLI, AGENT, OWNER_SYNC, ADMIN_SYNC, MAIN, SYSTEM)) {
        rows.add(new Case(verb + " by " + actor, actor, held, next, null));
      }
      rows.add(new Case(verb + " by another member", OTHER_API, held, next, Kind.NOT_OWNER));
      rows.add(new Case(verb + " by another's agent", OTHERS_AGENT, held, next, Kind.NOT_OWNER));
      rows.add(new Case(verb + " by a machine credential", MACHINE, held, next, Kind.NOT_OWNER));
      rows.add(new Case(verb + " pushed by another", OTHER_SYNC, held, next, Kind.NOT_OWNER));
      rows.add(new Case(verb + " by a viewer", VIEWER, held, next, Kind.READ_ONLY));
      rows.add(new Case(verb + " pushed by a viewer", VIEWER_SYNC, held, next, Kind.READ_ONLY));
      rows.add(new Case(verb + " by a room principal", ROOM, held, next, Kind.READ_ONLY));
    }
    rows.add(
        new Case(
            "an admin cannot move a review to another spec",
            ADMIN,
            RUNNING,
            with(RUNNING, "spec_id", "other"),
            Kind.FIXED));
    rows.add(
        new Case(
            "a push cannot move a review to another spec",
            OWNER_SYNC,
            RUNNING,
            with(RUNNING, "spec_id", "other"),
            Kind.FIXED));
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
