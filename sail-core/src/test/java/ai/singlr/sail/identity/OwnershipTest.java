/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OwnershipTest {

  @Test
  void theAssigneeOwnsWhateverTheCreator() {
    assertEquals("uday", Ownership.ownerOf("uday", "mady"));
    assertEquals("uday", Ownership.ownerOf("uday", null));
  }

  @Test
  void theCreatorOwnsWhileUnassigned() {
    assertEquals("mady", Ownership.ownerOf(null, "mady"));
    assertEquals("mady", Ownership.ownerOf("", "mady"));
    assertEquals("mady", Ownership.ownerOf(" \t", "mady"));
  }

  @Test
  void neitherKnownIsBlank() {
    assertEquals("", Ownership.ownerOf(null, null));
    assertEquals("", Ownership.ownerOf(" ", null));
  }

  @Test
  void ownsMatchesTheOwnerOnly() {
    assertTrue(Ownership.owns("uday", "uday", "mady"));
    assertFalse(Ownership.owns("mady", "uday", "mady"));
    assertTrue(Ownership.owns("mady", "", "mady"));
    assertFalse(Ownership.owns(null, null, null));
    assertFalse(Ownership.owns("", null, ""), "a blank identity owns nothing");
    assertFalse(Ownership.owns(" ", " ", null));
  }
}
