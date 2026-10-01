/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.authority.Actors.ADMIN;
import static ai.singlr.sail.authority.Actors.ADMIN_SYNC;
import static ai.singlr.sail.authority.Actors.AGENT;
import static ai.singlr.sail.authority.Actors.AGENT_HANDLE;
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
import static ai.singlr.sail.authority.Actors.SYSTEM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static ai.singlr.sail.authority.Actors.projection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Decidability;
import ai.singlr.sail.store.Standing;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The message rule's matrix: where is the posting rule's, and as whom is the actor's own handle on
 * this box's lanes; on the sync lane the pusher posts as itself, as its runs' principals in any
 * conversation, and as the pipeline where a run of its is in the conversation.
 */
class MessageAuthorityTest {

  private static final String MESSAGE = "019fee00-0000-7000-8000-0000000000f1";

  private Board board;
  private MessageAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    board.spec("auth", "lobby", OWNER, OWNER);
    rule = new MessageAuthority(board.db);
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(String name, Actor actor, String room, String author, Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Map<String, Object> post(String room, String author) {
    return projection(
        "room_id", room, "author", author, "body", "hi", "created_at", "2026-09-29T00:00:00Z");
  }

  static Stream<Case> matrix() {
    return Stream.of(
        new Case("the owner posts in their room", OWNER_API, "lobby", OWNER, null),
        new Case("the operator posts in their room", OWNER_CLI, "lobby", OWNER, null),
        new Case("an admin posts anywhere", ADMIN, "den", "root", null),
        new Case("the owner's agent posts as itself", AGENT, "lobby", AGENT_HANDLE, null),
        new Case("the owner's room principal posts as itself", ROOM, "lobby", ROOM_HANDLE, null),
        new Case("main's post is decided", MAIN, "den", OWNER, null),
        new Case("the pipeline narrates", SYSTEM, "den", "sail", null),
        new Case(
            "another member posts not in the owner's room",
            OTHER_API,
            "lobby",
            OTHER,
            Kind.NOT_OWNER),
        new Case(
            "another FDE's agent posts not there",
            OTHERS_AGENT,
            "lobby",
            "claude/" + OTHER_RUN,
            Kind.NOT_OWNER),
        new Case(
            "a room principal posts only for its FDE", ROOM, "den", ROOM_HANDLE, Kind.NOT_OWNER),
        new Case("a viewer posts nowhere", VIEWER, "lobby", OWNER, Kind.READ_ONLY),
        new Case("an agent posts as no FDE", AGENT, "lobby", OWNER, Kind.NOT_AUTHOR),
        new Case("a member posts as no one else", OWNER_API, "lobby", OTHER, Kind.NOT_AUTHOR),
        new Case("an admin posts as no one else", ADMIN, "lobby", OWNER, Kind.NOT_AUTHOR),
        new Case("a push as its pusher", OWNER_SYNC, "lobby", OWNER, null),
        new Case("a push as its run's principal", OWNER_SYNC, "lobby", AGENT_HANDLE, null),
        new Case("a push as its run's other principal", OWNER_SYNC, "lobby", ROOM_HANDLE, null),
        new Case("a push as the pipeline where its run is", OWNER_SYNC, "lobby", "sail", null),
        new Case("a push as another FDE", OWNER_SYNC, "lobby", OTHER, Kind.NOT_AUTHOR),
        new Case(
            "a push as another FDE's run",
            OWNER_SYNC,
            "lobby",
            "claude/" + OTHER_RUN,
            Kind.NOT_AUTHOR),
        new Case(
            "a push as the pipeline where it ran nothing",
            OTHER_SYNC,
            "den",
            "sail",
            Kind.NOT_AUTHOR),
        new Case(
            "a push where its pusher may not post", OTHER_SYNC, "lobby", OTHER, Kind.NOT_OWNER),
        new Case("an admin's push posts anywhere", ADMIN_SYNC, "lobby", OTHER, null),
        new Case("an admin's push as another FDE", ADMIN_SYNC, "lobby", OWNER, Kind.NOT_AUTHOR),
        new Case("a viewer's push posts nowhere", VIEWER_SYNC, "lobby", OWNER, Kind.READ_ONLY));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), MESSAGE, null, post(row.room(), row.author())).map(Refusal::kind));
  }

  @Test
  void anAgentPostsInAnotherSpecsRoomWhereItsFdeMayPost() {
    board.spec("other", "den", OWNER, OWNER);

    assertEquals(Optional.empty(), rule.decide(AGENT, MESSAGE, null, post("den", AGENT_HANDLE)));
    assertEquals(
        Optional.empty(), rule.decide(OWNER_SYNC, MESSAGE, null, post("den", AGENT_HANDLE)));
  }

  @Test
  void aPostAsARunMainDoesNotHoldYetIsRefusedNotDenied() {
    var unheld = "claude/019fee00-0000-7000-8000-0000000000ff";

    assertInstanceOf(
        Standing.Pending.class,
        Decidability.onMain(board.db)
            .standing("message", MESSAGE, post("lobby", unheld), OWNER_SYNC.handle()));
  }

  @Test
  void refusalsSpeakTheTextsClientsSee() {
    assertEquals(
        new Refusal(
            Kind.NOT_AUTHOR, "'ada' may not post as 'bob' in this room.", "Post as yourself."),
        rule.decide(OWNER_SYNC, MESSAGE, null, post("lobby", OTHER)).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "'lobby' belongs to ada: only ada or an admin may post there, not you.",
            "Ask ada to post it, or have an admin do it."),
        rule.decide(OTHER_API, MESSAGE, null, post("lobby", OTHER)).orElseThrow());
  }
}
