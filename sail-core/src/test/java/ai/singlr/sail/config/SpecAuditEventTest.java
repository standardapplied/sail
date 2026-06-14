/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecAuditEventTest {

  private static final Instant FIXED_TS = Instant.parse("2026-05-21T12:34:56Z");
  private static final String HOST = "sail-host-01";

  @Test
  void constructorRejectsNullTs() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new SpecAuditEvent(null, "dispatched", "sail", null, HOST, null));
    assertTrue(ex.getMessage().contains("ts"));
  }

  @Test
  void constructorRejectsBlankEvent() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SpecAuditEvent(FIXED_TS, " ", "sail", null, HOST, null));
    assertTrue(ex.getMessage().contains("event"));
  }

  @Test
  void constructorRejectsNullAgent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpecAuditEvent(FIXED_TS, "dispatched", null, null, HOST, null));
  }

  @Test
  void constructorRejectsBlankHost() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpecAuditEvent(FIXED_TS, "dispatched", "sail", null, "", null));
  }

  @Test
  void constructorRejectsZeroPid() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpecAuditEvent(FIXED_TS, "started", "claude-code", 0, HOST, null));
  }

  @Test
  void constructorRejectsNegativePid() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpecAuditEvent(FIXED_TS, "started", "claude-code", -5, HOST, null));
  }

  @Test
  void constructorAcceptsNullPid() {
    var event = new SpecAuditEvent(FIXED_TS, "dispatched", "sail", null, HOST, null);
    assertNull(event.pid());
  }

  @Test
  void dispatchedFactory() {
    var event = SpecAuditEvent.dispatched("claude-code", HOST, "first run");

    assertEquals("dispatched", event.event());
    assertEquals("claude-code", event.agent());
    assertEquals(HOST, event.host());
    assertEquals("first run", event.note());
    assertNull(event.pid());
    assertNotNull(event.ts());
  }

  @Test
  void restartedFactoryCarriesNote() {
    var event = SpecAuditEvent.restarted(SpecAuditEvent.SAIL_AGENT, HOST, "from in_progress");

    assertEquals("restarted", event.event());
    assertEquals("sail", event.agent());
    assertEquals("from in_progress", event.note());
  }

  @Test
  void startedFactoryRequiresPid() {
    var event = SpecAuditEvent.started("claude-code", 12345, HOST);

    assertEquals("started", event.event());
    assertEquals(12345, event.pid());
    assertNull(event.note());
  }

  @Test
  void stoppedFactoryCarriesPidAndNote() {
    var event = SpecAuditEvent.stopped("codex", 999, HOST, "exit 0");

    assertEquals("stopped", event.event());
    assertEquals(999, event.pid());
    assertEquals("exit 0", event.note());
  }

  @Test
  void completedFactory() {
    var event = SpecAuditEvent.completed("sail", HOST, null);

    assertEquals("completed", event.event());
    assertNull(event.pid());
    assertNull(event.note());
  }

  @Test
  void wellKnownEventsContainAllFactories() {
    assertTrue(SpecAuditEvent.WELL_KNOWN_EVENTS.contains("dispatched"));
    assertTrue(SpecAuditEvent.WELL_KNOWN_EVENTS.contains("restarted"));
    assertTrue(SpecAuditEvent.WELL_KNOWN_EVENTS.contains("started"));
    assertTrue(SpecAuditEvent.WELL_KNOWN_EVENTS.contains("stopped"));
    assertTrue(SpecAuditEvent.WELL_KNOWN_EVENTS.contains("completed"));
  }

  @Test
  void toMapPreservesFieldOrderAndDropsNulls() {
    var event = new SpecAuditEvent(FIXED_TS, "dispatched", "sail", null, HOST, null);

    var keys = event.toMap().keySet().toArray();
    assertArrayEquals(new String[] {"ts", "event", "agent", "host"}, keys);
  }

  @Test
  void toMapIncludesPidAndNoteWhenPresent() {
    var event = new SpecAuditEvent(FIXED_TS, "started", "claude-code", 42, HOST, "from cron");

    var map = event.toMap();
    assertEquals(42, map.get("pid"));
    assertEquals("from cron", map.get("note"));
    var keys = map.keySet().toArray();
    assertArrayEquals(new String[] {"ts", "event", "agent", "pid", "host", "note"}, keys);
  }

  @Test
  void toMapDropsBlankNote() {
    var event = new SpecAuditEvent(FIXED_TS, "dispatched", "sail", null, HOST, "  ");
    assertFalse(event.toMap().containsKey("note"));
  }

  @Test
  void toJsonLineIsSingleLineDeterministicJson() {
    var event = new SpecAuditEvent(FIXED_TS, "dispatched", "sail", null, HOST, null);

    var line = event.toJsonLine();

    assertFalse(line.contains("\n"));
    assertEquals(
        "{\"ts\": \"2026-05-21T12:34:56Z\", \"event\": \"dispatched\", \"agent\": \"sail\","
            + " \"host\": \"sail-host-01\"}",
        line);
  }

  @Test
  void roundTripPreservesAllFields() {
    var original = new SpecAuditEvent(FIXED_TS, "started", "claude-code", 42, HOST, "via cron");

    var rebuilt = SpecAuditEvent.fromJsonLine(original.toJsonLine());

    assertEquals(original, rebuilt);
  }

  @Test
  void fromJsonLineRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> SpecAuditEvent.fromJsonLine("   "));
  }

  @Test
  void fromJsonLineRejectsNull() {
    assertThrows(NullPointerException.class, () -> SpecAuditEvent.fromJsonLine(null));
  }

  @Test
  void fromJsonLineRejectsMissingEvent() {
    var line = "{\"ts\":\"2026-05-21T12:34:56Z\",\"agent\":\"sail\",\"host\":\"h\"}";
    var ex = assertThrows(IllegalArgumentException.class, () -> SpecAuditEvent.fromJsonLine(line));
    assertTrue(ex.getMessage().contains("event"));
  }

  @Test
  void fromJsonLineRejectsMissingTs() {
    var line = "{\"event\":\"dispatched\",\"agent\":\"sail\",\"host\":\"h\"}";
    var ex = assertThrows(IllegalArgumentException.class, () -> SpecAuditEvent.fromJsonLine(line));
    assertTrue(ex.getMessage().contains("ts"));
  }

  @Test
  void fromJsonLineRejectsMalformedTs() {
    var line =
        "{\"ts\":\"not-a-timestamp\",\"event\":\"dispatched\",\"agent\":\"sail\",\"host\":\"h\"}";
    var ex = assertThrows(IllegalArgumentException.class, () -> SpecAuditEvent.fromJsonLine(line));
    assertTrue(ex.getMessage().toLowerCase().contains("ts"));
  }

  @Test
  void fromMapParsesPidAsLong() {
    var map = baseMap();
    map.put("pid", 12345L);
    var event = SpecAuditEvent.fromMap(map);
    assertEquals(12345, event.pid());
  }

  @Test
  void fromMapParsesPidAsString() {
    var map = baseMap();
    map.put("pid", "67890");
    var event = SpecAuditEvent.fromMap(map);
    assertEquals(67890, event.pid());
  }

  @Test
  void fromMapTreatsBlankPidAsNull() {
    var map = baseMap();
    map.put("pid", "  ");
    var event = SpecAuditEvent.fromMap(map);
    assertNull(event.pid());
  }

  @Test
  void fromMapRejectsNullMap() {
    assertThrows(NullPointerException.class, () -> SpecAuditEvent.fromMap(null));
  }

  @Test
  void sailAgentConstant() {
    assertEquals("sail", SpecAuditEvent.SAIL_AGENT);
  }

  private static Map<String, Object> baseMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("ts", FIXED_TS.toString());
    map.put("event", "started");
    map.put("agent", "claude-code");
    map.put("host", HOST);
    return map;
  }
}
