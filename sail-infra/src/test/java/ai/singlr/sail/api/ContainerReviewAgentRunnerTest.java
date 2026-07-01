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
import org.junit.jupiter.api.Test;

class ContainerReviewAgentRunnerTest {

  private static ContainerReviewAgentRunner runner(ShellExec shell) {
    return new ContainerReviewAgentRunner(shell, new AgentSession(shell));
  }

  @Test
  void returnsFindingsFromReviewLogWhenTheAgentSucceeds() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("tail -c", "{\"type\":\"result\",\"result\":\"FINDINGS\"}");

    assertEquals("FINDINGS", runner(shell).run("acme", "codex", "review please"));
  }

  @Test
  void runsTheStreamingAgentCleanAndAppendsToReviewLog() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("tail -c", "[]");

    runner(shell).run("acme", "claude-code", "p");

    var exec =
        shell.invocations().stream()
            .filter(c -> c.contains(">> /home/dev/.sail/review.log"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the agent must stream to review.log"));
    assertTrue(exec.contains("stream-json"), "the reviewer streams so review.log fills live");
    assertTrue(exec.contains("cd /home/dev/workspace"), "runs in the workspace to read the diff");
    assertTrue(exec.contains("review-prompt.txt"), "prompt staged to the review task file");
    assertFalse(exec.contains("--settings"), "reviewer loads no hooks");
    assertFalse(
        exec.contains("SAIL_SPEC_ID"), "reviewer runs without a spec id, so it can't recurse");
    assertFalse(
        exec.contains("systemd-run"), "review blocks; it needs no detached unit or user bus");
  }

  @Test
  void readsOnlyTheCurrentRunsBytesPastThePriorNegotiation() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("stat -c", "500")
            .onOk("tail -c +501", "{\"type\":\"result\",\"result\":\"CURRENT\"}");

    assertEquals(
        "CURRENT",
        runner(shell).run("acme", "codex", "re-review"),
        "reads from the byte after the accumulated negotiation, not the whole appended log");
  }

  @Test
  void throwsWhenTheReviewAgentExitsNonZero() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail(">> /home/dev/.sail/review.log", "boom");

    var ex = assertThrows(Exception.class, () -> runner(shell).run("acme", "codex", "p"));
    assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
  }

  @Test
  void returnsEmptyWhenTheReviewLogCannotBeRead() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail("tail -c", "gone");

    assertEquals("", runner(shell).run("acme", "codex", "p"));
  }
}
