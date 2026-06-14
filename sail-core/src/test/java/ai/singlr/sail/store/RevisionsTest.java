/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RevisionsTest {

  @Test
  void firstRevisionFromNullIsCounterOne() {
    var rev = Revisions.next(null, "content");
    assertTrue(rev.startsWith("1-"), rev);
    assertEquals(1, Revisions.counterOf(rev));
  }

  @Test
  void counterIncrementsFromPreviousRevision() {
    var first = Revisions.next(null, "a");
    var second = Revisions.next(first, "b");
    assertEquals(2, Revisions.counterOf(second));
    var third = Revisions.next(second, "c");
    assertEquals(3, Revisions.counterOf(third));
  }

  @Test
  void hashIsContentAddressable() {
    assertEquals(Revisions.next(null, "same"), Revisions.next(null, "same"));
    assertNotEquals(Revisions.next(null, "x"), Revisions.next(null, "y"));
  }

  @Test
  void counterOfHandlesBlankAndMalformed() {
    assertEquals(0, Revisions.counterOf(null));
    assertEquals(0, Revisions.counterOf(""));
    assertEquals(0, Revisions.counterOf("  "));
    assertEquals(0, Revisions.counterOf("notanumber-abc"));
    assertEquals(7, Revisions.counterOf("7-deadbeef"));
  }
}
