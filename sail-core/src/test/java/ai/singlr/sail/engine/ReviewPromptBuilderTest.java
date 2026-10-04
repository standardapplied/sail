/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewPromptBuilderTest {

  private static String prompt(String branch, List<String> repos, List<String> categories) {
    return prompt(branch, repos, categories, List.of(), List.of());
  }

  private static String prompt(
      String branch,
      List<String> repos,
      List<String> categories,
      List<MessageStore.MessageRow> messages,
      List<Finding> carried) {
    return ReviewPromptBuilder.build(branch, repos, categories, messages, carried).prompt();
  }

  @Test
  void includesBranchAndRepo() {
    var prompt = prompt("feat/auth", List.of("backend"), List.of());
    assertTrue(prompt.contains("feat/auth"));
    assertTrue(prompt.contains("backend"));
  }

  @Test
  void includesCategories() {
    var prompt = prompt("main", List.of("app"), List.of("security", "injection"));
    assertTrue(prompt.contains("security, injection"));
  }

  @Test
  void emptyCategoriesToDefaultsToAny() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(prompt.contains("any relevant category"));
  }

  @Test
  void instructsTheVerdictEnvelopeFromIterationOne() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(prompt.contains("```json"));
    assertTrue(prompt.contains("\"verdicts\""));
    assertTrue(prompt.contains("\"findings\""));
    assertTrue(
        prompt.contains("\"verdicts\" must be an empty array"),
        "the envelope is the only contract even when nothing is carried");
  }

  @Test
  void carryForwardSectionRendersIdsSeveritiesAndLocations() {
    var carried =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.CONCURRENCY,
            "src/Worker.java",
            10,
            14,
            "Non-atomic target selection",
            "d",
            "e",
            null,
            0.9);

    var prompt = prompt("main", List.of("app"), List.of(), List.of(), List.of(carried));

    assertTrue(prompt.contains("finding_id " + carried.id()), prompt);
    assertTrue(prompt.contains("[HIGH] Non-atomic target selection"), prompt);
    assertTrue(prompt.contains("(src/Worker.java:10-14)"), prompt);
    assertTrue(
        prompt.contains("include a verdict for every single one"),
        "every carried finding demands a ruling");
    assertTrue(
        prompt.contains("treated as still_open"), "the reviewer is told silence resolves nothing");
  }

  @Test
  void carryForwardSectionRendersThePriorRulingsEvidence() {
    var carried =
        Finding.create(
                Finding.Severity.HIGH,
                Finding.Category.CONCURRENCY,
                "src/Worker.java",
                10,
                14,
                "Non-atomic target selection",
                "d",
                "e",
                null,
                0.9)
            .carriedCopy("the seed window still races between reserve and claim");

    var prompt = prompt("main", List.of("app"), List.of(), List.of(), List.of(carried));

    assertTrue(
        prompt.contains(
            "Prior ruling's evidence that it remains open: the seed window still races"
                + " between reserve and claim"),
        prompt);
  }

  @Test
  void aCarriedFindingWithoutCarryEvidenceRendersItsLineAlone() {
    var carried =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.CONCURRENCY,
            "src/Worker.java",
            10,
            14,
            "Non-atomic target selection",
            "d",
            "e",
            null,
            0.9);

    var prompt = prompt("main", List.of("app"), List.of(), List.of(), List.of(carried));

    assertTrue(!prompt.contains("Prior ruling's evidence"), prompt);
  }

  @Test
  void asksForTheResidualScenarioOnStillOpenVerdicts() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(prompt.contains("For still_open, describe the exact scenario"));
    assertTrue(prompt.contains("reproduction target"));
  }

  @Test
  void carriedFindingWithoutFileRendersWithoutLocation() {
    var carried =
        Finding.create(
            Finding.Severity.LOW,
            Finding.Category.API_CONTRACT,
            null,
            0,
            0,
            "Contract drift",
            "d",
            "e",
            null,
            0.4);

    var prompt = prompt("main", List.of("app"), List.of(), List.of(), List.of(carried));

    assertTrue(prompt.contains("[LOW] Contract drift\n"), prompt);
  }

  @Test
  void noCarriedFindingsRendersNoCarrySection() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(!prompt.contains("The previous review left these findings open"));
  }

  @Test
  void demandsEvidenceForFixedAndDisputedVerdicts() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(prompt.contains("required for fixed"));
    assertTrue(prompt.contains("for disputed"));
    assertTrue(prompt.contains("without evidence is treated as still_open"));
  }

  @Test
  void requiresEvidenceInFindings() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(prompt.contains("evidence"));
    assertTrue(prompt.contains("If you cannot prove it, do not report it"));
  }

  @Test
  void includesSeverityLevels() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(prompt.contains("CRITICAL"));
    assertTrue(prompt.contains("HIGH"));
    assertTrue(prompt.contains("MEDIUM"));
    assertTrue(prompt.contains("LOW"));
  }

  @Test
  void includesSuggestionFormat() {
    var prompt = prompt("main", List.of("app"), List.of());
    assertTrue(prompt.contains("suggestion"));
    assertTrue(prompt.contains("before"));
    assertTrue(prompt.contains("after"));
    assertTrue(prompt.contains("rationale"));
  }

  @Test
  void namesTheSpecReposNotTheProjectAndCoversTheMissingBranchCase() {
    var prompt = prompt("agent/x", List.of("sail", "mast"), List.of());

    assertTrue(prompt.contains("directories inside this\nworkspace: sail, mast"), prompt);
    assertTrue(
        prompt.contains("If that branch no longer exists"),
        "a deleted work branch must not send the reviewer hunting: " + prompt);
    assertTrue(prompt.contains("ignore any other repositories"), prompt);
  }

  @Test
  void placesConversationBeforeReviewInstructions() {
    var message =
        new MessageStore.MessageRow(
            "01900000-0000-7000-8000-000000000001",
            "auth",
            "ada",
            "The token decision is intentional",
            null,
            "2026-07-28T00:00:00Z",
            "1-a",
            null,
            false);

    var built =
        ReviewPromptBuilder.build("main", List.of("app"), List.of(), List.of(message), List.of());

    assertTrue(built.prompt().startsWith("Conversation on this spec:"));
    assertTrue(
        built.prompt().indexOf("token decision") < built.prompt().indexOf("Review the changes"));
    assertEquals(List.of(message), built.renderedMessages());
  }
}
