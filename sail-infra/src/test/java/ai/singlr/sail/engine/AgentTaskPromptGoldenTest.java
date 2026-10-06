/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.MessageStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The build's prompt, whole: a spec that names its repos, agent, model and reasoning effort, spans
 * two repos and has one room message, so every paragraph the builder can write is in the text.
 */
class AgentTaskPromptGoldenTest {

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
          null);

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
      ## Autonomous Operation
      Execute without waiting for confirmation: plan, implement, test, commit. When complete, run
      the full local verification the project uses (including any coverage or lint gates), commit
      with a clear message, push the branch, and open a pull request.

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

      Never add AI attribution to the work: no Co-Authored-By trailers and no "Generated with"
      footers in commit messages or pull request descriptions.

      This spec spans multiple repos: branch, commit, and open a linked pull request in each affected repo.
      If the build fails repeatedly on the same error, or three different approaches fail, stop and report rather than retrying. Never leave work uncommitted — a WIP commit beats lost work.
      """;

  @Test
  void theBuildPromptIsThisTextWhole() {
    var prompt = AgentTaskPrompt.build(SPEC, "Implement the flow.", List.of(MESSAGE)).prompt();

    assertEquals(BUILD, prompt);
  }
}
