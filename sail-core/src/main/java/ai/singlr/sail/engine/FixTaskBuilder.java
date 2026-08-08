/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.Finding;
import java.util.List;

/**
 * Generates a structured fix task from review findings for the coding agent. Each finding is
 * presented with its severity, category, file/line reference, evidence, and concrete suggestion.
 * The agent receives actionable instructions, not vague feedback.
 */
public final class FixTaskBuilder {

  private FixTaskBuilder() {}

  /**
   * The commit message for a fix iteration's work — used by the guardrail rescue when the fix agent
   * leaves the tree dirty, so the PR history explains what the commit addresses instead of only
   * recording that an agent forgot to commit.
   */
  public static String commitMessage(List<Finding> findings) {
    var subject =
        "fix: address %d review finding%s"
            .formatted(findings.size(), findings.size() == 1 ? "" : "s");
    var body =
        findings.stream()
            .map(f -> "- [%s] %s".formatted(f.severity(), f.title()))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    return body.isEmpty() ? subject : subject + "\n\n" + body;
  }

  public static String build(String specTitle, List<Finding> findings) {
    if (findings.isEmpty()) {
      return "No review findings to address for spec \"%s\".".formatted(specTitle);
    }

    var sb = new StringBuilder();
    sb.append(
        "Your implementation for spec \"%s\" received %d review finding(s).%n"
            .formatted(specTitle, findings.size()));
    sb.append("Address each finding below. The reviewer will re-check after you commit.\n\n");

    for (var i = 0; i < findings.size(); i++) {
      var f = findings.get(i);
      sb.append("--- Finding %d [%s] %s ---\n".formatted(i + 1, f.severity(), f.category()));
      sb.append("Title: %s\n".formatted(f.title()));

      if (f.file() != null) {
        sb.append("File: %s:%d".formatted(f.file(), f.lineStart()));
        if (f.lineEnd() != f.lineStart()) {
          sb.append("-%d".formatted(f.lineEnd()));
        }
        sb.append("\n");
      }

      if (!f.description().isEmpty()) {
        sb.append("Issue: %s\n".formatted(f.description()));
      }

      if (f.evidence() != null && !f.evidence().isEmpty()) {
        sb.append("Evidence: %s\n".formatted(f.evidence()));
      }

      if (f.suggestion() != null && !f.suggestion().rationale().isEmpty()) {
        sb.append("Fix: %s\n".formatted(f.suggestion().rationale()));
        if (!f.suggestion().before().isEmpty()) {
          sb.append("  Before: %s\n".formatted(f.suggestion().before()));
        }
        if (!f.suggestion().after().isEmpty()) {
          sb.append("  After:  %s\n".formatted(f.suggestion().after()));
        }
      }

      sb.append("\n");
    }

    sb.append(
        """
        When every finding is addressed: run the project's verification, commit all changes to
        the current branch with a clear message, and push. Never leave uncommitted work in the
        workspace — the re-review reads the branch, and uncommitted files contaminate the next
        dispatch in this shared clone.
        """);

    return sb.toString();
  }
}
