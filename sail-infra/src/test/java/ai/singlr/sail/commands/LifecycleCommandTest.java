/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

/**
 * CLI wiring tests for lifecycle commands: up, down, switch, project list, project destroy, snap,
 * restore, snaps, agent. Tests verify picocli registration, help output, and argument validation.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LifecycleCommandTest {

  private PrintStream originalOut;
  private PrintStream originalErr;
  private ByteArrayOutputStream capturedOut;
  private ByteArrayOutputStream capturedErr;

  @BeforeEach
  void captureStreams() {
    originalOut = System.out;
    originalErr = System.err;
    capturedOut = new ByteArrayOutputStream();
    capturedErr = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capturedOut));
    System.setErr(new PrintStream(capturedErr));
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @Test
  void upHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "start", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Start a project container"));
  }

  @Test
  void upWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "start");

    assertNotEquals(0, exitCode);
  }

  @Test
  void downHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "stop", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Stop a project container"));
  }

  @Test
  void downWithNoNameIsValid() {
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("project", "stop");

    assertTrue(exitCode == 0 || exitCode == 1, "Should not be a usage error (2)");
  }

  @Test
  void switchHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "restart", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Switch to a project"));
  }

  @Test
  void switchWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "restart");

    assertNotEquals(0, exitCode);
  }

  @Test
  void projectListHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "list", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("List all project containers"));
  }

  @Test
  void projectDestroyHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "destroy", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--yes"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Destroy a project"));
  }

  @Test
  void projectDestroyWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "destroy");

    assertNotEquals(0, exitCode);
  }

  @Test
  void projectDestroyNonDryRunWithoutRootFails() {
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("project", "destroy", "test-proj", "--yes");

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Root privileges required"), "Should require root");
  }

  @Test
  void snapHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "snapshot", "create", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Create a snapshot"));
  }

  @Test
  void snapWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "snapshot", "create");

    assertNotEquals(0, exitCode);
  }

  @Test
  void restoreHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "snapshot", "restore", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Restore"));
  }

  @Test
  void restoreWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "snapshot", "restore");

    assertNotEquals(0, exitCode);
  }

  @Test
  void snapsHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "snapshot", "list", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("List snapshots"));
  }

  @Test
  void snapsWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "snapshot", "list");

    assertNotEquals(0, exitCode);
  }

  @Test
  void agentHelpShowsSubcommands() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("launch"), "Should list 'launch' subcommand");
    assertTrue(output.contains("status"), "Should list 'status' subcommand");
    assertTrue(output.contains("stop"), "Should list 'stop' subcommand");
    assertTrue(output.contains("log"), "Should list 'log' subcommand");
  }

  @Test
  void agentLaunchHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "start", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--path"));
    assertTrue(output.contains("--file"));
    assertTrue(output.contains("--task"));
    assertTrue(output.contains("--background"));
    assertTrue(output.contains("Launch"));
  }

  @Test
  void agentLaunchWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("agent", "start");

    assertNotEquals(0, exitCode);
  }

  @Test
  void agentStatusHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "status", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Check agent session status"));
  }

  @Test
  void agentStopHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "stop", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Stop a running agent"));
  }

  @Test
  void agentLogHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "logs", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--follow"));
    assertTrue(output.contains("--tail"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("View agent session"));
  }

  @Test
  void singHelpListsLifecycleCommands() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("host"), "Should list 'host' command");
    assertTrue(output.contains("project"), "Should list 'project' command");
    assertTrue(output.contains("spec"), "Should list 'spec' command");
  }

  @Test
  void singHelpListsSnapshotAndAgentCommands() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("project"), "Should list 'project' command");
    assertTrue(output.contains("spec"), "Should list 'spec' command");
    assertTrue(output.contains("agent"), "Should list 'agent' command");
  }

  @Test
  void projectHelpListsNewSubcommands() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("create"), "Should list 'create' subcommand");
    assertTrue(output.contains("list"), "Should list 'list' subcommand");
    assertTrue(output.contains("destroy"), "Should list 'destroy' subcommand");
    assertTrue(output.contains("config"), "Should list 'config' subcommand");
  }

  @Test
  void projectConfigHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "config", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--file"));
    assertTrue(output.contains("Show project configuration"));
  }

  @Test
  void projectConfigWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "config");

    assertNotEquals(0, exitCode);
  }

  @Test
  void projectInstallAgentHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "install-agent", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("claude-code, codex"));
  }

  @Test
  void projectInstallAgentWithNoArgsShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "install-agent");

    assertNotEquals(0, exitCode);
  }

  @Test
  void hostStatusHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("host", "status", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Show server health"));
  }

  @Test
  void hostHelpListsAllSubcommands() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("host", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("init"), "Should list 'init' subcommand");
    assertTrue(output.contains("status"), "Should list 'status' subcommand");
    assertTrue(output.contains("update"), "Should list 'update' subcommand");
  }

  @Test
  void hostUpdateHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("host", "update", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Update system packages"));
  }

  @Test
  void psHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "containers", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("Show Podman container status"));
  }

  @Test
  void psWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "containers");

    assertNotEquals(0, exitCode);
  }

  @Test
  void logsHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "logs", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--follow"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--tail"));
    assertTrue(output.contains("Tail logs"));
  }

  @Test
  void logsWithNoNameShowsError() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "logs");

    assertNotEquals(0, exitCode);
  }

  @Test
  void hostInitHelpShowsJsonOption() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("host", "init", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--dry-run"));
  }

  @Test
  void execHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "exec", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Run a command inside a project container"));
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
  }

  @Test
  void execMissingArgsFails() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project", "exec");

    assertNotEquals(0, exitCode);
  }

  @Test
  void singHelpListsPsLogsAndExecCommands() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("project"), "Should list 'project' command");
    assertTrue(output.contains("agent"), "Should list 'agent' command");
    assertTrue(output.contains("events"), "Should list 'events' command");
  }

  @Test
  void connectHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "connect", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("SSH config snippet"));
  }

  @Test
  void connectMissingArgsFails() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "connect");

    assertNotEquals(0, exitCode);
  }

  @Test
  void agentContextRegenHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "context", "regen", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Regenerate the agent context file"));
  }

  @Test
  void agentContextRegenMissingArgsFails() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "context", "regen");

    assertNotEquals(0, exitCode);
  }

  @Test
  void agentContextHelpListsRegenSubcommand() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "context", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("regen"), "Should list 'regen' subcommand");
  }

  @Test
  void agentHelpListsContextSubcommand() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("context"), "Should list 'context' subcommand");
    assertTrue(output.contains("audit"), "Should list 'audit' subcommand");
  }

  @Test
  void agentAuditHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "audit", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Run a security audit"));
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
  }

  @Test
  void agentAuditMissingArgsFails() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "audit");

    assertNotEquals(0, exitCode);
  }

  @Test
  void projectConfigHelpDoesNotShowRegenContext() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "config", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertFalse(output.contains("--regen-context"), "Should not show removed --regen-context flag");
  }

  @Test
  void projectPullHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "pull", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Pull a project descriptor"));
    assertTrue(output.contains("--github-token"));
    assertTrue(output.contains("--repo"));
    assertTrue(output.contains("--ref"));
    assertTrue(output.contains("--output"));
  }

  @Test
  void projectPullMissingArgsFails() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "pull");

    assertNotEquals(0, exitCode);
  }

  @Test
  void projectHelpListsPullSubcommand() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("pull"), "Should list 'pull' subcommand");
    assertTrue(output.contains("apply"), "Should list 'apply' subcommand");
  }

  @Test
  void projectApplyHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "apply", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Apply incremental changes"));
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--git-token"));
  }

  @Test
  void projectApplyMissingArgsFails() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "apply");

    assertNotEquals(0, exitCode);
  }
}
