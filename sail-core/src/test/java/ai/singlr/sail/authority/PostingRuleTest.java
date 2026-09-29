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
import static ai.singlr.sail.authority.Actors.OWNER_CLI;
import static ai.singlr.sail.authority.Actors.OWNER_SYNC;
import static ai.singlr.sail.authority.Actors.ROOM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The posting rule's matrix: off the room lane a writer who is an admin or acts for an owner, on
 * the room lane a principal that acts for an owner.
 */
class PostingRuleTest {

  record Case(String name, Actor actor, List<String> owners, Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> matrix() {
    var ownedByAda = List.of(OWNER);
    var shared = List.of(OTHER, OWNER);
    return Stream.of(
        new Case("an owner posts", OWNER_API, ownedByAda, null),
        new Case("the operator posts", OWNER_CLI, ownedByAda, null),
        new Case("an owner's push posts", OWNER_SYNC, ownedByAda, null),
        new Case("an owner's agent posts", AGENT, ownedByAda, null),
        new Case("an owner's room principal posts", ROOM, ownedByAda, null),
        new Case("any owner of a shared conversation posts", OTHER_API, shared, null),
        new Case("an owner's room principal posts in a shared one", ROOM, shared, null),
        new Case("an admin posts anywhere", ADMIN, ownedByAda, null),
        new Case("an admin's push posts anywhere", ADMIN_SYNC, ownedByAda, null),
        new Case("an admin posts where no one owns", ADMIN, List.of(), null),
        new Case("another member may not", OTHER_API, ownedByAda, Kind.NOT_OWNER),
        new Case("another member's push may not", OTHER_SYNC, ownedByAda, Kind.NOT_OWNER),
        new Case("another FDE's agent may not", OTHERS_AGENT, ownedByAda, Kind.NOT_OWNER),
        new Case("a machine credential may not", MACHINE, ownedByAda, Kind.NOT_OWNER),
        new Case("a room principal of another FDE may not", ROOM, List.of(OTHER), Kind.NOT_OWNER),
        new Case("a room principal may not where no one owns", ROOM, List.of(), Kind.NOT_OWNER),
        new Case("a member may not where no one owns", OWNER_API, List.of(), Kind.NOT_OWNER),
        new Case("a viewer may not, even as the owner", VIEWER, ownedByAda, Kind.READ_ONLY),
        new Case("a viewer's push may not", VIEWER_SYNC, ownedByAda, Kind.READ_ONLY),
        new Case(
            "a viewing admin may not",
            new Actor("root", Role.VIEWER, Actor.Lane.API),
            ownedByAda,
            Kind.READ_ONLY));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        PostingRule.decide(row.actor(), "lobby", row.owners()).map(Refusal::kind));
  }

  @Test
  void refusalsNameTheOwnersOrSayNoOneOwnsIt() {
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "'lobby' belongs to bob or ada: only bob or ada or an admin may post there, not you.",
            "Ask bob or ada to post it, or have an admin do it."),
        PostingRule.decide(MACHINE, "lobby", List.of(OTHER, OWNER)).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.NOT_OWNER,
            "No one owns 'lobby' yet, so only an admin may post there.",
            "Claim its spec first with --assignee <you>, or have an admin post."),
        PostingRule.decide(OWNER_API, "lobby", List.of()).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.READ_ONLY,
            "Your credential is read-only and cannot change specs.",
            "Ask an admin for a member or admin credential."),
        PostingRule.decide(VIEWER, "lobby", List.of(OWNER)).orElseThrow());
  }
}
