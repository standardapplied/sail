/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoleTest {

  @Test
  void missingOrBlankRoleFailsSafeToViewer() {
    assertEquals(Role.VIEWER, Role.fromAttribute(null));
    assertEquals(Role.VIEWER, Role.fromAttribute(""));
    assertEquals(Role.VIEWER, Role.fromAttribute("   "));
  }

  @Test
  void recognizedRolesParseCaseInsensitively() {
    assertEquals(Role.ADMIN, Role.fromAttribute("admin"));
    assertEquals(Role.ADMIN, Role.fromAttribute("ADMIN"));
    assertEquals(Role.MEMBER, Role.fromAttribute("member"));
    assertEquals(Role.MEMBER, Role.fromAttribute(" Member "));
    assertEquals(Role.VIEWER, Role.fromAttribute("viewer"));
  }

  @Test
  void unrecognizedRoleFailsSafeToViewer() {
    assertEquals(Role.VIEWER, Role.fromAttribute("superuser"));
    assertEquals(Role.VIEWER, Role.fromAttribute("root"));
  }

  @Test
  void adminAllowsEverything() {
    assertTrue(Role.ADMIN.allows(Capability.READ));
    assertTrue(Role.ADMIN.allows(Capability.WRITE));
    assertTrue(Role.ADMIN.allows(Capability.ADMIN));
  }

  @Test
  void memberAllowsReadAndWriteButNotAdmin() {
    assertTrue(Role.MEMBER.allows(Capability.READ));
    assertTrue(Role.MEMBER.allows(Capability.WRITE));
    assertFalse(Role.MEMBER.allows(Capability.ADMIN));
  }

  @Test
  void viewerAllowsOnlyRead() {
    assertTrue(Role.VIEWER.allows(Capability.READ));
    assertFalse(Role.VIEWER.allows(Capability.WRITE));
    assertFalse(Role.VIEWER.allows(Capability.ADMIN));
  }
}
