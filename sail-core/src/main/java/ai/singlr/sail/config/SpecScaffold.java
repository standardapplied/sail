/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.NameValidator;
import java.util.Locale;

/** Utilities for generating new specs from a human-readable title. */
public final class SpecScaffold {

  private SpecScaffold() {}

  public static String deriveId(String title) {
    if (Strings.isBlank(title)) {
      throw new IllegalArgumentException("Spec title is required.");
    }
    var slug =
        title
            .strip()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "")
            .replaceAll("-{2,}", "-");
    if (slug.isBlank()) {
      throw new IllegalArgumentException(
          "Spec title must contain at least one ASCII letter or digit.");
    }
    NameValidator.requireValidSpecId(slug);
    return slug;
  }

  public static String markdownTemplate(String title) {
    if (Strings.isBlank(title)) {
      throw new IllegalArgumentException("Spec title is required.");
    }
    return """
        # %s

        ## Goal
        What this spec achieves in one or two sentences.

        ## Background
        Why this work is needed. Link to prior specs or decisions if relevant.

        ## Requirements
        - Concrete, testable requirements
        - Each item should be independently verifiable

        ## Approach
        High-level design and key decisions. Include:
        - Components affected
        - Data model changes (if any)
        - API contracts (if any)

        ## Edge Cases
        - Known edge cases and how to handle them

        ## Test Strategy
        - What to test and how
        - Key scenarios to cover

        ## Out of Scope
        - What this spec explicitly does NOT cover
        """
        .formatted(title.strip());
  }
}
