/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The single {@code spec_dispatched} payload builder, exercised as both lanes use it — one shape,
 * no hand-synced duplicate.
 */
class DispatchEventsTest {

  @Test
  void includesBranchAndModeInOrder() {
    var data = DispatchEvents.dispatchedData("sail/oauth-flow", "background");

    assertEquals("sail/oauth-flow", data.get("branch"));
    assertEquals("background", data.get("mode"));
    assertEquals(List.of("branch", "mode"), List.copyOf(data.keySet()));
  }

  @Test
  void omitsBranchWhenNull() {
    var data = DispatchEvents.dispatchedData(null, "foreground");

    assertFalse(data.containsKey("branch"));
    assertEquals("foreground", data.get("mode"));
  }

  @Test
  void omitsBranchWhenBlank() {
    var data = DispatchEvents.dispatchedData("   ", "background");

    assertFalse(data.containsKey("branch"), "blank branch must not leak into the data payload");
    assertEquals("background", data.get("mode"));
  }
}
