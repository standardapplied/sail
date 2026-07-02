/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import java.util.Comparator;
import java.util.List;

/**
 * Renders a follow-up spec draft from a review's open findings: the derived title, a markdown body
 * with one actionable section per finding (ordered by severity), and a priority derived from the
 * highest severity present. Pure functions over {@link Finding}s — no store access, no I/O.
 */
final class FollowupDraft {

  private FollowupDraft() {}

  static String title(String sourceTitle) {
    return "Address review findings: " + sourceTitle;
  }

  static int priority(List<Finding> findings) {
    var top =
        findings.stream()
            .map(Finding::severity)
            .min(Comparator.naturalOrder())
            .orElseThrow(() -> new IllegalArgumentException("At least one finding is required."));
    return switch (top) {
      case CRITICAL -> 4;
      case HIGH -> 3;
      case MEDIUM -> 2;
      case LOW -> 1;
    };
  }

  static String body(String sourceSpecId, ReviewStore.ReviewRow review, List<Finding> findings) {
    var sb = new StringBuilder();
    sb.append("# Follow-up: open review findings for `")
        .append(sourceSpecId)
        .append("`\n\n")
        .append("Drafted from review `")
        .append(review.id())
        .append("` (iteration ")
        .append(review.iteration())
        .append("). Each section below is one open finding, ordered by severity.\n")
        .append("The linked findings are marked resolved automatically when this spec")
        .append(" reaches `done`.\n");
    var index = 1;
    for (var finding : findings) {
      appendFinding(sb, index++, finding);
    }
    return sb.toString();
  }

  private static void appendFinding(StringBuilder sb, int index, Finding finding) {
    sb.append("\n## ")
        .append(index)
        .append(". [")
        .append(finding.severity())
        .append("] ")
        .append(finding.title())
        .append("\n\n");
    sb.append("- **Severity:** ").append(finding.severity()).append('\n');
    sb.append("- **Category:** ").append(finding.category()).append('\n');
    if (Strings.isNotBlank(finding.file())) {
      sb.append("- **Location:** `").append(location(finding)).append("`\n");
    }
    sb.append("- **Confidence:** ").append(finding.confidence()).append('\n');
    sb.append('\n').append(finding.description()).append('\n');
    if (Strings.isNotBlank(finding.evidence())) {
      sb.append("\n**Evidence:**\n\n").append(finding.evidence()).append('\n');
    }
    appendSuggestion(sb, finding.suggestion());
  }

  private static void appendSuggestion(StringBuilder sb, Finding.Suggestion suggestion) {
    if (suggestion == null) {
      return;
    }
    var hasDiff = Strings.isNotBlank(suggestion.before()) || Strings.isNotBlank(suggestion.after());
    if (!hasDiff && Strings.isBlank(suggestion.rationale())) {
      return;
    }
    sb.append("\n**Suggested fix:**\n");
    if (Strings.isNotBlank(suggestion.before())) {
      sb.append("\nBefore:\n\n```\n").append(suggestion.before()).append("\n```\n");
    }
    if (Strings.isNotBlank(suggestion.after())) {
      sb.append("\nAfter:\n\n```\n").append(suggestion.after()).append("\n```\n");
    }
    if (Strings.isNotBlank(suggestion.rationale())) {
      sb.append("\nRationale: ").append(suggestion.rationale()).append('\n');
    }
  }

  private static String location(Finding finding) {
    var lines =
        finding.lineEnd() > finding.lineStart()
            ? finding.lineStart() + "-" + finding.lineEnd()
            : String.valueOf(finding.lineStart());
    return finding.file() + ":" + lines;
  }
}
