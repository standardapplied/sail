/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NativeTest {

  @Test
  void eachSystemTheJvmNamesIsMappedToItsOwnFacts() {
    assertSame(Native.LINUX, Native.platform("Linux"));
    assertSame(Native.DARWIN, Native.platform("Mac OS X"));
    assertSame(Native.DARWIN, Native.platform("Darwin"));
  }

  @Test
  void aSystemThePtyLayerDoesNotKnowIsRefusedByName() {
    var refused = assertThrows(IllegalStateException.class, () -> Native.platform("Windows 11"));

    assertEquals(
        "The pty layer runs on Linux and macOS, not on 'Windows 11'.", refused.getMessage());
  }
}
