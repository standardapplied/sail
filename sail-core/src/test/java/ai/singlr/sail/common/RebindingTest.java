/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A listener whose address is not up yet is tried again only once each backoff has passed, doubling
 * up to its last, and says so once per change of state, never once per try.
 */
class RebindingTest {

  private static final long SECOND = 1_000_000_000L;

  @Test
  void anAddressNotUpIsTriedAgainAfterEachBackoffAndSaidOncePerChange() {
    var said = new ArrayList<String>();
    var tries = new ArrayList<Long>();
    var refusals = new ArrayList<>(List.of("not up", "not up", "not up", "still busy"));
    var rebinding =
        new Rebinding(
            "The door",
            () -> {
              tries.add(0L);
              if (!refusals.isEmpty()) {
                throw new IOException(refusals.removeFirst());
              }
            },
            said::add);

    rebinding.tick(0);
    rebinding.tick(SECOND - 1);
    rebinding.tick(SECOND);
    rebinding.tick(3 * SECOND - 1);
    rebinding.tick(3 * SECOND);
    rebinding.tick(7 * SECOND);
    assertFalse(rebinding.listening());
    rebinding.tick(15 * SECOND);
    rebinding.tick(16 * SECOND);

    assertTrue(rebinding.listening());
    assertEquals(5, tries.size());
    assertEquals(
        List.of(
            "The door is not listening (not up); it is tried again until it binds.",
            "The door is not listening (still busy); it is tried again until it binds.",
            "The door is listening."),
        said);
  }

  @Test
  void theBackoffStopsGrowingAtItsLast() {
    var tries = new ArrayList<Long>();
    var rebinding =
        new Rebinding(
            "The door",
            () -> {
              tries.add(0L);
              throw new IOException("not up");
            },
            said -> {});
    var now = 0L;
    for (var i = 0; i < 12; i++) {
      rebinding.tick(now);
      now += Rebinding.LAST_RETRY.toNanos();
    }

    assertEquals(12, tries.size());
  }
}
