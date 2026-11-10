/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A run principal's handle is minted in one shape, and only that shape names a run. */
class PrincipalHandleTest {

  private static final String RUN = "019fee00-0000-7000-8000-0000000ada01";

  @Test
  void aHandleIsTheAgentsFamilyOverItsMarkedRunId() {
    assertEquals(
        "claude/" + RUN, PrincipalHandle.mint("claude-code", PrincipalHandle.Marker.BUILD, RUN));
    assertEquals(
        "codex/review-" + RUN, PrincipalHandle.mint("codex", PrincipalHandle.Marker.REVIEW, RUN));
    assertEquals("/fix-" + RUN, PrincipalHandle.mint(null, PrincipalHandle.Marker.FIX, RUN));
    assertEquals(
        "claude/room-" + RUN, PrincipalHandle.mint("claude", PrincipalHandle.Marker.ROOM, RUN));
  }

  @Test
  void onlyTheMintedShapeNamesARun() {
    for (var marker : PrincipalHandle.Marker.values()) {
      var handle = PrincipalHandle.mint("claude", marker, RUN);
      assertEquals(Optional.of(RUN), PrincipalHandle.runOf(handle), handle);
      assertTrue(PrincipalHandle.namesRun(handle, RUN), handle);
    }
    assertFalse(PrincipalHandle.matches(null));
    assertFalse(PrincipalHandle.matches("ada"));
    assertEquals(Optional.empty(), PrincipalHandle.runOf("ada"));
    assertEquals(Optional.empty(), PrincipalHandle.runOf("claude/a/" + RUN));
    assertEquals(Optional.empty(), PrincipalHandle.runOf("claude/"));
    assertEquals(Optional.empty(), PrincipalHandle.runOf("claude/room-"));
    assertFalse(PrincipalHandle.namesRun("claude/" + RUN, null));
    assertFalse(PrincipalHandle.namesRun("claude/" + RUN, "other"));
  }
}
