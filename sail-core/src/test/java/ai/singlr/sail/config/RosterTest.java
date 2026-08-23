/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RosterTest {

  private static Engagement member(String agent, String mode) {
    return Engagement.of(agent, mode, null, "2026-08-23T00:00:00Z");
  }

  @Test
  void aSoloRosterSeatsItsStandingMemberAndRoundTripsThroughJson() {
    var roster = Roster.solo(member("claude-code", "full"));

    var json = roster.toJson();
    assertTrue(json.startsWith("[") && json.endsWith("]"), "the stored form is a JSON array");

    var read = Roster.fromJson(json);
    assertEquals(1, read.members().size());
    assertEquals("claude-code", read.standing().agent());
    assertTrue(read.standing().full());
  }

  @Test
  void manyMembersRoundTripPreservingOrderAndModes() {
    var roster =
        new Roster(java.util.List.of(member("claude-code", "full"), member("codex", "read_only")));

    var read = Roster.fromJson(roster.toJson());

    assertEquals(2, read.members().size());
    assertEquals("claude-code", read.standing().agent(), "the first member is the standing agent");
    assertEquals("read_only", read.members().getLast().mode());
  }

  @Test
  void anEmptyRosterStoresAsNullAndReadsBackEmpty() {
    assertNull(Roster.EMPTY.toJson(), "empty matches the no-engagement column convention");
    assertTrue(Roster.fromJson(null).isEmpty());
    assertTrue(Roster.fromJson("  ").isEmpty());
    assertNull(Roster.fromJson(null).standing());
  }

  @Test
  void aCorruptValueReadsAsEmptyNeverThrows() {
    assertTrue(Roster.fromJson("not json at all").isEmpty());
    assertTrue(Roster.fromJson("{\"agent\":\"x\"}").isEmpty(), "a non-array value is corrupt");
    assertTrue(
        Roster.fromJson("[{\"mode\":\"full\"}]").isEmpty(),
        "a member missing its identity poisons nothing");
  }

  @Test
  void theLegacySingleEngagementSpellingNormalizesThroughTheMember() {
    var read =
        Roster.fromJson(
            "[{\"agent\":\"claude-code\",\"mode\":\"read-only\",\"engaged_at\":\"t0\"}]");

    assertEquals("read_only", read.standing().mode(), "legacy read-only normalizes on read");
  }

  @Test
  void aSoloRosterRejectsANullMember() {
    assertThrows(IllegalArgumentException.class, () -> Roster.solo(null));
  }
}
