/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.FindingParser.ParseResult;
import ai.singlr.sail.engine.FindingParser.Ruling;
import ai.singlr.sail.engine.FindingParser.Verdict;
import ai.singlr.sail.store.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingParserTest {

  private static ParseResult.Parsed parsed(String output) {
    var result = FindingParser.parse(output);
    assertInstanceOf(
        ParseResult.Parsed.class, result, "expected a parsed envelope, got: " + result);
    return (ParseResult.Parsed) result;
  }

  private static ParseResult.Unparseable unparseable(String output) {
    var result = FindingParser.parse(output);
    assertInstanceOf(
        ParseResult.Unparseable.class, result, "expected unparseable output, got: " + result);
    return (ParseResult.Unparseable) result;
  }

  @Test
  void parsesAnEnvelopeWithFindings() {
    var output =
        """
        Here are my findings:

        ```json
        {"verdicts": [],
         "findings": [
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
        ]}
        ```
        """;

    var result = parsed(output);
    assertEquals(1, result.findings().size());
    assertTrue(result.verdicts().isEmpty());

    var finding = result.findings().getFirst();
    assertEquals(Finding.Severity.HIGH, finding.severity());
    assertEquals(Finding.Category.SECURITY, finding.category());
    assertEquals("src/Auth.java", finding.file());
    assertEquals(42, finding.lineStart());
    assertEquals("SQL injection", finding.title());
    assertEquals(0.95, finding.confidence(), 0.001);
  }

  @Test
  void parsesVerdictsAlongsideNewFindings() {
    var output =
        """
        ```json
        {"verdicts": [
          {"finding_id": "f-1", "verdict": "fixed", "evidence": "commit abc123 parameterizes the query"},
          {"finding_id": "f-2", "verdict": "disputed", "evidence": "the cap is enforced upstream"},
          {"finding_id": "f-3", "verdict": "still_open"}
         ],
         "findings": [
          {"severity": "LOW", "category": "LOGIC", "title": "New issue", "description": "D"}
        ]}
        ```
        """;

    var result = parsed(output);
    assertEquals(3, result.verdicts().size());
    assertEquals(
        new Verdict("f-1", Ruling.FIXED, "commit abc123 parameterizes the query"),
        result.verdicts().get(0));
    assertEquals(Ruling.DISPUTED, result.verdicts().get(1).ruling());
    assertEquals(Ruling.STILL_OPEN, result.verdicts().get(2).ruling());
    assertEquals("", result.verdicts().get(2).evidence());
    assertEquals(1, result.findings().size());
  }

  @Test
  void parsesAnEmptyEnvelope() {
    var output =
        """
        No issues found.

        ```json
        {"verdicts": [], "findings": []}
        ```
        """;

    var result = parsed(output);
    assertTrue(result.verdicts().isEmpty());
    assertTrue(result.findings().isEmpty());
  }

  @Test
  void aBareFindingsArrayIsOffContractAndUnparseable() {
    var output =
        """
        ```json
        [{"severity": "MEDIUM", "category": "LOGIC", "title": "Issue", "description": "Desc"}]
        ```
        """;

    var result = unparseable(output);
    assertFalse(
        result.warnings().isEmpty(),
        "a bare findings array must error the stage, never silently pass or half-parse");
  }

  @Test
  void anEnvelopeMissingVerdictsIsUnparseable() {
    assertFalse(unparseable("{\"findings\": []}").warnings().isEmpty());
  }

  @Test
  void anEnvelopeMissingFindingsIsUnparseable() {
    assertFalse(unparseable("{\"verdicts\": []}").warnings().isEmpty());
  }

  @Test
  void anEnvelopeWhereFindingsIsNotAnArrayIsUnparseable() {
    var result = unparseable("{\"verdicts\": [], \"findings\": {\"severity\": \"CRITICAL\"}}");
    assertFalse(
        result.warnings().isEmpty(),
        "a malformed findings member must ride the errored-retry lane — resolving carried"
            + " findings while dropping new ones would launder the new issues");
  }

  @Test
  void handlesBareEnvelopeWithoutFence() {
    var output =
        """
        {"verdicts": [], "findings": [{"severity": "MEDIUM", "category": "LOGIC", "title": "Issue", "description": "Desc"}]}
        """;

    var result = parsed(output);
    assertEquals(1, result.findings().size());
  }

  @Test
  void handlesNullInput() {
    var result = unparseable(null);
    assertEquals(1, result.warnings().size());
  }

  @Test
  void handlesEmptyInput() {
    assertFalse(unparseable("").warnings().isEmpty());
  }

  @Test
  void handlesNoJsonBlock() {
    var result = unparseable("Just some text without JSON.");
    assertEquals(1, result.warnings().size());
    assertTrue(result.warnings().getFirst().contains("No JSON block"));
  }

  @Test
  void handlesMalformedJson() {
    var output =
        """
        ```json
        {"verdicts": [{"finding_id":
        ```
        """;

    assertFalse(unparseable(output).warnings().isEmpty());
  }

  @Test
  void aMalformedFindingEntryRejectsTheWholeEnvelope() {
    var output =
        """
        ```json
        {"verdicts": [{"finding_id": "f-1", "verdict": "fixed", "evidence": "commit abc"}],
         "findings": [
          {"severity": "CRITICAL", "category": "SECURTY", "title": "Bad", "description": "X"},
          {"severity": "HIGH", "category": "LOGIC", "title": "Good", "description": "Y"}
        ]}
        ```
        """;

    var result = unparseable(output);
    assertFalse(
        result.warnings().isEmpty(),
        "an unreadable finding has unknown severity; accepting the rest would resolve the"
            + " carried blocker while silently dropping a reported issue from the gate");
  }

  @Test
  void aMalformedVerdictEntryRejectsTheWholeEnvelope() {
    var output =
        """
        ```json
        {"verdicts": [
          {"verdict": "fixed", "evidence": "no finding_id"},
          {"finding_id": "f-1", "verdict": "still_open"}
         ],
         "findings": []}
        ```
        """;

    assertFalse(unparseable(output).warnings().isEmpty());
  }

  @Test
  void anEnvelopeWhereNothingParsesIsUnparseableNotACleanPass() {
    var output =
        """
        ```json
        {"verdicts": [{"bogus": true}], "findings": [{"severity": "NOPE"}]}
        ```
        """;

    assertFalse(unparseable(output).warnings().isEmpty());
  }

  @Test
  void extractJsonBlockHandlesUnclosedBlock() {
    var output =
        """
        ```json
        {"verdicts": [], "findings": []}
        """;

    var json = FindingParser.extractJsonBlocks(output);
    assertEquals(1, json.size());
    assertTrue(json.getFirst().startsWith("{"));
  }

  @Test
  void extractJsonBlockHandlesCodeFenceWithoutJson() {
    var output =
        """
        ```
        {"verdicts": [], "findings": []}
        ```
        """;

    var json = FindingParser.extractJsonBlocks(output);
    assertEquals(1, json.size());
  }

  @Test
  void parsesTheEnvelopeWhenTheTranscriptEchoesThePromptFence() {
    var transcript =
        """
        Respond with exactly one JSON object — the verdict envelope:
        {"verdicts": [<one verdict per carried finding>], "findings": [<new findings only>]}

        Begin the JSON object with ```json and end with ```.
        codex
        I inspected the diff and found one issue.

        ```json
        {"verdicts": [],
         "findings": [{"severity": "MEDIUM", "category": "LOGIC", "file": "a.ts",
          "line_start": 5, "line_end": 5, "title": "Leaked body",
          "description": "Response not cancelled.", "confidence": 0.86}]}
        ```
        """;

    var result = parsed(transcript);
    assertEquals(1, result.findings().size());
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
        {"verdicts": [], "findings": []}
        ```
        """;

    var result = parsed(transcript);
    assertTrue(result.findings().isEmpty());
    assertTrue(result.verdicts().isEmpty(), "a clean empty envelope is a valid verdict");
  }

  @Test
  void parsesAnUppercaseFence() {
    var output =
        """
        ```JSON
        {"verdicts": [], "findings": [{"severity": "LOW", "category": "LOGIC", "title": "Case", "description": "D"}]}
        ```
        """;

    var result = parsed(output);
    assertEquals("Case", result.findings().getFirst().title());
  }

  @Test
  void findsTheEnvelopeEmbeddedInProseWithoutAFence() {
    var output =
        """
        I reviewed the branch and found one issue.
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "SECURITY", "title": "Leak", "description": "D"}]}
        Let me know if you need more detail.
        """;

    var result = parsed(output);
    assertEquals(1, result.findings().size());
    assertEquals("Leak", result.findings().getFirst().title());
  }

  @Test
  void fallsThroughToAnEmbeddedEnvelopeWhenTheOnlyFenceIsThePromptEcho() {
    var output =
        """
        Begin the JSON object with ```json and end with ```.
        I inspected the diff and found one issue.
        {"verdicts": [], "findings": [{"severity": "MEDIUM", "category": "LOGIC", "title": "Bug", "description": "D"}]}
        """;

    var result = parsed(output);
    assertEquals(1, result.findings().size());
    assertEquals("Bug", result.findings().getFirst().title());
  }

  @Test
  void thePromptEchoesPlaceholderEnvelopeIsNeverACleanPass() {
    var result =
        FindingParser.parse(
            "Respond with exactly one JSON object — the verdict envelope:\n"
                + "{\"verdicts\": [<one verdict per carried finding>], \"findings\":"
                + " [<new findings only>]}");

    assertInstanceOf(
        ParseResult.Unparseable.class,
        result,
        "an echoed placeholder envelope must never count as an empty verdict");
  }

  @Test
  void reconcileDefaultsAnUnmentionedCarriedFindingToStillOpen() {
    var carried = List.of(finding("Old high"));

    var reconciled = FindingParser.reconcile(carried, List.of());

    assertEquals(Ruling.STILL_OPEN, reconciled.rulings().get(carried.getFirst().id()).ruling());
    assertTrue(reconciled.warnings().isEmpty(), "silence is still_open, not an error");
  }

  @Test
  void reconcileDowngradesFixedWithoutEvidenceToStillOpen() {
    var carried = List.of(finding("Old high"));
    var verdicts = List.of(new Verdict(carried.getFirst().id(), Ruling.FIXED, " "));

    var reconciled = FindingParser.reconcile(carried, verdicts);

    assertEquals(Ruling.STILL_OPEN, reconciled.rulings().get(carried.getFirst().id()).ruling());
    assertEquals(1, reconciled.warnings().size());
    assertTrue(reconciled.warnings().getFirst().contains("no evidence"));
  }

  @Test
  void reconcileDowngradesDisputedWithoutEvidenceToStillOpen() {
    var carried = List.of(finding("Old high"));
    var verdicts = List.of(new Verdict(carried.getFirst().id(), Ruling.DISPUTED, ""));

    var reconciled = FindingParser.reconcile(carried, verdicts);

    assertEquals(Ruling.STILL_OPEN, reconciled.rulings().get(carried.getFirst().id()).ruling());
  }

  @Test
  void reconcileWarnsOnAnUnknownFindingIdInsteadOfCrashing() {
    var carried = List.of(finding("Old high"));
    var verdicts = List.of(new Verdict("ghost", Ruling.FIXED, "evidence"));

    var reconciled = FindingParser.reconcile(carried, verdicts);

    assertEquals(Ruling.STILL_OPEN, reconciled.rulings().get(carried.getFirst().id()).ruling());
    assertEquals(1, reconciled.warnings().size());
    assertTrue(reconciled.warnings().getFirst().contains("ghost"));
  }

  @Test
  void reconcileAppliesEvidenceBackedRulings() {
    var fixed = finding("Fixed one");
    var disputed = finding("Disputed one");
    var verdicts =
        List.of(
            new Verdict(fixed.id(), Ruling.FIXED, "commit abc"),
            new Verdict(disputed.id(), Ruling.DISPUTED, "wrong assumption"));

    var reconciled = FindingParser.reconcile(List.of(fixed, disputed), verdicts);

    assertEquals(Ruling.FIXED, reconciled.rulings().get(fixed.id()).ruling());
    assertEquals("commit abc", reconciled.rulings().get(fixed.id()).evidence());
    assertEquals(Ruling.DISPUTED, reconciled.rulings().get(disputed.id()).ruling());
  }

  private static Finding finding(String title) {
    return Finding.create(
        Finding.Severity.HIGH, Finding.Category.LOGIC, "a.java", 1, 1, title, "d", "e", null, 0.9);
  }
}
