/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeReviewTest {

  @Test
  void fromMapParsesEnabledWithAuditor() {
    var review = CodeReview.fromMap(Map.of("enabled", true, "auditor", "codex"));

    assertTrue(review.enabled());
    assertEquals("codex", review.auditor());
  }

  @Test
  void fromMapDisabledByDefault() {
    var review = CodeReview.fromMap(Map.of("auditor", "codex"));

    assertFalse(review.enabled());
    assertEquals("codex", review.auditor());
  }

  @Test
  void fromMapExplicitlyDisabled() {
    var review = CodeReview.fromMap(Map.of("enabled", false, "auditor", "codex"));

    assertFalse(review.enabled());
  }

  @Test
  void fromMapEnabledWithoutAuditorUsesAutoResolution() {
    var review = CodeReview.fromMap(Map.of("enabled", true));

    assertTrue(review.enabled());
    assertNull(review.auditor());
  }

  @Test
  void fromMapAllowsDisabledWithoutAuditor() {
    var review = CodeReview.fromMap(Map.of("enabled", false));

    assertFalse(review.enabled());
    assertNull(review.auditor());
  }

  @Test
  void resolveAuditorUsesExplicitOverride() {
    var review = new CodeReview(true, "codex");

    assertEquals(
        "codex", review.resolveAuditor("claude-code", List.of("claude-code", "codex"), Set.of()));
  }

  @Test
  void resolveAuditorPicksFirstNonPrimary() {
    var review = new CodeReview(true, null);

    assertEquals(
        "codex", review.resolveAuditor("claude-code", List.of("claude-code", "codex"), Set.of()));
  }

  @Test
  void resolveAuditorSkipsExcluded() {
    var review = new CodeReview(true, null);

    assertEquals(
        "claude-code",
        review.resolveAuditor("claude-code", List.of("claude-code", "codex"), Set.of("codex")));
  }

  @Test
  void resolveAuditorFallsBackToPrimaryWhenOnlyNonPrimaryExcluded() {
    var review = new CodeReview(true, null);

    assertEquals(
        "claude-code",
        review.resolveAuditor("claude-code", List.of("claude-code", "codex"), Set.of("codex")));
  }

  @Test
  void resolveAuditorFallsBackToPrimaryWhenOnlyPrimaryInstalled() {
    var review = new CodeReview(true, null);

    assertEquals(
        "claude-code", review.resolveAuditor("claude-code", List.of("claude-code"), Set.of()));
  }

  @Test
  void resolveAuditorReturnsNullWhenInstallListNull() {
    var review = new CodeReview(true, null);

    assertNull(review.resolveAuditor("claude-code", null, Set.of()));
  }

  @Test
  void resolveAuditorReturnsNullWhenInstallListEmpty() {
    var review = new CodeReview(true, null);

    assertNull(review.resolveAuditor("claude-code", List.of(), Set.of()));
  }

  @Test
  void resolveAuditorHandlesNullExcludeSet() {
    var review = new CodeReview(true, null);

    assertEquals(
        "codex", review.resolveAuditor("claude-code", List.of("claude-code", "codex"), null));
  }

  @Test
  void resolveAuditorRejectsUnknownAgent() {
    var review = new CodeReview(true, "unknown-agent");

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> review.resolveAuditor("claude-code", List.of("codex"), Set.of()));

    assertTrue(ex.getMessage().contains("Unknown code review auditor 'unknown-agent'"));
    assertTrue(ex.getMessage().contains("Known agents:"));
  }

  @Test
  void resolveAuditorAllowsSameAsPrimary() {
    var review = new CodeReview(true, "claude-code");

    assertEquals(
        "claude-code",
        review.resolveAuditor("claude-code", List.of("claude-code", "codex"), Set.of()));
  }

  @Test
  void resolveAuditorRejectsNotInInstallList() {
    var review = new CodeReview(true, "codex");

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> review.resolveAuditor("claude-code", List.of("claude-code"), Set.of()));

    assertTrue(ex.getMessage().contains("is not in the agent install list"));
    assertTrue(ex.getMessage().contains("Add 'codex' to 'agent.install'"));
  }

  @Test
  void resolveAuditorRejectsNotInInstallListWhenNull() {
    var review = new CodeReview(true, "codex");

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> review.resolveAuditor("claude-code", null, Set.of()));

    assertTrue(ex.getMessage().contains("is not in the agent install list"));
  }

  @Test
  void toMapSerializesEnabled() {
    var review = new CodeReview(true, null);
    var map = review.toMap();

    assertEquals(true, map.get("enabled"));
    assertFalse(map.containsKey("auditor"));
  }

  @Test
  void toMapSerializesAuditor() {
    var review = new CodeReview(true, "codex");
    var map = review.toMap();

    assertEquals(true, map.get("enabled"));
    assertEquals("codex", map.get("auditor"));
  }
}
