/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentSessionTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final AgentUnit RUN_UNIT = AgentUnit.forRun(RUN_ID);

  private static List<String> background(
      boolean fullPermissions,
      AgentCli cli,
      String model,
      String reasoningEffort,
      String specId,
      String agentType) {
    return AgentSession.buildBackgroundLaunchCommand(
        "acme",
        "dev",
        "/home/dev/workspace",
        fullPermissions,
        cli,
        model,
        reasoningEffort,
        specId,
        agentType,
        RUN_UNIT.logPath(),
        RUN_ID,
        "cred-0");
  }

  private static List<String> foreground(
      boolean fullPermissions,
      AgentCli cli,
      String model,
      String reasoningEffort,
      String specId,
      String agentType) {
    return AgentSession.buildForegroundTaskCommand(
        "acme",
        "dev",
        "/home/dev/workspace",
        fullPermissions,
        cli,
        model,
        reasoningEffort,
        specId,
        agentType,
        RUN_UNIT.logPath(),
        RUN_ID,
        "cred-0");
  }

  @Test
  void ensureDirectoryRunsCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var session = new AgentSession(shell);

    session.ensureDirectory("acme-health");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertTrue(cmds.getFirst().contains("mkdir -p /home/dev/.sail"));
    assertTrue(cmds.getFirst().contains("acme-health"));
  }

  @Test
  void queryStatusWhenRunning() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "12345\n")
            .onOk("kill -0 12345", "")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"implement auth\",\"started_at\":\"2026-02-21T03:00:00Z\","
                    + "\"branch\":\"sail/snap-20260221\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health", RUN_UNIT);

    assertNotNull(info);
    assertTrue(info.running());
    assertEquals(12345, info.pid());
    assertEquals("implement auth", info.task());
    assertEquals("2026-02-21T03:00:00Z", info.startedAt());
    assertEquals("sail/snap-20260221", info.branch());
    assertEquals(RUN_UNIT.logPath(), info.logPath());
  }

  @Test
  void parseStartTicksReadsField22AfterTheCommField() {
    assertEquals(
        12345L,
        AgentSession.parseStartTicks(
            "77 (my (weird) app) S 1 2 3 4 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 12345 100 5"));
    assertNull(AgentSession.parseStartTicks("no stat line"));
    assertNull(AgentSession.parseStartTicks("77 (short) S 1 2"));
  }

  @Test
  void readProcessStartTicksReadsProcStatInTheContainer() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "cat /proc/123/stat",
                "123 (bash) S 1 123 123 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0 555 0 0");
    var session = new AgentSession(shell);

    assertEquals(555L, session.readProcessStartTicks("acme-health", 123));
    assertNull(session.readProcessStartTicks("acme-health", 999));
  }

  @Test
  void unitActiveAsksSystemdAndNeverTheFile() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("is-active " + RUN_UNIT.service());
    var session = new AgentSession(shell);

    assertTrue(session.unitActive("acme-health", RUN_UNIT));
    assertTrue(
        shell.invocations().getFirst().contains("systemctl --user --quiet is-active"),
        shell.invocations().getFirst());
    assertFalse(new AgentSession(new ScriptedShellExecutor()).unitActive("acme-health", RUN_UNIT));
  }

  @Test
  void queryStatusWhenStopped() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"implement auth\",\"started_at\":\"2026-02-21T03:00:00Z\","
                    + "\"branch\":\"\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health", RUN_UNIT);

    assertNotNull(info);
    assertFalse(info.running());
    assertEquals(12345, info.pid());
  }

  @Test
  void queryStatusWhenNoPidFile() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat " + RUN_UNIT.pidPath(), "No such file")
            .onOk(
                "systemctl --user show " + RUN_UNIT.service() + " --property=MainPID --value",
                "0\n");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health", RUN_UNIT);

    assertNull(info);
  }

  @Test
  void queryStatusFallsBackToSystemdMainPid() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat " + RUN_UNIT.pidPath(), "No such file")
            .onOk(
                "systemctl --user show " + RUN_UNIT.service() + " --property=MainPID --value",
                "54321\n")
            .onOk("kill -0 54321", "")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"polish ui\",\"started_at\":\"2026-05-07T03:00:00Z\","
                    + "\"branch\":\"feat/ui\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health", RUN_UNIT);

    assertNotNull(info);
    assertTrue(info.running());
    assertEquals(54321, info.pid());
    assertEquals("polish ui", info.task());
  }

  @Test
  void queryStatusWhenPidFileEmpty() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("cat " + RUN_UNIT.pidPath(), "");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health", RUN_UNIT);

    assertNull(info);
  }

  @Test
  void queryStatusWhenPidNotANumber() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("cat " + RUN_UNIT.pidPath(), "not-a-number\n");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health", RUN_UNIT);

    assertNull(info);
  }

  @Test
  void killAgentKillsTheWholeUnitCgroupNeverABarePid() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var session = new AgentSession(shell);

    session.killAgent("acme-health", RUN_UNIT);

    var cmds = shell.invocations();
    var service = RUN_UNIT.service();
    assertTrue(
        cmds.stream().anyMatch(c -> c.contains("--kill-who=all --signal=SIGTERM " + service)),
        "TERM must address every member of the unit cgroup");
    assertTrue(cmds.stream().anyMatch(c -> c.contains("sleep 3")));
    assertTrue(
        cmds.stream().anyMatch(c -> c.contains("--kill-who=all --signal=SIGKILL " + service)),
        "a unit still active after the grace gets a cgroup-wide SIGKILL");
    assertTrue(cmds.stream().anyMatch(c -> c.contains("rm -f " + RUN_UNIT.pidPath())));
    assertTrue(
        cmds.stream().noneMatch(c -> c.contains("kill 9999") || c.contains("kill -9 9999")),
        "the pid file names only the launch wrapper; a bare pid kill orphans the agent's"
            + " children inside the still-active unit");
  }

  @Test
  void killAgentSkipsSigkillWhenTheUnitDiesInTheGrace() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("is-active " + RUN_UNIT.service(), "");
    var session = new AgentSession(shell);

    session.killAgent("acme-health", RUN_UNIT);

    var cmds = shell.invocations();
    assertFalse(cmds.stream().anyMatch(c -> c.contains("--signal=SIGKILL")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("rm -f")));
  }

  @Test
  void killAgentFallsBackToThePidFileOnlyForAUnitlessSession() throws Exception {
    var foreground = AgentUnit.recorded(RUN_ID, "");
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat " + foreground.pidPath(), "9999\n");
    var session = new AgentSession(shell);

    session.killAgent("acme-health", foreground);

    var cmds = shell.invocations();
    assertTrue(cmds.stream().anyMatch(c -> c.contains("kill 9999")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("kill -9 9999")));
    assertTrue(cmds.stream().noneMatch(c -> c.contains("systemctl --user kill")));
  }

  @Test
  void killAgentThrowsWhenSigkillFailsOnALiveProcess() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("--signal=SIGKILL " + RUN_UNIT.service(), "Operation not permitted");
    var session = new AgentSession(shell);

    var failure = assertThrows(IOException.class, () -> session.killAgent("acme-health", RUN_UNIT));

    assertTrue(failure.getMessage().contains("SIGKILL"));
    assertTrue(failure.getMessage().contains(RUN_UNIT.service()));
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("rm -f")));
  }

  @Test
  void killAgentTreatsSigkillOfAnAlreadyDeadProcessAsSuccess() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "9999\n")
            .onOk("kill 9999")
            .onOk("sleep 3")
            .onceOnOk("kill -0 9999")
            .onFail("kill -9 9999", "No such process")
            .onOk("rm -f");
    var session = new AgentSession(shell);

    session.killAgent("acme-health", RUN_UNIT);

    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("rm -f")));
  }

  @Test
  void killAgentIgnoresNonNumericPid() throws Exception {
    var foreground = AgentUnit.recorded(RUN_ID, "");
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat " + foreground.pidPath(), "; rm -rf /\n");
    var session = new AgentSession(shell);

    session.killAgent("acme-health", foreground);

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertFalse(cmds.stream().anyMatch(c -> c.contains("kill")));
  }

  @Test
  void killAgentNoPidFileIsNoOp() throws Exception {
    var foreground = AgentUnit.recorded(RUN_ID, "");
    var shell = new ScriptedShellExecutor().onFail("cat " + foreground.pidPath(), "No such file");
    var session = new AgentSession(shell);

    session.killAgent("acme-health", foreground);

    assertEquals(1, shell.invocations().size());
  }

  @Test
  void buildBackgroundLaunchCommandStructure() {
    var cmd = background(false, AgentCli.CLAUDE_CODE, null, null, null, null);

    assertEquals("incus", cmd.getFirst());
    assertTrue(cmd.contains("acme"));
    var joined = String.join(" ", cmd);
    assertFalse(joined.contains("nohup"));
    assertTrue(
        joined.contains(
            "systemd-run --user --setenv \"SAIL_SPEC_ID=$6\" --setenv \"SAIL_AGENT=$7\""
                + " --setenv \"SAIL_RUN_ID=$8\" --setenv \"SAIL_RUN_CREDENTIAL=$9\" --unit "
                + RUN_UNIT.unitName()));
    assertTrue(joined.contains("claude --print"));
    assertTrue(joined.contains("--settings " + ClaudeCodeHookConfig.SETTINGS_PATH));
    assertTrue(cmd.contains(RUN_UNIT.logPath()));
    assertTrue(cmd.contains(RUN_UNIT.pidPath()));
    assertTrue(joined.contains(RUN_UNIT.taskPath()));
    assertFalse(joined.contains("--dangerously-skip-permissions"));
  }

  @Test
  void buildBackgroundLaunchCommandRedirectsToARunScopedLog() {
    var cmd = background(true, AgentCli.CLAUDE_CODE, null, null, "spec-1", "claude-code");

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains(RUN_UNIT.logPath()),
        "the agent's output is redirected to the run-scoped log");
    assertTrue(cmd.contains(RUN_ID), "the run id is carried in as SAIL_RUN_ID");
    assertTrue(
        joined.contains("mkdir -p \"$(dirname \"$4\")\""), "the run's log directory is created");
    assertFalse(
        joined.contains("/home/dev/.sail/agent.log "),
        "the shared per-container log is no longer the redirect target");
  }

  @Test
  void twoConcurrentLaunchesGetDistinctUnitsPidSessionAndTaskFiles() {
    var runA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    var runB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    var unitA = AgentUnit.forRun(runA);
    var unitB = AgentUnit.forRun(runB);
    var cmdA = backgroundLaunch(runA, unitA);
    var cmdB = backgroundLaunch(runB, unitB);

    var joinedA = String.join(" ", cmdA);
    var joinedB = String.join(" ", cmdB);
    assertTrue(joinedA.contains("--unit " + unitA.unitName()), joinedA);
    assertTrue(joinedB.contains("--unit " + unitB.unitName()), joinedB);
    assertTrue(cmdA.contains(unitA.pidPath()) && cmdB.contains(unitB.pidPath()));
    assertTrue(joinedA.contains(unitA.taskPath()) && joinedB.contains(unitB.taskPath()));
    assertFalse(joinedA.contains(unitB.unitName()), "run A never touches run B's unit");
    assertFalse(joinedB.contains(unitA.unitName()), "run B never touches run A's unit");
    assertFalse(
        AgentUnit.forRun(runA).sessionPath().equals(AgentUnit.forRun(runB).sessionPath()),
        "two runs never share a session file");
  }

  private static List<String> backgroundLaunch(String runId, AgentUnit unit) {
    return AgentSession.buildBackgroundLaunchCommand(
        "acme",
        "dev",
        "/home/dev/workspace",
        true,
        AgentCli.CLAUDE_CODE,
        null,
        null,
        "spec-1",
        "claude-code",
        unit.logPath(),
        runId,
        "cred-0");
  }

  @Test
  void buildForegroundTaskCommandRedirectsToARunScopedLog() {
    var cmd = foreground(true, AgentCli.CLAUDE_CODE, null, null, "spec-1", "claude-code");

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains(RUN_UNIT.logPath()),
        "the foreground agent's output is redirected to its run log");
    assertTrue(cmd.contains(RUN_ID), "the run id is carried in as SAIL_RUN_ID");
    assertTrue(
        joined.contains("SAIL_RUN_ID=\"$6\""), "the run id is exported into the launched process");
  }

  @Test
  void buildForegroundTaskCommandWritesTheRunScopedPidFile() {
    var cmd = foreground(true, AgentCli.CLAUDE_CODE, null, null, "spec-1", "claude-code");

    assertEquals(
        RUN_UNIT.pidPath(),
        cmd.get(cmd.size() - 2),
        "the wrapper's pid lands in the run's pid file so a foreground session stays probeable"
            + " and stoppable");
    var script = cmd.get(cmd.indexOf("-c") + 1);
    assertTrue(script.contains("printf '%s\\n' \"$$\" > \"$7\""));
  }

  @Test
  void runScopedLaunchCommandsRejectABlankOrInvalidRunId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentSession.buildBackgroundLaunchCommand(
                "acme",
                "dev",
                "/home/dev/workspace",
                false,
                AgentCli.CLAUDE_CODE,
                null,
                null,
                null,
                null,
                RUN_UNIT.logPath(),
                "",
                "cred-0"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentSession.buildForegroundTaskCommand(
                "acme",
                "dev",
                "/home/dev/workspace",
                false,
                AgentCli.CLAUDE_CODE,
                null,
                null,
                null,
                null,
                RUN_UNIT.logPath(),
                "../escape",
                "cred-0"));
  }

  @Test
  void writeTaskFileCreatesTheRunScopedParentDirectoryBeforeWriting() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var unit = AgentUnit.forRun("0197a2f0-0000-7000-8000-000000000001");

    new AgentSession(shell).writeTaskFile("acme", "do it", unit);

    var cmd = shell.invocations().getFirst();
    assertTrue(
        cmd.contains("mkdir -p \"$(dirname \"$2\")\" && printf"),
        "the run directory must exist before the redirect: dispatch stages the task before the"
            + " launch script's own mkdir runs");
    assertTrue(cmd.contains(unit.taskPath()));
  }

  @Test
  void writeSessionCreatesTheRunScopedParentDirectoryBeforeWriting() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var unit = AgentUnit.forRun("0197a2f0-0000-7000-8000-000000000001");

    new AgentSession(shell)
        .writeSession("acme", "do it", "b1", "auth", "claude-code", "r1", List.of("app"), unit);

    var cmd = shell.invocations().getFirst();
    assertTrue(cmd.contains("mkdir -p \"$(dirname \"$2\")\" && printf"));
    assertTrue(cmd.contains(unit.sessionPath()));
  }

  @Test
  void buildBackgroundLaunchCommandStreamsClaudeOutput() {
    var cmd = background(false, AgentCli.CLAUDE_CODE, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains("--output-format stream-json --verbose"),
        "dispatched Claude agents must stream incremental events so the log fills live");
  }

  @Test
  void buildForegroundTaskCommandDoesNotStream() {
    var cmd = foreground(false, AgentCli.CLAUDE_CODE, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertFalse(
        joined.contains("stream-json"),
        "foreground/review path keeps its non-streaming final-result output");
  }

  @Test
  void buildBackgroundLaunchCommandCodexDoesNotGetStreamFlag() {
    var cmd = background(false, AgentCli.CODEX, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertFalse(
        joined.contains("stream-json"), "Codex already streams readable text; no flag added");
  }

  @Test
  void buildBackgroundLaunchCommandPassesEmptySpecForAdHocLaunches() {
    var cmd = background(false, AgentCli.CLAUDE_CODE, null, null, null, null);

    var specId = cmd.get(cmd.size() - 4);
    var agent = cmd.get(cmd.size() - 3);
    var runId = cmd.get(cmd.size() - 2);
    var credential = cmd.getLast();
    assertEquals("", specId, "ad-hoc launches pass empty specId so the in-container hook no-ops");
    assertEquals("claude-code", agent, "agent type defaults to CLI yamlName when blank");
    assertEquals(RUN_ID, runId, "an ad-hoc launch is still a run, so SAIL_RUN_ID carries its id");
    assertEquals("cred-0", credential, "the run credential rides in as SAIL_RUN_CREDENTIAL");
  }

  @Test
  void buildBackgroundLaunchCommandPassesSpecIdAndAgent() {
    var cmd = background(true, AgentCli.CLAUDE_CODE, null, null, "oauth-flow", "claude-code");

    assertTrue(cmd.contains("oauth-flow"), "specId must be present as positional arg");
    assertTrue(cmd.contains("claude-code"), "agent type must be present as positional arg");
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("--setenv \"SAIL_SPEC_ID=$6\""));
    assertTrue(joined.contains("--setenv \"SAIL_AGENT=$7\""));
    assertTrue(joined.contains("--setenv \"SAIL_RUN_ID=$8\""));
  }

  @Test
  void buildBackgroundLaunchCommandNonClaudeOmitsSettingsFlag() {
    var cmd = background(true, AgentCli.CODEX, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertFalse(joined.contains("--settings"), "only Claude Code gets the sail settings file");
  }

  @Test
  void buildBackgroundLaunchCommandWithPermissions() {
    var cmd = background(true, AgentCli.CLAUDE_CODE, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains(
            "claude --print --output-format stream-json --verbose --settings "
                + ClaudeCodeHookConfig.SETTINGS_PATH
                + " --dangerously-skip-permissions"));
  }

  @Test
  void buildBackgroundLaunchCommandCodexUsesExec() {
    var cmd = background(false, AgentCli.CODEX, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("codex exec"));
    assertFalse(joined.contains("--print"));
    assertFalse(joined.contains("claude"));
  }

  @Test
  void buildBackgroundLaunchCommandCodexFullAuto() {
    var cmd = background(true, AgentCli.CODEX, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("codex exec --dangerously-bypass-approvals-and-sandbox"));
  }

  @Test
  void buildBackgroundLaunchCommandCodexModelOptions() {
    var cmd = background(true, AgentCli.CODEX, "gpt-5.5", "high", null, null);

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains(
            "codex exec --dangerously-bypass-approvals-and-sandbox"
                + " --dangerously-bypass-hook-trust --model gpt-5.5"));
    assertTrue(joined.contains("model_reasoning_effort='\"high\"'"));
    assertFalse(joined.contains("exec codex exec"));
  }

  @Test
  void buildBackgroundLaunchCommandClaudeCodeToleratesModelAndDropsReasoningWithWarning() {
    var originalErr = System.err;
    var captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    List<String> cmd;
    try {
      cmd =
          background(
              true, AgentCli.CLAUDE_CODE, "claude-opus-4", "high", "auth-flow", "claude-code");
    } finally {
      System.setErr(originalErr);
    }

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("claude --print"));
    assertTrue(joined.contains("--model claude-opus-4"), "explicit model choice is honored");
    assertFalse(joined.contains("reasoning"), "reasoning_effort is dropped for Claude Code");

    var warning = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(warning.contains("reasoning_effort"), "the drop must never be silent");
    assertTrue(warning.contains("high"));
    assertTrue(warning.contains("auth-flow"), "the warning names the spec");
  }

  @Test
  void buildBackgroundLaunchCommandClaudeCodeWarnsForReasoningEffortNone() {
    var originalErr = System.err;
    var captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      background(true, AgentCli.CLAUDE_CODE, null, "none", "auth-flow", "claude-code");
    } finally {
      System.setErr(originalErr);
    }

    var warning = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(warning.contains("auth-flow"));
    assertTrue(warning.contains("none"));
  }

  @Test
  void buildBackgroundLaunchCommandClaudeCodeWithoutReasoningEffortIsSilent() {
    var originalErr = System.err;
    var captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      background(true, AgentCli.CLAUDE_CODE, "claude-opus-4", null, null, null);
    } finally {
      System.setErr(originalErr);
    }

    assertTrue(captured.toString(java.nio.charset.StandardCharsets.UTF_8).isEmpty());
  }

  @Test
  void buildForegroundTaskCommandClaudeCodeDropsReasoningWithWarning() {
    var originalErr = System.err;
    var captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    List<String> cmd;
    try {
      cmd = foreground(true, AgentCli.CLAUDE_CODE, null, "high", "auth-flow", "claude-code");
    } finally {
      System.setErr(originalErr);
    }

    assertFalse(String.join(" ", cmd).contains("reasoning"));
    assertTrue(captured.toString(java.nio.charset.StandardCharsets.UTF_8).contains("auth-flow"));
  }

  @Test
  void buildBackgroundLaunchCommandDefaultsToClaudeCode() {
    var cmd = background(false, null, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("claude --print"));
  }

  @Test
  void backgroundLaunchKeepsWorkDirOutOfShellScript() {
    var workDir = "/home/dev/workspace; touch /tmp/pwned";
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme",
            "dev",
            workDir,
            false,
            AgentCli.CLAUDE_CODE,
            null,
            null,
            null,
            null,
            RUN_UNIT.logPath(),
            RUN_ID,
            "cred-0");

    var script = cmd.get(cmd.indexOf("-lc") + 1);
    assertTrue(script.contains("systemd-run"));
    assertTrue(script.contains("--unit " + RUN_UNIT.unitName()));
    assertTrue(script.contains("systemctl --user show " + RUN_UNIT.service()));
    assertTrue(script.contains("cd \"$1\""));
    assertFalse(script.contains(workDir));
    assertTrue(cmd.contains(workDir));
  }

  @Test
  void foregroundLaunchKeepsWorkDirOutOfShellScript() {
    var workDir = "/home/dev/workspace; touch /tmp/pwned";
    var cmd =
        AgentSession.buildForegroundTaskCommand(
            "acme",
            "dev",
            workDir,
            false,
            AgentCli.CLAUDE_CODE,
            null,
            null,
            null,
            null,
            RUN_UNIT.logPath(),
            RUN_ID,
            "cred-0");

    var script = cmd.get(cmd.indexOf("-c") + 1);
    assertTrue(script.contains("cd \"$1\""));
    assertFalse(script.contains(workDir));
    assertTrue(cmd.contains(workDir));
  }

  @Test
  void buildForegroundTaskCommandStructure() {
    var cmd = foreground(false, AgentCli.CLAUDE_CODE, null, null, null, null);

    assertEquals("incus", cmd.getFirst());
    assertTrue(cmd.contains("acme"));
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("claude --print"));
    assertTrue(joined.contains("--settings " + ClaudeCodeHookConfig.SETTINGS_PATH));
    assertTrue(joined.contains("agent-task.txt"));
    var script = cmd.get(cmd.indexOf("-c") + 1);
    assertEquals(
        "mkdir -p \"$(dirname \"$5\")\"; printf '%s\\n' \"$$\" > \"$7\"; cd \"$1\" && "
            + "SAIL_SPEC_ID=\"$3\" SAIL_AGENT=\"$4\" SAIL_RUN_ID=\"$6\""
            + " SAIL_RUN_CREDENTIAL=\"$8\""
            + " exec bash -l -c \"$2\" > \"$5\" 2>&1",
        script);
    assertEquals(RUN_UNIT.pidPath(), cmd.get(cmd.size() - 2));
  }

  @Test
  void buildForegroundTaskCommandPassesSpecIdAndAgent() {
    var cmd = foreground(true, AgentCli.CLAUDE_CODE, null, null, "oauth-flow", "claude-code");

    assertTrue(cmd.contains("oauth-flow"));
    assertTrue(cmd.contains("claude-code"));
  }

  @Test
  void buildForegroundTaskCommandCodexExec() {
    var cmd = foreground(true, AgentCli.CODEX, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("codex exec --dangerously-bypass-approvals-and-sandbox"));
    assertFalse(joined.contains("claude"));
  }

  @Test
  void launchWorkDirUsesSingleTargetRepo() {
    var repo = new SailYaml.Repo("https://github.com/org/chorus.git", "chorus", null);

    assertEquals("/home/dev/workspace/chorus", AgentSession.launchWorkDir("dev", List.of(repo)));
  }

  @Test
  void launchWorkDirUsesWorkspaceForMultipleTargets() {
    var first = new SailYaml.Repo("https://github.com/org/chorus.git", "chorus", null);
    var second = new SailYaml.Repo("https://github.com/org/sing.git", "sing", null);

    assertEquals("/home/dev/workspace", AgentSession.launchWorkDir("dev", List.of(first, second)));
  }

  @Test
  void buildForegroundTaskCommandDefaultsToClaudeCode() {
    var cmd = foreground(false, null, null, null, null, null);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("claude --print"));
  }

  @Test
  void parseExitStateReadsActiveUnit() {
    var state =
        AgentSession.parseExitState(
            """
            ActiveState=active
            ExecMainStatus=0
            Environment=SAIL_SPEC_ID=scrum-12 SAIL_AGENT=claude-code LANG=C.UTF-8
            """);

    assertTrue(state.active());
    assertEquals(0, state.exitCode());
    assertEquals("scrum-12", state.specId());
    assertEquals("claude-code", state.agentType());
  }

  @Test
  void parseExitStateReadsCleanExit() {
    var state =
        AgentSession.parseExitState(
            """
            ActiveState=inactive
            ExecMainStatus=0
            Environment=SAIL_SPEC_ID=scrum-12 SAIL_AGENT=codex
            """);

    assertFalse(state.active());
    assertEquals(0, state.exitCode());
    assertEquals("scrum-12", state.specId());
    assertEquals("codex", state.agentType());
  }

  @Test
  void parseExitStateReadsFailedExitWithCode() {
    var state =
        AgentSession.parseExitState(
            """
            ActiveState=failed
            ExecMainStatus=137
            Environment=SAIL_SPEC_ID=scrum-12 SAIL_AGENT=claude-code
            """);

    assertFalse(state.active());
    assertEquals(137, state.exitCode());
    assertEquals("scrum-12", state.specId());
  }

  @Test
  void parseExitStateDefaultsToActiveWhenStateUnknown() {
    var state = AgentSession.parseExitState("");

    assertTrue(state.active());
    assertEquals(0, state.exitCode());
    assertEquals("", state.specId());
    assertEquals("", state.agentType());
  }

  @Test
  void parseExitStateToleratesMissingEnvAndGarbageExitCode() {
    var state =
        AgentSession.parseExitState(
            """
            ActiveState=inactive
            ExecMainStatus=
            Environment=
            """);

    assertFalse(state.active());
    assertEquals(0, state.exitCode());
    assertEquals("", state.specId());
    assertEquals("", state.agentType());
  }

  @Test
  void queryExitStatusShowsTheUnit() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show " + RUN_UNIT.service(),
                """
                ActiveState=failed
                ExecMainStatus=1
                Environment=SAIL_SPEC_ID=auth SAIL_AGENT=claude-code SAIL_RUN_ID=%s
                """
                    .formatted(RUN_ID));
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("acme", RUN_UNIT);

    assertFalse(state.active());
    assertEquals(1, state.exitCode());
    assertEquals("auth", state.specId());
    assertEquals(RUN_ID, state.runId());
  }

  @Test
  void queryExitStatusTreatsAShellFailureAsStillActive() throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("systemctl --user show " + RUN_UNIT.service(), "boom");
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("acme", RUN_UNIT);

    assertTrue(state.active());
  }

  @Test
  void queryExitStatusRecoversSpecIdFromTheSessionFileWhenTheUnitWasCollected() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show " + RUN_UNIT.service(),
                """
                ActiveState=inactive
                ExecMainStatus=0
                Environment=
                """)
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"t\",\"branch\":\"b\",\"spec_id\":\"v1-sync-commit-integrity\","
                    + "\"agent_type\":\"claude-code\",\"run_id\":\""
                    + RUN_ID
                    + "\",\"started_at\":\"2026-06-30T16:55:14Z\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}");
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("sail-mast", RUN_UNIT);

    assertFalse(state.active(), "a successfully-exited unit is collected and reads as inactive");
    assertEquals(0, state.exitCode());
    assertEquals(
        "v1-sync-commit-integrity",
        state.specId(),
        "spec id must survive unit garbage collection via the durable session file");
    assertEquals("claude-code", state.agentType());
    assertEquals(RUN_ID, state.runId(), "run id must survive unit garbage collection too");
  }

  @Test
  void queryExitStatusPrefersUnitEnvironmentWhileItIsStillPresent() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show " + RUN_UNIT.service(),
                """
                ActiveState=active
                ExecMainStatus=0
                Environment=SAIL_SPEC_ID=scrum-12 SAIL_AGENT=codex SAIL_RUN_ID=%s
                """
                    .formatted(RUN_ID));
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("acme", RUN_UNIT);

    assertTrue(state.active());
    assertEquals("scrum-12", state.specId());
    assertEquals("codex", state.agentType());
  }

  @Test
  void writeSessionPersistsSpecIdAgentTypeAndRunIdForDurableRecovery() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var session = new AgentSession(shell);

    session.writeSession(
        "acme",
        "implement auth",
        "branch",
        "v1-sync-commit-integrity",
        "claude-code",
        RUN_ID,
        List.of(),
        RUN_UNIT);

    var cmd = shell.invocations().getFirst();
    assertTrue(cmd.contains(RUN_UNIT.sessionPath()));
    assertTrue(cmd.contains("spec_id"), "session must persist spec_id for watcher recovery");
    assertTrue(cmd.contains("v1-sync-commit-integrity"));
    assertTrue(cmd.contains("claude-code"));
    assertTrue(cmd.contains("run_id"), "session must persist run_id for watcher recovery");
    assertTrue(cmd.contains(RUN_ID));
  }

  @Test
  void writeSessionPersistsTheSpecReposForStopGateScoping() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var session = new AgentSession(shell);

    session.writeSession(
        "acme",
        "task",
        "branch",
        "spec-1",
        "claude-code",
        RUN_ID,
        List.of("manatee-nexus"),
        RUN_UNIT);

    var cmd = shell.invocations().getFirst();
    assertTrue(cmd.contains("repos"), "session must persist repos for stop-gate scoping");
    assertTrue(cmd.contains("manatee-nexus"), cmd);
  }
}
