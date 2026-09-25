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

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ActorTest {

  @Test
  void cliOperatorIsAdminOnItsOwnBox() {
    var actor = Actor.cliOperator("sumesh");

    assertEquals("sumesh", actor.handle());
    assertEquals(Role.ADMIN, actor.role());
    assertEquals(Actor.Lane.CLI, actor.lane());
    assertTrue(actor.isAdmin());
    assertTrue(actor.canWrite());
  }

  @Test
  void memberCanWriteButIsNotAdmin() {
    var actor = new Actor("raj", Role.MEMBER, Actor.Lane.API);

    assertFalse(actor.isAdmin());
    assertTrue(actor.canWrite());
    assertEquals(Actor.Lane.API, actor.lane());
  }

  @Test
  void viewerCannotWrite() {
    var actor = new Actor("obs", Role.VIEWER, Actor.Lane.API);

    assertFalse(actor.isAdmin());
    assertFalse(actor.canWrite());
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
    assertTrue(actor.canWrite());
    assertFalse(actor.isAdmin());
  }

  @Test
  void actsForMatchesTheHandleOrTheOwnerAndNothingElse() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "uday");
    assertTrue(agent.actsFor("claude/a1b2c3"));
    assertTrue(agent.actsFor("uday"));
    assertFalse(agent.actsFor("sumesh"));
    assertFalse(agent.actsFor(null));

    var human = Actor.cliOperator("uday");
    assertFalse(human.agentLane());
    assertNull(human.owner());
    assertTrue(human.actsFor("uday"));
    assertFalse(human.actsFor("claude/a1b2c3"));
  }

  @Test
  void machineTokenActsForNoOne() {
    var machine = new Actor(null, Role.MEMBER, Actor.Lane.API);
    assertFalse(machine.actsFor("uday"));
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

    assertEquals("uday", Actor.call(Actor.cliOperator("uday"), () -> Actor.current().handle()));
    assertThrows(IllegalStateException.class, Actor::current);
  }

  @Test
  void aCarriedTaskActsAsWhoeverSubmittedItOnAnotherThread() throws Exception {
    var seen = new AtomicReference<Actor>();
    var task =
        Actor.call(
            Actor.cliOperator("uday"), () -> Actor.carrying(() -> seen.set(Actor.current())));
    var callable = Actor.call(Actor.system(), () -> Actor.carrying(() -> Actor.current().handle()));

    var thread = Thread.ofVirtual().start(task);
    thread.join();

    assertEquals(Actor.cliOperator("uday"), seen.get());
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
    var pusher = Actor.sync("sumesh", Role.MEMBER);
    assertEquals(Actor.Lane.SYNC, pusher.lane());
    assertTrue(pusher.canWrite());
    assertFalse(Actor.sync(null, Role.VIEWER).canWrite());
    assertEquals(new Actor("main", Role.ADMIN, Actor.Lane.MAIN), Actor.main());
    assertEquals(new Actor("sail", Role.ADMIN, Actor.Lane.SYSTEM), Actor.system());
  }

  @Test
  void onlyASyncedRevisionNamesAPeerOrKeepsTheAuthorItOffers() {
    var pusher = Actor.sync("sumesh", Role.MEMBER);
    assertEquals("sumesh", pusher.peer());
    assertEquals("uday", pusher.authorOf("uday"));
    assertEquals("sumesh", pusher.authorOf(null));

    assertEquals("main", Actor.main().peer());
    assertEquals("uday", Actor.main().authorOf("uday"));

    var operator = Actor.cliOperator("mady");
    assertNull(operator.peer());
    assertEquals("mady", operator.authorOf("uday"));
    assertNull(Actor.system().peer());
    assertEquals("sail", Actor.system().authorOf("uday"));
  }
}
