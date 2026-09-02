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
    assertFalse(RoomWakePolicy.humanAuthor(""));
    assertFalse(RoomWakePolicy.humanAuthor(null));
  }

  @Test
  void anUnsetModeIsDerivedFromTheRosterSize() {
    assertEquals("on", RoomWakePolicy.effectiveMode(null, 0));
    assertEquals("on", RoomWakePolicy.effectiveMode(null, 1));
    assertEquals("mention", RoomWakePolicy.effectiveMode(null, 2));
    assertEquals("mention", RoomWakePolicy.effectiveMode("", 3));
  }

  @Test
  void anExplicitModeIsNeverOverriddenByTheRoster() {
    assertEquals("mention", RoomWakePolicy.effectiveMode("mention", 1));
    assertEquals("off", RoomWakePolicy.effectiveMode("off", 1));
    assertEquals("on", RoomWakePolicy.effectiveMode("on", 4));
  }

  @Test
  void aSoloRoomAnswersAnyHumanMessageByDefault() {
    assertTrue(RoomWakePolicy.shouldWake(null, 1, "uday", "hello"));
    assertTrue(RoomWakePolicy.shouldWake(null, 0, "uday", "hello"));
    assertTrue(RoomWakePolicy.shouldWake("on", 3, "uday", "hello"));
  }

  @Test
  void aMultiMemberRoomWakesOnlyWhenAddressedByDefault() {
    assertTrue(RoomWakePolicy.shouldWake(null, 2, "uday", "hey @agent what's up"));
    assertFalse(RoomWakePolicy.shouldWake(null, 2, "uday", "hey what's up"));
    assertFalse(RoomWakePolicy.shouldWake(null, 2, "uday", null));
    assertTrue(RoomWakePolicy.shouldWake("mention", 1, "uday", "@agent please"));
    assertFalse(RoomWakePolicy.shouldWake("mention", 1, "uday", "please"));
  }

  @Test
  void offNeverWakesEvenWhenAddressed() {
    assertFalse(RoomWakePolicy.shouldWake("off", 1, "uday", "@agent please"));
    assertFalse(RoomWakePolicy.shouldWake("off", 0, "uday", "hello"));
  }

  @Test
  void agentsAndSailNeverWakeRegardlessOfMode() {
    assertFalse(RoomWakePolicy.shouldWake("on", 1, "sail", "Review passed."));
    assertFalse(RoomWakePolicy.shouldWake("on", 1, "claude/room-1", "answered"));
    assertFalse(RoomWakePolicy.shouldWake(null, 2, "claude/1", "@agent"));
    assertFalse(RoomWakePolicy.shouldWake(null, 1, "", "hello"));
  }
}
