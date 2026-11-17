/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import org.junit.jupiter.api.Test;

/** A session's allowance and the bounds it is held to, with no process and no host. */
class UploadAllowanceTest {

  @Test
  void aSessionTakesFilesUpToItsBoundAndOneHandedBackMayBeTakenAgain() {
    var allowance = new UploadAllowance();
    for (var i = 0; i < UploadAllowance.FILES; i++) {
      assertNull(allowance.take(1));
    }

    assertEquals(PutRefusal.TOO_MANY, allowance.take(1));
    allowance.give(1);
    assertNull(allowance.take(1));
  }

  @Test
  void aSessionTakesBytesUpToItsBoundAndBytesHandedBackMayBeTakenAgain() {
    var allowance = new UploadAllowance();
    assertNull(allowance.take(UploadAllowance.SESSION - 1));

    assertEquals(PutRefusal.SESSION_FULL, allowance.take(2));
    assertNull(allowance.take(1));
    allowance.give(UploadAllowance.SESSION - 1);
    assertNull(allowance.take(UploadAllowance.SESSION - 1));
  }

  @Test
  void aRefusedTakeTakesNothingAndOnlyNothingTakenIsEmpty() {
    var allowance = new UploadAllowance();
    assertTrue(allowance.empty());

    assertEquals(PutRefusal.SESSION_FULL, allowance.take(UploadAllowance.SESSION + 1));
    assertTrue(allowance.empty());
    assertNull(allowance.take(0));
    assertFalse(allowance.empty());
    allowance.give(0);
    assertTrue(allowance.empty());
  }

  @Test
  void aFileOverTheLargestOrOfNoSizeAtAllIsTooLarge() {
    assertEquals(PutRefusal.TOO_LARGE, UploadAllowance.sizeRefusal(UploadAllowance.ITEM + 1));
    assertEquals(PutRefusal.TOO_LARGE, UploadAllowance.sizeRefusal(-1));
    assertNull(UploadAllowance.sizeRefusal(UploadAllowance.ITEM));
    assertNull(UploadAllowance.sizeRefusal(0));
  }

  @Test
  void eachRefusalNamesTheBoundItMetAndOthersNone() {
    assertEquals(UploadAllowance.ITEM, UploadAllowance.limit(PutRefusal.TOO_LARGE));
    assertEquals(UploadAllowance.FILES, UploadAllowance.limit(PutRefusal.TOO_MANY));
    assertEquals(UploadAllowance.SESSION, UploadAllowance.limit(PutRefusal.SESSION_FULL));
    for (var kind : PutRefusal.values()) {
      if (kind != PutRefusal.TOO_LARGE
          && kind != PutRefusal.TOO_MANY
          && kind != PutRefusal.SESSION_FULL) {
        assertEquals(0, UploadAllowance.limit(kind), kind.wire());
      }
    }
  }
}
