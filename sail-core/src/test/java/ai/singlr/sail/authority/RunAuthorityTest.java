/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.identity.Actors.ADMIN;
import static ai.singlr.sail.identity.Actors.ADMIN_SYNC;
import static ai.singlr.sail.identity.Actors.AGENT;
import static ai.singlr.sail.identity.Actors.AGENT_HANDLE;
import static ai.singlr.sail.identity.Actors.MACHINE;
import static ai.singlr.sail.identity.Actors.MAIN;
import static ai.singlr.sail.identity.Actors.OTHER;
import static ai.singlr.sail.identity.Actors.OTHERS_AGENT;
import static ai.singlr.sail.identity.Actors.OTHER_API;
import static ai.singlr.sail.identity.Actors.OTHER_RUN;
import static ai.singlr.sail.identity.Actors.OTHER_SYNC;
import static ai.singlr.sail.identity.Actors.OWNER;
import static ai.singlr.sail.identity.Actors.OWNER_API;
import static ai.singlr.sail.identity.Actors.OWNER_CLI;
import static ai.singlr.sail.identity.Actors.OWNER_SYNC;
import static ai.singlr.sail.identity.Actors.ROOM;
import static ai.singlr.sail.identity.Actors.ROOM_HANDLE;
import static ai.singlr.sail.identity.Actors.RUN;
import static ai.singlr.sail.identity.Actors.SYSTEM;
import static ai.singlr.sail.identity.Actors.VIEWER;
import static ai.singlr.sail.identity.Actors.VIEWER_SYNC;
import static ai.singlr.sail.identity.Actors.projection;
import static ai.singlr.sail.identity.Actors.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Refusal.Kind;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.RunStore;
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
 * The run rule's matrix: on every lane a run is created by an admin or for the owner of the spec or
 * room it names; on this box's lanes a run is its owners' or an admin's, and its own principal may
 * report its session even read-only; on the sync lane it is only ever its executing box's, acting
 * for that box's FDE, never brought back once deleted; on every lane it carries no principal but
 * its own and never moves to another spec or room.
 */
class RunAuthorityTest {

  private static final String NEW_RUN = "019fee00-0000-7000-8000-0000000000e1";
  private static final Map<String, Object> RUNNING =
      projection(
          "project",
          "acme",
          "spec_id",
          "auth",
          "room_id",
          null,
          "node",
          OWNER,
          "owner",
          OWNER,
          "role",
          "build",
          "status",
          "running",
          "principal",
          AGENT_HANDLE,
          "session_id",
          null,
          "principals",
          List.of(AGENT_HANDLE, ROOM_HANDLE));

  private static final Map<String, Object> MARKED = Map.of("_actor", OWNER);

  private Board board;
  private RunAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    board.spec("auth", "auth", "carol", "carol");
    board.spec("own", "own", OWNER, OWNER);
    board.spec("open", "open", null, "carol");
    rule = new RunAuthority(board.db);
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(
      String name,
      Actor actor,
      String id,
      Map<String, Object> held,
      Map<String, Object> next,
      Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Case row(
      String name, Actor actor, Map<String, Object> held, Map<String, Object> next, Kind kind) {
    return new Case(name, actor, RUN, held, next, kind);
  }

  static Stream<Case> matrix() {
    var stopped = with(RUNNING, "status", "stopped");
    var session = with(RUNNING, "session_id", "s-1");
    var carol = new Actor("carol", Role.MEMBER, Actor.Lane.API);
    var born = with(RUNNING, "spec_id", "own");
    var specless = with(RUNNING, "spec_id", null);
    var fresh =
        with(
            with(with(born, "principal", "claude/" + NEW_RUN), "principals", List.of()),
            "_actor",
            "claude/" + NEW_RUN);
    return Stream.of(
        row("a deletion of a run this box holds no head of", OTHER_API, null, null, null),
        row("an admin stops it", ADMIN, RUNNING, stopped, null),
        row("the FDE it acts for stops it", OWNER_API, RUNNING, stopped, null),
        row("the operator stops it", OWNER_CLI, RUNNING, stopped, null),
        row("its agent reports on it", AGENT, RUNNING, stopped, null),
        row("its own principal deletes it as a change, its FDE's", AGENT, RUNNING, null, null),
        row("its spec's owner stops it", carol, RUNNING, stopped, null),
        row("main's stop is decided", MAIN, RUNNING, stopped, null),
        row("the machinery completes it", SYSTEM, RUNNING, stopped, null),
        row("another member cannot", OTHER_API, RUNNING, stopped, Kind.NOT_OWNER),
        row("another FDE's agent cannot", OTHERS_AGENT, RUNNING, stopped, Kind.NOT_OWNER),
        row("a machine credential cannot", MACHINE, RUNNING, stopped, Kind.NOT_OWNER),
        row("a viewer cannot", VIEWER, RUNNING, stopped, Kind.READ_ONLY),
        row("its room principal reports its session", ROOM, RUNNING, session, null),
        row("its room principal cannot stop it", ROOM, RUNNING, stopped, Kind.READ_ONLY),
        row(
            "a viewer's session report is not its principal's",
            VIEWER,
            RUNNING,
            session,
            Kind.READ_ONLY),
        row("a local create on its creator's spec", OWNER_CLI, null, born, null),
        row("a local create on another FDE's spec", OWNER_CLI, null, RUNNING, Kind.NOT_OWNER),
        row("an agent creates on its FDE's spec", AGENT, null, born, null),
        row(
            "a create on a spec no one is assigned is its own FDE's, a claim on its way",
            OWNER_CLI,
            null,
            with(RUNNING, "spec_id", "open"),
            null),
        row(
            "a box pushes a birth ahead of its claim of a spec no one is assigned",
            OWNER_SYNC,
            null,
            with(RUNNING, "spec_id", "open"),
            null),
        row("an admin creates on anyone's spec", ADMIN, null, RUNNING, null),
        row("the machinery creates on anyone's spec", SYSTEM, null, RUNNING, null),
        row(
            "a create on a spec this box holds nothing of is its own FDE's",
            OWNER_CLI,
            null,
            with(RUNNING, "spec_id", "unheard"),
            null),
        row(
            "a create for another FDE on a spec this box holds nothing of",
            OTHER_API,
            null,
            with(RUNNING, "spec_id", "unheard"),
            Kind.NOT_OWNER),
        row(
            "a create in its creator's room",
            OWNER_CLI,
            null,
            with(specless, "room_id", "lobby"),
            null),
        row(
            "a create in another FDE's room",
            OWNER_CLI,
            null,
            with(specless, "room_id", "den"),
            Kind.NOT_OWNER),
        row(
            "a create in a room this box holds nothing of is its own FDE's",
            OWNER_CLI,
            null,
            with(specless, "room_id", "unheard"),
            null),
        row(
            "a create naming its own spec and another FDE's room",
            OWNER_CLI,
            null,
            with(born, "room_id", "den"),
            Kind.FIXED),
        row(
            "a box cannot push a birth naming its own spec and another FDE's room",
            OWNER_SYNC,
            null,
            with(born, "room_id", "den"),
            Kind.FIXED),
        row(
            "an admin creates none naming a spec and a room, its own included",
            ADMIN,
            null,
            with(born, "room_id", "lobby"),
            Kind.FIXED),
        row("a create naming no work is its own FDE's", OWNER_CLI, null, specless, null),
        row("a create naming no work for another FDE", OTHER_API, null, specless, Kind.NOT_OWNER),
        row(
            "a create naming no work and acting for no one is an admin's",
            OWNER_CLI,
            null,
            with(specless, "owner", null),
            Kind.NOT_OWNER),
        row("a viewer creates none", VIEWER, null, RUNNING, Kind.READ_ONLY),
        row("an admin deletes it", ADMIN, RUNNING, null, null),
        row("another member cannot delete it", OTHER_API, RUNNING, null, Kind.NOT_OWNER),
        row("its box pushes its progress", OWNER_SYNC, RUNNING, stopped, null),
        row("its box pushes its session", OWNER_SYNC, RUNNING, session, null),
        row("its box pushes its deletion", OWNER_SYNC, RUNNING, null, null),
        row("its box pushes its birth", OWNER_SYNC, null, born, null),
        row(
            "a box cannot push a birth on another FDE's spec",
            OWNER_SYNC,
            null,
            RUNNING,
            Kind.NOT_OWNER),
        row(
            "an admin's box pushes a birth on anyone's spec",
            ADMIN_SYNC,
            null,
            with(with(RUNNING, "node", OTHER), "owner", OTHER),
            null),
        row(
            "a box pushes a birth ahead of the spec it made",
            OWNER_SYNC,
            null,
            with(RUNNING, "spec_id", "unheard"),
            null),
        row(
            "a box pushes a birth in its own room",
            OWNER_SYNC,
            null,
            with(specless, "room_id", "lobby"),
            null),
        row(
            "a box cannot push a birth in another FDE's room",
            OWNER_SYNC,
            null,
            with(specless, "room_id", "den"),
            Kind.NOT_OWNER),
        row("a box pushes a birth naming no work", OWNER_SYNC, null, specless, null),
        row(
            "its box cannot push a birth acting for no one",
            OWNER_SYNC,
            null,
            with(born, "owner", null),
            Kind.NOT_AUTHOR),
        row(
            "its box cannot push progress acting for no one",
            OWNER_SYNC,
            with(born, "owner", null),
            with(with(born, "status", "stopped"), "owner", null),
            Kind.NOT_AUTHOR),
        row("another box cannot push it", OTHER_SYNC, RUNNING, stopped, Kind.NOT_OWNER),
        row("an admin's box cannot push it", ADMIN_SYNC, RUNNING, stopped, Kind.NOT_OWNER),
        row("another box cannot delete it", OTHER_SYNC, RUNNING, null, Kind.NOT_OWNER),
        row(
            "another box cannot re-stamp it",
            OTHER_SYNC,
            RUNNING,
            with(stopped, "node", OTHER),
            Kind.NOT_OWNER),
        row(
            "its box cannot hand it to another",
            OWNER_SYNC,
            RUNNING,
            with(stopped, "node", OTHER),
            Kind.NOT_OWNER),
        row("a box cannot push a birth on another's", OTHER_SYNC, null, RUNNING, Kind.NOT_OWNER),
        row(
            "a box cannot push an unstamped birth",
            OWNER_SYNC,
            null,
            with(RUNNING, "node", null),
            Kind.NOT_OWNER),
        row(
            "a box's run acts for no other FDE",
            OWNER_SYNC,
            null,
            with(born, "owner", OTHER),
            Kind.NOT_AUTHOR),
        row("a viewer's box pushes none", VIEWER_SYNC, RUNNING, stopped, Kind.READ_ONLY),
        row(
            "a viewer is told its role before a principal naming an FDE",
            VIEWER_SYNC,
            RUNNING,
            with(stopped, "principal", OTHER),
            Kind.READ_ONLY),
        row(
            "a viewer is told its role before a run's fixed spec",
            VIEWER,
            RUNNING,
            with(stopped, "spec_id", "other"),
            Kind.READ_ONLY),
        row(
            "a principal naming an FDE",
            OWNER_SYNC,
            RUNNING,
            with(stopped, "principal", OTHER),
            Kind.NOT_AUTHOR),
        row(
            "principals naming another run",
            OWNER_SYNC,
            RUNNING,
            with(stopped, "principals", List.of(AGENT_HANDLE, "claude/" + OTHER_RUN)),
            Kind.NOT_AUTHOR),
        row(
            "an admin's principal naming an FDE",
            ADMIN,
            RUNNING,
            with(stopped, "principal", OWNER),
            Kind.NOT_AUTHOR),
        row(
            "a local birth naming another run",
            OWNER_CLI,
            null,
            with(born, "principal", "claude/review-" + OTHER_RUN),
            Kind.NOT_AUTHOR),
        row(
            "a birth on another FDE's spec is told whose it is before what it names",
            OWNER_CLI,
            null,
            with(RUNNING, "principal", "claude/review-" + OTHER_RUN),
            Kind.NOT_OWNER),
        row(
            "a push of another box's run is told whose it is before who wrote it",
            OTHER_SYNC,
            RUNNING,
            with(stopped, "_actor", OWNER),
            Kind.NOT_OWNER),
        row(
            "a review principal of its own",
            OWNER_SYNC,
            RUNNING,
            with(stopped, "principals", List.of("codex/review-" + RUN, "claude/fix-" + RUN)),
            null),
        row(
            "an admin cannot move it to another spec",
            ADMIN,
            RUNNING,
            with(stopped, "spec_id", "other"),
            Kind.FIXED),
        row(
            "its box cannot move it to another room",
            OWNER_SYNC,
            RUNNING,
            with(stopped, "room_id", "lobby"),
            Kind.FIXED),
        row(
            "a push authored by another FDE",
            OWNER_SYNC,
            RUNNING,
            with(stopped, "_actor", OTHER),
            Kind.NOT_AUTHOR),
        row(
            "a push authored by its own principal",
            OWNER_SYNC,
            RUNNING,
            with(session, "_actor", ROOM_HANDLE),
            null),
        new Case("a birth authored by its own principal", OWNER_SYNC, NEW_RUN, null, fresh, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), row.id(), row.held(), row.next()).map(Refusal::kind));
  }

  /**
   * A scoped member's marked deletion of a run this box holds no head of, asked as the journal and
   * main's commit ask it, behind the scope rule: it names no project, so the scope rule lets it
   * through, and it is decided as main decided it, a birth naming no work, refused as an admin's,
   * never thrown as a write outside the actor's audience.
   */
  @Test
  void aScopedMembersMarkedDeletionOfARunThisBoxHoldsNoHeadOfIsAnAdminsAsOnMain() {
    var scoped = ProjectScope.of(List.of("acme"));
    var adHoc =
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Run run-x names no spec and no one it acts for.",
                "Only an admin may access this run."));
    var scoping = new ScopeRule(new ChangeLog(board.db), "run").before(rule);

    assertEquals(adHoc, scoping.decide(OWNER_API.withScope(scoped), "run-x", null, MARKED));
    assertEquals(adHoc, scoping.decide(OWNER_SYNC.withScope(scoped), "run-x", null, MARKED));
    assertEquals(Optional.empty(), scoping.decide(ADMIN.withScope(scoped), "run-x", null, MARKED));
  }

  /**
   * The same deletion pushed to main, of a run whose row main holds with no head — the one such
   * deletion main's commit decides rather than takes as changing nothing — is denied there as main
   * denied it.
   */
  @Test
  void mainsCommitDeniesAScopedNodesMarkedDeletionOfARunItHoldsNoHeadOf() {
    var runs = new RunStore(board.db);
    board.db.execute(
        "DELETE FROM change_heads WHERE entity_type = 'run' AND entity_id = ?", OTHER_RUN);

    var outcome =
        Actor.call(
            OWNER_SYNC.withScope(ProjectScope.of(List.of("acme"))),
            () -> runs.commitRevision(OTHER_RUN, MARKED, null, runs.authority()));

    assertEquals(
        "Run " + OTHER_RUN + " names no spec and no one it acts for.",
        assertInstanceOf(PushOutcome.Denied.class, outcome).reason());
  }

  /**
   * Only one of a run's own principals narrates its session; anyone else's session report is a
   * change of the run, refused as one.
   */
  @Test
  void aSessionReportByAnyoneButTheRunsOwnPrincipalIsAChange() {
    var session = with(RUNNING, "session_id", "s-1");

    assertEquals(
        Optional.of(Refusal.readOnly("change runs")), rule.decide(VIEWER, RUN, RUNNING, session));
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Run " + RUN + " belongs to spec 'auth', owned by 'ada' and 'carol', not you.",
                "Only ada or carol or an admin may access this run.")),
        rule.decide(OTHERS_AGENT, RUN, RUNNING, session));
  }

  /** A run's principals are its own, said before who a push may not write as. */
  @Test
  void aForeignPrincipalIsSaidBeforeWhoAPushMayNotWriteAs() {
    var stopped = with(RUNNING, "status", "stopped");

    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_AUTHOR,
                "Run '" + RUN + "' names '" + OTHER + "' as its principal, which is not its own.",
                "A run's principals are the ones minted for it.")),
        rule.decide(
            OWNER_SYNC, RUN, RUNNING, with(with(stopped, "principal", OTHER), "_actor", OTHER)));
  }

  @Test
  void aDeletedRunIsNeverBroughtBack() {
    var runs = new RunStore(board.db);
    var held = Acting.system(() -> runs.comparableSnapshot(RUN));
    Acting.system(() -> runs.applyRevision(RUN, null, runs.latestRev(RUN) + "-tombstone"));

    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Run '" + RUN + "' was deleted, and a deleted run cannot be brought back.",
                Remedy.NONE)),
        rule.decide(OWNER_SYNC, RUN, held, held));
  }

  @Test
  void aBirthNamingASpecAndARoomSpeaksTheTextClientsSee() {
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.FIXED,
                "Run '" + RUN + "' names both spec 'own' and room 'den', and a run works one.",
                "Name a room only on a run that works no spec.")),
        rule.decide(
            OWNER_SYNC, RUN, null, with(with(RUNNING, "spec_id", "own"), "room_id", "den")));
  }

  @Test
  void aSpeclessRunIsOwnedByTheBoxThatRanIt() {
    var speclessHeld = with(with(RUNNING, "spec_id", null), "owner", null);

    assertEquals(
        List.of(OWNER),
        Resources.run(RUN, "acme", null, null, OWNER, spec -> Optional.of("carol")).owners());
    assertEquals(
        Optional.empty(),
        rule.decide(OWNER_API, RUN, speclessHeld, with(speclessHeld, "status", "x")));
    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(
                new Actor("carol", Role.MEMBER, Actor.Lane.API),
                RUN,
                speclessHeld,
                with(speclessHeld, "status", "x"))
            .map(Refusal::kind));
  }

  @Test
  void aRunIsOwnedByItsFdeAndItsSpecsOwnerOrTheBoxThatRanIt() {
    assertEquals(
        List.of("uday", "raj"),
        Resources.run(RUN, "acme", "uday", "auth", "box", spec -> Optional.of("raj")).owners(),
        "its own FDE may still reach it after its spec moved");
    assertEquals(
        List.of("uday", "box"),
        Resources.run(RUN, "acme", "uday", null, "box", spec -> Optional.empty()).owners());
    assertEquals(
        List.of("box"),
        Resources.run(RUN, "acme", null, " ", "box", spec -> Optional.empty()).owners());
    assertEquals(
        List.of(),
        Resources.run(RUN, "acme", " ", "auth", "box", spec -> Optional.empty()).owners());
    assertEquals(
        List.of("uday"),
        Resources.run(RUN, "acme", "uday", "auth", "box", spec -> Optional.of("uday")).owners());
  }

  @Test
  void aRowsOwnersAreItsRunsAsTheDoorsSeeItsSpec() {
    var run = new RunStore(board.db).findById(RUN).orElseThrow();

    assertEquals(
        List.of(OWNER, "carol"),
        Resources.run(
                run.id(),
                run.project(),
                run.owner(),
                run.specId(),
                run.node(),
                spec -> Optional.of("carol"))
            .owners());
    assertEquals(
        List.of(OWNER),
        Resources.run(
                run.id(),
                run.project(),
                run.owner(),
                run.specId(),
                run.node(),
                spec -> Optional.empty())
            .owners());
  }
}
