/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static ai.singlr.sail.access.AccessTest.Outcome.ADMIN_ONLY;
import static ai.singlr.sail.access.AccessTest.Outcome.ALLOWED;
import static ai.singlr.sail.access.AccessTest.Outcome.LANE;
import static ai.singlr.sail.access.AccessTest.Outcome.OWNED;
import static ai.singlr.sail.access.AccessTest.Outcome.OWN_WORK;
import static ai.singlr.sail.access.AccessTest.Outcome.READ_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Actors;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A1, A4 and A12: every action, asked by every kind of principal on every lane a person or an agent
 * comes in by, inside and outside its scope, on work it owns and work it does not, gets today's
 * answer, and every refusal names its fix. {@link Actors#OWNER} owns the {@code own} resource of
 * each row and {@link Actors#OTHER} the {@code others} one; both are filed under {@code acme}.
 */
class AccessTest {

  private static final String READ_ONLY_FIX = "Ask an admin for a member or admin credential.";
  private static final String LANE_FIX = "Only an operator lane (CLI or API token) may do this.";

  private static final Resource SPEC_ADA = Resources.spec("auth", "acme", "ada", "ada");
  private static final Resource SPEC_BOB = Resources.spec("auth", "acme", "bob", "bob");
  private static final Resource UNASSIGNED_ADA = Resources.spec("auth", "acme", null, "ada");
  private static final Resource UNASSIGNED_BOB = Resources.spec("auth", "acme", null, "bob");
  private static final Resource LOBBY = Resources.conversation("lobby", "acme", List.of("ada"));
  private static final Resource DEN = Resources.conversation("den", "acme", List.of("bob"));
  private static final Resource REVIEW_ADA = Resources.review("rev-1", "acme", "auth", "ada");
  private static final Resource REVIEW_BOB = Resources.review("rev-1", "acme", "auth", "bob");
  private static final Resource RUN_ADA =
      Resources.run(Actors.RUN, "acme", "ada", "auth", "ada", spec -> Optional.of("ada"));
  private static final Resource RUN_BOB =
      Resources.run(Actors.OTHER_RUN, "acme", "bob", "auth", "bob", spec -> Optional.of("bob"));
  private static final Resource PROJECT_ADA = Resources.project("acme", "ada");
  private static final Resource PROJECT_BOB = Resources.project("acme", "bob");
  private static final Resource BOX_ADA = Resources.box("ada");
  private static final Resource BOX_BOB = Resources.box("bob");
  private static final Resource PEOPLE = Resources.people();

  private static final Refusal SPEC_NOT_OWNER =
      refusal(
          Refusal.Kind.NOT_OWNER,
          "Spec 'auth' is assigned to 'bob', not you.",
          "Ask bob to make this change, or have an admin do it.");
  private static final Refusal SPEC_NOT_DISPATCHER =
      refusal(
          Refusal.Kind.NOT_OWNER,
          "Spec 'auth' is assigned to 'bob', not you.",
          "Ask bob to dispatch it, or have an admin reassign it before dispatching.");
  private static final Refusal RUN_NOT_OWNER =
      refusal(
          Refusal.Kind.NOT_OWNER,
          "Run " + Actors.OTHER_RUN + " belongs to spec 'auth', owned by 'bob', not you.",
          "Only bob or an admin may access this run.");

  /** What a column answers, on the resource its row's owner owns and on the other's. */
  enum Outcome {
    /** Allowed on both. */
    ALLOWED,
    /** Allowed on its own resource, refused on the other's with the row's refusal. */
    OWNED,
    /** Allowed on its own work, refused on the other's as outside its work. */
    OWN_WORK,
    /** Refused on both: read-only. */
    READ_ONLY,
    /** Refused on both with the row's admin-only refusal. */
    ADMIN_ONLY,
    /** Refused on both: the agent's lane may never. */
    LANE
  }

  /**
   * One action's expected answers, a column each in {@link Policy.Column} order up to the room
   * agent: {@code act} is how its refusals word it, {@code refusal} its admin-only refusal or its
   * refusal of the other's resource, and {@code outsideWork} an agent's refusal of work not its
   * own.
   */
  record Expected(
      Action action,
      Resource own,
      Resource others,
      String act,
      List<Outcome> outcomes,
      Refusal refusal,
      Function<Actor, Refusal> outsideWork) {}

  record Case(String name, Actor actor, Action action, Resource resource, Decision expected) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static final List<Expected> EXPECTED =
      List.of(
          new Expected(
              Action.READ,
              SPEC_ADA,
              SPEC_BOB,
              "read specs",
              List.of(ALLOWED, ALLOWED, ALLOWED, ALLOWED, ALLOWED),
              null,
              null),
          new Expected(
              Action.READ_DEFINITION,
              PROJECT_ADA,
              PROJECT_BOB,
              "read a project's definition, which holds its services' secrets",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, READ_ONLY),
              null,
              null),
          new Expected(
              Action.POST,
              LOBBY,
              DEN,
              "post in rooms",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, OWN_WORK),
              null,
              actor ->
                  refusal(
                      Refusal.Kind.NOT_OWNER,
                      "'den' belongs to bob: only bob or an admin may post there, not you.",
                      "Ask bob to post it, or have an admin do it.")),
          new Expected(
              Action.CREATE,
              SPEC_ADA,
              SPEC_BOB,
              "create specs",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, READ_ONLY),
              null,
              null),
          new Expected(
              Action.CHANGE,
              SPEC_ADA,
              SPEC_BOB,
              "change specs",
              List.of(READ_ONLY, OWNED, ALLOWED, OWNED, READ_ONLY),
              SPEC_NOT_OWNER,
              null),
          new Expected(
              Action.CLAIM,
              UNASSIGNED_ADA,
              UNASSIGNED_BOB,
              "claim specs",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, READ_ONLY),
              null,
              null),
          new Expected(
              Action.ASSIGN,
              SPEC_BOB,
              SPEC_BOB,
              "assign specs",
              List.of(READ_ONLY, ADMIN_ONLY, ALLOWED, ADMIN_ONLY, READ_ONLY),
              refusal(
                  Refusal.Kind.ADMIN_ONLY,
                  "Reassigning spec 'auth' moves work between FDEs and is an admin-only action"
                      + " (currently 'bob').",
                  "Ask an admin to reassign it. You may grab a spec only while it is unassigned."),
              null),
          new Expected(
              Action.APPROVE,
              REVIEW_ADA,
              REVIEW_BOB,
              "approve reviews",
              List.of(READ_ONLY, OWNED, ALLOWED, OWNED, READ_ONLY),
              refusal(
                  Refusal.Kind.NOT_OWNER,
                  "Review rev-1 is for spec 'auth', owned by 'bob', not you.",
                  "Only bob or an admin may approve or dismiss it."),
              null),
          new Expected(
              Action.DISPATCH,
              SPEC_ADA,
              SPEC_BOB,
              "dispatch specs",
              List.of(READ_ONLY, OWNED, ALLOWED, LANE, READ_ONLY),
              SPEC_NOT_DISPATCHER,
              null),
          new Expected(
              Action.OPERATE_RUN,
              RUN_ADA,
              RUN_BOB,
              "stop runs",
              List.of(READ_ONLY, OWNED, ALLOWED, LANE, READ_ONLY),
              RUN_NOT_OWNER,
              null),
          new Expected(
              Action.NARRATE,
              RUN_ADA,
              RUN_BOB,
              "narrate runs",
              List.of(READ_ONLY, OWNED, ALLOWED, OWN_WORK, OWN_WORK),
              RUN_NOT_OWNER,
              actor ->
                  refusal(
                      Refusal.Kind.NOT_OWNER,
                      "'"
                          + actor.handle()
                          + "' may narrate only its own work, not run '"
                          + Actors.OTHER_RUN
                          + "'.",
                      "An agent acts only on the work sail launched it for.")),
          new Expected(
              Action.SHARE,
              PROJECT_ADA,
              PROJECT_BOB,
              "share files and skills in projects",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, READ_ONLY),
              null,
              null),
          new Expected(
              Action.SERVE,
              PROJECT_ADA,
              PROJECT_BOB,
              "start or stop a project's processes",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, READ_ONLY),
              null,
              null),
          new Expected(
              Action.RESTORE,
              PROJECT_ADA,
              PROJECT_BOB,
              "restore or delete snapshots",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, READ_ONLY),
              null,
              null),
          new Expected(
              Action.CONFIGURE,
              PROJECT_ADA,
              PROJECT_BOB,
              "configure projects",
              List.of(READ_ONLY, ALLOWED, ALLOWED, ALLOWED, READ_ONLY),
              null,
              null),
          new Expected(
              Action.PRUNE,
              SPEC_ADA,
              SPEC_BOB,
              "prune specs",
              List.of(READ_ONLY, OWNED, ALLOWED, LANE, READ_ONLY),
              SPEC_NOT_OWNER,
              null),
          new Expected(
              Action.PURGE,
              PROJECT_BOB,
              PROJECT_BOB,
              "purge projects",
              List.of(READ_ONLY, ADMIN_ONLY, ALLOWED, ADMIN_ONLY, READ_ONLY),
              new Refusal(
                  Refusal.Kind.ADMIN_ONLY,
                  "Only an admin may erase the name of project 'acme' everywhere, as purging or"
                      + " renaming it does.",
                  "Ask an admin to do it."),
              null),
          new Expected(
              Action.MANAGE_PEOPLE,
              PEOPLE,
              PEOPLE,
              "manage people",
              List.of(READ_ONLY, ADMIN_ONLY, ALLOWED, ADMIN_ONLY, READ_ONLY),
              refusal(
                  Refusal.Kind.ADMIN_ONLY,
                  "Only an admin may manage people.",
                  "Ask an admin to do it."),
              null),
          new Expected(
              Action.OPERATE_BOX,
              BOX_ADA,
              BOX_BOB,
              "operate boxes",
              List.of(READ_ONLY, OWNED, ALLOWED, LANE, READ_ONLY),
              refusal(
                  Refusal.Kind.NOT_OWNER,
                  "Box 'bob' belongs to 'bob', not you.",
                  "Ask bob to operate it, or have an admin do it."),
              null));

  /** The principals of each column, on every lane its kind comes in by. */
  private static final List<List<Actor>> COLUMNS =
      List.of(
          List.of(Actors.VIEWER, Actors.VIEWER_SYNC),
          List.of(Actors.OWNER_API, Actors.OWNER_CLI, Actors.OWNER_SYNC),
          List.of(Actors.ADMIN, Actors.ADMIN_SYNC),
          List.of(Actors.AGENT),
          List.of(Actors.ROOM));

  private static final Map<String, ProjectScope> SCOPES =
      Map.of(
          "every project", ProjectScope.all(),
          "acme granted", ProjectScope.of(List.of("acme")),
          "only beta", ProjectScope.of(List.of("beta")));

  static Stream<Case> matrix() {
    var cases = new ArrayList<Case>();
    for (var row : EXPECTED) {
      for (var column = 0; column < COLUMNS.size(); column++) {
        for (var principal : COLUMNS.get(column)) {
          for (var scope : SCOPES.entrySet()) {
            var actor = principal.withScope(scope.getValue());
            var outcome = row.outcomes().get(column);
            add(cases, row, actor, scope.getKey(), "own", row.own(), outcome);
            add(cases, row, actor, scope.getKey(), "others'", row.others(), outcome);
          }
        }
      }
      for (var sail : List.of(Actors.MAIN, Actors.SYSTEM)) {
        for (var resource : List.of(row.own(), row.others())) {
          var actor = sail.withScope(ProjectScope.none());
          cases.add(
              new Case(
                  row.action() + " by " + sail.lane() + " seeing nothing",
                  actor,
                  row.action(),
                  resource,
                  new Decision.Allowed()));
        }
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void everyActionGetsTodaysAnswerFromEveryPrincipal(Case c) {
    assertEquals(c.expected(), Access.decide(c.actor(), c.action(), c.resource()));
  }

  @Test
  void everyRefusalNamesItsFix() {
    var principals =
        List.of(
            Actors.ADMIN,
            Actors.OWNER_API,
            Actors.OTHER_API,
            Actors.VIEWER,
            Actors.MACHINE,
            Actors.AGENT,
            Actors.OTHERS_AGENT,
            Actors.ROOM,
            Actors.OWNER_SYNC,
            Actors.VIEWER_SYNC,
            new Actor(Actors.AGENT_HANDLE, Role.VIEWER, Actor.Lane.AGENT, Actors.OWNER));
    var kinds = EnumSet.noneOf(Refusal.Kind.class);
    for (var row : EXPECTED) {
      for (var principal : principals) {
        for (var resource : List.of(row.own(), row.others())) {
          if (Access.decide(principal, row.action(), resource)
              instanceof Decision.Refused(var refusal)) {
            kinds.add(refusal.kind());
            assertFalse(Strings.isBlank(refusal.message()), row.action() + " " + principal);
            assertFalse(refusal.fix().isEmpty(), row.action() + " " + principal);
          }
        }
      }
    }
    assertEquals(
        EnumSet.of(
            Refusal.Kind.READ_ONLY,
            Refusal.Kind.ADMIN_ONLY,
            Refusal.Kind.LANE_FORBIDDEN,
            Refusal.Kind.NOT_OWNER),
        kinds);
  }

  @Test
  void aSpecBornInARoomTakesThatRoomsOwnersToBeCreatedOrClaimed() {
    var inDen = Resources.bornIn(Resources.spec("brief", "acme", null, "ada"), DEN);
    var seated = "'den' belongs to bob: only bob or an admin may start work there, not you.";

    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER, seated, "Ask bob to create it there, or have an admin do it."),
        Access.decide(Actors.OWNER_API, Action.CREATE, inDen));
    assertEquals(
        refused(Refusal.Kind.ADMIN_ONLY, seated, "Ask an admin to assign it to you."),
        Access.decide(Actors.OWNER_API, Action.CLAIM, inDen));
    assertEquals(new Decision.Allowed(), Access.decide(Actors.OWNER_API, Action.CHANGE, inDen));
    assertEquals(new Decision.Allowed(), Access.decide(Actors.ADMIN, Action.CLAIM, inDen));
    assertEquals(new Decision.Allowed(), Access.decide(Actors.OTHER_API, Action.CLAIM, inDen));
    assertEquals(
        new Decision.Allowed(),
        Access.decide(Actors.OWNER_API, Action.CREATE, Resources.bornIn(inDen, LOBBY)));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "No one owns 'den' yet, so only an admin may start work there.",
            "Have an admin create or assign the spec."),
        Access.decide(
            Actors.OWNER_API,
            Action.CREATE,
            Resources.bornIn(inDen, Resources.conversation("den", "acme", List.of()))));
  }

  @Test
  void aSpecIsDispatchedByItsAssigneeAloneWhereverItWasBornAndAnUnassignedOneIsNoOnes() {
    var inDen = Resources.bornIn(Resources.spec("brief", "acme", "ada", "ada"), DEN);
    var dispatched = Resources.dispatched("auth", "acme", "ada");

    assertEquals(new Decision.Allowed(), Access.decide(Actors.OWNER_API, Action.DISPATCH, inDen));
    assertEquals(
        new Decision.Allowed(), Access.decide(Actors.OWNER_API, Action.DISPATCH, dispatched));
    assertEquals(new Decision.Allowed(), Access.decide(Actors.ADMIN, Action.DISPATCH, dispatched));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Spec 'auth' is assigned to 'ada', not you.",
            "Ask ada to dispatch it, or have an admin reassign it before dispatching."),
        Access.decide(Actors.OTHER_API, Action.DISPATCH, dispatched));
    assertEquals(
        "The assignee of a dispatched spec must not be blank.",
        assertThrows(
                IllegalArgumentException.class, () -> Resources.dispatched("auth", "acme", " "))
            .getMessage());
  }

  @Test
  void whatNoOneHereCanOwnIsSailsAndAnAdminsToWriteAndRefusedByItsFactForEveryoneElse() {
    var fact = Refusal.noConversation("ghost");

    for (var overrules : List.of(Actors.SYSTEM, Actors.MAIN, Actors.ADMIN)) {
      assertEquals(Optional.empty(), Access.unowned(overrules, fact));
    }
    for (var bound : List.of(Actors.OWNER_API, Actors.AGENT, Actors.ROOM, Actors.VIEWER)) {
      assertEquals(Optional.of(fact), Access.unowned(bound, fact));
    }
  }

  @Test
  void aDecisionWithinTheAudienceIsEveryStepButTheAudience() {
    var outside = Actors.OTHER_API.withScope(ProjectScope.of(List.of("beta")));

    assertEquals(new Decision.NotFound(), Access.decide(outside, Action.CHANGE, SPEC_BOB));
    assertEquals(Optional.empty(), Access.withinAudience(outside, Action.CHANGE, SPEC_BOB));
    assertEquals(
        Optional.of(
            refusal(
                Refusal.Kind.NOT_OWNER,
                "Spec 'auth' is assigned to 'ada', not you.",
                "Ask ada to make this change, or have an admin do it.")),
        Access.withinAudience(outside, Action.CHANGE, SPEC_ADA));
    assertEquals(Optional.empty(), Access.withinAudience(Actor.system(), Action.PURGE, SPEC_ADA));
  }

  @Test
  void resolvingAConflictIsAChangeOfItAtAllSaidAsResolvingIt() {
    assertEquals(
        Optional.of(
            refusal(
                Refusal.Kind.READ_ONLY,
                "Your credential is read-only and cannot resolve conflicts.",
                READ_ONLY_FIX)),
        Access.atAll(Actors.VIEWER, Action.CHANGE, Resource.Kind.CONFLICT));
    assertEquals(
        Optional.empty(), Access.atAll(Actors.OTHER_API, Action.CHANGE, Resource.Kind.CONFLICT));
  }

  @Test
  void seatingAgentsInARoomIsSaidAsManagingItsMembership() {
    assertEquals(
        refused(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot manage room membership.",
            READ_ONLY_FIX),
        Access.decide(Actors.VIEWER, Action.DISPATCH, LOBBY));
    assertEquals(
        refused(
            Refusal.Kind.LANE_FORBIDDEN,
            "Agent principals cannot manage room membership.",
            LANE_FIX),
        Access.decide(Actors.AGENT, Action.DISPATCH, LOBBY));
  }

  @Test
  void aRunsSpecIsNoRoomSoItsOwnerDispatchesWorkOnIt() {
    assertEquals(
        new Decision.Allowed(),
        Access.decide(
            new Actor("eng", Role.MEMBER, Actor.Lane.API),
            Action.DISPATCH,
            Resources.launched("r1", "acme", "s1", "eng")));
  }

  @Test
  void anUnassignedSpecIsItsCreatorsAndNamesThem() {
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Spec 'auth' is unassigned; only bob or an admin may change it.",
            "Have an admin change it, or claim it first with --assignee <you>."),
        Access.decide(Actors.OWNER_API, Action.CHANGE, UNASSIGNED_BOB));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Spec 'auth' is unassigned; only its creator or an admin may change it.",
            "Have an admin change it, or claim it first with --assignee <you>."),
        Access.decide(Actors.OWNER_API, Action.CHANGE, Resources.spec("auth", "acme", "", null)));
    assertEquals(
        refused(
            Refusal.Kind.ADMIN_ONLY,
            "Reassigning spec 'auth' moves work between FDEs and is an admin-only action.",
            "Ask an admin to reassign it. You may grab a spec only while it is unassigned."),
        Access.decide(Actors.OWNER_API, Action.ASSIGN, UNASSIGNED_ADA));
  }

  @Test
  void aRunNamesTheWorkItDoesAndTheFdesItWorksFor() {
    var adHoc = Resources.run("r-1", "acme", "bob", null, "bob", spec -> Optional.empty());
    var nobodys = Resources.run("r-1", "acme", null, null, null, spec -> Optional.empty());
    var shared = Resources.run("r-1", "acme", "bob", "auth", "bob", spec -> Optional.of("cy"));
    var unowned = Resources.run("r-1", "acme", null, "auth", null, spec -> Optional.empty());
    var launched = Resources.launched("r-1", "acme", "auth", "bob");

    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Run r-1 is an ad-hoc session launched by 'bob', not you.",
            "Only bob or an admin may access this run."),
        Access.decide(Actors.OWNER_API, Action.OPERATE_RUN, adHoc));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Run r-1 names no spec and no one it acts for.",
            "Only an admin may access this run."),
        Access.decide(Actors.OWNER_API, Action.OPERATE_RUN, nobodys));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Run r-1 belongs to spec 'auth', owned by 'bob' and 'cy', not you.",
            "Only bob or cy or an admin may access this run."),
        Access.decide(Actors.OWNER_API, Action.OPERATE_RUN, shared));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Run r-1 belongs to spec 'auth', which has no owner.",
            "Only its owner or an admin may access this run."),
        Access.decide(Actors.OWNER_API, Action.OPERATE_RUN, unowned));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Run r-1 belongs to spec 'auth', owned by 'bob', not you.",
            "Only bob or an admin may access this run."),
        Access.decide(Actors.OWNER_API, Action.CHANGE, launched));
    assertEquals(new Decision.Allowed(), Access.decide(Actors.OTHER_API, Action.CHANGE, launched));
    assertEquals(List.of("bob"), Resources.launched("r-1", "acme", null, "bob").owners());
  }

  @Test
  void theAudienceAFilterReadsIsTheOneDecideReads() {
    var acme = ProjectScope.of(List.of("acme"));

    assertEquals(acme, Access.audience(Actors.VIEWER.withScope(acme)));
    assertEquals(ProjectScope.all(), Access.audience(Actors.SYSTEM.withScope(acme)));
    assertEquals(ProjectScope.all(), Access.audience(Actors.MAIN.withScope(ProjectScope.none())));
  }

  @Test
  void aReviewOfASpecNoOneOwnsIsAnAdminsToApprove() {
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Review rev-1 is for spec 'auth', which has no owner.",
            "Only the spec's owner or an admin may approve or dismiss it."),
        Access.decide(
            Actors.OWNER_API, Action.APPROVE, Resources.review("rev-1", "acme", "auth", null)));
  }

  @Test
  void aRoomsSettingsAreItsOwnersAndARoomNoOneOwnsIsAnAdmins() {
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Room 'lobby' belongs to 'bob', not you.",
            "Ask bob to change it, or have an admin do it."),
        Access.decide(Actors.OWNER_API, Action.CHANGE, Resources.room("lobby", "acme", "bob")));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Room 'lobby' has no owner, so only an admin may change it.",
            "Have an admin change it."),
        Access.decide(Actors.OWNER_API, Action.CHANGE, Resources.room("lobby", "acme", null)));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Room 'lobby' belongs to 'bob' or 'cy', not you.",
            "Ask bob or cy to change it, or have an admin do it."),
        Access.decide(
            Actors.OWNER_API,
            Action.CHANGE,
            Resources.conversation("lobby", "acme", List.of("bob", "cy"))));
  }

  @Test
  void aRoomAgentPostsOnlyWhereItsPersonOwnsTheConversation() {
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "No one owns 'den' yet, so only an admin may post there.",
            "Claim its spec first with --assignee <you>, or have an admin post."),
        Access.decide(Actors.ROOM, Action.POST, Resources.conversation("den", "acme", List.of())));
    assertEquals(
        new Decision.Allowed(),
        Access.decide(
            Actors.ROOM,
            Action.POST,
            Resources.conversation("den", "acme", List.of("bob", "ada"))));
  }

  @Test
  void anAgentNarratesOnlyItsOwnRunNotTheOtherRunsOfItsPerson() {
    var sibling = Actor.agentPrincipal("claude/" + Actors.OTHER_RUN, Actors.OWNER);

    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "'claude/"
                + Actors.OTHER_RUN
                + "' may narrate only its own work, not run '"
                + Actors.RUN
                + "'.",
            "An agent acts only on the work sail launched it for."),
        Access.decide(sibling, Action.NARRATE, RUN_ADA));
    assertEquals(new Decision.Allowed(), Access.decide(sibling, Action.CHANGE, RUN_ADA));
  }

  @Test
  void aWorkAgentIsCappedByItsRoleExceptOnItsOwnRun() {
    var demoted = new Actor(Actors.AGENT_HANDLE, Role.VIEWER, Actor.Lane.AGENT, Actors.OWNER);

    assertEquals(
        refused(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot post in rooms.",
            READ_ONLY_FIX),
        Access.decide(demoted, Action.POST, LOBBY));
    assertEquals(
        refused(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot dispatch specs.",
            READ_ONLY_FIX),
        Access.decide(demoted, Action.DISPATCH, SPEC_ADA));
    assertEquals(new Decision.Allowed(), Access.decide(demoted, Action.NARRATE, RUN_ADA));
  }

  @Test
  void whetherAnActorMayEverTakeAnActionIsItsRoleAndLaneAlone() {
    var unplaced = Actors.OTHER_API.withScope(ProjectScope.none());

    assertEquals(
        Optional.empty(),
        Access.atAll(unplaced, Action.PRUNE, Resource.Kind.SPEC),
        "no one resource is asked of, so neither its audience nor its owners");
    assertEquals(
        Optional.empty(), Access.atAll(Actor.system(), Action.PURGE, Resource.Kind.PROJECT));
    assertEquals(
        refusedAtAll(
            Refusal.Kind.NOT_OWNER,
            "'" + Actors.ROOM_HANDLE + "' acts only on its own work, and no room of it is named.",
            "An agent acts only on the work sail launched it for."),
        Access.atAll(Actors.ROOM, Action.POST, Resource.Kind.ROOM),
        "a lane's grant on its own work alone has no work in hand to be its own");
    assertEquals(
        refusedAtAll(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot prune specs.",
            READ_ONLY_FIX),
        Access.atAll(Actors.VIEWER, Action.PRUNE, Resource.Kind.SPEC));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.ADMIN_ONLY,
                "Pruning by policy or a whole project is admin-only.",
                Remedy.of(
                    "Name the specs you own by id: ", Remedy.sail("spec prune", "<id...>"), "."))),
        Access.atAll(Actors.OWNER_API, Action.PURGE, Resource.Kind.PROJECT));
    assertEquals(
        refusedAtAll(
            Refusal.Kind.ADMIN_ONLY, "Only an admin may assign specs.", "Ask an admin to do it."),
        Access.atAll(Actors.OWNER_API, Action.ASSIGN, Resource.Kind.SPEC));
    assertEquals(
        refusedAtAll(Refusal.Kind.LANE_FORBIDDEN, "Agent principals cannot prune specs.", LANE_FIX),
        Access.atAll(Actors.AGENT, Action.PRUNE, Resource.Kind.SPEC));
  }

  @Test
  void aMachineCredentialActsForNoFdeSoOwnsNothing() {
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Spec 'auth' is assigned to 'ada', not you.",
            "Ask ada to make this change, or have an admin do it."),
        Access.decide(Actors.MACHINE, Action.CHANGE, SPEC_ADA));
    assertEquals(new Decision.Allowed(), Access.decide(Actors.MACHINE, Action.CREATE, SPEC_ADA));
  }

  @Test
  void aBoxAndThePeopleAreFiledUnderNoProjectSoNoScopeHidesThem() {
    var unplaced = Actors.OWNER_API.withScope(ProjectScope.none());

    assertEquals(new Decision.Allowed(), Access.decide(unplaced, Action.OPERATE_BOX, BOX_ADA));
    assertEquals(
        refused(
            Refusal.Kind.ADMIN_ONLY, "Only an admin may manage people.", "Ask an admin to do it."),
        Access.decide(unplaced, Action.MANAGE_PEOPLE, PEOPLE));
  }

  @Test
  void aRowOfNoKnownProjectIsOnlyAnUnrestrictedScopes() {
    var placeless = Resources.spec("auth", null, "ada", "ada");

    assertEquals(
        new Decision.NotFound(),
        Access.decide(
            Actors.ADMIN.withScope(ProjectScope.of(List.of("acme"))), Action.READ, placeless));
    assertEquals(new Decision.Allowed(), Access.decide(Actors.VIEWER, Action.READ, placeless));
  }

  @Test
  void aResourceNamesEachOwnerOnceAndNeverBlank() {
    var resource =
        new Resource(
            Resource.Kind.SPEC,
            "auth",
            "acme",
            List.of("ada", " ", "ada", "bob"),
            null,
            null,
            null);

    assertEquals(List.of("ada", "bob"), resource.owners());
    assertThrows(
        NullPointerException.class,
        () -> new Resource(null, "auth", "acme", List.of(), null, null, null));
    assertEquals(
        "Only a spec is born in a room, not RUN r-1",
        assertThrows(
                IllegalArgumentException.class,
                () -> Resources.bornIn(Resources.launched("r-1", "acme", "auth", "ada"), DEN))
            .getMessage());
    assertThrows(NullPointerException.class, () -> new Decision.Refused(null));
    assertEquals(
        "A refusal names its fix: no.",
        assertThrows(
                IllegalArgumentException.class,
                () -> new Decision.Refused(new Refusal(Refusal.Kind.NOT_OWNER, "no.", Remedy.NONE)))
            .getMessage());
  }

  @Test
  void theOwnershipStepIsAnAdminsOrAnOwnersOrOneActingForAnOwnerReadOnlyIncluded() {
    assertEquals(
        Optional.empty(),
        owned(
            Actors.ADMIN,
            Resources.run("r1", "acme", null, "auth", null, spec -> Optional.empty())));
    assertEquals(
        Optional.empty(),
        owned(
            Actors.OWNER_API,
            Resources.run("r1", "acme", Actors.OWNER, "auth", null, spec -> Optional.of("carol"))));
    assertEquals(
        Optional.empty(),
        owned(
            Actors.VIEWER,
            Resources.run("r1", "acme", Actors.OWNER, "auth", null, spec -> Optional.of("carol"))));
    assertEquals(
        Optional.empty(),
        owned(
            Actors.ROOM,
            Resources.run("r1", "acme", Actors.OWNER, "auth", null, spec -> Optional.of("carol"))));
    assertEquals(
        Optional.of(Refusal.Kind.NOT_OWNER),
        owned(
                Actors.MACHINE,
                Resources.run(
                    "r1", "acme", Actors.OWNER, "auth", null, spec -> Optional.of("carol")))
            .map(Refusal::kind));
    assertEquals(
        Optional.of(Refusal.Kind.NOT_OWNER),
        owned(
                Actors.OTHERS_AGENT,
                Resources.run(
                    "r1", "acme", Actors.OWNER, "auth", null, spec -> Optional.of("carol")))
            .map(Refusal::kind));
  }

  @Test
  void theOwnershipStepOfARunSpeaksTheTextsClientsSee() {
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NOT_OWNER,
                "Run r1 belongs to spec 'auth', owned by 'ada' and 'carol', not you.",
                "Only ada or carol or an admin may access this run.")),
        owned(
            Actors.OTHER_API,
            Resources.run("r1", "acme", Actors.OWNER, "auth", null, spec -> Optional.of("carol"))));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NOT_OWNER,
                "Run r1 is an ad-hoc session launched by 'ada', not you.",
                "Only ada or an admin may access this run.")),
        owned(
            Actors.OTHER_API,
            Resources.run("r1", "acme", Actors.OWNER, null, null, spec -> Optional.empty())));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NOT_OWNER,
                "Run r1 belongs to spec 'auth', which has no owner.",
                "Only its owner or an admin may access this run.")),
        owned(
            Actors.OTHER_API,
            Resources.run("r1", "acme", null, "auth", null, spec -> Optional.empty())));
    assertEquals(
        "Run r1 names no spec and no one it acts for.",
        owned(
                Actors.OTHER_API,
                Resources.run("r1", "acme", null, null, null, spec -> Optional.empty()))
            .orElseThrow()
            .message());
    assertEquals(
        Optional.empty(),
        owned(
            Actors.AGENT,
            Resources.run("r1", "acme", Actors.OWNER, null, null, spec -> Optional.empty())));
  }

  private static Optional<Refusal> owned(Actor actor, Resource run) {
    return Access.ownsRun(actor, run);
  }

  private static void add(
      List<Case> cases,
      Expected row,
      Actor actor,
      String scope,
      String whose,
      Resource resource,
      Outcome outcome) {
    var name =
        row.action()
            + " by "
            + actor.role()
            + " on "
            + actor.lane()
            + " ("
            + scope
            + ") on "
            + whose
            + " "
            + resource.kind();
    var hidden = resource.kind().filed() && !actor.scope().admits(resource.project());
    var expected =
        hidden ? new Decision.NotFound() : expected(row, actor, resource == row.own(), outcome);
    cases.add(new Case(name, actor, row.action(), resource, expected));
  }

  private static Decision expected(Expected row, Actor actor, boolean own, Outcome outcome) {
    return switch (outcome) {
      case ALLOWED -> new Decision.Allowed();
      case OWNED -> own ? new Decision.Allowed() : new Decision.Refused(row.refusal());
      case OWN_WORK ->
          own ? new Decision.Allowed() : new Decision.Refused(row.outsideWork().apply(actor));
      case READ_ONLY ->
          refused(
              Refusal.Kind.READ_ONLY,
              "Your credential is read-only and cannot " + row.act() + ".",
              READ_ONLY_FIX);
      case ADMIN_ONLY -> new Decision.Refused(row.refusal());
      case LANE ->
          refused(
              Refusal.Kind.LANE_FORBIDDEN, "Agent principals cannot " + row.act() + ".", LANE_FIX);
    };
  }

  private static Refusal refusal(Refusal.Kind kind, String message, String fix) {
    return new Refusal(kind, message, fix);
  }

  private static Optional<Refusal> refusedAtAll(Refusal.Kind kind, String message, String fix) {
    return Optional.of(refusal(kind, message, fix));
  }

  private static Decision refused(Refusal.Kind kind, String message, String fix) {
    return new Decision.Refused(refusal(kind, message, fix));
  }
}
