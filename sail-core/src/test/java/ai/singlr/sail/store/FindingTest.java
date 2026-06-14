/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FindingTest {

  @Test
  void createGeneratesUniqueId() {
    var a =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "a.java",
            1,
            1,
            "Title",
            "Desc",
            "Evidence",
            null,
            0.9);
    var b =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "b.java",
            1,
            1,
            "Title",
            "Desc",
            "Evidence",
            null,
            0.9);
    assertNotEquals(a.id(), b.id());
  }

  @Test
  void createSetsOpenResolution() {
    var finding =
        Finding.create(
            Finding.Severity.LOW,
            Finding.Category.LOGIC,
            "a.java",
            1,
            1,
            "Title",
            "Desc",
            "",
            null,
            0.5);
    assertEquals(Finding.Resolution.OPEN, finding.resolution());
  }

  @Test
  void fromMapParsesAllFields() {
    var map = new LinkedHashMap<String, Object>();
    map.put("severity", "CRITICAL");
    map.put("category", "SECURITY");
    map.put("file", "src/Auth.java");
    map.put("line_start", 42);
    map.put("line_end", 45);
    map.put("title", "SQL injection");
    map.put("description", "User input in query");
    map.put("evidence", "Data flows from getParam to execute");
    map.put(
        "suggestion",
        Map.of(
            "before", "old code",
            "after", "new code",
            "rationale", "prevents injection"));
    map.put("confidence", 0.95);

    var finding = Finding.fromMap(map);
    assertEquals(Finding.Severity.CRITICAL, finding.severity());
    assertEquals(Finding.Category.SECURITY, finding.category());
    assertEquals("src/Auth.java", finding.file());
    assertEquals(42, finding.lineStart());
    assertEquals(45, finding.lineEnd());
    assertEquals("SQL injection", finding.title());
    assertEquals("prevents injection", finding.suggestion().rationale());
    assertEquals(0.95, finding.confidence(), 0.001);
  }

  @Test
  void fromMapHandlesMissingOptionalFields() {
    var map = new LinkedHashMap<String, Object>();
    map.put("severity", "low");
    map.put("category", "logic");
    map.put("title", "Minor issue");
    map.put("description", "Details");

    var finding = Finding.fromMap(map);
    assertEquals(Finding.Severity.LOW, finding.severity());
    assertNull(finding.file());
    assertEquals(0, finding.lineStart());
    assertNotNull(finding.suggestion());
    assertEquals(Finding.Resolution.OPEN, finding.resolution());
  }

  @Test
  void fromMapHandlesStringLineNumbers() {
    var map = new LinkedHashMap<String, Object>();
    map.put("severity", "HIGH");
    map.put("category", "EDGE_CASE");
    map.put("title", "Issue");
    map.put("description", "Desc");
    map.put("line_start", "10");
    map.put("line_end", "20");

    var finding = Finding.fromMap(map);
    assertEquals(10, finding.lineStart());
    assertEquals(20, finding.lineEnd());
  }

  @Test
  void toMapRoundTrip() {
    var original =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.EDGE_CASE,
            "src/Service.java",
            10,
            15,
            "Off-by-one",
            "Loop iterates one too many times",
            "Array index will be out of bounds when list is empty",
            new Finding.Suggestion(
                "for (i = 0; i <= n; i++)",
                "for (i = 0; i < n; i++)",
                "Use strict less-than to prevent overflow"),
            0.85);

    var map = original.toMap();
    var restored = Finding.fromMap(map);

    assertEquals(original.severity(), restored.severity());
    assertEquals(original.category(), restored.category());
    assertEquals(original.file(), restored.file());
    assertEquals(original.lineStart(), restored.lineStart());
    assertEquals(original.title(), restored.title());
    assertEquals(original.suggestion().before(), restored.suggestion().before());
    assertEquals(original.confidence(), restored.confidence(), 0.001);
  }

  @Test
  void severityParseCaseInsensitive() {
    assertEquals(Finding.Severity.CRITICAL, Finding.Severity.parse("critical"));
    assertEquals(Finding.Severity.HIGH, Finding.Severity.parse(" HIGH "));
  }

  @Test
  void severityIsAtLeast() {
    assertTrue(Finding.Severity.CRITICAL.isAtLeast(Finding.Severity.HIGH));
    assertTrue(Finding.Severity.HIGH.isAtLeast(Finding.Severity.HIGH));
    assertFalse(Finding.Severity.MEDIUM.isAtLeast(Finding.Severity.HIGH));
    assertFalse(Finding.Severity.LOW.isAtLeast(Finding.Severity.HIGH));
  }

  @Test
  void categoryParseCaseInsensitive() {
    assertEquals(Finding.Category.SECURITY, Finding.Category.parse("security"));
    assertEquals(Finding.Category.API_CONTRACT, Finding.Category.parse(" API_CONTRACT "));
  }

  @Test
  void suggestionFromMapHandlesNull() {
    var suggestion = Finding.Suggestion.fromMap(null);
    assertEquals("", suggestion.before());
    assertEquals("", suggestion.after());
    assertEquals("", suggestion.rationale());
  }

  @Test
  void suggestionToMapOmitsEmptyFields() {
    var suggestion = new Finding.Suggestion("", "", "");
    assertTrue(suggestion.toMap().isEmpty());

    var withRationale = new Finding.Suggestion("", "", "reason");
    assertEquals(1, withRationale.toMap().size());
    assertEquals("reason", withRationale.toMap().get("rationale"));
  }
}
