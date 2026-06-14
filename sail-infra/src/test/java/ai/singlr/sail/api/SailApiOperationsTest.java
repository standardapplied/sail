/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailApiOperationsTest {

  private static final String RUNNING_JSON =
      """
      [
        {
          "name": "acme",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  private static final String STOPPED_JSON =
      """
      [{"name": "acme", "status": "Stopped", "state": {}}]
      """;

  private static final String EMPTY_JSON = "[]";

  private static final String SPEC_PATHS =
      """
      /home/dev/workspace/specs/auth/spec.yaml
      /home/dev/workspace/specs/billing/spec.yaml
      /home/dev/workspace/specs/setup/spec.yaml
      """;

  private static final String SETUP_SPEC_YAML =
      """
      id: setup
      title: Setup project
      status: done
      """;

  private static final String AUTH_SPEC_YAML =
      """
      id: auth
      title: Add auth
      status: pending
      depends_on: [setup]
      """;

  private static final String BILLING_SPEC_YAML =
      """
      id: billing
      title: Add billing
      status: pending
      depends_on: [missing]
      """;

  private static final String AUTH_BRANCH_SPEC_YAML =
      """
      id: auth
      title: Add auth
      status: pending
      branch: feat/custom
      """;

  private static final String AUTH_CODEX_SPEC_YAML =
      """
      id: auth
      title: Add auth
      status: pending
      agent: codex
      model: gpt-5.5
      reasoning_effort: high
      """;

  private static final String DONE_SPEC_YAML =
      """
      id: done
      title: Done
      status: done
      """;

  @TempDir Path tempDir;

  @Test
  void healthReturnsOk() {
    var operations = new SailApiOperations(new FakeShell(), "sail.yaml");

    assertEquals("ok", get(operations.health(), "status"));
  }

  @Test
  void defaultConstructorSupportsHealthChecks() {
    assertEquals("ok", get(new SailApiOperations().health(), "status"));
  }

  @Test
  void projectReturnsContainerAndAgentStatus() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON));

    var result = operations.project("acme");

    assertEquals("acme", get(result, "name"));
    assertEquals("running", get(result, "container_status"));
    assertTrue(get(result, "agent").toString().contains("claude-code"));
  }

  @Test
  void projectMapsStoppedMissingAndErrorStates() throws Exception {
    assertEquals(
        "stopped",
        get(
            operations(shell().on("incus list ^acme$", STOPPED_JSON)).project("acme"),
            "container_status"));
    assertEquals(
        "not_created",
        get(
            operations(shell().on("incus list ^acme$", EMPTY_JSON)).project("acme"),
            "container_status"));
    assertEquals(
        "error",
        get(
            operations(shell().on("incus list ^acme$", new ShellExec.Result(1, "", "boom")))
                .project("acme"),
            "container_status"));
  }

  @Test
  void projectOmitsAgentWhenUnconfigured() throws Exception {
    var operations = operations(noAgentYaml(), shell().on("incus list ^acme$", RUNNING_JSON));

    var result = operations.project("acme");

    assertFalse(containsKey(result, "agent"));
  }

  @Test
  void specsReturnsBoardSummary() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON).withSpecs());

    var result = operations.specs("acme");

    assertEquals("acme", get(result, "name"));
    assertTrue(get(result, "specs") instanceof List<?>);
    assertTrue(ApiJson.withSchema(result.orThrow()).toString().contains("next_ready_id=auth"));
  }

  @Test
  void specReturnsContentWhenPresent() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "# Auth"));

    var result = operations.spec("acme", "auth");

    assertEquals(true, get(result, "content_available"));
    assertEquals("# Auth", get(result, "content"));
  }

  @Test
  void specReturnsNotFoundForUnknownSpec() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON).withSpecs());

    var error = operations.spec("acme", "missing");

    assertError(ErrorCode.SPEC_NOT_FOUND, error);
  }

  @Test
  void specAllowsMissingContent() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .withSpecs()
                .on(
                    "cat /home/dev/workspace/specs/auth/spec.md",
                    new ShellExec.Result(1, "", "No such file")));

    var result = operations.spec("acme", "auth");

    assertEquals(false, get(result, "content_available"));
    assertFalse(containsKey(result, "content"));
  }

  @Test
  void dispatchReturnsNoPendingSpecs() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON).withDoneSpec());

    var result = operations.dispatch("acme", request());

    assertEquals(false, get(result, "dispatched"));
    assertEquals("no_pending_specs", get(result, "reason"));
  }

  @Test
  void dispatchDryRunUpdatesSpecAndReturnsStructuredResult() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals(true, get(result, "dispatched"));
    assertTrue(get(result, "spec").toString().contains("status=in_progress"));
    assertTrue(get(result, "agent").toString().contains("running=false"));
  }

  @Test
  void dispatchRejectsBlockedSpec() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs());

    var error = operations.dispatch("acme", request("billing"));

    assertError(ErrorCode.SPEC_NOT_READY, error);
  }

  @Test
  void dispatchRejectsInvalidMode() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON));

    var error = operations.dispatch("acme", request(null, "sideways", false));

    assertError(ErrorCode.INVALID_MODE, error);
  }

  @Test
  void dispatchRejectsUnknownSpec() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs());

    var error = operations.dispatch("acme", request("missing"));

    assertError(ErrorCode.SPEC_NOT_FOUND, error);
  }

  @Test
  void dispatchRejectsRunningAgent() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}"));

    var error = operations.dispatch("acme", request());

    assertError(ErrorCode.AGENT_ALREADY_RUNNING, error);
  }

  @Test
  void projectStoppedMapsToConflict() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", STOPPED_JSON));

    var error = operations.specs("acme");

    assertError(ErrorCode.PROJECT_STOPPED, error);
  }

  @Test
  void projectMissingMapsToNotFound() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", EMPTY_JSON));

    var error = operations.specs("acme");

    assertError(ErrorCode.PROJECT_NOT_CREATED, error);
  }

  @Test
  void projectErrorMapsToContainerError() throws Exception {
    var operations =
        operations(shell().on("incus list ^acme$", new ShellExec.Result(1, "", "incus down")));

    var error = operations.specs("acme");

    assertError(ErrorCode.CONTAINER_ERROR, error);
  }

  @Test
  void agentEndpointRejectsMissingProject() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", EMPTY_JSON));

    var error = operations.agentStatus("acme");

    assertError(ErrorCode.PROJECT_NOT_CREATED, error);
  }

  @Test
  void agentEndpointRejectsContainerErrors() throws Exception {
    var operations =
        operations(shell().on("incus list ^acme$", new ShellExec.Result(1, "", "incus down")));

    var error = operations.agentStatus("acme");

    assertError(ErrorCode.CONTAINER_ERROR, error);
  }

  @Test
  void specsRequireConfiguredAgentDirectory() throws Exception {
    var operations = operations(noAgentYaml(), shell().on("incus list ^acme$", RUNNING_JSON));

    var error = operations.specs("acme");

    assertError(ErrorCode.SPECS_NOT_CONFIGURED, error);
  }

  @Test
  void agentStatusReturnsNotRunning() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")));

    var result = operations.agentStatus("acme");

    assertEquals(false, get(result, "agent_running"));
  }

  @Test
  void agentStatusReturnsRunningSessionDetails() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on(
                    "cat /home/dev/.sail/agent-session.json",
                    "{\"task\": \"work\", \"started_at\": \"2026-01-01T00:00:00Z\", \"branch\": \"sail/auth\"}"));

    var result = operations.agentStatus("acme");

    assertEquals(true, get(result, "agent_running"));
    assertEquals(123, get(result, "pid"));
    assertEquals("work", get(result, "task"));
  }

  @Test
  void agentStatusMapsQueryFailures() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("cat /home/dev/.sail/agent.pid", new IOException("denied")));

    var error = operations.agentStatus("acme");

    assertError(ErrorCode.AGENT_STATUS_FAILED, error);
  }

  @Test
  void agentLogHandlesMissingLog() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on(
                    "tail -n 200 /home/dev/.sail/agent.log",
                    new ShellExec.Result(1, "", "No such file")));

    var result = operations.agentLog("acme", 200);

    assertEquals("No agent log found", get(result, "error"));
  }

  @Test
  void agentLogReturnsLines() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("tail -n 2 /home/dev/.sail/agent.log", "one\ntwo\n"));

    var result = operations.agentLog("acme", 2);

    assertEquals(List.of("one", "two"), get(result, "lines"));
  }

  @Test
  void agentLogMapsThrownCommandsToApiError() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("tail -n 200 /home/dev/.sail/agent.log", new IOException("no shell")));

    var error = operations.agentLog("acme", 200);

    assertError(ErrorCode.COMMAND_FAILED, error);
  }

  @Test
  void stopAgentReturnsNoAgentRunning() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")));

    var result = operations.stopAgent("acme");

    assertEquals(false, get(result, "stopped"));
  }

  @Test
  void dispatchLaunchesBackgroundAgent() throws Exception {
    var operations =
        operations(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("mkdir -p /home/dev/.sail", "")
                .on("claude", ""));

    var result = operations.dispatch("acme", request("auth"));

    assertEquals(true, get(result, "dispatched"));
    assertTrue(get(result, "agent").toString().contains("mode=background"));
  }

  @Test
  void dispatchUsesSpecAgentWhenPresent() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
            .withCodexSpec()
            .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
            .on("mkdir -p /home/dev/workspace/specs", "")
            .on("printf '%s'", "")
            .on("mkdir -p /home/dev/.sail", "")
            .on("codex exec --dangerously-bypass-approvals-and-sandbox --model gpt-5.5", "");
    var operations = operations(baseYaml(), shell);

    var result = operations.dispatch("acme", request("auth"));

    assertEquals(true, get(result, "dispatched"));
    assertTrue(get(result, "spec").toString().contains("agent=codex"));
    assertTrue(get(result, "spec").toString().contains("model=gpt-5.5"));
    assertTrue(get(result, "spec").toString().contains("reasoning_effort=high"));
    assertTrue(get(result, "agent").toString().contains("type=codex"));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                command ->
                    command.contains(
                            "codex exec --dangerously-bypass-approvals-and-sandbox --model gpt-5.5")
                        && command.contains("model_reasoning_effort='\"high\"'")));
    assertFalse(
        shell.invocations().stream().anyMatch(command -> command.contains("claude --print")));
  }

  @Test
  void dispatchLaunchesForegroundAgentAndReturnsSessionDetails() throws Exception {
    var operations =
        operations(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", new ShellExec.Result(1, "", "missing"))
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("mkdir -p /home/dev/.sail", "")
                .on("bash -l -c", ""));

    var result = operations.dispatch("acme", request("auth", "foreground", false));

    assertTrue(get(result, "agent").toString().contains("mode=foreground"));
    assertTrue(get(result, "agent").toString().contains("pid=123"));
  }

  @Test
  void dispatchMapsUnexpectedFailures() throws Exception {
    var operations = operations(baseYaml(), shell().on("incus list ^acme$", RUNNING_JSON));

    var error = operations.dispatch("acme", null);

    assertError(ErrorCode.INTERNAL, error);
  }

  @Test
  void dispatchMapsLaunchFailure() throws Exception {
    var operations =
        operations(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("-- mkdir -p /home/dev/.sail", "")
                .on("claude", new ShellExec.Result(1, "", "missing cli")));

    var error = operations.dispatch("acme", request("auth"));

    assertError(ErrorCode.AGENT_LAUNCH_FAILED, error);
  }

  @Test
  void dispatchLaunchesWatcherWhenGuardrailsAreConfigured() throws Exception {
    var launched = new LinkedHashMap<String, Object>();
    var operations =
        operations(
            guardrailsYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("-- mkdir -p /home/dev/.sail", "")
                .on("claude", ""),
            (command, logPath) -> {
              launched.put("command", command);
              launched.put("log_path", logPath.toString());
            });

    operations.dispatch("acme", request("auth"));

    assertTrue(launched.get("command").toString().contains("agent, watch, acme"));
    assertTrue(launched.get("log_path").toString().endsWith("watch.log"));
  }

  @Test
  void dispatchMapsWatcherFailureToLaunchFailure() throws Exception {
    var operations =
        operations(
            guardrailsYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("-- mkdir -p /home/dev/.sail", "")
                .on("claude", ""),
            (command, logPath) -> {
              throw new IOException("watch failed");
            });

    var error = operations.dispatch("acme", request("auth"));

    assertError(ErrorCode.AGENT_LAUNCH_FAILED, error);
  }

  @Test
  void watcherProcessLauncherStartsCommand() throws Exception {
    var logPath = tempDir.resolve("watch.log");

    SailApiOperations.launchWatcherProcess(List.of("sh", "-c", "true"), logPath);

    assertTrue(Files.exists(logPath));
  }

  @Test
  void dispatchCreatesBranchWhenConfigured() throws Exception {
    var operations =
        operations(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("test -d /home/dev/workspace/app/.git", "")
                .on("git -C /home/dev/workspace/app checkout -b sail/auth", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals(true, get(result, "branch_created"));
    assertTrue(get(result, "spec").toString().contains("sail/auth"));
  }

  @Test
  void dispatchUsesSpecBranchWhenProvided() throws Exception {
    var operations =
        operations(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withAuthBranchSpec()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("test -d /home/dev/workspace/app/.git", "")
                .on("git -C /home/dev/workspace/app checkout -b feat/custom", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertTrue(get(result, "spec").toString().contains("feat/custom"));
  }

  @Test
  void dispatchSkipsBranchWhenRepoIsMissing() throws Exception {
    var operations =
        operations(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on(
                    "test -d /home/dev/workspace/app/.git",
                    new ShellExec.Result(1, "", "missing")));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals(false, get(result, "branch_created"));
  }

  @Test
  void dispatchMapsBranchFailure() throws Exception {
    var operations =
        operations(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("test -d /home/dev/workspace/app/.git", "")
                .on(
                    "git -C /home/dev/workspace/app checkout -b sail/auth",
                    new ShellExec.Result(1, "", "exists")));

    var error = operations.dispatch("acme", request("auth", "background", true));

    assertError(ErrorCode.BRANCH_CREATE_FAILED, error);
  }

  @Test
  void dispatchCreatesSnapshotWhenConfigured() throws Exception {
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("incus snapshot list acme --format json", "[]")
                .on("incus snapshot create acme", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertFalse(get(result, "snapshot").toString().isBlank());
  }

  @Test
  void dispatchSkipsRecentSnapshot() throws Exception {
    var snapshots = "[{\"name\": \"snap\", \"created_at\": \"" + Instant.now() + "\"}]";
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("incus snapshot list acme --format json", snapshots));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals("", get(result, "snapshot"));
  }

  @Test
  void dispatchCreatesSnapshotWhenLatestTimestampIsInvalid() throws Exception {
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on(
                    "incus snapshot list acme --format json",
                    "[{\"name\": \"snap\", \"created_at\": \"bad\"}]")
                .on("incus snapshot create acme", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertFalse(get(result, "snapshot").toString().isBlank());
  }

  @Test
  void dispatchMapsSnapshotFailure() throws Exception {
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .on("cat /home/dev/workspace/specs/auth/spec.md", "Do auth")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("incus snapshot list acme --format json", "[]")
                .on("incus snapshot create acme", new ShellExec.Result(1, "", "no space")));

    var error = operations.dispatch("acme", request("auth", "background", true));

    assertError(ErrorCode.SNAPSHOT_FAILED, error);
  }

  @Test
  void stopAgentKillsRunningAgent() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
                .on("kill 123", "")
                .on("sleep 3", "")
                .on("kill -9 123", "")
                .on("rm -f /home/dev/.sail/agent.pid", ""));

    var result = operations.stopAgent("acme");

    assertEquals(true, get(result, "stopped"));
    assertEquals(123, get(result, "pid"));
  }

  @Test
  void stopAgentMapsKillFailure() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
                .throwOn("kill 123", new IOException("permission denied")));

    var error = operations.stopAgent("acme");

    assertError(ErrorCode.AGENT_STOP_FAILED, error);
  }

  @Test
  void agentReportReturnsSummary() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs());

    var result = operations.agentReport("acme");

    assertEquals("acme", get(result, "name"));
    assertEquals("No session", get(result, "session_status"));
  }

  @Test
  void agentReportMapsReporterFailure() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("cat /home/dev/.sail/agent.pid", new IOException("boom")));

    var error = operations.agentReport("acme");

    assertError(ErrorCode.AGENT_REPORT_FAILED, error);
  }

  @Test
  void agentLogFailureMapsToApiError() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on(
                    "tail -n 200 /home/dev/.sail/agent.log",
                    new ShellExec.Result(1, "", "permission denied")));

    var error = operations.agentLog("acme", 200);

    assertError(ErrorCode.AGENT_LOG_FAILED, error);
  }

  @Test
  void missingDescriptorMapsToNotFound() {
    var operations = new SailApiOperations(shell(), tempDir.resolve("missail.yaml").toString());

    var error = operations.project("acme");

    assertError(ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND, error);
  }

  @Test
  void malformedDescriptorMapsToProjectLoadFailure() throws Exception {
    var operations = operations("name: [", shell().on("incus list ^acme$", RUNNING_JSON));

    var error = operations.project("acme");

    assertError(ErrorCode.PROJECT_LOAD_FAILED, error);
  }

  @Test
  void specIndexFailuresMapToApiErrors() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on(
                    "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                    new ShellExec.Result(1, "", "denied")));

    var error = operations.specs("acme");

    assertError(ErrorCode.SPECS_READ_FAILED, error);
  }

  @Test
  void specBodyFailuresMapToApiErrors() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .withSpecs()
                .throwOn("cat /home/dev/workspace/specs/auth/spec.md", new IOException("denied")));

    var error = operations.spec("acme", "auth");

    assertError(ErrorCode.SPEC_READ_FAILED, error);
  }

  @Test
  void statusUpdateFailuresMapToApiErrors() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .withSpecs()
                .throwOn("mkdir -p /home/dev/workspace/specs", new IOException("denied")));

    var error = operations.dispatch("acme", request("auth"));

    assertError(ErrorCode.SPEC_STATUS_UPDATE_FAILED, error);
  }

  @Test
  void publishEventFailsWhenBusNotWired() throws Exception {
    var operations = operations(baseYaml(), shell());
    var result =
        operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "host-01"));
    assertError(ErrorCode.INTERNAL, result);
  }

  @Test
  void publishEventReturnsStampedIdWhenBusWired(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      var operations = new SailApiOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);
      var result =
          operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "host-01"));
      assertTrue(result.isSuccess());
      assertEquals(1L, get(result, "id"));
      assertNotNull(get(result, "event"));
    }
  }

  @Test
  void recentEventsRejectsBadLimit() throws Exception {
    var operations = operations(baseYaml(), shell());
    assertError(ErrorCode.INVALID_REQUEST, operations.recentEvents(0));
    assertError(ErrorCode.INVALID_REQUEST, operations.recentEvents(-1));
    assertError(ErrorCode.INVALID_REQUEST, operations.recentEvents(99999));
  }

  @Test
  void recentEventsEmptyWhenPersisterMissing() throws Exception {
    var operations = operations(baseYaml(), shell());
    var result = operations.recentEvents(10);
    assertTrue(result.isSuccess());
    assertEquals(10, get(result, "limit"));
    assertEquals(0, get(result, "returned"));
  }

  @Test
  void recentEventsReplaysFromPersister(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      bus.subscribe(persister);
      var operations = new SailApiOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);

      operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "h"));
      operations.publishEvent(Event.of("acme", null, "snapshot_created", "sail", "h"));

      Thread.sleep(100);
      var result = operations.recentEvents(5);
      assertTrue(result.isSuccess());
    }
  }

  @Test
  void eventBusStatsEmptyWithoutBus() throws Exception {
    var operations = operations(baseYaml(), shell());
    var result = operations.eventBusStats();
    assertTrue(result.isSuccess());
    assertEquals(0L, get(result, "published"));
  }

  @Test
  void eventBusStatsReflectsBusState(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      var operations = new SailApiOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);
      operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "h"));
      var result = operations.eventBusStats();
      assertTrue(result.isSuccess());
      assertEquals(1L, get(result, "published"));
    }
  }

  private Path baseYamlPath(Path dir) throws IOException {
    var yaml = dir.resolve("sail.yaml");
    Files.writeString(yaml, baseYaml());
    return yaml;
  }

  private static Object get(Result<?> result, String key) {
    return ApiJson.withSchema(result.orThrow()).get(key);
  }

  private static boolean containsKey(Result<?> result, String key) {
    return ApiJson.withSchema(result.orThrow()).containsKey(key);
  }

  private static void assertError(ErrorCode errorCode, Result<?> result) {
    assertTrue(result.isFailure());
    assertEquals(errorCode, result.errorCode());
  }

  private static DispatchRequest request() {
    return request(null, "background", false);
  }

  private static DispatchRequest request(String specId) {
    return request(specId, "background", false);
  }

  private static DispatchRequest request(String specId, String mode, boolean dryRun) {
    return new DispatchRequest(specId, mode, dryRun);
  }

  private SailApiOperations operations(String yamlContent, FakeShell shell) throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, yamlContent);
    return new SailApiOperations(shell, yaml.toString());
  }

  private SailApiOperations operations(
      String yamlContent, FakeShell shell, SailApiOperations.WatcherLauncher watcherLauncher)
      throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, yamlContent);
    return new SailApiOperations(shell, yaml.toString(), watcherLauncher);
  }

  private static String baseYaml() {
    return """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
        """;
  }

  private static String noAgentYaml() {
    return """
        name: acme
        ssh:
          user: dev
        """;
  }

  private static String branchYaml() {
    return """
        name: acme
        ssh:
          user: dev
        repos:
          - url: https://github.com/acme/app.git
            path: app
        agent:
          type: claude-code
          specs_dir: specs
          auto_branch: true
          branch_prefix: sail/
        """;
  }

  private static String snapshotYaml() {
    return """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
          auto_snapshot: true
        """;
  }

  private static String guardrailsYaml() {
    return """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
          guardrails:
            max_duration: 4h
            action: stop
        """;
  }

  private SailApiOperations operations(FakeShell shell) throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, baseYaml());
    return new SailApiOperations(shell, yaml.toString());
  }

  private static FakeShell shell() {
    return new FakeShell();
  }

  private static final class FakeShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final Map<String, Exception> failures = new LinkedHashMap<>();
    private final List<String> invocations = new ArrayList<>();

    FakeShell on(String pattern, String stdout) {
      return on(pattern, new Result(0, stdout, ""));
    }

    FakeShell on(String pattern, Result result) {
      scripts.put(pattern, result);
      return this;
    }

    FakeShell throwOn(String pattern, Exception failure) {
      failures.put(pattern, failure);
      return this;
    }

    FakeShell withSpecs() {
      return on(
              "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
              SPEC_PATHS)
          .on("cat /home/dev/workspace/specs/auth/spec.yaml", AUTH_SPEC_YAML)
          .on("cat /home/dev/workspace/specs/billing/spec.yaml", BILLING_SPEC_YAML)
          .on("cat /home/dev/workspace/specs/setup/spec.yaml", SETUP_SPEC_YAML);
    }

    FakeShell withAuthBranchSpec() {
      return on(
              "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
              "/home/dev/workspace/specs/auth/spec.yaml\n")
          .on("cat /home/dev/workspace/specs/auth/spec.yaml", AUTH_BRANCH_SPEC_YAML);
    }

    FakeShell withCodexSpec() {
      return on(
              "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
              "/home/dev/workspace/specs/auth/spec.yaml\n")
          .on("cat /home/dev/workspace/specs/auth/spec.yaml", AUTH_CODEX_SPEC_YAML);
    }

    FakeShell withDoneSpec() {
      return on(
              "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
              "/home/dev/workspace/specs/done/spec.yaml\n")
          .on("cat /home/dev/workspace/specs/done/spec.yaml", DONE_SPEC_YAML);
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      var joined = String.join(" ", command);
      invocations.add(joined);
      for (var entry : failures.entrySet()) {
        if (joined.contains(entry.getKey())) {
          throw (IOException) entry.getValue();
        }
      }
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    List<String> invocations() {
      return List.copyOf(invocations);
    }
  }
}
