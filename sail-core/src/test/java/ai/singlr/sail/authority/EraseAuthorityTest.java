/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.identity.Actors.ADMIN;
import static ai.singlr.sail.identity.Actors.ADMIN_SYNC;
import static ai.singlr.sail.identity.Actors.AGENT;
import static ai.singlr.sail.identity.Actors.MACHINE;
import static ai.singlr.sail.identity.Actors.OTHER;
import static ai.singlr.sail.identity.Actors.OTHERS_AGENT;
import static ai.singlr.sail.identity.Actors.OTHER_API;
import static ai.singlr.sail.identity.Actors.OTHER_SYNC;
import static ai.singlr.sail.identity.Actors.OWNER;
import static ai.singlr.sail.identity.Actors.OWNER_API;
import static ai.singlr.sail.identity.Actors.OWNER_SYNC;
import static ai.singlr.sail.identity.Actors.VIEWER;
import static ai.singlr.sail.identity.Actors.VIEWER_SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Refusal.Kind;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Actors;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
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
        new Case(
            "the owner's agent may not: erasing is its FDE's",
            AGENT,
            archived,
            Kind.LANE_FORBIDDEN),
        new Case("an admin erases anyone's", ADMIN, archived, null),
        new Case("an admin's push erases anyone's", ADMIN_SYNC, archived, null),
        new Case("the creator erases an unassigned one", OWNER_API, unassigned, null),
        new Case("the owner erases a deleted one", OWNER_API, deleted, null),
        new Case("an admin erases an ownerless one", ADMIN, ownerless, null),
        new Case("another member may not", OTHER_API, archived, Kind.NOT_OWNER),
        new Case("another member's push may not", OTHER_SYNC, archived, Kind.NOT_OWNER),
        new Case(
            "another FDE's agent is told its lane first",
            OTHERS_AGENT,
            archived,
            Kind.LANE_FORBIDDEN),
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
        Optional.ofNullable(row.refused()),
        refused(rule.spec(row.actor(), row.spec())).map(Refusal::kind));
  }

  @Test
  void aWholeProjectOrASweepIsAnAdminsAndNoneAViewers() {
    assertEquals(Optional.empty(), rule.purge(ADMIN));
    assertEquals(Optional.empty(), rule.purge(ADMIN_SYNC));
    assertEquals(Optional.empty(), Access.atAll(OWNER_API, Action.PRUNE, Resource.Kind.SPEC));
    assertEquals(Optional.of(Kind.ADMIN_ONLY), rule.purge(OWNER_API).map(Refusal::kind));
    assertEquals(Optional.of(Kind.ADMIN_ONLY), rule.purge(OWNER_SYNC).map(Refusal::kind));
    assertEquals(
        Optional.of(Kind.READ_ONLY),
        Access.atAll(VIEWER, Action.PRUNE, Resource.Kind.SPEC).map(Refusal::kind));
    assertEquals(
        Optional.of(Kind.READ_ONLY),
        rule.purge(new Actor(OWNER, Role.VIEWER, Actor.Lane.CLI)).map(Refusal::kind));
  }

  @Test
  void whatLiesOutsideTheAudienceIsNotFoundBeforeAnyRoleIsAsked() {
    var archived = spec(SpecStatus.ARCHIVED, OWNER, OWNER, true);
    var outsider = VIEWER.withScope(ProjectScope.of(List.of("beta")));

    assertEquals(new Decision.NotFound(), rule.spec(outsider, archived));
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_PRUNABLE,
                "main holds no spec 'auth', so it cannot tell whose it is; sync it before pruning",
                "Sync the spec before pruning it.")),
        rule.requested(Actor.sync(OWNER, Role.VIEWER).withScope(outsider.scope()), "spec", "auth"));
  }

  @Test
  void anErasureAskedAgainIsAnsweredToWhoeverMayEraseAtAll() {
    Acting.system(
        () -> {
          new SpecStore(board.db).create(row("gone", "acme"));
          var erasure = new Erasure(board.db);
          erasure.erase(
              erasure.closure(List.of(new Erasure.Target("spec", "gone"))), ChangeLog.Entry.LOCAL);
        });

    assertEquals(Optional.empty(), rule.requested(OTHER_SYNC, "spec", "gone"));
    assertEquals(
        Optional.of(Kind.READ_ONLY),
        rule.requested(VIEWER_SYNC, "spec", "gone").map(Refusal::kind));
    assertEquals(
        new Decision.NotFound(),
        rule.prune(OTHER_SYNC.withScope(ProjectScope.of(List.of("beta"))), "gone"),
        "and one erased outside the asker's projects is not there");
  }

  @Test
  void aProjectErasedAlreadyIsAnsweredToAnAdminAsNothingLeftToDo() {
    Acting.system(
        () -> {
          var erasure = new Erasure(board.db);
          erasure.erase(
              erasure.closure(List.of(new Erasure.Target("project", "acme"))),
              ChangeLog.Entry.LOCAL);
        });

    assertEquals(Optional.empty(), rule.requested(Actors.ADMIN_SYNC, "project", "acme"));
  }

  @Test
  void aProjectMainHoldsNoneOfIsSaidSoBeforeTheRoleAsWhatThisBoxDoesNotHoldIsEverywhere() {
    var none =
        Optional.of(
            new Refusal(
                Kind.NOT_PRUNABLE,
                "main holds no project 'ghost'",
                "Sync the project before pruning it."));

    for (var actor : List.of(VIEWER_SYNC, OWNER_SYNC, Actors.ADMIN_SYNC)) {
      assertEquals(none, rule.requested(actor, "project", "ghost"), actor.toString());
    }
    assertEquals(
        Optional.of(Kind.ADMIN_ONLY),
        rule.requested(OWNER_SYNC, "project", "acme").map(Refusal::kind),
        "a project main holds is purged by an admin alone");
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
                Remedy.of("Stop it first: ", Remedy.sail("agent stop"), ", then prune."))),
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
  void aScopedOwnerErasesASpecOnlyWhenAllThatBelongsToItIsInsideTheScope() {
    var scoped = OWNER_API.withScope(ProjectScope.of(List.of("acme")));
    Acting.system(
        () -> {
          var specs = new SpecStore(board.db);
          specs.create(row("whole", "acme"));
          specs.create(row("moved", "other"));
          run("019fee00-0000-7000-8000-0000000000d1", "acme", "whole");
          run("019fee00-0000-7000-8000-0000000000d2", "other", "moved");
          specs.update(row("moved", "acme"));
        });
    var specs = new SpecStore(board.db);

    assertEquals(new Decision.Allowed(), rule.spec(scoped, specs.lastKnown("whole").orElseThrow()));
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_PRUNABLE,
                "Pruning spec 'moved' would erase work outside your project scope.",
                "Ask an admin to prune it.")),
        refused(rule.spec(scoped, specs.lastKnown("moved").orElseThrow())));
    assertEquals(
        new Decision.Allowed(),
        rule.spec(OWNER_API, specs.lastKnown("moved").orElseThrow()),
        "an owner main never scoped erases it whole");
    assertEquals(
        Optional.of(Kind.NOT_PRUNABLE),
        rule.requested(Actor.sync(OWNER, Role.MEMBER).withScope(scoped.scope()), "spec", "moved")
            .map(Refusal::kind),
        "and main answers a scoped node's request by the same rule");
  }

  private static SpecStore.SpecRow row(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        id,
        SpecStatus.ARCHIVED,
        OWNER,
        null,
        null,
        null,
        null,
        0,
        OWNER,
        null,
        null,
        OWNER,
        List.of(),
        List.of());
  }

  private void run(String id, String project, String spec) {
    var runs = new RunStore(board.db);
    runs.create(id, project, spec, OWNER, "build", "claude-code", "b", "t", null, null, "/l", "u");
    runs.complete(id, "completed", 0);
  }

  @Test
  void refusalsSpeakTheTextsClientsSee() {
    assertEquals(
        new Refusal(
            Kind.NOT_PRUNABLE,
            "Spec 'auth' is in_progress: only archived, cancelled or deleted specs are pruned.",
            Remedy.of(
                "Archive it first: ",
                Remedy.sail("spec update", "auth", "--status", "archived"),
                ".")),
        refused(rule.spec(ADMIN, spec(SpecStatus.IN_PROGRESS, OWNER, OWNER, true))).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.READ_ONLY,
            "Your credential is read-only and cannot prune specs.",
            "Ask an admin for a member or admin credential."),
        Access.atAll(VIEWER, Action.PRUNE, Resource.Kind.SPEC).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.LANE_FORBIDDEN,
            "Agent principals cannot prune specs.",
            "Only an operator lane (CLI or API token) may do this."),
        Access.atAll(AGENT, Action.PRUNE, Resource.Kind.SPEC).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.ADMIN_ONLY,
            "Pruning by policy or a whole project is admin-only.",
            Remedy.of("Name the specs you own by id: ", Remedy.sail("spec prune", "<id...>"), ".")),
        rule.purge(OWNER_API).orElseThrow());
    assertEquals(
        "Spec 'auth' is assigned to 'ada', not you.",
        refused(rule.spec(OTHER_API, spec(SpecStatus.ARCHIVED, OWNER, OWNER, true)))
            .orElseThrow()
            .message());
  }

  private static Optional<Refusal> refused(Decision decision) {
    return decision instanceof Decision.Refused refused
        ? Optional.of(refused.refusal())
        : Optional.empty();
  }
}
