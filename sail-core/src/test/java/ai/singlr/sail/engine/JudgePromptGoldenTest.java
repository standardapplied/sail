/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static ai.singlr.sail.engine.WorkPromptGoldenTest.FINDING;
import static ai.singlr.sail.engine.WorkPromptGoldenTest.MESSAGE;
import static ai.singlr.sail.engine.WorkPromptGoldenTest.SPEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The judge prompt under sail's default brief, whole: one room message, a spec body, one carried
 * finding with everything a finding can hold, so every paragraph the prompt can carry is in the
 * text.
 */
class JudgePromptGoldenTest {

  static final String VERDICT_CONTRACT =
      """
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

  static final String JUDGE =
      """
      Conversation on this spec:

      ada: Use PKCE

      ## The spec

      "OAuth" (id: oauth)

      Implement the flow.

      Review the changes on branch feat/oauth in the following repository directories inside this
      workspace: api, web. Review only those checkouts — ignore any other repositories present. If that
      branch no longer exists, review the spec's changes as merged on the default branch instead; do
      not go hunting for the missing ref.

      ## What this stage judges (stage: review)

      Judge the branch against the spec first: its stated design, its bar and its non-goals. Drift
      from the spec is a finding.

      Then judge the code as a senior reviewer would: correctness, security, error handling,
      concurrency, resource use and API contracts; single responsibility and no duplication; tests
      that prove the behaviour the spec changed. Formatting and style are not findings.

      Report only genuine issues, each with evidence; if you cannot prove it, do not report it. Every
      finding carries a concrete suggestion with before and after code.

      The previous review left these findings open. Re-examine each one against the current
      state of the branch and include a verdict for every single one in "verdicts":

      - finding_id 01900000-0000-7000-8000-00000000000f [HIGH] Seed-window race (src/Dispatch.java:10-12)
        Prior ruling's evidence that it remains open: restart between reserve and claim still double-seeds

      """
          + VERDICT_CONTRACT;

  static String judge(
      Spec spec,
      String body,
      String brief,
      List<MessageStore.MessageRow> messages,
      List<Finding> carried) {
    return JudgePrompt.build(spec, body, "review", brief, messages, carried).prompt();
  }

  @Test
  void theDefaultJudgePromptIsThisTextWhole() {
    assertEquals(
        JUDGE,
        judge(
            SPEC,
            "Implement the flow.",
            JudgePrompt.DEFAULT_BRIEF,
            List.of(MESSAGE),
            List.of(FINDING)));
  }

  @Test
  void aStagesBriefGoesWhereTheDefaultWentAndNothingElseDiffers() {
    var brief = "Run the project's e2e skill against this branch and report every failure.";

    assertEquals(
        JUDGE.replace(JudgePrompt.DEFAULT_BRIEF, brief),
        judge(SPEC, "Implement the flow.", brief + "\n", List.of(MESSAGE), List.of(FINDING)));
  }

  @Test
  void aSpecInOneRepoWithNoRoomNoBodyAndNothingCarried() {
    var bare =
        new Spec(
            "oauth",
            "test",
            "OAuth",
            SpecStatus.PENDING,
            null,
            List.of(),
            List.of("api"),
            null,
            null,
            null,
            "feat/oauth");

    assertEquals(
        """
        ## The spec

        "OAuth" (id: oauth)

        Review the changes on branch feat/oauth in the following repository directory inside this
        workspace: api. Review only those checkouts — ignore any other repositories present. If that
        branch no longer exists, review the spec's changes as merged on the default branch instead; do
        not go hunting for the missing ref.

        ## What this stage judges (stage: review)

        """
            + JudgePrompt.DEFAULT_BRIEF
            + "\n\n"
            + VERDICT_CONTRACT,
        judge(bare, "", JudgePrompt.DEFAULT_BRIEF, List.of(), List.of()));
  }

  @Test
  void aBodyLongerThanTheBudgetIsCutAtASectionBoundaryAndSaysSo() {
    var section = "## Section\n\n" + "x".repeat(10_000) + "\n";
    var body = "Intro.\n\n" + section + "\n" + section + "\n" + section + "\n" + section;

    var prompt = judge(SPEC, body, JudgePrompt.DEFAULT_BRIEF, List.of(), List.of());

    var expected =
        "Intro.\n\n"
            + section
            + "\n"
            + section
            + "\n"
            + section.stripTrailing()
            + "\n\n… (the spec continues; 1 section omitted for length)\n\n"
            + "Review the changes on branch";
    assertTrue(prompt.contains(expected), prompt.substring(0, 200));
    assertTrue(
        prompt.codePointCount(0, prompt.indexOf("Review the changes"))
            <= PromptConversation.MAX_CODE_POINTS + 200,
        "the body is within the budget");
  }

  @Test
  void aFirstSectionAloneOverTheBudgetIsCutInsideItAndEveryOtherSectionIsCounted() {
    var cut = JudgePrompt.cutAtSection("a".repeat(50) + "\n## Two\nb\n## Three\nc", 10);

    assertEquals(new JudgePrompt.Cut("a".repeat(10), 2), cut);
  }

  @Test
  void aBodyWithinTheBudgetIsCarriedWholeAndCountsNothing() {
    assertEquals(
        new JudgePrompt.Cut("Intro\n## One\nx\n### Two\ny", 0),
        JudgePrompt.cutAtSection("Intro\n## One\nx\n### Two\ny", 100));
  }

  @Test
  void aHashLineThatIsNoHeadingOpensNoSection() {
    assertEquals(
        new JudgePrompt.Cut("#tag\n#######\nx", 1),
        JudgePrompt.cutAtSection("#tag\n#######\nx\n## Two\n" + "y".repeat(20), 14));
  }

  @Test
  void theRenderedMessagesAreThoseThePromptCarriesWhole() {
    assertEquals(
        List.of(MESSAGE),
        JudgePrompt.build(
                SPEC, "", "review", JudgePrompt.DEFAULT_BRIEF, List.of(MESSAGE), List.of())
            .renderedMessages());
  }
}
