/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardrailsTest {

  @Test
  void parsesCompleteConfig() {
    var map =
        Map.<String, Object>of(
            "max_duration", "4h",
            "action", "snapshot-and-stop");

    var g = Guardrails.fromMap(map);

    assertEquals("4h", g.maxDuration());
    assertEquals("snapshot-and-stop", g.action());
  }

  @Test
  void defaultsActionToStop() {
    var g = Guardrails.fromMap(Map.of("max_duration", "4h"));

    assertEquals("stop", g.action());
  }

  @Test
  void legacyFieldsAreIgnored() {
    var map =
        Map.<String, Object>of(
            "max_duration", "4h",
            "idle_timeout", "90m",
            "commit_burst", 20,
            "action", "stop");

    var g = Guardrails.fromMap(map);

    assertEquals("4h", g.maxDuration());
    assertEquals("stop", g.action());
  }

  @Test
  void rejectsInvalidAction() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> Guardrails.fromMap(Map.of("action", "restart")));

    assertTrue(ex.getMessage().contains("Invalid guardrail action"));
    assertTrue(ex.getMessage().contains("restart"));
  }

  @Test
  void acceptsAllValidActions() {
    for (var action : new String[] {"stop", "snapshot-and-stop", "notify"}) {
      var g = Guardrails.fromMap(Map.of("action", action));
      assertEquals(action, g.action());
    }
  }

  @Test
  void nullFieldsAllowed() {
    var g = Guardrails.fromMap(Map.of());

    assertNull(g.maxDuration());
    assertEquals("stop", g.action());
  }

  @Test
  void parsesHours() {
    assertEquals(Duration.ofHours(4), Guardrails.parseDuration("4h"));
  }

  @Test
  void parsesMinutes() {
    assertEquals(Duration.ofMinutes(90), Guardrails.parseDuration("90m"));
  }

  @Test
  void parsesSeconds() {
    assertEquals(Duration.ofSeconds(30), Guardrails.parseDuration("30s"));
  }

  @Test
  void returnsNullForNullInput() {
    assertNull(Guardrails.parseDuration(null));
  }

  @Test
  void handlesLeadingTrailingWhitespace() {
    assertEquals(Duration.ofHours(2), Guardrails.parseDuration("  2h  "));
  }

  @Test
  void rejectsCompoundFormat() {
    assertThrows(IllegalArgumentException.class, () -> Guardrails.parseDuration("4h30m"));
  }

  @Test
  void rejectsBareNumber() {
    assertThrows(IllegalArgumentException.class, () -> Guardrails.parseDuration("90"));
  }

  @Test
  void rejectsEmptyString() {
    assertThrows(IllegalArgumentException.class, () -> Guardrails.parseDuration(""));
  }

  @Test
  void rejectsInvalidUnit() {
    assertThrows(IllegalArgumentException.class, () -> Guardrails.parseDuration("4d"));
  }

  @Test
  void rejectsOverflowDuration() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> Guardrails.parseDuration("2562047788015216h"));
    assertTrue(ex.getMessage().contains("too large"));
  }
}
