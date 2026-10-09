/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.ArrayList;
import java.util.List;

/**
 * The one prompt every agent stage of the review reads: the room, the spec, what to review, what
 * this stage judges (its brief, the one text a project writes, or sail's default) and the verdict
 * contract, which is sail's and follows the brief: a ruling on every finding carried forward from
 * the previous review plus any newly discovered findings, as one JSON object. Nothing in a brief
 * changes how a verdict is read, and a carried finding can only be retired by a verdict with
 * evidence, never by omission.
 */
public final class JudgePrompt {

  /** What a stage judges when the project writes no brief for it. */
  public static final String DEFAULT_BRIEF =
      """
      Judge the branch against the spec first: its stated design, its bar and its non-goals. Drift
      from the spec is a finding.

      Then judge the code as a senior reviewer would: correctness, security, error handling,
      concurrency, resource use and API contracts; single responsibility and no duplication; tests
      that prove the behaviour the spec changed. Formatting and style are not findings.

      Report only genuine issues, each with evidence; if you cannot prove it, do not report it. Every
      finding carries a concrete suggestion with before and after code.""";

  private JudgePrompt() {}

  /**
   * A built prompt and the room messages it rendered in full — the exact set the caller may
   * acknowledge as delivered at the reviewer's launch. Delivery derives from presentation: a
   * message the budget truncated or omitted is absent here and stays owed a full delivery.
   */
  public record Built(String prompt, List<MessageStore.MessageRow> renderedMessages) {}

  /**
   * @param spec the spec under review: its title and id head the prompt, its repos are the
   *     checkouts to review (never the project name: a multi-repo workspace root holds several
   *     repos, and a wrong name sends the reviewer into an unrelated codebase) and its branch is
   *     the one to review
   * @param body the spec's body, cut at a section boundary to the prompt budget and saying so
   * @param stage the name of the stage
   * @param brief what the stage judges; the verdict contract follows it, and nothing it says
   *     replaces that
   * @param messages the room's recent conversation, of which the newest that fit the prompt budget
   *     are rendered
   * @param carried the previous review's still-open findings, which the reviewer must rule on: one
   *     verdict per carried finding, alongside (not instead of) any new findings
   */
  public static Built build(
      Spec spec,
      String body,
      String stage,
      String brief,
      List<MessageStore.MessageRow> messages,
      List<Finding> carried) {
    var conversation =
        PromptConversation.renderNewest(
            messages, message -> message.author() + ": " + message.body() + "\n\n");
    return new Built(
        (messages.isEmpty() ? "" : "Conversation on this spec:\n\n" + conversation.text())
            + theSpec(spec, body)
            + whatToReview(spec)
            + "## What this stage judges (stage: "
            + stage
            + ")\n\n"
            + brief.strip()
            + "\n\n"
            + carryForward(carried)
            + verdictContract(),
        conversation.fullyRendered());
  }

  private static String theSpec(Spec spec, String body) {
    var heading = "## The spec\n\n\"" + spec.title() + "\" (id: " + spec.id() + ")\n\n";
    if (body == null || body.isBlank()) {
      return heading;
    }
    var cut = cutAtSection(body.strip(), PromptConversation.MAX_CODE_POINTS);
    var text = cut.text();
    if (cut.omitted() > 0) {
      text +=
          "\n\n… (the spec continues; "
              + cut.omitted()
              + " section"
              + (cut.omitted() == 1 ? "" : "s")
              + " omitted for length)";
    }
    return heading + text + "\n\n";
  }

  /** A body cut to {@code budget} code points, and how many of its sections were left out. */
  record Cut(String text, int omitted) {}

  /**
   * The body up to the last markdown section that fits the budget whole; the sections after it are
   * counted. A body whose first section alone exceeds the budget is cut inside that section, and
   * every other section is counted as omitted.
   */
  static Cut cutAtSection(String body, int budget) {
    if (body.codePointCount(0, body.length()) <= budget) {
      return new Cut(body, 0);
    }
    var sections = sections(body);
    var kept = new StringBuilder();
    var count = 0;
    for (var section : sections) {
      var next = kept.isEmpty() ? section : kept + "\n" + section;
      if (next.codePointCount(0, next.length()) > budget) {
        break;
      }
      kept.setLength(0);
      kept.append(next);
      count++;
    }
    if (count == 0) {
      var first = sections.getFirst();
      return new Cut(
          first.substring(0, first.offsetByCodePoints(0, budget)).stripTrailing(),
          sections.size() - 1);
    }
    return new Cut(kept.toString().stripTrailing(), sections.size() - count);
  }

  /** The body split before each markdown heading line; the text before the first is a section. */
  private static List<String> sections(String body) {
    var sections = new ArrayList<String>();
    var current = new StringBuilder();
    for (var line : body.split("\n", -1)) {
      if (isHeading(line) && !current.isEmpty()) {
        sections.add(current.toString());
        current.setLength(0);
      }
      if (!current.isEmpty()) {
        current.append('\n');
      }
      current.append(line);
    }
    sections.add(current.toString());
    return sections;
  }

  private static boolean isHeading(String line) {
    var hashes = 0;
    while (hashes < line.length() && line.charAt(hashes) == '#') {
      hashes++;
    }
    return hashes >= 1 && hashes <= 6 && hashes < line.length() && line.charAt(hashes) == ' ';
  }

  private static String whatToReview(Spec spec) {
    var repos = spec.repos();
    var repoList = repos.isEmpty() ? "the repository in the workspace" : String.join(", ", repos);
    return """
        Review the changes on branch %s in the following repository director%s inside this
        workspace: %s. Review only those checkouts — ignore any other repositories present. If that
        branch no longer exists, review the spec's changes as merged on the default branch instead; do
        not go hunting for the missing ref.

        """
        .formatted(spec.branch(), repos.size() == 1 ? "y" : "ies", repoList);
  }

  /**
   * The carry-forward contract: the previous review's open findings, each with the id the verdict
   * must cite and the evidence its carrying ruling rested on, so the re-review rules against its
   * own prior scenario rather than rediscovering it. Rendered before the response-format
   * instructions so the reviewer reads what it must rule on before it reads how to answer.
   */
  private static String carryForward(List<Finding> carried) {
    if (carried.isEmpty()) {
      return "";
    }
    var lines =
        carried.stream()
            .map(
                f ->
                    "- finding_id %s [%s] %s%s%s"
                        .formatted(f.id(), f.severity(), f.title(), location(f), carryEvidence(f)))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    return """
        The previous review left these findings open. Re-examine each one against the current
        state of the branch and include a verdict for every single one in "verdicts":

        %s

        """
        .formatted(lines);
  }

  private static String carryEvidence(Finding f) {
    if (f.carryEvidence() == null || f.carryEvidence().isBlank()) {
      return "";
    }
    return "\n  Prior ruling's evidence that it remains open: " + f.carryEvidence();
  }

  private static String location(Finding f) {
    if (f.file() == null) {
      return "";
    }
    var span = f.lineEnd() != f.lineStart() ? "-" + f.lineEnd() : "";
    return " (" + f.file() + ":" + f.lineStart() + span + ")";
  }

  private static String verdictContract() {
    return """
        Respond with exactly one JSON object — the verdict envelope:
        {"verdicts": [<one verdict per carried finding>], "findings": [<new findings only>]}

        Each entry in "verdicts" rules on one carried finding listed above and must have:
        - finding_id: the id exactly as listed above
        - verdict: "fixed", "still_open", or "disputed"
        - evidence: required for fixed (cite the commit or current code that resolves it) and
          for disputed (state why the finding is wrong; an argument posted in the conversation
          above counts). A fixed or disputed verdict without evidence is treated as still_open.
          For still_open, describe the exact scenario that is still broken — the next fix
          iteration reads it as its reproduction target.
        If no carried findings are listed above, "verdicts" must be an empty array.

        Each entry in "findings" reports a NEW issue (never repeat a carried finding) and must have:
        - severity: CRITICAL, HIGH, MEDIUM, or LOW
        - category: one of SECURITY, LOGIC, EDGE_CASE, PERFORMANCE, ERROR_HANDLING, CONCURRENCY, RESOURCE_LEAK, API_CONTRACT
        - file: relative file path
        - line_start: first affected line number
        - line_end: last affected line number (same as line_start for single-line issues)
        - title: one-line summary of the issue
        - description: detailed explanation of the problem
        - evidence: proof this is a real issue (data flow trace, failing test case, CVE reference, or logical argument)
        - suggestion: an object with "before" (current code), "after" (fixed code), and "rationale" (why the fix works)
        - confidence: 0.0 to 1.0 indicating your certainty

        Rules:
        1. Rule on EVERY carried finding: a carried finding you do not mention is treated as
           still_open, so silence never resolves anything.
        2. If there are no new issues, "findings" must be an empty array.

        Begin the JSON object with ```json and end with ```.
        """;
  }
}
