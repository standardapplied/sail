/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Guardrails;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardrailCheckerTest {

  private static final String CONTAINER = "acme-health";
  private static final String REPO = "/home/dev/workspace/acme";

  @Test
  void wallClockWithinLimitReturnsOk() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var checker = new GuardrailChecker(shell);
    var guardrails = new Guardrails("4h", "stop");

    var result =
        checker.check(
            CONTAINER, guardrails, Instant.now().minus(Duration.ofMinutes(30)), List.of(REPO));

    assertInstanceOf(GuardrailChecker.GuardrailResult.Ok.class, result);
  }

  @Test
  void wallClockExceededTriggers() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var checker = new GuardrailChecker(shell);
    var guardrails = new Guardrails("1h", "snapshot-and-stop");

    var result =
        checker.check(
            CONTAINER, guardrails, Instant.now().minus(Duration.ofHours(2)), List.of(REPO));

    assertInstanceOf(GuardrailChecker.GuardrailResult.Triggered.class, result);
    var triggered = (GuardrailChecker.GuardrailResult.Triggered) result;
    assertEquals("max_duration", triggered.reason());
    assertTrue(triggered.detail().contains("limit: 1h"));
    assertEquals("snapshot-and-stop", triggered.action());
  }

  @Test
  void noMaxDurationConfiguredReturnsOk() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var checker = new GuardrailChecker(shell);
    var guardrails = new Guardrails(null, "stop");

    var result =
        checker.check(
            CONTAINER, guardrails, Instant.now().minus(Duration.ofHours(10)), List.of(REPO));

    assertInstanceOf(GuardrailChecker.GuardrailResult.Ok.class, result);
  }

  @Test
  void notifyActionPreserved() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var checker = new GuardrailChecker(shell);
    var guardrails = new Guardrails("1h", "notify");

    var result =
        checker.check(
            CONTAINER, guardrails, Instant.now().minus(Duration.ofHours(2)), List.of(REPO));

    assertInstanceOf(GuardrailChecker.GuardrailResult.Triggered.class, result);
    var triggered = (GuardrailChecker.GuardrailResult.Triggered) result;
    assertEquals("notify", triggered.action());
  }

  @Test
  void queryGitActivityParsesOutput() throws Exception {
    var epoch = Instant.now().minusSeconds(600).getEpochSecond();
    var shell =
        new ScriptedShellExecutor()
            .onOk("git -C " + REPO + " log -1 --format=%ct", epoch + "\n")
            .onOk("git -C " + REPO + " rev-list --count", "7\n");
    var checker = new GuardrailChecker(shell);

    var activity =
        checker.queryGitActivity(CONTAINER, REPO, Instant.now().minus(Duration.ofHours(1)));

    assertEquals(7, activity.commitCount());
    assertEquals(epoch, activity.lastCommitEpoch());
  }

  @Test
  void queryGitActivityHandlesNoCommits() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("git -C " + REPO + " log -1 --format=%ct", "fatal: bad default revision")
            .onOk("git -C " + REPO + " rev-list --count", "0\n");
    var checker = new GuardrailChecker(shell);

    var activity =
        checker.queryGitActivity(CONTAINER, REPO, Instant.now().minus(Duration.ofHours(1)));

    assertEquals(0, activity.commitCount());
    assertEquals(0L, activity.lastCommitEpoch());
  }

  @Test
  void checkDoesNotQueryGit() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var checker = new GuardrailChecker(shell);
    var guardrails = new Guardrails("4h", "stop");

    checker.check(CONTAINER, guardrails, Instant.now().minusSeconds(300), List.of(REPO));

    var gitCalls = shell.invocations().stream().filter(cmd -> cmd.contains("git")).count();
    assertEquals(0, gitCalls, "check() should not query git — wall clock only");
  }

  @Test
  void formatDurationHoursAndMinutes() {
    assertEquals("3h 27m", GuardrailChecker.formatDuration(Duration.ofMinutes(207)));
  }

  @Test
  void formatDurationMinutesOnly() {
    assertEquals("45m", GuardrailChecker.formatDuration(Duration.ofMinutes(45)));
  }

  @Test
  void formatDurationZero() {
    assertEquals("0m", GuardrailChecker.formatDuration(Duration.ZERO));
  }
}
