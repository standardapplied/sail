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

  private static ContainerReviewAgentRunner runner(ShellExec shell) {
    return new ContainerReviewAgentRunner(shell, new AgentSession(shell));
  }

  @Test
  void returnsFindingsFromReviewLogWhenTheAgentSucceeds() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("tail -c", "{\"type\":\"result\",\"result\":\"FINDINGS\"}");

    assertEquals("FINDINGS", runner(shell).run("acme", "codex", "review please", REVIEW_ID));
  }

  @Test
  void runsTheStreamingAgentCleanAndAppendsToItsOwnReviewLog() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "claude-code", "p", REVIEW_ID);

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
    assertFalse(exec.contains("--settings"), "reviewer loads no hooks");
    assertFalse(
        exec.contains("SAIL_SPEC_ID"), "reviewer runs without a spec id, so it can't recurse");
    assertFalse(
        exec.contains("systemd-run"), "review blocks; it needs no detached unit or user bus");
  }

  @Test
  void concurrentReviewsNeverShareAPromptOrLogPath() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "codex", "review a", REVIEW_ID);
    runner(shell).run("acme", "codex", "review b", OTHER_REVIEW_ID);

    var joined = String.join("\n", shell.invocations());
    assertTrue(joined.contains(REVIEW_DIR + "/review.log"));
    assertTrue(joined.contains("/home/dev/.sail/runs/" + OTHER_REVIEW_ID + "/review.log"));
    assertFalse(
        joined.contains("/home/dev/.sail/review.log"),
        "no invocation may touch the fixed shared review log");
  }

  @Test
  void ensureCommittedRescuesWorkLeftUncommittedOnTheSpecBranch() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n")
            .onOk("status --porcelain", " M Api.java\n");

    var rescued = runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec");

    assertEquals(List.of("api"), rescued);
    var joined = String.join("\n", shell.invocations());
    assertTrue(joined.contains("git -C /home/dev/workspace/api add -A"));
    assertTrue(joined.contains("git -C /home/dev/workspace/api commit -m"));
    assertTrue(joined.contains("git -C /home/dev/workspace/api push"));
  }

  @Test
  void ensureCommittedLeavesACleanRepoUntouched() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n");

    var rescued = runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec");

    assertTrue(rescued.isEmpty());
    assertFalse(String.join("\n", shell.invocations()).contains("add -A"));
  }

  @Test
  void ensureCommittedNeverCommitsOnAForeignBranch() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "main\n")
            .onOk("status --porcelain", " M Api.java\n");

    var rescued = runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec");

    assertTrue(
        rescued.isEmpty(),
        "a repo parked on another branch is not this run's to commit — auto-committing there is"
            + " how a shared clone's main gets contaminated");
    assertFalse(String.join("\n", shell.invocations()).contains("add -A"));
  }

  @Test
  void ensureCommittedToleratesAPushFailure() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("rev-parse --abbrev-ref HEAD", "agent/spec\n")
            .onOk("status --porcelain", " M Api.java\n")
            .onFail("push", "no network");

    assertEquals(
        List.of("api"),
        runner(shell).ensureCommitted("acme", List.of("api"), "agent/spec"),
        "the commit is the rescue; a push failure must not fail the pipeline");
  }

  @Test
  void rejectsAForgedReviewIdBeforeTouchingAnyFile() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    assertThrows(
        IllegalArgumentException.class,
        () -> runner(shell).run("acme", "codex", "p", "../../etc/passwd"));
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
        runner(shell).run("acme", "codex", "re-review", REVIEW_ID),
        "reads from the byte after the accumulated negotiation, not the whole appended log");
  }

  @Test
  void throwsWhenTheReviewAgentExitsNonZero() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail(">> " + REVIEW_DIR + "/review.log", "boom");

    var ex =
        assertThrows(Exception.class, () -> runner(shell).run("acme", "codex", "p", REVIEW_ID));
    assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
    assertEquals(1, ((ReviewAgentExecutionException) ex).exitCode());
  }

  @Test
  void returnsEmptyWhenTheReviewLogCannotBeRead() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail("tail -c", "gone");

    assertEquals("", runner(shell).run("acme", "codex", "p", REVIEW_ID));
  }
}
