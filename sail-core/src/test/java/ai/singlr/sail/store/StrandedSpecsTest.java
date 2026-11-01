/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrandedSpecsTest {

  private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z");

  private static SpecStore.SpecRow spec(String id, SpecStatus status, Instant updatedAt) {
    return new SpecStore.SpecRow(
        id, "acme", "T", status, null, null, null, null, null, 0, "me", null, updatedAt, "me",
        List.of(), List.of());
  }

  @Test
  void flagsActiveSpecsOlderThanTheThreshold() {
    var old = NOW.minus(Duration.ofHours(7));
    var recent = NOW.minus(Duration.ofHours(1));
    var specs =
        List.of(
            spec("stuck-ip", SpecStatus.IN_PROGRESS, old),
            spec("stuck-rev", SpecStatus.REVIEW, old),
            spec("recent", SpecStatus.IN_PROGRESS, recent),
            spec("done", SpecStatus.DONE, old),
            spec("pending", SpecStatus.PENDING, old),
            spec("waiting-on-human", SpecStatus.AWAITING_MERGE, old));

    var stranded = StrandedSpecs.find(specs, NOW, Duration.ofHours(6));

    assertEquals(
        List.of("stuck-ip", "stuck-rev"), stranded.stream().map(SpecStore.SpecRow::id).toList());
  }

  @Test
  void ignoresASpecWithNoTimestamp() {
    var specs = List.of(spec("no-ts", SpecStatus.IN_PROGRESS, null));

    assertTrue(StrandedSpecs.find(specs, NOW, Duration.ofHours(6)).isEmpty());
  }
}
