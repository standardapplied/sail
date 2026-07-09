/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
