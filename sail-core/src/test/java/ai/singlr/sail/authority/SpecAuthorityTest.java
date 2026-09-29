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
import static ai.singlr.sail.authority.Actors.SYSTEM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static ai.singlr.sail.authority.Actors.projection;
import static ai.singlr.sail.authority.Actors.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.SyncedStore;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The spec rule's whole matrix: every kind of actor, on every lane, creating, editing, moving the
 * assignee of, deleting and restoring a spec — its own room's or one born in a room — with the
 * claim rule, the fixed room and, on the sync lane, whom a revision may name.
 */
class SpecAuthorityTest {

  private static final String SPEC = "auth";

  private static final Map<String, Object> OWNED =
      projection("title", "Auth", "assignee", OWNER, "room_id", SPEC, "_created_by", OTHER);
  private static final Map<String, Object> UNASSIGNED =
      projection("title", "Auth", "assignee", null, "room_id", SPEC, "_created_by", OWNER);
  private static final Map<String, Object> BORN_IN_LOBBY =
      projection("title", "Auth", "assignee", null, "room_id", "lobby", "_created_by", OWNER);
  private static final Map<String, Object> BORN_IN_DEN =
      projection("title", "Auth", "assignee", null, "room_id", "den", "_created_by", OTHER);

  private Board board;
  private SpecAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    rule = new SpecAuthority(board.db);
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

  private static Case allows(
      String name, Actor actor, Map<String, Object> held, Map<String, Object> next) {
    return new Case(name, actor, held, next, null);
  }

  private static Case refuses(
      String name, Actor actor, Map<String, Object> held, Map<String, Object> next, Kind kind) {
    return new Case(name, actor, held, next, kind);
  }

  static Stream<Case> matrix() {
    var edited = with(OWNED, "title", "Edited");
    var ownRoomCreate = projection("title", "New", "assignee", null, "room_id", SPEC);
    var namingOther = with(ownRoomCreate, "assignee", OTHER);
    var inLobby = projection("title", "New", "assignee", null, "room_id", "lobby");
    return Stream.of(
        allows("an admin creates", ADMIN, null, ownRoomCreate),
        allows("the owner creates", OWNER_API, null, ownRoomCreate),
        allows("the operator creates", OWNER_CLI, null, ownRoomCreate),
        allows("another member creates", OTHER_API, null, ownRoomCreate),
        allows("a machine credential creates", MACHINE, null, ownRoomCreate),
        allows("an agent creates", AGENT, null, ownRoomCreate),
        allows("a sync push creates", OWNER_SYNC, null, ownRoomCreate),
        allows("main creates", MAIN, null, ownRoomCreate),
        allows("the machinery creates", SYSTEM, null, ownRoomCreate),
        refuses("a viewer cannot create", VIEWER, null, ownRoomCreate, Kind.READ_ONLY),
        refuses("a room principal cannot create", ROOM, null, ownRoomCreate, Kind.READ_ONLY),
        refuses("a viewer's push cannot create", VIEWER_SYNC, null, ownRoomCreate, Kind.READ_ONLY),
        allows("a create in its own room may name anyone", OTHER_API, null, namingOther),
        allows("the room's owner is born into it", OWNER_API, null, inLobby),
        allows("an agent of the room's owner is born into it", AGENT, null, inLobby),
        allows("an admin is born into any room", ADMIN, null, inLobby),
        refuses("another member is not born in", OTHER_API, null, inLobby, Kind.NOT_OWNER),
        refuses("another FDE's agent is not born in", OTHERS_AGENT, null, inLobby, Kind.NOT_OWNER),
        refuses("a machine credential is not born in", MACHINE, null, inLobby, Kind.NOT_OWNER),
        refuses("another member's push is not born in", OTHER_SYNC, null, inLobby, Kind.NOT_OWNER),
        allows("a birth claims for oneself", OWNER_API, null, with(inLobby, "assignee", OWNER)),
        refuses(
            "a birth naming another is admin-only",
            OWNER_API,
            null,
            with(inLobby, "assignee", OTHER),
            Kind.ADMIN_ONLY),
        allows("an admin's birth names anyone", ADMIN, null, with(inLobby, "assignee", OTHER)),
        allows("an admin edits", ADMIN, OWNED, edited),
        allows("the owner edits", OWNER_API, OWNED, edited),
        allows("the owner's agent edits", AGENT, OWNED, edited),
        allows("the owner pushes an edit", OWNER_SYNC, OWNED, edited),
        allows("an admin pushes an edit", ADMIN_SYNC, OWNED, edited),
        allows("main's edit is decided", MAIN, OWNED, edited),
        allows("the machinery edits", SYSTEM, OWNED, edited),
        refuses("another member cannot edit", OTHER_API, OWNED, edited, Kind.NOT_OWNER),
        refuses("another FDE's agent cannot edit", OTHERS_AGENT, OWNED, edited, Kind.NOT_OWNER),
        refuses("a machine credential cannot edit", MACHINE, OWNED, edited, Kind.NOT_OWNER),
        refuses("another member's push cannot edit", OTHER_SYNC, OWNED, edited, Kind.NOT_OWNER),
        refuses("a viewer cannot edit", VIEWER, OWNED, edited, Kind.READ_ONLY),
        refuses("a room principal cannot edit", ROOM, OWNED, edited, Kind.READ_ONLY),
        refuses(
            "the creator of an assigned spec cannot edit",
            OTHER_API,
            OWNED,
            edited,
            Kind.NOT_OWNER),
        allows(
            "the creator edits an unassigned spec",
            OWNER_API,
            UNASSIGNED,
            with(UNASSIGNED, "title", "x")),
        refuses(
            "another member cannot edit an unassigned spec",
            OTHER_API,
            UNASSIGNED,
            with(UNASSIGNED, "title", "x"),
            Kind.NOT_OWNER),
        allows("an admin deletes", ADMIN, OWNED, null),
        allows("the owner deletes", OWNER_API, OWNED, null),
        allows("the owner's push deletes", OWNER_SYNC, OWNED, null),
        refuses("another member cannot delete", OTHER_API, OWNED, null, Kind.NOT_OWNER),
        refuses("another member's push cannot delete", OTHER_SYNC, OWNED, null, Kind.NOT_OWNER),
        refuses("a viewer cannot delete", VIEWER, OWNED, null, Kind.READ_ONLY),
        allows("the owner restores over the last live state", OWNER_API, OWNED, OWNED),
        refuses("another member cannot restore", OTHER_API, OWNED, OWNED, Kind.NOT_OWNER),
        allows("an admin reassigns", ADMIN, OWNED, with(OWNED, "assignee", OTHER)),
        allows("an admin's push reassigns", ADMIN_SYNC, OWNED, with(OWNED, "assignee", OWNER)),
        refuses(
            "the owner cannot hand it on",
            OWNER_API,
            OWNED,
            with(OWNED, "assignee", OTHER),
            Kind.ADMIN_ONLY),
        refuses(
            "another member cannot take it",
            OTHER_API,
            OWNED,
            with(OWNED, "assignee", OTHER),
            Kind.ADMIN_ONLY),
        refuses(
            "another member's push cannot take it",
            OTHER_SYNC,
            OWNED,
            with(OWNED, "assignee", OTHER),
            Kind.ADMIN_ONLY),
        refuses(
            "the owner cannot unassign it",
            OWNER_API,
            OWNED,
            with(OWNED, "assignee", null),
            Kind.ADMIN_ONLY),
        allows(
            "a member claims an unassigned spec",
            OTHER_API,
            UNASSIGNED,
            with(UNASSIGNED, "assignee", OTHER)),
        allows(
            "a push claims an unassigned spec",
            OTHER_SYNC,
            UNASSIGNED,
            with(UNASSIGNED, "assignee", OTHER)),
        allows(
            "an agent claims for its FDE", AGENT, UNASSIGNED, with(UNASSIGNED, "assignee", OWNER)),
        refuses(
            "an agent cannot claim for itself",
            AGENT,
            UNASSIGNED,
            with(UNASSIGNED, "assignee", AGENT_HANDLE),
            Kind.ADMIN_ONLY),
        refuses(
            "a claim is for oneself",
            OTHER_API,
            UNASSIGNED,
            with(UNASSIGNED, "assignee", OWNER),
            Kind.ADMIN_ONLY),
        refuses(
            "a machine credential cannot claim",
            MACHINE,
            UNASSIGNED,
            with(UNASSIGNED, "assignee", OTHER),
            Kind.ADMIN_ONLY),
        refuses(
            "a viewer cannot claim",
            VIEWER,
            UNASSIGNED,
            with(UNASSIGNED, "assignee", OWNER),
            Kind.READ_ONLY),
        allows(
            "the room's owner claims a spec born there",
            OWNER_API,
            BORN_IN_LOBBY,
            with(BORN_IN_LOBBY, "assignee", OWNER)),
        refuses(
            "a member claims no spec born where they may not post",
            OTHER_API,
            BORN_IN_LOBBY,
            with(BORN_IN_LOBBY, "assignee", OTHER),
            Kind.ADMIN_ONLY),
        refuses(
            "a push claims no spec born where it may not post",
            OTHER_SYNC,
            BORN_IN_LOBBY,
            with(BORN_IN_LOBBY, "assignee", OTHER),
            Kind.ADMIN_ONLY),
        allows(
            "an admin assigns a spec born anywhere",
            ADMIN,
            BORN_IN_DEN,
            with(BORN_IN_DEN, "assignee", OWNER)),
        refuses(
            "an admin cannot move a spec's room",
            ADMIN,
            OWNED,
            with(OWNED, "room_id", "lobby"),
            Kind.FIXED),
        refuses(
            "the owner cannot move a spec's room",
            OWNER_API,
            OWNED,
            with(OWNED, "room_id", "lobby"),
            Kind.FIXED),
        refuses(
            "a push cannot move a spec's room",
            OWNER_SYNC,
            OWNED,
            with(OWNED, "room_id", "den"),
            Kind.FIXED),
        allows("main moves nothing it did not decide", MAIN, OWNED, with(OWNED, "room_id", "den")),
        allows("a push authored by its pusher", OWNER_SYNC, OWNED, with(edited, "_actor", OWNER)),
        allows(
            "a push authored by the machinery", OWNER_SYNC, OWNED, with(edited, "_actor", "sail")),
        allows(
            "a push authored by the pusher's run",
            OWNER_SYNC,
            OWNED,
            with(edited, "_actor", AGENT_HANDLE)),
        refuses(
            "a push authored by another FDE",
            OWNER_SYNC,
            OWNED,
            with(edited, "_actor", OTHER),
            Kind.NOT_AUTHOR),
        refuses(
            "an admin's push authored by another FDE",
            ADMIN_SYNC,
            OWNED,
            with(edited, "_actor", OWNER),
            Kind.NOT_AUTHOR),
        refuses(
            "a push authored by another FDE's run",
            OWNER_SYNC,
            OWNED,
            with(edited, "_actor", "claude/" + OTHER_RUN),
            Kind.NOT_AUTHOR),
        allows(
            "a local edit's author is its actor", OWNER_API, OWNED, with(edited, "_actor", OTHER)),
        allows(
            "a create by its pusher", OWNER_SYNC, null, with(ownRoomCreate, "_created_by", OWNER)),
        allows(
            "a create naming no creator",
            OWNER_SYNC,
            null,
            with(ownRoomCreate, "_created_by", null)),
        refuses(
            "a create for another FDE",
            OWNER_SYNC,
            null,
            with(ownRoomCreate, "_created_by", OTHER),
            Kind.NOT_AUTHOR),
        refuses(
            "an admin's create for another FDE",
            ADMIN_SYNC,
            null,
            with(ownRoomCreate, "_created_by", OWNER),
            Kind.NOT_AUTHOR),
        allows(
            "a revision's creator is the store's to keep",
            OWNER_SYNC,
            OWNED,
            with(edited, "_created_by", OWNER)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), SPEC, row.held(), row.next()).map(Refusal::kind));
  }

  @Test
  void aPushNamingARunMainDoesNotHoldYetIsRefusedNotDenied() {
    var next = with(OWNED, "_actor", "claude/019fee00-0000-7000-8000-0000000000ff");

    assertThrows(SyncedStore.Unheld.class, () -> rule.decide(OWNER_SYNC, SPEC, OWNED, next));
  }

  @Test
  void aBirthNeverAdmitsItselfButOwningASpecBornThereIsAVoice() {
    var claimingBirth = with(BORN_IN_DEN, "assignee", OWNER);

    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(OWNER_API, SPEC, null, claimingBirth).map(Refusal::kind),
        "the spec being born is not yet one of the room's");

    board.spec("child", "den", OWNER, OWNER);

    assertEquals(Optional.empty(), rule.decide(OWNER_API, SPEC, null, claimingBirth));
  }

  @Test
  void aCreateOnARoomIdIsRefusedUnlessItMovesNoOwnershipOrAnAdminAsks() {
    var create = projection("title", "Den", "assignee", null, "room_id", "den");
    board.db.execute(
        """
        INSERT INTO rooms (id, project, title, assignee, created_by, created_at, updated_at)
        VALUES ('nook', 'acme', 'nook', ?, ?, 'now', 'now'),
            ('mine', 'acme', 'mine', NULL, ?, 'now', 'now')""",
        OTHER,
        OWNER,
        OWNER);

    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Room 'den' already exists, and a spec's id is reserved for its own room.",
                "Pick another spec id.")),
        rule.decide(OWNER_SYNC, "den", null, create));
    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(OWNER_API, "den", null, create).map(Refusal::kind));
    assertEquals(Optional.empty(), rule.decide(OTHER_SYNC, "den", null, create));
    assertEquals(Optional.empty(), rule.decide(ADMIN, "den", null, create));
    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(OWNER_SYNC, "nook", null, with(create, "room_id", "nook")).map(Refusal::kind),
        "the creator of a room assigned away cannot take it back through a spec");
    assertEquals(
        Optional.empty(),
        rule.decide(OTHER_SYNC, "nook", null, with(create, "room_id", "nook")),
        "a spec its room's owner would own moves nothing");
    assertEquals(
        Optional.empty(),
        rule.decide(OWNER_SYNC, "mine", null, with(create, "room_id", "mine")),
        "a room the pusher minted reaches main before its spec");
  }

  @Test
  void refusalsSpeakTheTextsClientsSee() {
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "Spec 'auth' is assigned to 'ada', not you.",
            "Ask ada to make this change, or have an admin do it."),
        rule.decide(OTHER_API, SPEC, OWNED, OWNED).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "Spec 'auth' is unassigned; only ada or an admin may change it.",
            "Have an admin change it, or claim it first with --assignee <you>."),
        rule.decide(OTHER_API, SPEC, UNASSIGNED, UNASSIGNED).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.ADMIN_ONLY,
            "Reassigning spec 'auth' moves work between FDEs and is an admin-only action"
                + " (currently 'ada').",
            "Ask an admin to reassign it. You may grab a spec only while it is unassigned."),
        rule.decide(OTHER_API, SPEC, OWNED, with(OWNED, "assignee", OTHER)).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.ADMIN_ONLY,
            "Spec 'auth' lives in 'lobby', where you may not post, and claiming it would give you"
                + " a voice there.",
            "Ask an admin to assign it to you."),
        rule.decide(OTHER_API, SPEC, BORN_IN_LOBBY, with(BORN_IN_LOBBY, "assignee", OTHER))
            .orElseThrow());
    assertEquals(
        new Refusal(
            Kind.READ_ONLY,
            "Your credential is read-only and cannot change specs.",
            "Ask an admin for a member or admin credential."),
        rule.decide(VIEWER, SPEC, OWNED, OWNED).orElseThrow());
  }
}
