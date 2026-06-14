/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventRendererTest {

  private static final Instant TS = Instant.parse("2026-05-21T12:34:56Z");

  @Test
  void humanIncludesTimestampProjectTypeAgentHost() {
    var event =
        new Event(1, 1L, TS, "light-grid", null, "spec_dispatched", "sail", "host-01", Map.of());

    var rendered = EventRenderer.human(event);

    assertTrue(rendered.contains("2026-05-21T12:34:56Z"));
    assertTrue(rendered.contains("light-grid"));
    assertTrue(rendered.contains("spec_dispatched"));
    assertTrue(rendered.contains("sail"));
    assertTrue(rendered.contains("host-01"));
  }

  @Test
  void humanIncludesSpecWhenPresent() {
    var event =
        new Event(1, 1L, TS, "light-grid", "oauth-flow", "spec_dispatched", "sail", "h", Map.of());
    assertTrue(EventRenderer.human(event).contains("light-grid/oauth-flow"));
  }

  @Test
  void humanOmitsSpecSegmentWhenNull() {
    var event = new Event(1, 1L, TS, "light-grid", null, "snapshot_created", "sail", "h", Map.of());
    assertFalse(EventRenderer.human(event).contains("light-grid/"));
  }

  @Test
  void humanShowsPidWhenPresentInData() {
    var event =
        new Event(
            1, 1L, TS, "p", null, "agent_session_started", "claude-code", "h", Map.of("pid", 4242));
    assertTrue(EventRenderer.human(event).contains("pid=4242"));
  }

  @Test
  void humanIncludesDataPayloadWhenNonEmpty() {
    var event =
        new Event(1, 1L, TS, "p", null, "snapshot_created", "sail", "h", Map.of("label", "snap"));
    assertTrue(EventRenderer.human(event).contains("label"));
    assertTrue(EventRenderer.human(event).contains("snap"));
  }
}
