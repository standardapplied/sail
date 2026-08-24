/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.store.RoomStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoomViewTest {

  private static RoomStore.RoomRow row(String roster, String assignee, String wake) {
    return new RoomStore.RoomRow(
        "design-room", "acme", "Design talk", assignee, wake, roster, "uday", "t0", "t1", "sam");
  }

  @Test
  void fromRendersEveryFieldAndTheSeatedMembers() {
    var roster = Roster.solo(Engagement.of("claude-code", "full", "opus-x", "t2")).toJson();

    var view = RoomView.from(row(roster, "uday", "on"), List.of("attached-spec"));

    assertEquals("design-room", view.id());
    assertEquals("acme", view.project());
    assertEquals("Design talk", view.title());
    assertEquals("uday", view.assignee());
    assertEquals("on", view.wake());
    assertEquals(1, view.members().size());
    assertEquals(List.of("attached-spec"), view.specIds());
    assertEquals("uday", view.createdBy());
    assertEquals("t0", view.createdAt());
    assertEquals("t1", view.updatedAt());
    assertEquals("sam", view.updatedBy());

    var map = view.toMap();
    assertEquals("design-room", map.get("id"));
    assertEquals("uday", map.get("assignee"));
    assertEquals("on", map.get("wake"));
    @SuppressWarnings("unchecked")
    var members = (List<Map<String, Object>>) map.get("members");
    assertEquals("claude-code", members.getFirst().get("agent"));
    assertEquals("opus-x", members.getFirst().get("model"));
    assertEquals("t2", members.getFirst().get("engaged_at"));
    assertEquals(List.of("attached-spec"), map.get("spec_ids"));
    assertEquals("uday", map.get("created_by"));
    assertEquals("sam", map.get("updated_by"));
  }

  @Test
  void nullableFieldsStayOffTheWireAndAModellessMemberCarriesNoModelKey() {
    var roster = Roster.solo(Engagement.of("claude-code", "read_only", null, "t2")).toJson();
    var bare =
        new RoomStore.RoomRow(
            "bare-room", "acme", "Bare", null, null, roster, null, "t0", "t1", null);

    var map = RoomView.from(bare, List.of()).toMap();

    assertFalse(map.containsKey("assignee"));
    assertFalse(map.containsKey("wake"));
    assertFalse(map.containsKey("created_by"));
    assertFalse(map.containsKey("updated_by"));
    @SuppressWarnings("unchecked")
    var members = (List<Map<String, Object>>) map.get("members");
    assertFalse(members.getFirst().containsKey("model"));
    assertEquals("read_only", members.getFirst().get("mode"));
  }

  @Test
  void anEmptyRosterRendersAsNoMembers() {
    var view = RoomView.from(row(null, "uday", "off"), List.of());

    assertTrue(view.members().isEmpty());
    assertNull(Roster.fromJson(null).standing());
    @SuppressWarnings("unchecked")
    var members = (List<Map<String, Object>>) view.toMap().get("members");
    assertTrue(members.isEmpty());
  }
}
