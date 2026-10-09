/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every paragraph of the three prompts sail wrote at {@code fe47ae67} — the build's under {@code
 * sail-build}, the reviewer's under {@code sail-review}, the fix agent's under {@code sail-fix},
 * each skill's body included — is in the matching prompt of today, whitespace collapsed, or is
 * named here as reworded or deleted, with the sentence that says it now.
 */
class PromptCoverageTest {

  static final String BUILD_AT_FE47AE67 =
      """
      Your current spec: "OAuth" (id: oauth).
      Target repos: api, web

      Target agent: claude-code

      Target model: claude-opus-4-7

      Target reasoning effort: high

      ## Conversation on this spec

      ada (2026-07-28T00:00:00Z):
      Use PKCE

      Implement the flow.

      ## How to do this work (skill: sail-build)

      Execute without waiting for confirmation: plan, implement, test, commit. When complete, run the full local verification the project uses (including any coverage or lint gates).

      Never add AI attribution to the work: no Co-Authored-By trailers and no "Generated with" footers in commit messages or pull request descriptions.

      If the build fails repeatedly on the same error, or three different approaches fail, stop and report rather than retrying.

      ## Autonomous Operation
      When the work is complete, commit with a clear message, push the branch, and open a pull
      request.

      Post progress, questions, and your final summary to this spec's room with
      `spec comment <id> --body <text>` (or `--body -` for stdin).

      The room is a live channel, not a log: replies posted while you work are delivered into
      your context automatically after a tool call finishes, and unread messages block your
      first attempt to stop. When you are blocked on a decision, read the room with
      `spec comments <id>` rather than guessing; if the room does not resolve it, post the
      question with `spec comment <id> --question --body <text>` — the flag pages the
      engineer on the board, and their reply clears it and wakes you. Read the room once more
      before posting your final summary, and acknowledge in that summary any guidance it
      carried.

      The spec is not complete until CI is green: after opening the pull request, watch its
      checks with the CLI of the forge hosting the repo (e.g. `gh pr checks <number> --watch`
      on GitHub, `glab ci status --live` on GitLab, or the equivalent on your forge), and if any
      check fails, diagnose it, fix it on the branch, push, and watch again until every check
      passes.

      This spec spans multiple repos: branch, commit, and open a linked pull request in each affected repo.

      Never leave work uncommitted — a WIP commit beats lost work.
      """;

  static final String REVIEW_AT_FE47AE67 =
      """
      Conversation on this spec:

      ada: Use PKCE

      Review the changes on branch feat/oauth in the following repository directories inside this
      workspace: api, web. Review only those checkouts — ignore any other repositories present.
      If that branch no longer exists, review the spec's changes as merged on the default
      branch instead; do not go hunting for the missing ref.

      Focus on these categories: security, correctness

      ## How to do this work (skill: sail-review)

      Only report genuine issues. Do not flag style preferences or working code.

      Every finding MUST include evidence. If you cannot prove it, do not report it.

      Every finding MUST include a concrete suggestion with before/after code.

      Focus on correctness, security, and reliability — not formatting.

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
      1. Rule on EVERY carried finding: a carried finding you do not mention is treated as
         still_open, so silence never resolves anything.
      2. If there are no new issues, "findings" must be an empty array.

      Begin the JSON object with ```json and end with ```.
      """;

  static final String FIX_AT_FE47AE67 =
      """
      Your implementation for spec "OAuth" received 1 review finding(s).
      Address each finding below. The reviewer will re-check after you commit.
      If you believe a finding is wrong, do NOT code around it and do NOT silently skip it: post
      your argument to the spec room (spec comment oauth --body "...") naming the finding's id,
      and leave that code alone. The re-review reads the room and rules fixed, still_open, or
      disputed with your argument as evidence — a finding is retired by argument in the open,
      never by omission.

      Conversation on this spec — it may carry guidance on the findings below:

      ada: Use PKCE

      ## How to do this work (skill: sail-fix)

      Fix what is real.

      When every finding is addressed, run the project's verification locally before you commit.

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

      When every finding is addressed: commit all changes to the current branch with a clear
      message, push, and end your turn. Do not wait for or watch CI: the re-review judges the
      branch, and the pull request shows its checks to whoever merges. Never leave uncommitted
      work in the workspace — the re-review reads the branch, and uncommitted files contaminate
      the next dispatch in this shared clone.
      """;

  /** A prompt's paragraphs, each with its whitespace collapsed. */
  static List<String> paragraphs(String prompt) {
    return Arrays.stream(prompt.split("\\n\\s*\\n"))
        .map(PromptCoverageTest::collapsed)
        .filter(paragraph -> !paragraph.isEmpty())
        .toList();
  }

  static String collapsed(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }

  /** The paragraphs of {@code before} that {@code after}, whitespace collapsed, no longer holds. */
  static List<String> reworded(String before, String after) {
    var now = collapsed(after);
    return paragraphs(before).stream().filter(paragraph -> !now.contains(paragraph)).toList();
  }

  static void assertHolds(String prompt, String... fragments) {
    var now = collapsed(prompt);
    for (var fragment : fragments) {
      assertTrue(now.contains(fragment), "missing: " + fragment);
    }
  }

  private static List<String> startingWith(List<String> paragraphs, String... prefixes) {
    return Arrays.stream(prefixes)
        .map(
            prefix ->
                paragraphs.stream()
                    .filter(paragraph -> paragraph.startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no paragraph starts: " + prefix)))
        .toList();
  }

  @Test
  void theWorkPromptSaysEverythingTheBuildPromptAndItsSkillSaid() {
    var reworded = reworded(BUILD_AT_FE47AE67, WorkPromptGoldenTest.BUILD);

    assertEquals(
        startingWith(
            paragraphs(BUILD_AT_FE47AE67),
            "## How to do this work (skill: sail-build)",
            "Execute without waiting for confirmation",
            "If the build fails repeatedly on the same error",
            "## Autonomous Operation When the work is complete",
            "The spec is not complete until CI is green: after opening the pull request, watch"),
        reworded);
    assertHolds(
        WorkPromptGoldenTest.BUILD,
        "Act without waiting for confirmation: plan, implement, test, commit.",
        "run the project's full verification (its tests, coverage and lint gates) before you"
            + " commit",
        "If the same error defeats three attempts, or three different approaches fail, stop and"
            + " say so in the room rather than retrying.",
        "When the work is complete, commit with a clear message, push the branch, and make sure a"
            + " pull request is open for it.",
        "The work is not complete until CI is green: check the pull request's checks with the"
            + " forge's CLI (`gh pr checks <number>` on GitHub, `glab ci status` on GitLab, or your"
            + " forge's equivalent) in short polls, each well under 20 minutes, never one long"
            + " watch; if a check fails, diagnose it, fix it on the branch, push, and check again"
            + " until every check passes.");
  }

  @Test
  void theJudgePromptSaysEverythingTheReviewPromptAndItsSkillSaid() {
    var reworded = reworded(REVIEW_AT_FE47AE67, JudgePromptGoldenTest.JUDGE);

    assertEquals(
        startingWith(
            paragraphs(REVIEW_AT_FE47AE67),
            "Focus on these categories",
            "## How to do this work (skill: sail-review)",
            "Only report genuine issues.",
            "Every finding MUST include evidence.",
            "Every finding MUST include a concrete suggestion",
            "Focus on correctness, security, and reliability"),
        reworded);
    assertHolds(
        JudgePromptGoldenTest.JUDGE,
        "Report only genuine issues, each with evidence; if you cannot prove it, do not report it.",
        "Every finding carries a concrete suggestion with before and after code.",
        "correctness, security, error handling, concurrency, resource use and API contracts",
        "Formatting and style are not findings.");
  }

  @Test
  void theWorkPromptSaysEverythingTheFixTaskAndItsSkillSaid() {
    var reworded = reworded(FIX_AT_FE47AE67, WorkPromptGoldenTest.FIX);

    assertEquals(
        startingWith(
            paragraphs(FIX_AT_FE47AE67),
            "Your implementation for spec \"OAuth\" received 1 review finding(s).",
            "Conversation on this spec — it may carry guidance",
            "ada: Use PKCE",
            "## How to do this work (skill: sail-fix)",
            "Fix what is real.",
            "When every finding is addressed, run the project's verification locally",
            "When every finding is addressed: commit all changes"),
        reworded);
    assertHolds(
        WorkPromptGoldenTest.FIX,
        "Your implementation received 1 review finding(s). Address each one below; the reviewer"
            + " re-checks the branch after you push.",
        "If you believe a finding is wrong, do NOT code around it and do NOT silently skip it:"
            + " post your argument to the spec room (spec comment oauth --body \"...\") naming the"
            + " finding's id, and leave that code alone. The re-review reads the room and rules"
            + " fixed, still_open, or disputed with your argument as evidence — a finding is"
            + " retired by argument in the open, never by omission.",
        "## Conversation on this spec ada (2026-07-28T00:00:00Z): Use PKCE",
        "run the project's full verification (its tests, coverage and lint gates) before you"
            + " commit",
        "Never leave work uncommitted — a WIP commit beats lost work. Uncommitted files"
            + " contaminate the next run in this shared clone.");
  }
}
