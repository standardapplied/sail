/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerReviewAgentRunnerTest {

  private static final String REVIEW_ID = "0197a2f0-0000-7000-8000-0000000000aa";
  private static final String OTHER_REVIEW_ID = "0197a2f0-0000-7000-8000-0000000000bb";
  private static final String REVIEW_DIR = "/home/dev/.sail/runs/" + REVIEW_ID;
  private static final String CREDENTIAL = "sailrun_review_cred";

  private static ContainerReviewAgentRunner runner(ShellExec shell) {
    return new ContainerReviewAgentRunner(shell, new AgentSession(shell));
  }

  @Test
  void returnsFindingsFromReviewLogWhenTheAgentSucceeds() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("tail -c", "{\"type\":\"result\",\"result\":\"FINDINGS\"}");

    assertEquals(
        "FINDINGS", runner(shell).run("acme", "codex", "review please", REVIEW_ID, CREDENTIAL));
  }

  @Test
  void runsTheStreamingAgentCleanAndAppendsToItsOwnReviewLog() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "claude-code", "p", REVIEW_ID, CREDENTIAL);

    var exec =
        shell.invocations().stream()
            .filter(c -> c.contains(">> " + REVIEW_DIR + "/review.log"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the agent must stream to its review's own log"));
    assertTrue(exec.contains("stream-json"), "the reviewer streams so review.log fills live");
    assertTrue(exec.contains("cd /home/dev/workspace"), "runs in the workspace to read the diff");
    assertTrue(
        exec.contains(REVIEW_DIR + "/review-prompt.txt"),
        "prompt staged to the review's own task file");
    assertTrue(
        exec.contains("--settings /home/dev/.sail/claude-settings.json"),
        "the reviewer loads the same single hooks layer as every sail-launched claude session;"
            + " with neither SAIL_SPEC_ID nor SAIL_RUN_ID exported every hook is inert");
    assertFalse(
        exec.contains("SAIL_SPEC_ID"), "reviewer runs without a spec id, so it can't recurse");
    assertFalse(
        exec.contains("systemd-run"), "review blocks; it needs no detached unit or user bus");
  }

  @Test
  void injectsTheRunCredentialPositionallyNeverInterpolatedIntoTheScript() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "codex", "p", REVIEW_ID, CREDENTIAL);

    var exec =
        shell.invocations().stream()
            .filter(c -> c.contains(">> " + REVIEW_DIR + "/review.log"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        exec.contains("export SAIL_RUN_CREDENTIAL=\"$1\""),
        "the script reads the credential from its positional argument");
    assertTrue(exec.contains(CREDENTIAL), "the credential travels as the argument itself");
    assertFalse(
        exec.contains("SAIL_RUN_CREDENTIAL=" + CREDENTIAL),
        "the plaintext is never interpolated into the script text");
  }

  @Test
  void concurrentReviewsNeverShareAPromptOrLogPath() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "codex", "review a", REVIEW_ID, CREDENTIAL);
    runner(shell).run("acme", "codex", "review b", OTHER_REVIEW_ID, CREDENTIAL);

    var joined = String.join("\n", shell.invocations());
    assertTrue(joined.contains(REVIEW_DIR + "/review.log"));
    assertTrue(joined.contains("/home/dev/.sail/runs/" + OTHER_REVIEW_ID + "/review.log"));
    assertFalse(
        joined.contains("/home/dev/.sail/review.log"),
        "no invocation may touch the fixed shared review log");
  }

  @Test
  void theFixLaneArmsTheStopGateWithTheRunIdentity() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "done");

    runner(shell)
        .runFix(
            "acme",
            "claude-code",
            "fix it",
            REVIEW_ID,
            CREDENTIAL,
            "agent/spec",
            List.of("api"),
            null,
            null);

    var exec =
        shell.invocations().stream()
            .filter(c -> c.contains(">> " + REVIEW_DIR + "/review.log"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the fix agent must append to the review log"));
    assertTrue(
        exec.contains("export SAIL_RUN_ID=\"$2\""),
        "the run id arms the stop gate, read from its positional argument");
    assertTrue(exec.contains(REVIEW_ID), "the review id travels as the argument itself");
    assertTrue(
        exec.contains("--settings /home/dev/.sail/claude-settings.json"),
        "one hooks layer: the fix lane loads the same settings as dispatch; the run id alone"
            + " arms the gate and the missing spec id keeps the event hooks silent");
    assertFalse(
        exec.contains("SAIL_SPEC_ID"),
        "no spec id: the event helper stays silent and the pipeline can never re-enter");
  }

  @Test
  void theFixLaneForCodexArmsTheGateWithoutClaudeSettings() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "done");

    runner(shell)
        .runFix(
            "acme",
            "codex",
            "fix it",
            REVIEW_ID,
            CREDENTIAL,
            "agent/spec",
            List.of("api"),
            null,
            null);

    var exec =
        shell.invocations().stream()
            .filter(c -> c.contains(">> " + REVIEW_DIR + "/review.log"))
            .findFirst()
            .orElseThrow();
    assertTrue(exec.contains("export SAIL_RUN_ID=\"$2\""));
    assertTrue(
        exec.contains("--dangerously-bypass-hook-trust"),
        "codex discovers the global hooks file; the trust bypass is what arms it headlessly");
    assertFalse(exec.contains("--settings"), "the claude settings flag never leaks into codex");
  }

  @Test
  void theFixLaneScopesTheGateToTheSpecReposViaTheSessionFile() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "done");

    runner(shell)
        .runFix(
            "acme",
            "claude-code",
            "fix it",
            REVIEW_ID,
            CREDENTIAL,
            "agent/spec",
            List.of("api", "web"),
            null,
            null);

    var write =
        shell.invocations().stream()
            .filter(c -> c.contains(REVIEW_DIR + "/agent-session.json"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the fix lane must stamp the gate's session file, or the gate checks"
                            + " every repo in the shared container"));
    assertTrue(write.contains("api") && write.contains("web"), "session carries the spec repos");
    assertTrue(write.contains("agent/spec"), "session carries the spec branch");
  }

  @Test
  void theReviewerLaneCarriesTheSpecsReasoningEffort() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "codex", "review please", REVIEW_ID, CREDENTIAL, null, "xhigh");

    var exec =
        shell.invocations().stream()
            .filter(c -> c.contains(">> " + REVIEW_DIR + "/review.log"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        exec.contains("model_reasoning_effort='\"xhigh\"'"),
        "a spec dispatched at xhigh must be judged at xhigh — the review lane must not"
            + " silently drop to the default effort");
  }

  @Test
  void theFixLaneCarriesTheSpecsModelAndEffort() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "done");

    runner(shell)
        .runFix(
            "acme",
            "codex",
            "fix it",
            REVIEW_ID,
            CREDENTIAL,
            "agent/spec",
            List.of("api"),
            "gpt-5.3-codex",
            "xhigh");

    var exec =
        shell.invocations().stream()
            .filter(c -> c.contains(">> " + REVIEW_DIR + "/review.log"))
            .findFirst()
            .orElseThrow();
    assertTrue(exec.contains("--model gpt-5.3-codex"), "the fix agent is the spec's own agent");
    assertTrue(exec.contains("model_reasoning_effort='\"xhigh\"'"));
  }

  @Test
  void theReviewerLaneStaysUngated() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "claude-code", "review please", REVIEW_ID, CREDENTIAL);

    var joined = String.join("\n", shell.invocations());
    assertFalse(
        joined.contains("SAIL_RUN_ID"),
        "a reviewer must be free to stop without committing — gating it would nudge it to"
            + " commit its own scratch into the spec branch");
    assertFalse(joined.contains("agent-session.json"), "no gate scope file for the reviewer");
  }

  @Test
  void ensureCommittedRescuesWorkLeftUncommittedOnTheSpecBranch() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n")
            .onOk("status --porcelain", " M Api.java\n?? docs/New.md\n");

    var rescued =
        runner(shell)
            .ensureCommitted(
                "acme", List.of("api"), "agent/spec", "fix: address 2 review findings");

    assertEquals(1, rescued.size());
    assertEquals("api", rescued.getFirst().repo());
    assertEquals(
        List.of("Api.java", "docs/New.md"),
        rescued.getFirst().files(),
        "the rescue names what it swept up, so the guardrail event can show it");
    var joined = String.join("\n", shell.invocations());
    assertTrue(joined.contains("git -C /home/dev/workspace/api add -A"));
    assertTrue(
        joined.contains("fix: address 2 review findings"),
        "the rescue commit says what the work was, not that an agent forgot to commit");
    assertTrue(joined.contains("git -C /home/dev/workspace/api push"));
  }

  @Test
  void ensureCommittedResolvesARenameToItsNewPath() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n")
            .onOk("status --porcelain", "R  Old.java -> New.java\n");

    var rescued = runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec", "fix: x");

    assertEquals(List.of("New.java"), rescued.getFirst().files());
  }

  @Test
  void ensureCommittedLeavesACleanRepoUntouched() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n");

    var rescued = runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec", "fix: x");

    assertTrue(rescued.isEmpty());
    assertFalse(String.join("\n", shell.invocations()).contains("add -A"));
  }

  @Test
  void ensureCommittedNeverCommitsOnAForeignBranch() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "main\n")
            .onOk("status --porcelain", " M Api.java\n");

    var rescued = runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec", "fix: x");

    assertTrue(
        rescued.isEmpty(),
        "a repo parked on another branch is not this run's to commit — auto-committing there is"
            + " how a shared clone's main gets contaminated");
    assertFalse(String.join("\n", shell.invocations()).contains("add -A"));
  }

  @Test
  void ensureCommittedSkipsASpecWithNoBranch() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    assertTrue(runner(shell).ensureCommitted("acme", List.of("api"), " ", "fix: x").isEmpty());
    assertEquals(0, shell.invocations().size(), "no branch to guard means nothing to touch");
  }

  @Test
  void ensureCommittedFailsLoudWhenTheCommitItselfFails() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n")
            .onOk("status --porcelain", " M Api.java\n")
            .onFail("commit -m", "git identity not configured");

    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec", "fix: x"));
    assertTrue(ex.getMessage().contains("git identity not configured"), ex.getMessage());
  }

  @Test
  void ensureCommittedToleratesAPushFailure() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n")
            .onOk("status --porcelain", " M Api.java\n")
            .onFail("push", "no network");

    var rescued = runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec", "fix: x");
    assertEquals(
        List.of("api"),
        rescued.stream().map(ReviewAgentRunner.Rescue::repo).toList(),
        "the commit is the rescue; a push failure must not fail the pipeline");
  }

  @Test
  void rejectsAForgedReviewIdBeforeTouchingAnyFile() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    assertThrows(
        IllegalArgumentException.class,
        () -> runner(shell).run("acme", "codex", "p", "../../etc/passwd", CREDENTIAL));
    assertEquals(0, shell.invocations().size());
  }

  @Test
  void readsOnlyTheCurrentRunsBytesPastThePriorNegotiation() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("stat -c", "500")
            .onOk("tail -c +501", "{\"type\":\"result\",\"result\":\"CURRENT\"}");

    assertEquals(
        "CURRENT",
        runner(shell).run("acme", "codex", "re-review", REVIEW_ID, CREDENTIAL),
        "reads from the byte after the accumulated negotiation, not the whole appended log");
  }

  @Test
  void throwsWhenTheReviewAgentExitsNonZero() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail(">> " + REVIEW_DIR + "/review.log", "boom");

    var ex =
        assertThrows(
            Exception.class, () -> runner(shell).run("acme", "codex", "p", REVIEW_ID, CREDENTIAL));
    assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
    assertEquals(1, ((ReviewAgentExecutionException) ex).exitCode());
  }

  @Test
  void returnsEmptyWhenTheReviewLogCannotBeRead() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail("tail -c", "gone");

    assertEquals("", runner(shell).run("acme", "codex", "p", REVIEW_ID, CREDENTIAL));
  }
}
