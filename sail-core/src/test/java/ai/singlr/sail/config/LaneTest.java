/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Lane is the canonical run-lane vocabulary: its {@code wire()} strings are the exact role values
 * stored and matched today, and its classifications ({@code triggersReview}, {@code isChat}, {@code
 * isSession}, {@code readOnly}) are the single source the reactors and stores consult — so the
 * "does this lane trigger review?" decision can no longer drift between two hand-maintained copies.
 */
class LaneTest {

  @Test
  void wireMatchesTheStoredRoleStrings() {
    assertEquals("build", Lane.BUILD.wire());
    assertEquals("adhoc", Lane.ADHOC.wire());
    assertEquals("room", Lane.ROOM.wire());
    assertEquals("room-full", Lane.ROOM_FULL.wire());
    assertEquals("review", Lane.REVIEW.wire());
    assertEquals("fix", Lane.FIX.wire());
  }

  @Test
  void ofRoundTripsEveryWireForm() {
    for (var lane : Lane.values()) {
      assertEquals(Optional.of(lane), Lane.of(lane.wire()));
    }
  }

  @Test
  void ofIsEmptyForAnUnknownOrNullRole() {
    assertEquals(Optional.empty(), Lane.of("nope"));
    assertEquals(Optional.empty(), Lane.of(null));
  }

  @Test
  void matchesIsTheNullSafeStringCheckItReplaces() {
    assertTrue(Lane.BUILD.matches("build"));
    assertFalse(Lane.BUILD.matches("adhoc"));
    assertFalse(Lane.BUILD.matches(null));
  }

  @Test
  void onlyBuildAndAdhocTriggerReview() {
    assertTrue(Lane.BUILD.triggersReview());
    assertTrue(Lane.ADHOC.triggersReview());
    for (var lane : Lane.values()) {
      if (lane != Lane.BUILD && lane != Lane.ADHOC) {
        assertFalse(lane.triggersReview(), lane + " must not trigger review");
      }
    }
  }

  @Test
  void chatIsTheTwoRoomModes() {
    assertTrue(Lane.ROOM.isChat());
    assertTrue(Lane.ROOM_FULL.isChat());
    assertFalse(Lane.BUILD.isChat());
  }

  @Test
  void theRetiredInviteRolesResolveToNoLane() {
    assertEquals(Optional.empty(), Lane.of("invite"));
    assertEquals(Optional.empty(), Lane.of("invite-full"));
    assertTrue(Lane.retired("invite"));
    assertTrue(Lane.retired("invite-full"));
    assertFalse(Lane.retired("room"));
    assertFalse(Lane.retired(null));
  }

  @Test
  void aHistoricalInviteRowKeepsTheContractItWasMintedUnder() {
    assertTrue(Lane.readOnly("invite"), "a plain invite was viewer-tier and stays so");
    assertFalse(Lane.readOnly("invite-full"));
    assertFalse(Lane.triggersReview("invite"));
    assertFalse(Lane.triggersReview("invite-full"));
    assertTrue(Lane.isSession("invite"));
    assertTrue(Lane.isSession("invite-full"));
  }

  @Test
  void theRoleClassifiersMatchTheLaneForALiveRoleAndFailConservativelyForAnUnknownOne() {
    for (var lane : Lane.values()) {
      assertEquals(lane.triggersReview(), Lane.triggersReview(lane.wire()));
      assertEquals(lane.isSession(), Lane.isSession(lane.wire()));
      assertEquals(lane.readOnly(), Lane.readOnly(lane.wire()));
    }
    assertTrue(Lane.triggersReview("nope"));
    assertTrue(Lane.triggersReview(null));
    assertFalse(Lane.isSession("nope"));
    assertFalse(Lane.readOnly(null));
  }

  @Test
  void sessionRolesListsTheLiveSessionLanesThenTheRetiredOnes() {
    assertEquals(
        List.of("build", "adhoc", "room", "room-full", "invite", "invite-full"),
        Lane.sessionRoles());
  }

  @Test
  void sessionIsEveryAgentSessionButNotAReviewExecution() {
    for (var lane : Lane.values()) {
      var expected =
          lane == Lane.BUILD || lane == Lane.ADHOC || lane == Lane.ROOM || lane == Lane.ROOM_FULL;
      assertEquals(expected, lane.isSession(), lane + " session classification");
    }
    assertFalse(Lane.REVIEW.isSession());
    assertFalse(Lane.FIX.isSession());
  }

  @Test
  void readOnlyIsTheWakeLane() {
    assertTrue(Lane.ROOM.readOnly());
    assertFalse(Lane.ROOM_FULL.readOnly());
    assertFalse(Lane.BUILD.readOnly());
  }
}
