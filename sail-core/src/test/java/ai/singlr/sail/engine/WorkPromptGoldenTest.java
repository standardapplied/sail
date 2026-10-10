/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The work prompt, whole: a build's for a spec that names its repos, agent, model and reasoning
 * effort, spans two repos and has one room message, and a fix run's for the same spec with one
 * carried finding holding everything a finding can hold, so every paragraph the prompt can carry is
 * in the text.
 */
class WorkPromptGoldenTest {

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

  static final Spec SPEC =
      new Spec(
          "oauth",
          "test",
          "OAuth",
          SpecStatus.PENDING,
          null,
          List.of(),
          List.of("api", "web"),
          "claude-code",
          "claude-opus-4-7",
          "high",
          "feat/oauth");

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

  static final String HOW_THIS_RUN_WORKS =
      """
      ## How this run works

      You are running unattended in a sail container. Act without waiting for confirmation: plan,
      implement, test, commit. Stay on the current branch.

      This repository's own instructions (AGENTS.md and the project's skills) say how it is built,
      tested and verified. Follow them, and run the project's full verification (its tests, coverage
      and lint gates) before you commit; sail runs none of it for you.

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

      The spec's status is sail's: never change it.

      Never add AI attribution to the work: no Co-Authored-By trailers and no "Generated with"
      footers in commit messages or pull request descriptions.

      If the same error defeats three attempts, or three different approaches fail, stop and say so
      in the room rather than retrying.

      When the work is complete, commit with a clear message, push the branch, and make sure a pull
      request is open for it. This spec spans multiple repos: branch, commit, and open a linked pull request in each affected repo. The work is not complete until CI is green: check the pull request's checks with the
      forge's CLI (`gh pr checks <number>` on GitHub, `glab ci status` on GitLab, or your forge's
      equivalent) in short polls, each well under 20 minutes, never one long watch; if a check
      fails, diagnose it, fix it on the branch, push, and check again until every check passes. If
      no authenticated forge CLI is available in this container, say so in the room with the pull
      request's URL and end your turn; do not claim CI green.

      Never leave work uncommitted — a WIP commit beats lost work. Uncommitted files contaminate the
      next run in this shared clone.
      """;

  static final String BUILD =
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

      """
          + HOW_THIS_RUN_WORKS;

  static final String FIX =
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

      ## Review findings on your branch

      Your implementation received 1 review finding(s). Address each one below; the reviewer
      re-checks the branch after you push.
      If you believe a finding is wrong, do NOT code around it and do NOT silently skip it: post
      your argument to the spec room (spec comment oauth --body "...") naming the finding's id, and
      leave that code alone. The re-review reads the room and rules fixed, still_open, or disputed
      with your argument as evidence — a finding is retired by argument in the open, never by
      omission.

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

      """
          + HOW_THIS_RUN_WORKS;

  static String build(Spec spec, List<Finding> findings) {
    return WorkPrompt.build(spec, "Implement the flow.", List.of(MESSAGE), findings).prompt();
  }

  @Test
  void theBuildPromptIsThisTextWhole() {
    assertEquals(BUILD, build(SPEC, List.of()));
  }

  @Test
  void theFixPromptIsTheBuildPromptWithTheFindingsSection() {
    assertEquals(FIX, build(SPEC, List.of(FINDING)));
  }

  @Test
  void aSpecInOneRepoIsToldNothingOfLinkedPullRequests() {
    var oneRepo =
        new Spec(
            "oauth",
            "test",
            "OAuth",
            SpecStatus.PENDING,
            null,
            List.of(),
            List.of("api"),
            "claude-code",
            "claude-opus-4-7",
            "high",
            "feat/oauth");

    assertEquals(
        BUILD
            .replace("Target repos: api, web", "Target repo: api")
            .replace(
                " This spec spans multiple repos: branch, commit, and open a linked pull request in"
                    + " each affected repo.",
                ""),
        build(oneRepo, List.of()));
  }

  @Test
  void aSpecWithNoRoomAndNoTargetsIsTheBodyAndHowTheRunWorks() {
    var bare =
        new Spec(
            "oauth",
            "test",
            "OAuth",
            SpecStatus.PENDING,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);

    assertEquals(
        "Your current spec: \"OAuth\" (id: oauth).\n\nImplement the flow.\n\n"
            + HOW_THIS_RUN_WORKS.replace(
                " This spec spans multiple repos: branch, commit, and open a linked pull request in"
                    + " each affected repo.",
                ""),
        WorkPrompt.build(bare, "Implement the flow.", List.of(), List.of()).prompt());
  }

  @Test
  void theRenderedMessagesAreThoseThePromptCarriesWhole() {
    assertEquals(
        List.of(MESSAGE),
        WorkPrompt.build(SPEC, "Implement the flow.", List.of(MESSAGE), List.of())
            .renderedMessages());
  }
}
