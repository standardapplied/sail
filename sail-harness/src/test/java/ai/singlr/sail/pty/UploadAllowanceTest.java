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

  /** Takes a file of {@code size} bytes and stores it, so it is no longer under way. */
  private static PutRefusal store(UploadAllowance allowance, long size) {
    var refused = allowance.take(size);
    if (refused == null) {
      allowance.done(size, true);
    }
    return refused;
  }

  @Test
  void aSessionKeepsFilesUpToItsBoundAndOneHandedBackMayBeTakenAgain() {
    var allowance = new UploadAllowance();
    for (var i = 0; i < UploadAllowance.FILES - 1; i++) {
      assertNull(store(allowance, 1));
    }
    assertNull(allowance.take(1));

    assertEquals(PutRefusal.TOO_MANY, allowance.take(1));
    allowance.done(1, false);
    assertNull(allowance.take(1));
  }

  @Test
  void aSessionKeepsBytesUpToItsBoundAndBytesHandedBackMayBeTakenAgain() {
    var allowance = new UploadAllowance();
    assertNull(allowance.take(UploadAllowance.SESSION - 1));

    assertEquals(PutRefusal.SESSION_FULL, allowance.take(2));
    assertNull(allowance.take(1));
    allowance.done(UploadAllowance.SESSION - 1, false);
    assertNull(allowance.take(UploadAllowance.SESSION - 1));
  }

  @Test
  void atMostItsBoundOfUploadsAreUnderWayAndAStoredOneGivesItsPlaceBack() {
    var allowance = new UploadAllowance();
    for (var i = 0; i < UploadAllowance.OPEN; i++) {
      assertNull(allowance.take(1));
    }

    assertEquals(PutRefusal.BUSY, allowance.take(1));
    allowance.done(1, true);
    assertNull(allowance.take(1));
  }

  @Test
  void aRefusedTakeTakesNothingAndOnlyNothingTakenIsEmpty() {
    var allowance = new UploadAllowance();
    assertTrue(allowance.empty());

    assertEquals(PutRefusal.SESSION_FULL, allowance.take(UploadAllowance.SESSION + 1));
    assertTrue(allowance.empty());
    assertNull(allowance.take(0));
    assertFalse(allowance.empty());
    allowance.done(0, false);
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
    assertEquals(UploadAllowance.OPEN, UploadAllowance.limit(PutRefusal.BUSY));
    for (var kind : PutRefusal.values()) {
      if (kind != PutRefusal.TOO_LARGE
          && kind != PutRefusal.TOO_MANY
          && kind != PutRefusal.SESSION_FULL
          && kind != PutRefusal.BUSY) {
        assertEquals(0, UploadAllowance.limit(kind), kind.wire());
      }
    }
  }
}
