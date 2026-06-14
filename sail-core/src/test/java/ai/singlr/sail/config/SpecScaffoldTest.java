/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpecScaffoldTest {

  @Test
  void deriveIdSlugifiesTitle() {
    assertEquals(
        "oauth-2-0-authorization-code-flow",
        SpecScaffold.deriveId("OAuth 2.0 authorization code flow"));
  }

  @Test
  void deriveIdRejectsBlankTitles() {
    assertThrows(IllegalArgumentException.class, () -> SpecScaffold.deriveId("   "));
  }

  @Test
  void markdownTemplateIncludesExpectedSections() {
    var markdown = SpecScaffold.markdownTemplate("OAuth Flow");

    assertTrue(markdown.startsWith("# OAuth Flow"));
    assertTrue(markdown.contains("## Goal"));
    assertTrue(markdown.contains("## Background"));
    assertTrue(markdown.contains("## Requirements"));
    assertTrue(markdown.contains("## Approach"));
    assertTrue(markdown.contains("## Edge Cases"));
    assertTrue(markdown.contains("## Test Strategy"));
    assertTrue(markdown.contains("## Out of Scope"));
  }
}
