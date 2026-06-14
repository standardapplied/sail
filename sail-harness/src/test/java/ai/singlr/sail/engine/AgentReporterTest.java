/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SpecStatus;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentReporterTest {

  private static final String CONTAINER = "acme-health";

  @Test
  void completedSessionWithSpecs(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(3600 * 4).toString();
    var lastCommit = String.valueOf(Instant.now().minusSeconds(300).getEpochSecond());
    var authSpecYaml =
        """
        id: auth
        title: Build auth module
        status: done
        """;
    var testsSpecYaml =
        """
        id: tests
        title: Write tests
        status: done
        """;
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/.sail/agent.pid", "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                "{\"task\":\"build auth\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"sail/snap-20260302\",\"log_path\":\"/home/dev/.sail/agent.log\"}")
            .onOk(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "/home/dev/workspace/specs/auth/spec.yaml\n/home/dev/workspace/specs/tests/spec.yaml\n")
            .onOk("cat /home/dev/workspace/specs/auth/spec.yaml", authSpecYaml)
            .onOk("cat /home/dev/workspace/specs/tests/spec.yaml", testsSpecYaml)
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", lastCommit + "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "18\n")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file");

    var config = buildConfig("specs");
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, stateDir);

    assertEquals(CONTAINER, report.name());
    assertEquals("Completed", report.sessionStatus());
    assertEquals("sail/snap-20260302", report.branch());
    assertEquals(2, report.specs().size());
    assertEquals(SpecStatus.DONE, report.specs().getFirst().status());
    assertEquals(SpecStatus.DONE, report.specs().get(1).status());
    assertEquals("Write tests", report.specs().get(1).title());
    assertEquals(18, report.commitCount());
    assertFalse(report.guardrailTriggered());
    assertFalse(report.rolledBack());
    assertNotNull(report.duration());
  }

  @Test
  void runningSessionReportsRunning(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(1800).toString();
    var lastCommit = String.valueOf(Instant.now().minusSeconds(60).getEpochSecond());
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/.sail/agent.pid", "9999\n")
            .onOk("kill -0 9999", "")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                "{\"task\":\"fix bug\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"\",\"log_path\":\"/home/dev/.sail/agent.log\"}")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", lastCommit + "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "5\n")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onFail(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "No such file");

    var config = buildConfig("specs");
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, stateDir);

    assertEquals("Running", report.sessionStatus());
    assertEquals(5, report.commitCount());
    assertTrue(report.lastCommitMinutesAgo() >= 0);
  }

  @Test
  void guardrailTriggeredSession(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(3600 * 5).toString();
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/.sail/agent.pid", "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                "{\"task\":\"implement API\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"sail/api\",\"log_path\":\"/home/dev/.sail/agent.log\"}")
            .onOk(
                "cat /home/dev/guardrail-triggered.yaml",
                "reason: max_duration\naction: snapshot-and-stop\n")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "0\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "45\n")
            .onFail(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "No such file");

    var config = buildConfig("specs");
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, stateDir);

    assertEquals("Killed by guardrail", report.sessionStatus());
    assertTrue(report.guardrailTriggered());
    assertEquals("max_duration", report.guardrailReason());
    assertEquals("snapshot-and-stop", report.guardrailAction());
    assertEquals(45, report.commitCount());
  }

  @Test
  void noSessionReturnsMinimalReport(@TempDir java.nio.file.Path stateDir) throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat /home/dev/.sail/agent.pid", "No such file")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onFail(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "No such file")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "0\n");

    var config = buildConfig("specs");
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, stateDir);

    assertEquals("No session", report.sessionStatus());
    assertFalse(report.guardrailTriggered());
    assertFalse(report.rolledBack());
    assertTrue(report.specs().isEmpty());
  }

  @Test
  void noSpecsDirConfiguredReturnsEmptySpecs(@TempDir java.nio.file.Path stateDir)
      throws Exception {
    var startedAt = Instant.now().minusSeconds(3600).toString();
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/.sail/agent.pid", "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                "{\"task\":\"fix bug\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"\",\"log_path\":\"/home/dev/.sail/agent.log\"}")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "3\n");

    var config = buildConfigNoSpecsDir();
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, stateDir);

    assertEquals("Completed", report.sessionStatus());
    assertTrue(report.specs().isEmpty());
    assertEquals(3, report.commitCount());
  }

  @Test
  void rolledBackSessionReportsRollback(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(3600 * 2).toString();
    var rolledBackAt = Instant.now().minusSeconds(3600).toString();
    var rollbackYaml =
        "rolled_back_at: \""
            + rolledBackAt
            + "\"\nexit_code: 1\nsnapshot_restored: pre-agent-20260302\ntask: implement auth\n";
    Files.writeString(stateDir.resolve("last-rollback.yaml"), rollbackYaml);

    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/.sail/agent.pid", "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                "{\"task\":\"implement auth\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"sail/snap\",\"log_path\":\"/home/dev/.sail/agent.log\"}")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "10\n")
            .onFail(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "No such file");

    var config = buildConfig("specs");
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, stateDir);

    assertEquals("Rolled back", report.sessionStatus());
    assertTrue(report.rolledBack());
    assertEquals("pre-agent-20260302", report.rollbackSnapshot());
    assertNotNull(report.endedAt());
  }

  @Test
  void specsWithDependenciesIncluded(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(3600).toString();
    var authSpecYaml =
        """
        id: auth
        title: Build auth
        status: done
        """;
    var docsSpecYaml =
        """
        id: docs
        title: Update docs
        status: pending
        depends_on:
          - auth
        """;
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/.sail/agent.pid", "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat /home/dev/.sail/agent-session.json",
                "{\"task\":\"build auth\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"\",\"log_path\":\"/home/dev/.sail/agent.log\"}")
            .onOk(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "/home/dev/workspace/specs/auth/spec.yaml\n/home/dev/workspace/specs/docs/spec.yaml\n")
            .onOk("cat /home/dev/workspace/specs/auth/spec.yaml", authSpecYaml)
            .onOk("cat /home/dev/workspace/specs/docs/spec.yaml", docsSpecYaml)
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "8\n")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file");

    var config = buildConfig("specs");
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, stateDir);

    assertEquals(2, report.specs().size());
    assertEquals("Build auth", report.specs().getFirst().title());
    assertEquals("Update docs", report.specs().get(1).title());
    assertEquals(List.of("auth"), report.specs().get(1).dependsOn());
  }

  @Test
  void reportToMapContainsAllFields() {
    var report =
        new AgentReporter.Report(
            "acme",
            "Completed",
            "2026-03-02T01:00:00Z",
            "2026-03-02T04:42:00Z",
            "3h 42m",
            "sail/snap",
            List.of(
                new ai.singlr.sail.config.Spec(
                    "auth", "Implement JWT", SpecStatus.DONE, null, List.of(), null)),
            18,
            47,
            false,
            null,
            null,
            false,
            null);

    var map = report.toMap();

    assertEquals("acme", map.get("name"));
    assertEquals("Completed", map.get("session_status"));
    assertEquals("2026-03-02T01:00:00Z", map.get("started_at"));
    assertEquals("3h 42m", map.get("duration"));
    assertEquals("sail/snap", map.get("branch"));
    assertEquals(18, map.get("commits_since_launch"));
    assertEquals(47L, map.get("last_commit_minutes_ago"));
    assertEquals(false, map.get("guardrail_triggered"));
    assertEquals(false, map.get("rolled_back"));
    assertFalse(map.containsKey("guardrail_reason"));
    assertFalse(map.containsKey("rollback_snapshot"));
  }

  @Test
  void reportToMapIncludesGuardrailFields() {
    var report =
        new AgentReporter.Report(
            "acme",
            "Killed by guardrail",
            null,
            null,
            "4h 0m",
            "",
            List.of(),
            45,
            -1,
            true,
            "max_duration",
            "snapshot-and-stop",
            false,
            null);

    var map = report.toMap();

    assertEquals(true, map.get("guardrail_triggered"));
    assertEquals("max_duration", map.get("guardrail_reason"));
    assertEquals("snapshot-and-stop", map.get("guardrail_action"));
    assertFalse(map.containsKey("last_commit_minutes_ago"));
  }

  private static SailYaml buildConfig(String taskFile) {
    return new SailYaml(
        CONTAINER,
        null,
        new SailYaml.Resources(4, "12GB", "150GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, taskFile, null, null, null),
        null,
        new SailYaml.Ssh("dev", null));
  }

  private static SailYaml buildConfigNoSpecsDir() {
    return new SailYaml(
        CONTAINER,
        null,
        new SailYaml.Resources(4, "12GB", "150GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null),
        null,
        new SailYaml.Ssh("dev", null));
  }
}
