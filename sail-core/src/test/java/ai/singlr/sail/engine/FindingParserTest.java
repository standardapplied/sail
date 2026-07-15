/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    var json = FindingParser.extractJsonBlocks(output);
    assertEquals(1, json.size());
    assertTrue(json.getFirst().startsWith("["));
  }

  @Test
  void extractJsonBlockHandlesCodeFenceWithoutJson() {
    var output =
        """
        ```
        [{"severity": "LOW", "category": "LOGIC", "title": "Fence", "description": "D"}]
        ```
        """;

    var json = FindingParser.extractJsonBlocks(output);
    assertEquals(1, json.size());
  }

  @Test
  void parsesTheFindingsWhenTheTranscriptEchoesThePromptFence() {
    var transcript =
        """
        Rules:
        5. If there are no issues, return an empty array: []

        Begin your response with ```json and end with ```.
        codex
        I inspected the diff and found one issue.

        ```json
        [{"severity": "MEDIUM", "category": "LOGIC", "file": "a.ts",
          "line_start": 5, "line_end": 5, "title": "Leaked body",
          "description": "Response not cancelled.", "confidence": 0.86}]
        ```
        """;

    var result = FindingParser.parse(transcript);

    assertEquals(
        1,
        result.findings().size(),
        "the prompt echo's fence must not shadow the real findings block: " + result.warnings());
    assertEquals("Leaked body", result.findings().getFirst().title());
  }

  @Test
  void theLastParseableBlockWinsWhenSeveralArePresent() {
    var transcript =
        """
        ```json
        not valid json at all
        ```
        thinking...
        ```json
        []
        ```
        """;

    var result = FindingParser.parse(transcript);

    assertEquals(0, result.findings().size());
    assertTrue(
        result.warnings().isEmpty(),
        "a clean empty array is a valid verdict: " + result.warnings());
  }

  @Test
  void parsesAnUppercaseFence() {
    var output =
        """
        ```JSON
        [{"severity": "LOW", "category": "LOGIC", "title": "Case", "description": "D"}]
        ```
        """;

    var result = FindingParser.parse(output);

    assertEquals(
        1, result.findings().size(), "fence casing is not a verdict: " + result.warnings());
    assertEquals("Case", result.findings().getFirst().title());
  }

  @Test
  void findsTheArrayEmbeddedInProseWithoutAFence() {
    var output =
        """
        I reviewed the branch and found one issue.
        [{"severity": "HIGH", "category": "SECURITY", "title": "Leak", "description": "D"}]
        Let me know if you need more detail.
        """;

    var result = FindingParser.parse(output);

    assertEquals(1, result.findings().size(), "unfenced JSON is still JSON: " + result.warnings());
    assertEquals("Leak", result.findings().getFirst().title());
  }

  @Test
  void fallsThroughToAnEmbeddedArrayWhenTheOnlyFenceIsThePromptEcho() {
    var output =
        """
        Begin your response with ```json and end with ```.
        I inspected the diff and found one issue.
        [{"severity": "MEDIUM", "category": "LOGIC", "title": "Bug", "description": "D"}]
        """;

    var result = FindingParser.parse(output);

    assertEquals(
        1,
        result.findings().size(),
        "an unparseable prompt-echo fence must not hide real findings: " + result.warnings());
    assertEquals("Bug", result.findings().getFirst().title());
  }

  @Test
  void aBareEmptyArrayLineInProseIsACleanVerdict() {
    var output =
        """
        I examined every change on the branch.
        []
        No issues worth reporting.
        """;

    var result = FindingParser.parse(output);

    assertTrue(result.findings().isEmpty());
    assertTrue(result.warnings().isEmpty(), "a bare [] is a verdict: " + result.warnings());
  }

  @Test
  void proseMentioningAnEmptyArrayMidSentenceIsNotAVerdict() {
    var result = FindingParser.parse("If there are no issues, return an empty array: []");

    assertTrue(result.findings().isEmpty());
    assertFalse(result.warnings().isEmpty(), "a prompt echo must never count as a clean pass");
  }

  @Test
  void reportsAWarningWhenNoBlockParses() {
    var result = FindingParser.parse("Begin your response with ```json and end with ```.");

    assertTrue(result.findings().isEmpty());
    assertTrue(
        !result.warnings().isEmpty(), "an unparseable review must say so, never pass silently");
  }
}
