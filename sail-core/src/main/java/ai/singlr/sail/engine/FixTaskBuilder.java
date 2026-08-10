/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;

/**
 * Generates a structured fix task from review findings for the coding agent. Each finding is
 * presented with its id, severity, category, file/line reference, evidence, and concrete
 * suggestion. The agent receives actionable instructions, not vague feedback — and a dispute lane:
 * a finding it believes is wrong is argued in the spec room for the re-review to rule on, never
 * coded around and never silently skipped.
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

  public static String build(
      String specId,
      String specTitle,
      List<Finding> findings,
      List<MessageStore.MessageRow> messages) {
    if (findings.isEmpty()) {
      return "No review findings to address for spec \"%s\".".formatted(specTitle);
    }

    var sb = new StringBuilder();
    sb.append(
        "Your implementation for spec \"%s\" received %d review finding(s).%n"
            .formatted(specTitle, findings.size()));
    sb.append("Address each finding below. The reviewer will re-check after you commit.\n");
    sb.append(
        """
        Fix what is real. If you believe a finding is wrong, do NOT code around it and do NOT
        silently skip it: post your argument to the spec room (spec comment %s --body "...")
        naming the finding's id, and leave that code alone. The re-review reads the room and
        rules fixed, still_open, or disputed with your argument as evidence — a finding is
        retired by argument in the open, never by omission.

        """
            .formatted(specId));
    sb.append(conversation(messages));

    for (var i = 0; i < findings.size(); i++) {
      var f = findings.get(i);
      sb.append("--- Finding %d [%s] %s ---\n".formatted(i + 1, f.severity(), f.category()));
      sb.append("Id: %s\n".formatted(f.id()));
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

  private static String conversation(List<MessageStore.MessageRow> messages) {
    if (messages.isEmpty()) {
      return "";
    }
    return "Conversation on this spec — it may carry guidance on the findings below:\n\n"
        + PromptConversation.renderNewest(
            messages, message -> message.author() + ": " + message.body() + "\n\n");
  }
}
