/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuthorizerTest {

  @Test
  void safeMethodsRequireRead() {
    assertEquals(Capability.READ, Authorizer.capabilityFor("GET"));
    assertEquals(Capability.READ, Authorizer.capabilityFor("HEAD"));
  }

  @Test
  void mutatingMethodsRequireWrite() {
    assertEquals(Capability.WRITE, Authorizer.capabilityFor("POST"));
    assertEquals(Capability.WRITE, Authorizer.capabilityFor("PUT"));
    assertEquals(Capability.WRITE, Authorizer.capabilityFor("DELETE"));
    assertEquals(Capability.WRITE, Authorizer.capabilityFor("PATCH"));
  }

  @Test
  void capabilityValuesAreStable() {
    assertEquals(Capability.READ, Capability.valueOf("READ"));
    assertEquals(3, Capability.values().length);
  }
}
