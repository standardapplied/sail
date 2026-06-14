/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventTest {

  private static final Instant TS = Instant.parse("2026-05-21T12:34:56Z");

  @Test
  void constructorRejectsZeroVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Event(
                0, 0L, TS, "light-grid", null, "spec_dispatched", "sail", "host-01", Map.of()));
  }

  @Test
  void constructorRejectsNegativeId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Event(
                1, -1L, TS, "light-grid", null, "spec_dispatched", "sail", "host-01", Map.of()));
  }

  @Test
  void constructorRejectsNullTs() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Event(
                1, 0L, null, "light-grid", null, "spec_dispatched", "sail", "host-01", Map.of()));
  }

  @Test
  void constructorRejectsBlankProject() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Event(1, 0L, TS, " ", null, "spec_dispatched", "sail", "host-01", Map.of()));
  }

  @Test
  void constructorRejectsBlankType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Event(1, 0L, TS, "light-grid", null, " ", "sail", "host-01", Map.of()));
  }

  @Test
  void constructorRejectsBlankAgent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Event(1, 0L, TS, "light-grid", null, "spec_dispatched", "", "host-01", Map.of()));
  }

  @Test
  void constructorRejectsBlankHost() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Event(1, 0L, TS, "light-grid", null, "spec_dispatched", "sail", "", Map.of()));
  }

  @Test
  void constructorAcceptsNullDataAsEmpty() {
    var event = new Event(1, 0L, TS, "p", null, "t", "a", "h", null);
    assertEquals(Map.of(), event.data());
  }

  @Test
  void ofBuildsWithCurrentVersionAndZeroId() {
    var event = Event.of("light-grid", "oauth-flow", "spec_dispatched", "sail", "host-01");
    assertEquals(Event.CURRENT_VERSION, event.v());
    assertEquals(0L, event.id());
    assertEquals(Map.of(), event.data());
  }

  @Test
  void ofWithDataIncludesPayload() {
    var event =
        Event.of(
            "light-grid",
            null,
            "agent_session_started",
            "claude-code",
            "host-01",
            Map.of("pid", 42));
    assertEquals(42, event.data().get("pid"));
  }

  @Test
  void withIdRejectsZero() {
    var event = Event.of("p", null, "t", "a", "h");
    assertThrows(IllegalArgumentException.class, () -> event.withId(0L));
  }

  @Test
  void withIdProducesStampedCopy() {
    var event = Event.of("p", null, "t", "a", "h");
    var stamped = event.withId(7L);
    assertEquals(7L, stamped.id());
    assertEquals(event.project(), stamped.project());
    assertEquals(event.type(), stamped.type());
  }

  @Test
  void toMapDropsIdWhenZero() {
    var event = Event.of("p", null, "t", "a", "h");
    assertFalse(event.toMap().containsKey("id"));
  }

  @Test
  void toMapIncludesIdWhenStamped() {
    var event = Event.of("p", null, "t", "a", "h").withId(5);
    assertEquals(5L, event.toMap().get("id"));
  }

  @Test
  void toMapDropsBlankSpec() {
    var event = Event.of("p", "", "t", "a", "h");
    assertFalse(event.toMap().containsKey("spec"));
  }

  @Test
  void toMapIncludesSpecWhenSet() {
    var event = Event.of("p", "oauth-flow", "t", "a", "h");
    assertEquals("oauth-flow", event.toMap().get("spec"));
  }

  @Test
  void toMapDropsEmptyData() {
    var event = Event.of("p", null, "t", "a", "h");
    assertFalse(event.toMap().containsKey("data"));
  }

  @Test
  void toMapIncludesNonEmptyData() {
    var event = Event.of("p", null, "t", "a", "h", Map.of("k", "v"));
    @SuppressWarnings("unchecked")
    var data = (Map<String, Object>) event.toMap().get("data");
    assertEquals("v", data.get("k"));
  }

  @Test
  void toJsonLineIsSingleLine() {
    var line = Event.of("p", null, "t", "a", "h").toJsonLine();
    assertFalse(line.contains("\n"));
  }

  @Test
  void roundTripPreservesAllFields() {
    var original = new Event(1, 9L, TS, "p", "s", "t", "a", "h", Map.of("k", "v"));
    var rebuilt = Event.fromJsonLine(original.toJsonLine());
    assertEquals(original, rebuilt);
  }

  @Test
  void fromJsonLineRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> Event.fromJsonLine("   "));
  }

  @Test
  void fromJsonLineRejectsNull() {
    assertThrows(NullPointerException.class, () -> Event.fromJsonLine(null));
  }

  @Test
  void fromJsonLineRejectsMissingProject() {
    var line = "{\"v\":1,\"ts\":\"" + TS + "\",\"type\":\"t\",\"agent\":\"a\",\"host\":\"h\"}";
    assertThrows(IllegalArgumentException.class, () -> Event.fromJsonLine(line));
  }

  @Test
  void fromJsonLineRejectsMalformedTs() {
    var line =
        "{\"v\":1,\"ts\":\"bogus\",\"project\":\"p\",\"type\":\"t\",\"agent\":\"a\",\"host\":\"h\"}";
    assertThrows(IllegalArgumentException.class, () -> Event.fromJsonLine(line));
  }

  @Test
  void fromMapDefaultsVersionWhenMissing() {
    var map = new LinkedHashMap<String, Object>();
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    var event = Event.fromMap(map);
    assertEquals(Event.CURRENT_VERSION, event.v());
  }

  @Test
  void fromMapParsesIdAsLong() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", 1);
    map.put("id", 42L);
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(42L, Event.fromMap(map).id());
  }

  @Test
  void wellKnownTypesContainsKeyEvents() {
    assertEquals("spec_dispatched", Event.WellKnownTypes.SPEC_DISPATCHED);
    assertEquals("agent_session_started", Event.WellKnownTypes.AGENT_SESSION_STARTED);
    assertEquals("guardrail_triggered", Event.WellKnownTypes.GUARDRAIL_TRIGGERED);
  }

  @Test
  void sailAgentConstant() {
    assertEquals("sail", Event.SAIL_AGENT);
  }

  @Test
  void fromJsonLineRejectsBlankProject() {
    var line =
        "{\"v\":1,\"ts\":\""
            + TS
            + "\",\"project\":\" \",\"type\":\"t\",\"agent\":\"a\",\"host\":\"h\"}";
    assertThrows(IllegalArgumentException.class, () -> Event.fromJsonLine(line));
  }

  @Test
  void fromMapParsesIntFieldAsLong() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", 7L);
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(7, Event.fromMap(map).v());
  }

  @Test
  void fromMapParsesIntFieldAsNumber() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", 1.0d);
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(1, Event.fromMap(map).v());
  }

  @Test
  void fromMapFallsBackOnUnrecognizedIntType() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", java.util.List.of(1, 2));
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(Event.CURRENT_VERSION, Event.fromMap(map).v());
  }

  @Test
  void fromMapParsesLongFieldFromInteger() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", 1);
    map.put("id", 42);
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(42L, Event.fromMap(map).id());
  }

  @Test
  void fromMapParsesLongFieldAsNumber() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", 1);
    map.put("id", 99.0d);
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(99L, Event.fromMap(map).id());
  }

  @Test
  void fromMapParsesIntFieldAsString() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", "3");
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(3, Event.fromMap(map).v());
  }

  @Test
  void fromMapParsesLongFieldAsString() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", 1);
    map.put("id", "8");
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(8L, Event.fromMap(map).id());
  }

  @Test
  void fromMapFallsBackOnUnrecognizedLongType() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", 1);
    map.put("id", java.util.List.of("x"));
    map.put("ts", TS.toString());
    map.put("project", "p");
    map.put("type", "t");
    map.put("agent", "a");
    map.put("host", "h");
    assertEquals(0L, Event.fromMap(map).id());
  }
}
