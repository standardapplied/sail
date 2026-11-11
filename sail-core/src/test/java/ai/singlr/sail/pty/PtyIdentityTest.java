/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import org.junit.jupiter.api.Test;

class PtyIdentityTest {

  @Test
  void aPersonIsNamedByItsActorAndTheDispatchAuthorityIsNoPerson() {
    var person = new PtyIdentity.Person(new Actor("ada", Role.VIEWER, Actor.Lane.CLI));

    assertEquals("ada", person.fde());
    assertEquals("dispatch", PtyIdentity.DISPATCH.fde());
    assertThrows(NullPointerException.class, () -> new PtyIdentity.Person(null));
  }
}
