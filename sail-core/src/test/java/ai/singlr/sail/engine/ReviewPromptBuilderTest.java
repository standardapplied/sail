/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewPromptBuilderTest {

  @Test
  void includesBranchAndRepo() {
    var prompt = ReviewPromptBuilder.build("feat/auth", List.of("backend"), List.of());
    assertTrue(prompt.contains("feat/auth"));
    assertTrue(prompt.contains("backend"));
  }

  @Test
  void includesCategories() {
    var prompt =
        ReviewPromptBuilder.build("main", List.of("app"), List.of("security", "injection"));
    assertTrue(prompt.contains("security, injection"));
  }

  @Test
  void emptyCategoriesToDefaultsToAny() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
    assertTrue(prompt.contains("any relevant category"));
  }

  @Test
  void instructsTheVerdictEnvelopeFromIterationOne() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
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

    var prompt =
        ReviewPromptBuilder.build("main", List.of("app"), List.of(), List.of(), List.of(carried));

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

    var prompt =
        ReviewPromptBuilder.build("main", List.of("app"), List.of(), List.of(), List.of(carried));

    assertTrue(prompt.contains("[LOW] Contract drift\n"), prompt);
  }

  @Test
  void noCarriedFindingsRendersNoCarrySection() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
    assertTrue(!prompt.contains("The previous review left these findings open"));
  }

  @Test
  void demandsEvidenceForFixedAndDisputedVerdicts() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
    assertTrue(prompt.contains("required for fixed"));
    assertTrue(prompt.contains("for disputed"));
    assertTrue(prompt.contains("without evidence is treated as still_open"));
  }

  @Test
  void requiresEvidenceInFindings() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
    assertTrue(prompt.contains("evidence"));
    assertTrue(prompt.contains("If you cannot prove it, do not report it"));
  }

  @Test
  void includesSeverityLevels() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
    assertTrue(prompt.contains("CRITICAL"));
    assertTrue(prompt.contains("HIGH"));
    assertTrue(prompt.contains("MEDIUM"));
    assertTrue(prompt.contains("LOW"));
  }

  @Test
  void includesSuggestionFormat() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
    assertTrue(prompt.contains("suggestion"));
    assertTrue(prompt.contains("before"));
    assertTrue(prompt.contains("after"));
    assertTrue(prompt.contains("rationale"));
  }

  @Test
  void namesTheSpecReposNotTheProjectAndCoversTheMissingBranchCase() {
    var prompt = ReviewPromptBuilder.build("agent/x", List.of("sail", "mast"), List.of());

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
            null);

    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of(), List.of(message));

    assertTrue(prompt.startsWith("Conversation on this spec:"));
    assertTrue(prompt.indexOf("token decision") < prompt.indexOf("Review the changes"));
  }
}
