/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ContainerReviewAgentRunnerTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Supplier<Instant> clockOf(Instant... times) {
    var q = new ArrayDeque<>(List.of(times));
    return () -> q.size() > 1 ? q.poll() : q.peek();
  }

  private static ContainerReviewAgentRunner runner(ShellExec shell, Supplier<Instant> clock) {
    return new ContainerReviewAgentRunner(shell, new AgentSession(shell), clock, millis -> {}, 0);
  }

  @Test
  void returnsFindingsFromTheStreamedResultWhenTheReviewerExitsCleanly() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("property=ActiveState", "ActiveState=inactive\nExecMainStatus=0\nEnvironment=\n")
            .onOk(
                "cat /home/dev/.sail/review.log", "{\"type\":\"result\",\"result\":\"FINDINGS\"}");

    var out = runner(shell, clockOf(T0)).run("acme", "codex", "review please");

    assertEquals("FINDINGS", out, "findings come from the terminal stream-json result event");
  }

  @Test
  void launchesUnderTheSharedReviewUnitStreamingToReviewLog() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("property=ActiveState", "ActiveState=inactive\nExecMainStatus=0\n")
            .onOk("cat /home/dev/.sail/review.log", "{\"type\":\"result\",\"result\":\"x\"}");

    runner(shell, clockOf(T0)).run("acme", "claude-code", "p");

    var joined = String.join(" | ", shell.invocations());
    assertTrue(joined.contains("--unit sail-review"), "must launch on the shared REVIEW unit");
    assertTrue(joined.contains("review.log"), "must stream to review.log, not agent.log");
    assertTrue(joined.contains("stream-json"), "the reviewer streams so its log fills live");
    assertTrue(
        joined.contains("review-prompt.txt"),
        "the prompt is staged to the review unit's task file");
  }

  @Test
  void throwsWhenTheReviewerExitsNonZero() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("property=ActiveState", "ActiveState=failed\nExecMainStatus=2\n");

    var ex =
        assertThrows(Exception.class, () -> runner(shell, clockOf(T0)).run("acme", "codex", "p"));
    assertTrue(ex.getMessage().contains("exited 2"), ex.getMessage());
  }

  @Test
  void killsAndFailsWhenTheReviewerStallsPastMaxIdle() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("property=ActiveState", "ActiveState=active\nExecMainStatus=0\n")
            .onOk("stat -c", "100")
            .onOk("cat /home/dev/.sail/review.pid", "9999");

    var clock = clockOf(T0, T0, T0.plusSeconds(11 * 60));

    var ex = assertThrows(Exception.class, () -> runner(shell, clock).run("acme", "codex", "p"));
    assertTrue(ex.getMessage().toLowerCase().contains("no progress"), ex.getMessage());
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("kill")),
        "a stalled reviewer must be killed, not left running");
  }

  @Test
  void killsAndFailsWhenTheReviewerRunsPastMaxDuration() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("property=ActiveState", "ActiveState=active\nExecMainStatus=0\n")
            .onFail("stat -c", "no such file")
            .onOk("cat /home/dev/.sail/review.pid", "9999");

    var clock = clockOf(T0, T0, T0.plusSeconds(31 * 60));

    var ex = assertThrows(Exception.class, () -> runner(shell, clock).run("acme", "codex", "p"));
    assertTrue(ex.getMessage().contains("max_duration"), ex.getMessage());
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("kill")));
  }

  @Test
  void throwsWhenTheReviewUnitFailsToLaunch() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("systemd-run", "unit refused");

    var ex =
        assertThrows(Exception.class, () -> runner(shell, clockOf(T0)).run("acme", "codex", "p"));
    assertTrue(ex.getMessage().contains("Failed to launch"), ex.getMessage());
  }

  @Test
  void returnsEmptyWhenTheReviewLogCannotBeRead() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("property=ActiveState", "ActiveState=inactive\nExecMainStatus=0\n")
            .onFail("cat /home/dev/.sail/review.log", "gone");

    assertEquals("", runner(shell, clockOf(T0)).run("acme", "codex", "p"));
  }
}
