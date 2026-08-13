/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunPresenceTest {

  private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

  @Test
  void aRunningRunWithFreshActivityIsWorking() {
    assertEquals("working", RunPresence.of("running", "2026-08-13T11:59:00Z", NOW));
  }

  @Test
  void activityExactlyAtTheThresholdIsStillWorking() {
    assertEquals(
        "working",
        RunPresence.of("running", NOW.minus(RunPresence.THRESHOLD).toString(), NOW),
        "the boundary reads working; quiet begins strictly past the threshold");
  }

  @Test
  void aRunningRunSilentPastTheThresholdIsQuiet() {
    assertEquals("quiet", RunPresence.of("running", "2026-08-13T11:57:59Z", NOW));
  }

  @Test
  void aTerminalRunHasNoPresence() {
    assertNull(RunPresence.of("completed", "2026-08-13T11:59:00Z", NOW));
    assertNull(RunPresence.of("stopped", "2026-08-13T11:59:00Z", NOW));
    assertNull(RunPresence.of("failed", "2026-08-13T11:59:00Z", NOW));
  }

  @Test
  void aStoppingRunHasNoPresence() {
    assertNull(
        RunPresence.of("stopping", "2026-08-13T11:59:00Z", NOW),
        "a run mid-stop is the stop lane's story, not a liveness question");
  }

  @Test
  void aRunWithNoActivityStampHasNoPresence() {
    assertNull(RunPresence.of("running", null, NOW), "pre-upgrade rows must never guess");
    assertNull(RunPresence.of("running", "", NOW));
  }

  @Test
  void anUnparseableStampHasNoPresence() {
    assertNull(RunPresence.of("running", "not-a-timestamp", NOW));
  }

  @Test
  void theThresholdFiresWellBeforeAnySensibleMaxIdle() {
    assertTrue(
        RunPresence.THRESHOLD.toMinutes() <= 5,
        "presence is the early signal; it must cross long before a max_idle guardrail would kill"
            + " the run");
  }
}
