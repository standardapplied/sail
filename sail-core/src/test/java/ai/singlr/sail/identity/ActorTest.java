/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ActorTest {

  @Test
  void anActorBoundToTheOperationIsWhoReadsOverTheCommandsReader() {
    var person = new Actor("ada", Role.MEMBER, Actor.Lane.CLI);
    var bound = new Actor("bob", Role.ADMIN, Actor.Lane.API);

    assertEquals(person, Actor.readingAs(() -> person, Actor::reader));
    assertEquals(bound, Actor.readingAs(() -> person, () -> Actor.call(bound, Actor::reader)));
    assertEquals(Actor.system(), Actor.reader(), "with neither, the box reads as itself");
  }

  @Test
  void cliOperatorIsAdminOnItsOwnBox() {
    var actor = new Actor("sumesh", Role.ADMIN, Actor.Lane.CLI);

    assertEquals("sumesh", actor.handle());
    assertEquals(Role.ADMIN, actor.role());
    assertEquals(Actor.Lane.CLI, actor.lane());
    assertTrue(actor.isAdmin());
  }

  @Test
  void memberIsNotAdmin() {
    var actor = new Actor("raj", Role.MEMBER, Actor.Lane.API);

    assertFalse(actor.isAdmin());
    assertEquals(Actor.Lane.API, actor.lane());
  }

  @Test
  void viewerIsNotAdmin() {
    var actor = new Actor("obs", Role.VIEWER, Actor.Lane.API);

    assertFalse(actor.isAdmin());
  }

  @Test
  void machineCredentialHasNoHandle() {
    var actor = new Actor(null, Role.MEMBER, Actor.Lane.API);

    assertNull(actor.handle());
  }

  @Test
  void agentPrincipalIsMemberTierOnTheAgentLaneWithItsOwner() {
    var actor = Actor.agentPrincipal("claude/a1b2c3", "uday");

    assertEquals("claude/a1b2c3", actor.handle());
    assertEquals("uday", actor.owner());
    assertEquals(Role.MEMBER, actor.role());
    assertEquals(Actor.Lane.AGENT, actor.lane());
    assertTrue(actor.agentLane());
    assertFalse(actor.isAdmin());
  }

  @Test
  void actsForMatchesTheHandleOrTheOwnerAndNothingElse() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "uday");
    assertTrue(agent.actsFor("claude/a1b2c3"));
    assertTrue(agent.actsFor("uday"));
    assertFalse(agent.actsFor("sumesh"));
    assertFalse(agent.actsFor(null));

    var human = new Actor("uday", Role.ADMIN, Actor.Lane.CLI);
    assertFalse(human.agentLane());
    assertNull(human.owner());
    assertTrue(human.actsFor("uday"));
    assertFalse(human.actsFor("claude/a1b2c3"));
  }

  @Test
  void machineTokenActsForNoOne() {
    var machine = new Actor(null, Role.MEMBER, Actor.Lane.API);
    assertFalse(machine.actsFor("uday"));
    assertFalse(new Actor("", Role.MEMBER, Actor.Lane.API).actsFor(""), "blank is no one");
  }

  @Test
  void theActingFdeIsTheHandleOrTheFdeARunActsFor() {
    assertEquals("uday", new Actor("uday", Role.ADMIN, Actor.Lane.CLI).actingFde());
    assertEquals("uday", Actor.agentPrincipal("claude/a1b2c3", "uday").actingFde());
    assertEquals("uday", Actor.roomPrincipal("claude/room-a1b2c3", "uday").actingFde());
    assertEquals("carol", new Actor("carol", Role.MEMBER, Actor.Lane.SYNC).actingFde());
    assertNull(new Actor(null, Role.ADMIN, Actor.Lane.API).actingFde());
  }

  @Test
  void nothingBoundIsRefusedNamingTheMissingBinding() {
    var refusal = assertThrows(IllegalStateException.class, Actor::current);

    assertTrue(refusal.getMessage().contains("No actor is bound"));
  }

  @Test
  void aBindingHoldsForItsOperationOnlyAndTheInnerOneWins() throws Exception {
    Actor.run(
        Actor.system(),
        () -> {
          assertEquals(Actor.system(), Actor.current());
          Actor.run(Actor.main(), () -> assertEquals(Actor.main(), Actor.current()));
          assertEquals(Actor.system(), Actor.current());
        });

    assertEquals(
        "uday",
        Actor.call(new Actor("uday", Role.ADMIN, Actor.Lane.CLI), () -> Actor.current().handle()));
    assertThrows(IllegalStateException.class, Actor::current);
  }

  @Test
  void aReadNoDoorBoundAnActorToIsTheBoxsOwnAndABoundOneIsItsCallers() {
    var scoped =
        new Actor("uday", Role.ADMIN, Actor.Lane.CLI).withScope(ProjectScope.of(List.of("acme")));

    assertEquals(Actor.system(), Actor.reader());
    assertEquals(ProjectScope.all(), Actor.reader().scope());
    assertEquals(scoped, Actor.call(scoped, () -> Actor.reader()));
  }

  @Test
  void aCarriedTaskActsAsWhoeverSubmittedItOnAnotherThread() throws Exception {
    var seen = new AtomicReference<Actor>();
    var task =
        Actor.call(
            new Actor("uday", Role.ADMIN, Actor.Lane.CLI),
            () -> Actor.carrying(() -> seen.set(Actor.current())));
    var callable = Actor.call(Actor.system(), () -> Actor.carrying(() -> Actor.current().handle()));

    var thread = Thread.ofVirtual().start(task);
    thread.join();

    assertEquals(new Actor("uday", Role.ADMIN, Actor.Lane.CLI), seen.get());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertEquals("sail", executor.submit(callable).get());
    }
  }

  @Test
  void carryingOutsideAnyBindingIsRefusedAtSubmit() {
    assertThrows(IllegalStateException.class, () -> Actor.carrying(() -> {}));
  }

  @Test
  void syncMainAndSystemNameTheirLanes() {
    var pusher = new Actor("sumesh", Role.MEMBER, Actor.Lane.SYNC);
    assertEquals(Actor.Lane.SYNC, pusher.lane());
    assertEquals(new Actor("main", Role.ADMIN, Actor.Lane.MAIN), Actor.main());
    assertEquals(new Actor("sail", Role.ADMIN, Actor.Lane.SYSTEM), Actor.system());
  }

  @Test
  void onlyMainAndThisBoxsMachineryAreSailItself() {
    assertTrue(Actor.main().isSail());
    assertTrue(Actor.system().isSail());
    for (var lane : List.of(Actor.Lane.CLI, Actor.Lane.API, Actor.Lane.SYNC)) {
      assertFalse(new Actor("root", Role.ADMIN, lane).isSail(), lane + " is a person's");
    }
    assertFalse(Actor.agentPrincipal("claude/r-1", "ada").isSail());
    assertFalse(Actor.roomPrincipal("claude/room-r-1", "ada").isSail());
  }

  @Test
  void onlyASyncedRevisionNamesAPeerOrKeepsTheAuthorItOffers() {
    var pusher = new Actor("sumesh", Role.MEMBER, Actor.Lane.SYNC);
    assertEquals("sumesh", pusher.peer());
    assertEquals("uday", pusher.authorOf("uday"));
    assertEquals("sumesh", pusher.authorOf(null));

    assertEquals("main", Actor.main().peer());
    assertEquals("uday", Actor.main().authorOf("uday"));
    assertEquals("uday", Actor.main("uday").authorOf(null), "a tombstone keeps main's author");
    assertNull(Actor.main(null).authorOf(null), "main recorded none, so the node records none");
    assertEquals("main", Actor.main(null).peer());

    var operator = new Actor("mady", Role.ADMIN, Actor.Lane.CLI);
    assertNull(operator.peer());
    assertEquals("mady", operator.authorOf("uday"));
    assertNull(Actor.system().peer());
    assertEquals("sail", Actor.system().authorOf("uday"));
  }
}
