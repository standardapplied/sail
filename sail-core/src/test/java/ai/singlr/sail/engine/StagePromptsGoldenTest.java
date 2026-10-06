/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reviewer's prompt and the fix agent's task, whole: one room message, one carried finding with
 * everything a finding can hold, so every paragraph either builder can write is in the text.
 */
class StagePromptsGoldenTest {

  static final MessageStore.MessageRow MESSAGE =
      new MessageStore.MessageRow(
          "01900000-0000-7000-8000-000000000001",
          "oauth",
          "ada",
          "Use PKCE",
          null,
          "2026-07-28T00:00:00Z",
          "1-a",
          null,
          false);

  static final Finding FINDING =
      new Finding(
          "01900000-0000-7000-8000-00000000000f",
          Finding.Severity.HIGH,
          Finding.Category.CONCURRENCY,
          "src/Dispatch.java",
          10,
          12,
          "Seed-window race",
          "Two dispatches can claim one seed.",
          "Trace of the race",
          new Finding.Suggestion("claim(seed)", "claimOnce(seed)", "Claim under the lock"),
          0.9,
          Finding.Resolution.OPEN,
          null,
          "01900000-0000-7000-8000-00000000000e",
          "restart between reserve and claim still double-seeds");

  static final String REVIEW =
      """
      Conversation on this spec:

      ada: Use PKCE

      Review the changes on branch feat/oauth in the following repository directories inside this
      workspace: api, web. Review only those checkouts — ignore any other repositories present.
      If that branch no longer exists, review the spec's changes as merged on the default
      branch instead; do not go hunting for the missing ref.

      Focus on these categories: security, correctness

      The previous review left these findings open. Re-examine each one against the current
      state of the branch and include a verdict for every single one in "verdicts":

      - finding_id 01900000-0000-7000-8000-00000000000f [HIGH] Seed-window race (src/Dispatch.java:10-12)
        Prior ruling's evidence that it remains open: restart between reserve and claim still double-seeds

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
      1. Only report genuine issues. Do not flag style preferences or working code.
      2. Every finding MUST include evidence. If you cannot prove it, do not report it.
      3. Every finding MUST include a concrete suggestion with before/after code.
      4. Focus on correctness, security, and reliability — not formatting.
      5. Rule on EVERY carried finding: a carried finding you do not mention is treated as
         still_open, so silence never resolves anything.
      6. If there are no new issues, "findings" must be an empty array.

      Begin the JSON object with ```json and end with ```.
      """;

  static final String FIX =
      """
      Your implementation for spec "OAuth" received 1 review finding(s).
      Address each finding below. The reviewer will re-check after you commit.
      Fix what is real. If you believe a finding is wrong, do NOT code around it and do NOT
      silently skip it: post your argument to the spec room (spec comment oauth --body "...")
      naming the finding's id, and leave that code alone. The re-review reads the room and
      rules fixed, still_open, or disputed with your argument as evidence — a finding is
      retired by argument in the open, never by omission.

      Conversation on this spec — it may carry guidance on the findings below:

      ada: Use PKCE

      --- Finding 1 [HIGH] CONCURRENCY ---
      Id: 01900000-0000-7000-8000-00000000000f
      Title: Seed-window race
      File: src/Dispatch.java:10-12
      Issue: Two dispatches can claim one seed.
      Evidence: Trace of the race
      Reviewer's evidence that this remains open: restart between reserve and claim still double-seeds
      Treat this as a reproduction claim — investigate this exact scenario before
      re-claiming it fixed; if your investigation shows the scenario cannot occur,
      dispute with that analysis.
      Fix: Claim under the lock
        Before: claim(seed)
        After:  claimOnce(seed)

      When every finding is addressed: run the project's verification locally, commit all
      changes to the current branch with a clear message, push, and end your turn. Do not
      wait for or watch CI: the re-review judges the branch, and the pull request shows its
      checks to whoever merges. Never leave uncommitted work in the workspace — the re-review
      reads the branch, and uncommitted files contaminate the next dispatch in this shared
      clone.
      """;

  @Test
  void theReviewPromptIsThisTextWhole() {
    var prompt =
        ReviewPromptBuilder.build(
                "feat/oauth",
                List.of("api", "web"),
                List.of("security", "correctness"),
                List.of(MESSAGE),
                List.of(FINDING))
            .prompt();

    assertEquals(REVIEW, prompt);
  }

  @Test
  void theFixTaskIsThisTextWhole() {
    var task = FixTaskBuilder.build("oauth", "OAuth", List.of(FINDING), List.of(MESSAGE)).task();

    assertEquals(FIX, task);
  }
}
