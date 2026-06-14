/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.Finding;
import org.junit.jupiter.api.Test;

class FindingParserTest {

  @Test
  void parsesJsonBlockWithFindings() {
    var output =
        """
        Here are my findings:

        ```json
        [
          {
            "severity": "HIGH",
            "category": "SECURITY",
            "file": "src/Auth.java",
            "line_start": 42,
            "line_end": 42,
            "title": "SQL injection",
            "description": "User input in query",
            "evidence": "getParam flows to execute",
            "suggestion": {
              "before": "db.exec(sql + id)",
              "after": "db.exec(sql, id)",
              "rationale": "Use parameterized queries"
            },
            "confidence": 0.95
          }
        ]
        ```
        """;

    var result = FindingParser.parse(output);
    assertEquals(1, result.findings().size());
    assertTrue(result.warnings().isEmpty());

    var finding = result.findings().getFirst();
    assertEquals(Finding.Severity.HIGH, finding.severity());
    assertEquals(Finding.Category.SECURITY, finding.category());
    assertEquals("src/Auth.java", finding.file());
    assertEquals(42, finding.lineStart());
    assertEquals("SQL injection", finding.title());
    assertEquals(0.95, finding.confidence(), 0.001);
  }

  @Test
  void parsesEmptyArray() {
    var output =
        """
        No issues found.

        ```json
        []
        ```
        """;

    var result = FindingParser.parse(output);
    assertTrue(result.findings().isEmpty());
    assertTrue(result.warnings().isEmpty());
  }

  @Test
  void parsesMultipleFindings() {
    var output =
        """
        ```json
        [
          {"severity": "CRITICAL", "category": "SECURITY", "title": "First", "description": "A"},
          {"severity": "LOW", "category": "LOGIC", "title": "Second", "description": "B"}
        ]
        ```
        """;

    var result = FindingParser.parse(output);
    assertEquals(2, result.findings().size());
    assertEquals(Finding.Severity.CRITICAL, result.findings().get(0).severity());
    assertEquals(Finding.Severity.LOW, result.findings().get(1).severity());
  }

  @Test
  void handlesBareJsonArray() {
    var output =
        """
        [{"severity": "MEDIUM", "category": "LOGIC", "title": "Issue", "description": "Desc"}]
        """;

    var result = FindingParser.parse(output);
    assertEquals(1, result.findings().size());
  }

  @Test
  void handlesNullInput() {
    var result = FindingParser.parse(null);
    assertTrue(result.findings().isEmpty());
    assertEquals(1, result.warnings().size());
  }

  @Test
  void handlesEmptyInput() {
    var result = FindingParser.parse("");
    assertTrue(result.findings().isEmpty());
    assertFalse(result.warnings().isEmpty());
  }

  @Test
  void handlesNoJsonBlock() {
    var result = FindingParser.parse("Just some text without JSON.");
    assertTrue(result.findings().isEmpty());
    assertEquals(1, result.warnings().size());
    assertTrue(result.warnings().getFirst().contains("No JSON block"));
  }

  @Test
  void handlesMalformedJson() {
    var output =
        """
        ```json
        [{"severity": "HIGH", "category":
        ```
        """;

    var result = FindingParser.parse(output);
    assertTrue(result.findings().isEmpty());
    assertFalse(result.warnings().isEmpty());
  }

  @Test
  void handlesMalformedFindingInArray() {
    var output =
        """
        ```json
        [
          {"severity": "INVALID", "category": "SECURITY", "title": "Bad", "description": "X"},
          {"severity": "HIGH", "category": "LOGIC", "title": "Good", "description": "Y"}
        ]
        ```
        """;

    var result = FindingParser.parse(output);
    assertEquals(1, result.findings().size());
    assertEquals("Good", result.findings().getFirst().title());
    assertEquals(1, result.warnings().size());
  }

  @Test
  void extractJsonBlockHandlesUnclosedBlock() {
    var output =
        """
        ```json
        [{"severity": "LOW", "category": "LOGIC", "title": "Open", "description": "D"}]
        """;

    var json = FindingParser.extractJsonBlock(output);
    assertNotNull(json);
    assertTrue(json.startsWith("["));
  }

  @Test
  void extractJsonBlockHandlesCodeFenceWithoutJson() {
    var output =
        """
        ```
        [{"severity": "LOW", "category": "LOGIC", "title": "Fence", "description": "D"}]
        ```
        """;

    var json = FindingParser.extractJsonBlock(output);
    assertNotNull(json);
  }
}
