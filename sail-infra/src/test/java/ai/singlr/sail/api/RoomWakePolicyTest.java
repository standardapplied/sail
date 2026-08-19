/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoomWakePolicyTest {

  @Test
  void humansAreHandlesNeverPrincipalsNorSail() {
    assertTrue(RoomWakePolicy.humanAuthor("uday"));
    assertFalse(RoomWakePolicy.humanAuthor("sail"));
    assertFalse(RoomWakePolicy.humanAuthor("claude/room-0195a2f0"));
    assertFalse(RoomWakePolicy.humanAuthor("codex/review-0195a2f0"));
    assertFalse(
        RoomWakePolicy.humanAuthor("codex/invite-0195a2f0"),
        "an invited agent's post never wakes or invites anything");
    assertFalse(RoomWakePolicy.humanAuthor(""));
    assertFalse(RoomWakePolicy.humanAuthor(null));
  }

  @Test
  void anUnsetModeDefaultsToOnOnlyOnceDispatched() {
    assertEquals("off", RoomWakePolicy.effectiveMode(null, false));
    assertEquals("on", RoomWakePolicy.effectiveMode(null, true));
    assertEquals("off", RoomWakePolicy.effectiveMode("", false));
    assertEquals("mention", RoomWakePolicy.effectiveMode("mention", false));
    assertEquals("off", RoomWakePolicy.effectiveMode("off", true));
  }

  @Test
  void onWakesOnAnyHumanMessage() {
    assertTrue(RoomWakePolicy.shouldWake("on", false, false, "uday", "hello"));
    assertTrue(RoomWakePolicy.shouldWake(null, true, false, "uday", "hello"));
  }

  @Test
  void mentionWakesOnlyWhenTheMessageAddressesTheAgent() {
    assertTrue(RoomWakePolicy.shouldWake("mention", true, false, "uday", "hey @agent what's up"));
    assertFalse(RoomWakePolicy.shouldWake("mention", true, false, "uday", "hey what's up"));
    assertFalse(RoomWakePolicy.shouldWake("mention", true, false, "uday", null));
  }

  @Test
  void offAndUndispatchedDefaultsNeverWake() {
    assertFalse(RoomWakePolicy.shouldWake("off", true, false, "uday", "@agent please"));
    assertFalse(RoomWakePolicy.shouldWake(null, false, false, "uday", "hello"));
  }

  @Test
  void agentsAndSailNeverWakeRegardlessOfMode() {
    assertFalse(RoomWakePolicy.shouldWake("on", true, false, "sail", "Review passed."));
    assertFalse(RoomWakePolicy.shouldWake("on", true, false, "claude/room-1", "answered"));
    assertFalse(RoomWakePolicy.shouldWake("mention", true, false, "claude/1", "@agent"));
  }

  @Test
  void anEngagedRoomAnswersEveryHumanMessageRegardlessOfWakeMode() {
    assertTrue(RoomWakePolicy.shouldWake(null, false, true, "uday", "hello"));
    assertTrue(RoomWakePolicy.shouldWake("off", false, true, "uday", "hello"));
    assertTrue(RoomWakePolicy.shouldWake("mention", false, true, "uday", "no address"));
  }

  @Test
  void engagementNeverOverridesTheHumanAuthorRule() {
    assertFalse(RoomWakePolicy.shouldWake(null, false, true, "sail", "Review passed."));
    assertFalse(RoomWakePolicy.shouldWake(null, false, true, "claude/room-1", "answered"));
    assertFalse(RoomWakePolicy.shouldWake(null, false, true, "", "hello"));
  }
}
