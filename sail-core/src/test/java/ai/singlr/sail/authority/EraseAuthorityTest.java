/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.authority.Actors.ADMIN;
import static ai.singlr.sail.authority.Actors.ADMIN_SYNC;
import static ai.singlr.sail.authority.Actors.AGENT;
import static ai.singlr.sail.authority.Actors.MACHINE;
import static ai.singlr.sail.authority.Actors.OTHER;
import static ai.singlr.sail.authority.Actors.OTHERS_AGENT;
import static ai.singlr.sail.authority.Actors.OTHER_API;
import static ai.singlr.sail.authority.Actors.OTHER_SYNC;
import static ai.singlr.sail.authority.Actors.OWNER;
import static ai.singlr.sail.authority.Actors.OWNER_API;
import static ai.singlr.sail.authority.Actors.OWNER_SYNC;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The erase rule's matrix, the one a local prune and main's decision on a node's request both ask:
 * who may erase at all, a whole project or a sweep only as an admin, a spec only as its owner or an
 * admin once it is off the board, and nothing while a run in it is going.
 */
class EraseAuthorityTest {

  private Board board;
  private EraseAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    rule = new EraseAuthority(board.db);
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(String name, Actor actor, SpecStore.LastKnown spec, Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static SpecStore.LastKnown spec(
      SpecStatus status, String assignee, String createdBy, boolean live) {
    return new SpecStore.LastKnown("auth", status, assignee, createdBy, "auth", live);
  }

  static Stream<Case> specs() {
    var archived = spec(SpecStatus.ARCHIVED, OWNER, OTHER, true);
    var unassigned = spec(SpecStatus.CANCELLED, null, OWNER, true);
    var ownerless = spec(SpecStatus.ARCHIVED, null, null, true);
    var deleted = spec(SpecStatus.PENDING, OWNER, OWNER, false);
    var live = spec(SpecStatus.IN_PROGRESS, OWNER, OWNER, true);
    return Stream.of(
        new Case("the owner erases their archived spec", OWNER_API, archived, null),
        new Case("the owner's push erases it", OWNER_SYNC, archived, null),
        new Case("the owner's agent passes the rule", AGENT, archived, null),
        new Case("an admin erases anyone's", ADMIN, archived, null),
        new Case("an admin's push erases anyone's", ADMIN_SYNC, archived, null),
        new Case("the creator erases an unassigned one", OWNER_API, unassigned, null),
        new Case("the owner erases a deleted one", OWNER_API, deleted, null),
        new Case("an admin erases an ownerless one", ADMIN, ownerless, null),
        new Case("another member may not", OTHER_API, archived, Kind.NOT_OWNER),
        new Case("another member's push may not", OTHER_SYNC, archived, Kind.NOT_OWNER),
        new Case("another FDE's agent may not", OTHERS_AGENT, archived, Kind.NOT_OWNER),
        new Case("a machine credential may not", MACHINE, archived, Kind.NOT_OWNER),
        new Case("a member may not erase an ownerless one", OWNER_API, ownerless, Kind.NOT_OWNER),
        new Case("a viewer may not", VIEWER, archived, Kind.READ_ONLY),
        new Case("a viewer's push may not", VIEWER_SYNC, archived, Kind.READ_ONLY),
        new Case("nobody erases work on the board", ADMIN, live, Kind.NOT_PRUNABLE),
        new Case("an outsider is told whose it is first", OTHER_API, live, Kind.NOT_OWNER));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("specs")
  void decidesASpec(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()), rule.spec(row.actor(), row.spec()).map(Refusal::kind));
  }

  @Test
  void aWholeProjectOrASweepIsAnAdminsAndNoneAViewers() {
    assertEquals(Optional.empty(), rule.request(ADMIN, true));
    assertEquals(Optional.empty(), rule.request(ADMIN_SYNC, true));
    assertEquals(Optional.empty(), rule.request(OWNER_API, false));
    assertEquals(Optional.of(Kind.ADMIN_ONLY), rule.request(OWNER_API, true).map(Refusal::kind));
    assertEquals(Optional.of(Kind.ADMIN_ONLY), rule.request(OWNER_SYNC, true).map(Refusal::kind));
    assertEquals(Optional.of(Kind.READ_ONLY), rule.request(VIEWER, false).map(Refusal::kind));
    assertEquals(
        Optional.of(Kind.READ_ONLY),
        rule.request(new Actor(OWNER, Role.VIEWER, Actor.Lane.CLI), true).map(Refusal::kind));
  }

  @Test
  void aPlanWithARunStillGoingMustWait() {
    board.spec("solo", "solo", OWNER, OWNER);
    var runs = new RunStore(board.db);
    var going =
        Acting.system(
            () ->
                runs.create(
                    "019fee00-0000-7000-8000-0000000000c3",
                    "acme",
                    "solo",
                    OWNER,
                    "build",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/l",
                    "u"));
    var spec = List.of(new Erasure.Target("spec", "solo"));
    var plan = Acting.system(() -> new Erasure(board.db).closure(spec));

    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_PRUNABLE,
                "Run '" + going + "' has not finished; a prune never erases work going on.",
                "Stop it first: sail agent stop, then prune.")),
        rule.idle(plan));
    assertEquals(
        Optional.empty(),
        rule.idle(
            Acting.system(
                () ->
                    new Erasure(board.db).closure(List.of(new Erasure.Target("project", "acme"))))),
        "a project is purged whole, once its container is gone, and never waits for a run");

    Acting.system(() -> runs.complete(going, "completed", 0));
    assertEquals(Optional.empty(), rule.idle(plan));
  }

  @Test
  void refusalsSpeakTheTextsClientsSee() {
    assertEquals(
        new Refusal(
            Kind.NOT_PRUNABLE,
            "Spec 'auth' is in_progress: only archived, cancelled or deleted specs are pruned.",
            "Archive it first: sail spec update auth --status archived."),
        rule.spec(ADMIN, spec(SpecStatus.IN_PROGRESS, OWNER, OWNER, true)).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.READ_ONLY,
            "Your role is read-only: it cannot prune.",
            "Ask an admin to prune, or for a member role."),
        rule.request(VIEWER, false).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.ADMIN_ONLY,
            "Pruning by policy or a whole project is admin-only.",
            "Name the specs you own by id: sail spec prune <id...>."),
        rule.request(OWNER_API, true).orElseThrow());
    assertEquals(
        "Spec 'auth' is assigned to 'ada', not you.",
        rule.spec(OTHER_API, spec(SpecStatus.ARCHIVED, OWNER, OWNER, true))
            .orElseThrow()
            .message());
  }
}
