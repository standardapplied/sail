/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class FollowupDraftTest {

  private static final ReviewStore.ReviewRow REVIEW =
      new ReviewStore.ReviewRow("r1", "auth", 1, "passed", "t0", "t1", null, null, null);

  private static Finding finding(Finding.Severity severity, Finding.Suggestion suggestion) {
    return Finding.create(
        severity,
        Finding.Category.SECURITY,
        "Auth.java",
        10,
        10,
        "Issue",
        "Description",
        "Evidence",
        suggestion,
        0.8);
  }

  @Test
  void priorityMapsTheHighestSeverityPresent() {
    assertEquals(4, FollowupDraft.priority(List.of(finding(Finding.Severity.CRITICAL, null))));
    assertEquals(3, FollowupDraft.priority(List.of(finding(Finding.Severity.HIGH, null))));
    assertEquals(2, FollowupDraft.priority(List.of(finding(Finding.Severity.MEDIUM, null))));
    assertEquals(1, FollowupDraft.priority(List.of(finding(Finding.Severity.LOW, null))));
    assertEquals(
        4,
        FollowupDraft.priority(
            List.of(finding(Finding.Severity.LOW, null), finding(Finding.Severity.CRITICAL, null))),
        "the top severity wins regardless of order");
  }

  @Test
  void priorityRequiresAtLeastOneFinding() {
    assertThrows(IllegalArgumentException.class, () -> FollowupDraft.priority(List.of()));
  }

  @Test
  void bodyOmitsTheSuggestedFixWhenTheSuggestionIsBlankOrAbsent() {
    var blank = new Finding.Suggestion("", "", "");

    var withBlank =
        FollowupDraft.body("auth", REVIEW, List.of(finding(Finding.Severity.HIGH, blank)));
    var withNull =
        FollowupDraft.body("auth", REVIEW, List.of(finding(Finding.Severity.HIGH, null)));

    assertFalse(withBlank.contains("Suggested fix"), withBlank);
    assertFalse(withNull.contains("Suggested fix"), withNull);
    assertTrue(withBlank.contains("Description"), "the finding itself still renders");
  }
}
