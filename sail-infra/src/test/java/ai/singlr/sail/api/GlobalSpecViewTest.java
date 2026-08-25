/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GlobalSpecViewTest {

  private static SpecStore.SpecRow row() {
    return new SpecStore.SpecRow(
        "auth",
        "acme",
        "OAuth",
        SpecStatus.DRAFT,
        "uday",
        null,
        null,
        null,
        null,
        0,
        "uday",
        "t0",
        "t1",
        "uday",
        List.of(),
        List.of());
  }

  private static RoomStore.RoomRow room(String wake, String roster) {
    return new RoomStore.RoomRow(
        "auth", "acme", "OAuth", "uday", wake, roster, "uday", "t0", "t1", "uday");
  }

  @Test
  void theRoomRowDecoratesWakeAndEngagementIntoTheViewAndItsMap() {
    var member = Engagement.of("claude-code", "full", "opus-x", "t0");

    var view = GlobalSpecView.from(row(), room("mention", Roster.solo(member).toJson()));

    assertEquals("mention", view.wake());
    assertEquals("claude-code", view.engagement().get("agent"));
    assertEquals("full", view.engagement().get("mode"));
    assertEquals("opus-x", view.engagement().get("model"));
    assertEquals("t0", view.engagement().get("engaged_at"));
    @SuppressWarnings("unchecked")
    var mapped = (Map<String, Object>) view.toMap().get("engagement");
    assertEquals("claude-code", mapped.get("agent"));
  }

  @Test
  void aModelLessMemberOmitsTheModelKey() {
    var member = Engagement.of("codex", "full", null, "t0");

    var view = GlobalSpecView.from(row(), room(null, Roster.solo(member).toJson()));

    assertFalse(view.engagement().containsKey("model"));
  }

  @Test
  void aMissingRoomOrEmptyOrCorruptRosterRendersNoEngagement() {
    assertNull(GlobalSpecView.from(row()).engagement());
    assertNull(GlobalSpecView.from(row()).wake());
    assertNull(GlobalSpecView.from(row(), room("on", null)).engagement());
    assertNull(GlobalSpecView.from(row(), room("on", "garbage {{{")).engagement());
    assertFalse(GlobalSpecView.from(row()).toMap().containsKey("engagement"));
  }
}
