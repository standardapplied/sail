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
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentSessionTest {

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
            .onOk("cat /home/dev/.sail/agent.pid", "12345\n")
            .onOk("kill -0 12345", "")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                """
                {"task":"implement auth","started_at":"2026-02-21T03:00:00Z","branch":"sail/snap-20260221","log_path":"/home/dev/.sail/agent.log"}
                """);
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health");

    assertNotNull(info);
    assertTrue(info.running());
    assertEquals(12345, info.pid());
    assertEquals("implement auth", info.task());
    assertEquals("2026-02-21T03:00:00Z", info.startedAt());
    assertEquals("sail/snap-20260221", info.branch());
    assertEquals("/home/dev/.sail/agent.log", info.logPath());
  }

  @Test
  void queryStatusWhenStopped() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/.sail/agent.pid", "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                """
                {"task":"implement auth","started_at":"2026-02-21T03:00:00Z","branch":"","log_path":"/home/dev/.sail/agent.log"}
                """);
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health");

    assertNotNull(info);
    assertFalse(info.running());
    assertEquals(12345, info.pid());
  }

  @Test
  void queryStatusWhenNoPidFile() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat /home/dev/.sail/agent.pid", "No such file")
            .onOk("systemctl --user show sail-agent.service --property=MainPID --value", "0\n");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health");

    assertNull(info);
  }

  @Test
  void queryStatusFallsBackToSystemdMainPid() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat /home/dev/.sail/agent.pid", "No such file")
            .onOk("systemctl --user show sail-agent.service --property=MainPID --value", "54321\n")
            .onOk("kill -0 54321", "")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                """
                {"task":"polish ui","started_at":"2026-05-07T03:00:00Z","branch":"feat/ui","log_path":"/home/dev/.sail/agent.log"}
                """);
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health");

    assertNotNull(info);
    assertTrue(info.running());
    assertEquals(54321, info.pid());
    assertEquals("polish ui", info.task());
  }

  @Test
  void queryStatusWhenPidFileEmpty() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("cat /home/dev/.sail/agent.pid", "");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health");

    assertNull(info);
  }

  @Test
  void queryStatusWhenPidNotANumber() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("cat /home/dev/.sail/agent.pid", "not-a-number\n");
    var session = new AgentSession(shell);

    var info = session.queryStatus("acme-health");

    assertNull(info);
  }

  @Test
  void killAgentSendsTermThenKill() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat /home/dev/.sail/agent.pid", "9999\n");
    var session = new AgentSession(shell);

    session.killAgent("acme-health");

    var cmds = shell.invocations();
    assertTrue(cmds.stream().anyMatch(c -> c.contains("kill 9999")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("sleep 3")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("kill -9 9999")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("rm -f")));
  }

  @Test
  void killAgentCleanTermination() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat /home/dev/.sail/agent.pid", "9999\n")
            .onFail("kill -0 9999", "No such process");
    var session = new AgentSession(shell);

    session.killAgent("acme-health");

    var cmds = shell.invocations();
    assertFalse(cmds.stream().anyMatch(c -> c.contains("kill -9 9999")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("rm -f")));
  }

  @Test
  void killAgentIgnoresNonNumericPid() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat /home/dev/.sail/agent.pid", "; rm -rf /\n");
    var session = new AgentSession(shell);

    session.killAgent("acme-health");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertFalse(cmds.stream().anyMatch(c -> c.contains("kill")));
  }

  @Test
  void killAgentNoPidFileIsNoOp() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("cat /home/dev/.sail/agent.pid", "No such file");
    var session = new AgentSession(shell);

    session.killAgent("acme-health");

    assertEquals(1, shell.invocations().size());
  }

  @Test
  void buildBackgroundLaunchCommandStructure() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", false, AgentCli.CLAUDE_CODE);

    assertEquals("incus", cmd.getFirst());
    assertTrue(cmd.contains("acme"));
    var joined = String.join(" ", cmd);
    assertFalse(joined.contains("nohup"));
    assertTrue(
        joined.contains(
            "systemd-run --user --setenv \"SAIL_SPEC_ID=$6\" --setenv \"SAIL_AGENT=$7\" --unit"
                + " sail-agent"));
    assertTrue(joined.contains("claude --print"));
    assertTrue(joined.contains("--settings " + ClaudeCodeHookConfig.SETTINGS_PATH));
    assertTrue(joined.contains("agent.log"));
    assertTrue(joined.contains("agent.pid"));
    assertTrue(joined.contains("agent-task.txt"));
    assertFalse(joined.contains("--dangerously-skip-permissions"));
  }

  @Test
  void buildBackgroundLaunchCommandStreamsClaudeOutput() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", false, AgentCli.CLAUDE_CODE);

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains("--output-format stream-json --verbose"),
        "dispatched Claude agents must stream incremental events so the log fills live");
  }

  @Test
  void buildForegroundTaskCommandDoesNotStream() {
    var cmd =
        AgentSession.buildForegroundTaskCommand(
            "acme", "dev", "/home/dev/workspace", false, AgentCli.CLAUDE_CODE);

    var joined = String.join(" ", cmd);
    assertFalse(
        joined.contains("stream-json"),
        "foreground/review path keeps its non-streaming final-result output");
  }

  @Test
  void buildBackgroundLaunchCommandCodexDoesNotGetStreamFlag() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", false, AgentCli.CODEX);

    var joined = String.join(" ", cmd);
    assertFalse(
        joined.contains("stream-json"), "Codex already streams readable text; no flag added");
  }

  @Test
  void buildBackgroundLaunchCommandPassesEmptySpecForAdHocLaunches() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", false, AgentCli.CLAUDE_CODE);

    var specId = cmd.get(cmd.size() - 2);
    var agent = cmd.getLast();
    assertEquals("", specId, "ad-hoc launches pass empty specId so the in-container hook no-ops");
    assertEquals("claude-code", agent, "agent type defaults to CLI yamlName when blank");
  }

  @Test
  void buildBackgroundLaunchCommandPassesSpecIdAndAgent() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme",
            "dev",
            "/home/dev/workspace",
            true,
            AgentCli.CLAUDE_CODE,
            null,
            null,
            "oauth-flow",
            "claude-code");

    assertTrue(cmd.contains("oauth-flow"), "specId must be present as positional arg");
    assertTrue(cmd.contains("claude-code"), "agent type must be present as positional arg");
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("--setenv \"SAIL_SPEC_ID=$6\""));
    assertTrue(joined.contains("--setenv \"SAIL_AGENT=$7\""));
  }

  @Test
  void buildBackgroundLaunchCommandNonClaudeOmitsSettingsFlag() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", true, AgentCli.CODEX);

    var joined = String.join(" ", cmd);
    assertFalse(joined.contains("--settings"), "only Claude Code gets the sail settings file");
  }

  @Test
  void buildBackgroundLaunchCommandWithPermissions() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", true, AgentCli.CLAUDE_CODE);

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains(
            "claude --print --output-format stream-json --verbose --settings "
                + ClaudeCodeHookConfig.SETTINGS_PATH
                + " --dangerously-skip-permissions"));
  }

  @Test
  void buildBackgroundLaunchCommandCodexUsesExec() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", false, AgentCli.CODEX);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("codex exec"));
    assertFalse(joined.contains("--print"));
    assertFalse(joined.contains("claude"));
  }

  @Test
  void buildBackgroundLaunchCommandCodexFullAuto() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", true, AgentCli.CODEX);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("codex exec --dangerously-bypass-approvals-and-sandbox"));
  }

  @Test
  void buildBackgroundLaunchCommandCodexModelOptions() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", true, AgentCli.CODEX, "gpt-5.5", "high");

    var joined = String.join(" ", cmd);
    assertTrue(
        joined.contains("codex exec --dangerously-bypass-approvals-and-sandbox --model gpt-5.5"));
    assertTrue(joined.contains("model_reasoning_effort='\"high\"'"));
    assertFalse(joined.contains("exec codex exec"));
  }

  @Test
  void buildBackgroundLaunchCommandRejectsUnsupportedModelOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentSession.buildBackgroundLaunchCommand(
                "acme",
                "dev",
                "/home/dev/workspace",
                true,
                AgentCli.CLAUDE_CODE,
                "gpt-5.5",
                "high"));
  }

  @Test
  void buildBackgroundLaunchCommandDefaultsToClaudeCode() {
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", "/home/dev/workspace", false, null);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("claude --print"));
  }

  @Test
  void backgroundLaunchKeepsWorkDirOutOfShellScript() {
    var workDir = "/home/dev/workspace; touch /tmp/pwned";
    var cmd =
        AgentSession.buildBackgroundLaunchCommand(
            "acme", "dev", workDir, false, AgentCli.CLAUDE_CODE);

    var script = cmd.get(cmd.indexOf("-lc") + 1);
    assertTrue(script.contains("systemd-run"));
    assertTrue(script.contains("--unit sail-agent"));
    assertTrue(script.contains("systemctl --user show sail-agent.service"));
    assertTrue(script.contains("cd \"$1\""));
    assertFalse(script.contains(workDir));
    assertTrue(cmd.contains(workDir));
  }

  @Test
  void foregroundLaunchKeepsWorkDirOutOfShellScript() {
    var workDir = "/home/dev/workspace; touch /tmp/pwned";
    var cmd =
        AgentSession.buildForegroundTaskCommand(
            "acme", "dev", workDir, false, AgentCli.CLAUDE_CODE);

    var script = cmd.get(cmd.indexOf("-c") + 1);
    assertTrue(script.contains("cd \"$1\""));
    assertFalse(script.contains(workDir));
    assertTrue(cmd.contains(workDir));
  }

  @Test
  void buildForegroundTaskCommandStructure() {
    var cmd =
        AgentSession.buildForegroundTaskCommand(
            "acme", "dev", "/home/dev/workspace", false, AgentCli.CLAUDE_CODE);

    assertEquals("incus", cmd.getFirst());
    assertTrue(cmd.contains("acme"));
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("claude --print"));
    assertTrue(joined.contains("--settings " + ClaudeCodeHookConfig.SETTINGS_PATH));
    assertTrue(joined.contains("agent-task.txt"));
    assertTrue(joined.contains("SAIL_SPEC_ID=\"$3\" SAIL_AGENT=\"$4\""));
  }

  @Test
  void buildForegroundTaskCommandPassesSpecIdAndAgent() {
    var cmd =
        AgentSession.buildForegroundTaskCommand(
            "acme",
            "dev",
            "/home/dev/workspace",
            true,
            AgentCli.CLAUDE_CODE,
            null,
            null,
            "oauth-flow",
            "claude-code");

    assertTrue(cmd.contains("oauth-flow"));
    assertTrue(cmd.contains("claude-code"));
  }

  @Test
  void buildForegroundTaskCommandCodexExec() {
    var cmd =
        AgentSession.buildForegroundTaskCommand(
            "acme", "dev", "/home/dev/workspace", true, AgentCli.CODEX);

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
    var cmd =
        AgentSession.buildForegroundTaskCommand("acme", "dev", "/home/dev/workspace", false, null);

    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("claude --print"));
  }

  @Test
  void writeSessionExecutesCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var session = new AgentSession(shell);

    session.writeSession("acme", "implement auth", "sail/snap-20260221");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertTrue(cmds.getFirst().contains("agent-session.json"));
  }

  @Test
  void writeTaskFileExecutesCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var session = new AgentSession(shell);

    session.writeTaskFile("acme", "implement the payment webhook");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertTrue(cmds.getFirst().contains("agent-task.txt"));
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
                "systemctl --user show sail-agent.service",
                """
                ActiveState=failed
                ExecMainStatus=1
                Environment=SAIL_SPEC_ID=auth SAIL_AGENT=claude-code
                """);
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("acme");

    assertFalse(state.active());
    assertEquals(1, state.exitCode());
    assertEquals("auth", state.specId());
  }

  @Test
  void queryExitStatusTreatsAShellFailureAsStillActive() throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("systemctl --user show sail-agent.service", "boom");
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("acme");

    assertTrue(state.active());
  }

  @Test
  void queryExitStatusRecoversSpecIdFromTheSessionFileWhenTheUnitWasCollected() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show sail-agent.service",
                """
                ActiveState=inactive
                ExecMainStatus=0
                Environment=
                """)
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                """
                {"task":"t","branch":"b","spec_id":"v1-sync-commit-integrity","agent_type":"claude-code","started_at":"2026-06-30T16:55:14Z","log_path":"/home/dev/.sail/agent.log"}
                """);
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("sail-mast");

    assertFalse(state.active(), "a successfully-exited unit is collected and reads as inactive");
    assertEquals(0, state.exitCode());
    assertEquals(
        "v1-sync-commit-integrity",
        state.specId(),
        "spec id must survive unit garbage collection via the durable session file");
    assertEquals("claude-code", state.agentType());
  }

  @Test
  void queryExitStatusPrefersUnitEnvironmentWhileItIsStillPresent() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show sail-agent.service",
                """
                ActiveState=active
                ExecMainStatus=0
                Environment=SAIL_SPEC_ID=scrum-12 SAIL_AGENT=codex
                """);
    var session = new AgentSession(shell);

    var state = session.queryExitStatus("acme");

    assertTrue(state.active());
    assertEquals("scrum-12", state.specId());
    assertEquals("codex", state.agentType());
  }

  @Test
  void writeSessionPersistsSpecIdAndAgentTypeForDurableRecovery() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var session = new AgentSession(shell);

    session.writeSession(
        "acme", "implement auth", "branch", "v1-sync-commit-integrity", "claude-code");

    var cmd = shell.invocations().getFirst();
    assertTrue(cmd.contains("agent-session.json"));
    assertTrue(cmd.contains("spec_id"), "session must persist spec_id for watcher recovery");
    assertTrue(cmd.contains("v1-sync-commit-integrity"));
    assertTrue(cmd.contains("claude-code"));
  }
}
