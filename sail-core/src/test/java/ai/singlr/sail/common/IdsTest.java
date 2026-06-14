/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class IdsTest {

  private static final int COUNTER_BITS = 12;
  private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;

  @Test
  void newIdHasVersion7AndRfc4122Variant() {
    var id = Ids.newId();
    assertEquals(7, id.version());
    assertEquals(2, id.variant());
  }

  @Test
  void idsAreUniqueAndMonotonicallyIncreasing() {
    var count = 10_000;
    var seen = new HashSet<java.util.UUID>();
    var previous = Ids.newId();
    seen.add(previous);
    for (var i = 1; i < count; i++) {
      var next = Ids.newId();
      assertTrue(previous.compareTo(next) < 0, "each id sorts strictly after the previous one");
      assertTrue(seen.add(next), "ids never collide");
      previous = next;
    }
    assertEquals(count, seen.size());
  }

  @Test
  void aClockAdvanceResetsTheCounterToTheNewTimestamp() {
    var state = (1000L << COUNTER_BITS) | 42L;

    var next = Ids.nextState(state, 2000L);

    assertEquals(2000L << COUNTER_BITS, next, "new timestamp, counter reset to zero");
    assertTrue(next > state);
  }

  @Test
  void anExhaustedCounterAdvancesTheTimestampByOne() {
    var state = (1000L << COUNTER_BITS) | COUNTER_MASK;

    var next = Ids.nextState(state, 1000L);

    assertEquals(1001L << COUNTER_BITS, next, "same millisecond, counter full, bump timestamp");
    assertTrue(next > state);
  }

  @Test
  void withinAMillisecondTheCounterIncrements() {
    var state = (1000L << COUNTER_BITS) | 7L;

    var next = Ids.nextState(state, 1000L);

    assertEquals(state + 1, next);
    assertEquals(8L, next & COUNTER_MASK);
  }

  @Test
  void aStaleClockReadingNeverGoesBackwards() {
    var state = (5000L << COUNTER_BITS) | 3L;

    var next = Ids.nextState(state, 4000L);

    assertEquals(state + 1, next, "a clock that ticked backwards still advances monotonically");
  }
}
