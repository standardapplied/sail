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
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.SpecStore;
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
 * The room rule's matrix: any writer creates a room, and only its owner or an admin changes one
 * afterwards — its roster, wake, title and assignee alike — on every lane; a spec's identity room
 * is its spec's owner's, live or deleted.
 */
class RoomAuthorityTest {

  private static final Map<String, Object> LOBBY =
      projection(
          "project",
          "acme",
          "title",
          "lobby",
          "assignee",
          OWNER,
          "wake",
          null,
          "roster",
          null,
          "created_by",
          OWNER);

  private Board board;
  private RoomAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    rule = new RoomAuthority(board.db);
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
    var roster = with(LOBBY, "roster", "[{\"agent\":\"claude-code\",\"mode\":\"full\"}]");
    var wake = with(LOBBY, "wake", "off");
    var retitled = with(LOBBY, "title", "Lobby");
    var handedOn = with(LOBBY, "assignee", OTHER);
    var created = with(LOBBY, "created_by", null);
    var verbs = List.of(roster, wake, retitled, handedOn);
    var rows = Stream.<Case>builder();
    for (var actor : List.of(ADMIN, OWNER_API, OWNER_CLI, MACHINE, OTHER_API, AGENT, OWNER_SYNC)) {
      rows.add(new Case("create by " + actor, actor, null, created, null));
    }
    rows.add(new Case("a viewer cannot create", VIEWER, null, created, Kind.READ_ONLY));
    rows.add(new Case("a room principal cannot create", ROOM, null, created, Kind.READ_ONLY));
    for (var next : verbs) {
      var verb =
          next == roster
              ? "roster"
              : next == wake ? "wake" : next == retitled ? "title" : "assignee";
      rows.add(new Case("an admin changes the " + verb, ADMIN, LOBBY, next, null));
      rows.add(new Case("the owner changes the " + verb, OWNER_API, LOBBY, next, null));
      rows.add(new Case("the owner's agent changes the " + verb, AGENT, LOBBY, next, null));
      rows.add(new Case("the owner pushes the " + verb, OWNER_SYNC, LOBBY, next, null));
      rows.add(new Case("an admin pushes the " + verb, ADMIN_SYNC, LOBBY, next, null));
      rows.add(new Case("main's " + verb + " is decided", MAIN, LOBBY, next, null));
      rows.add(new Case("the machinery sets the " + verb, SYSTEM, LOBBY, next, null));
      rows.add(new Case("another member's " + verb, OTHER_API, LOBBY, next, Kind.NOT_OWNER));
      rows.add(
          new Case("another FDE's agent's " + verb, OTHERS_AGENT, LOBBY, next, Kind.NOT_OWNER));
      rows.add(new Case("a machine credential's " + verb, MACHINE, LOBBY, next, Kind.NOT_OWNER));
      rows.add(
          new Case("another member's pushed " + verb, OTHER_SYNC, LOBBY, next, Kind.NOT_OWNER));
      rows.add(new Case("a viewer's " + verb, VIEWER, LOBBY, next, Kind.READ_ONLY));
      rows.add(new Case("a viewer's pushed " + verb, VIEWER_SYNC, LOBBY, next, Kind.READ_ONLY));
      rows.add(new Case("a room principal's " + verb, ROOM, LOBBY, next, Kind.READ_ONLY));
    }
    rows.add(new Case("the owner deletes", OWNER_API, LOBBY, null, null));
    rows.add(new Case("an admin deletes", ADMIN, LOBBY, null, null));
    rows.add(new Case("the owner's push deletes", OWNER_SYNC, LOBBY, null, null));
    rows.add(new Case("another member cannot delete", OTHER_API, LOBBY, null, Kind.NOT_OWNER));
    rows.add(new Case("another push cannot delete", OTHER_SYNC, LOBBY, null, Kind.NOT_OWNER));
    rows.add(new Case("the owner restores", OWNER_API, LOBBY, LOBBY, null));
    rows.add(new Case("another member cannot restore", OTHER_API, LOBBY, LOBBY, Kind.NOT_OWNER));
    rows.add(
        new Case(
            "a push authored by another FDE",
            OWNER_SYNC,
            LOBBY,
            with(wake, "_actor", OTHER),
            Kind.NOT_AUTHOR));
    rows.add(
        new Case(
            "an admin's push authored by another FDE",
            ADMIN_SYNC,
            LOBBY,
            with(wake, "_actor", OWNER),
            Kind.NOT_AUTHOR));
    rows.add(
        new Case("a push by the machinery", OWNER_SYNC, LOBBY, with(wake, "_actor", "sail"), null));
    rows.add(
        new Case(
            "a create for another FDE",
            OWNER_SYNC,
            null,
            with(LOBBY, "created_by", OTHER),
            Kind.NOT_AUTHOR));
    rows.add(new Case("a create by its pusher", OWNER_SYNC, null, LOBBY, null));
    rows.add(
        new Case("an admin's create for another FDE", ADMIN_SYNC, null, LOBBY, Kind.NOT_AUTHOR));
    return rows.build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), "lobby", row.held(), row.next()).map(Refusal::kind));
  }

  @Test
  void anIdentityRoomIsItsSpecsOwnersWhateverItsRowSays() {
    board.spec("auth", "auth", OWNER, OWNER);
    var held = with(with(LOBBY, "assignee", OTHER), "created_by", OTHER);
    var next = with(held, "wake", "off");

    assertEquals(Optional.empty(), rule.decide(OWNER_API, "auth", held, next));
    assertEquals(
        Optional.of(Kind.NOT_OWNER), rule.decide(OTHER_API, "auth", held, next).map(Refusal::kind));
  }

  @Test
  void aDeletedSpecsIdentityRoomIsTheOwnerItsTombstoneNames() {
    var specs = new SpecStore(board.db);
    Acting.as(
        OWNER,
        () -> {
          specs.create(
              new SpecStore.SpecRow(
                  "gone",
                  "acme",
                  "Gone",
                  SpecStatus.PENDING,
                  OWNER,
                  null,
                  null,
                  null,
                  null,
                  0,
                  OWNER,
                  "",
                  "",
                  OWNER,
                  List.of(),
                  List.of()));
          specs.delete("gone");
        });
    var held = with(LOBBY, "assignee", OTHER);

    assertEquals(Optional.empty(), rule.decide(OWNER_API, "gone", held, null));
    assertEquals(
        Optional.of(Kind.NOT_OWNER), rule.decide(OTHER_API, "gone", held, null).map(Refusal::kind));
  }

  @Test
  void owningASpecBornInARoomIsAVoiceThereNotItsSettings() {
    board.spec("child", "lobby", OTHER, OTHER);

    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(OTHER_API, "lobby", LOBBY, with(LOBBY, "wake", "off")).map(Refusal::kind));
  }

  @Test
  void aRoomNoOneOwnsIsAnAdminsToChange() {
    var ownerless = with(with(LOBBY, "assignee", null), "created_by", null);

    assertEquals(
        Optional.of(
            new Refusal(
                Kind.NOT_OWNER,
                "Room 'lobby' has no owner, so only an admin may change it.",
                "Have an admin change it.")),
        rule.decide(OWNER_API, "lobby", ownerless, ownerless));
    assertEquals(Optional.empty(), rule.decide(ADMIN, "lobby", ownerless, ownerless));
  }

  @Test
  void refusalsSpeakTheTextsClientsSee() {
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "Room 'lobby' belongs to 'ada', not you.",
            "Ask ada to change it, or have an admin do it."),
        rule.decide(OTHER_API, "lobby", LOBBY, LOBBY).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.READ_ONLY,
            "Your credential is read-only and cannot create rooms.",
            "Ask an admin for a member or admin credential."),
        rule.decide(VIEWER, "lobby", null, LOBBY).orElseThrow());
    assertEquals(
        "Your credential is read-only and cannot change rooms.",
        rule.decide(VIEWER, "lobby", LOBBY, LOBBY).orElseThrow().message());
  }
}
