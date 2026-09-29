/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.authority.Actors.ADMIN;
import static ai.singlr.sail.authority.Actors.ADMIN_SYNC;
import static ai.singlr.sail.authority.Actors.AGENT;
import static ai.singlr.sail.authority.Actors.AGENT_HANDLE;
import static ai.singlr.sail.authority.Actors.MACHINE;
import static ai.singlr.sail.authority.Actors.MAIN;
import static ai.singlr.sail.authority.Actors.OTHER;
import static ai.singlr.sail.authority.Actors.OTHERS_AGENT;
import static ai.singlr.sail.authority.Actors.OTHER_API;
import static ai.singlr.sail.authority.Actors.OTHER_RUN;
import static ai.singlr.sail.authority.Actors.OTHER_SYNC;
import static ai.singlr.sail.authority.Actors.OWNER;
import static ai.singlr.sail.authority.Actors.OWNER_API;
import static ai.singlr.sail.authority.Actors.OWNER_CLI;
import static ai.singlr.sail.authority.Actors.OWNER_SYNC;
import static ai.singlr.sail.authority.Actors.ROOM;
import static ai.singlr.sail.authority.Actors.ROOM_HANDLE;
import static ai.singlr.sail.authority.Actors.RUN;
import static ai.singlr.sail.authority.Actors.SYSTEM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static ai.singlr.sail.authority.Actors.projection;
import static ai.singlr.sail.authority.Actors.with;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
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
 * The run rule's matrix: on this box's lanes a run is its owners' or an admin's, and its own
 * principal may report its session even read-only; on the sync lane it is only ever its executing
 * box's, acting for that box's FDE, never brought back once deleted; on every lane it carries no
 * principal but its own and never moves to another spec or room.
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

  private Board board;
  private RunAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    board.spec("auth", "auth", "carol", "carol");
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
    var fresh =
        with(
            with(with(RUNNING, "principal", "claude/" + NEW_RUN), "principals", List.of()),
            "_actor",
            "claude/" + NEW_RUN);
    return Stream.of(
        row("an admin stops it", ADMIN, RUNNING, stopped, null),
        row("the FDE it acts for stops it", OWNER_API, RUNNING, stopped, null),
        row("the operator stops it", OWNER_CLI, RUNNING, stopped, null),
        row("its agent reports on it", AGENT, RUNNING, stopped, null),
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
        row("a local create is any writer's", OWNER_CLI, null, RUNNING, null),
        row("a viewer creates none", VIEWER, null, RUNNING, Kind.READ_ONLY),
        row("an admin deletes it", ADMIN, RUNNING, null, null),
        row("another member cannot delete it", OTHER_API, RUNNING, null, Kind.NOT_OWNER),
        row("its box pushes its progress", OWNER_SYNC, RUNNING, stopped, null),
        row("its box pushes its session", OWNER_SYNC, RUNNING, session, null),
        row("its box pushes its deletion", OWNER_SYNC, RUNNING, null, null),
        row("its box pushes its birth", OWNER_SYNC, null, RUNNING, null),
        row(
            "its box pushes a birth acting for no one",
            OWNER_SYNC,
            null,
            with(RUNNING, "owner", null),
            null),
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
            with(RUNNING, "owner", OTHER),
            Kind.NOT_AUTHOR),
        row("a viewer's box pushes none", VIEWER_SYNC, RUNNING, stopped, Kind.READ_ONLY),
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
            with(RUNNING, "principal", "claude/review-" + OTHER_RUN),
            Kind.NOT_AUTHOR),
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
                null)),
        rule.decide(OWNER_SYNC, RUN, held, held));
  }

  @Test
  void aSpeclessRunIsOwnedByTheBoxThatRanIt() {
    var speclessHeld = with(with(RUNNING, "spec_id", null), "owner", null);

    assertEquals(
        List.of(OWNER), RunAuthority.owners(null, null, OWNER, spec -> Optional.of("carol")));
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
        RunAuthority.owners("uday", "auth", "box", spec -> Optional.of("raj")),
        "its own FDE may still reach it after its spec moved");
    assertEquals(
        List.of("uday", "box"), RunAuthority.owners("uday", null, "box", spec -> Optional.empty()));
    assertEquals(List.of("box"), RunAuthority.owners(null, " ", "box", spec -> Optional.empty()));
    assertEquals(List.of(), RunAuthority.owners(" ", "auth", "box", spec -> Optional.empty()));
    assertEquals(
        List.of("uday"), RunAuthority.owners("uday", "auth", "box", spec -> Optional.of("uday")));
  }

  @Test
  void accessIsAnAdminsOrAnOwnersOrOneActingForAnOwnerReadOnlyIncluded() {
    var owners = List.of(OWNER, "carol");

    assertEquals(Optional.empty(), RunAuthority.access(ADMIN, "r1", "auth", List.of()));
    assertEquals(Optional.empty(), RunAuthority.access(OWNER_API, "r1", "auth", owners));
    assertEquals(Optional.empty(), RunAuthority.access(VIEWER, "r1", "auth", owners));
    assertEquals(Optional.empty(), RunAuthority.access(ROOM, "r1", "auth", owners));
    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        RunAuthority.access(MACHINE, "r1", "auth", owners).map(Refusal::kind));
    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        RunAuthority.access(OTHERS_AGENT, "r1", "auth", owners).map(Refusal::kind));
  }

  @Test
  void accessSpeaksTheTextsClientsSee() {
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Run r1 belongs to spec 'auth', owned by 'ada' and 'carol', not you.",
                "Only ada or carol or an admin may access this run.")),
        RunAuthority.access(OTHER_API, "r1", "auth", List.of(OWNER, "carol")));
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Run r1 is an ad-hoc session launched by 'ada', not you.",
                "Only ada or an admin may access this run.")),
        RunAuthority.access(OTHER_API, "r1", null, List.of(OWNER)));
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Run r1 belongs to spec 'auth', which has no owner.",
                "Only its owner or an admin may access this run.")),
        RunAuthority.access(OTHER_API, "r1", "auth", List.of()));
    assertEquals(
        "Run r1 is an ad-hoc session.",
        RunAuthority.access(OTHER_API, "r1", null, List.of()).orElseThrow().message());
    assertEquals(Optional.empty(), RunAuthority.access(AGENT, "r1", null, List.of(OWNER)));
  }
}
