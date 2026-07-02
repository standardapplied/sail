/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void instructsJsonOutput() {
    var prompt = ReviewPromptBuilder.build("main", List.of("app"), List.of());
    assertTrue(prompt.contains("```json"));
    assertTrue(prompt.contains("JSON array"));
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
}
