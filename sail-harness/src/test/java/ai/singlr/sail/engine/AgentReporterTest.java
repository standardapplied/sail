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
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.RunStore;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentReporterTest {

  private static final String CONTAINER = "acme-health";
  private static final String RUN_ID = "019bd3a8-94b0-7f3d-a0f5-77d2b4cfda01";
  private static final AgentUnit RUN_UNIT = AgentUnit.forRun(RUN_ID);

  private static RunStore.RunRow sessionRow(
      String unit, String status, Integer exitCode, String startedAt, String completedAt) {
    return new RunStore.RunRow(
        RUN_ID,
        CONTAINER,
        "auth",
        "node-a",
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        123,
        null,
        status,
        exitCode,
        null,
        unit,
        startedAt,
        completedAt,
        List.of(),
        null);
  }

  @Test
  void completedSessionWithSpecs(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(3600 * 4).toString();
    var lastCommit = String.valueOf(Instant.now().minusSeconds(300).getEpochSecond());
    var specs =
        List.of(
            new Spec("auth", "test", "Build auth module", SpecStatus.DONE, null, List.of(), null),
            new Spec("tests", "test", "Write tests", SpecStatus.DONE, null, List.of(), null));
    var session = sessionRow(RUN_UNIT.unitName(), "completed", 0, startedAt, null);
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"build auth\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"sail/snap-20260302\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", lastCommit + "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "18\n")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file");

    var config = buildConfig();
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, specs, session, stateDir);

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
    var session = sessionRow(RUN_UNIT.unitName(), "running", null, startedAt, null);
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "9999\n")
            .onOk("kill -0 9999", "")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"fix bug\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", lastCommit + "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "5\n")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file");

    var config = buildConfig();
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, List.of(), session, stateDir);

    assertEquals("Running", report.sessionStatus());
    assertEquals(5, report.commitCount());
    assertTrue(report.lastCommitMinutesAgo() >= 0);
  }

  @Test
  void aForegroundSessionWithABlankUnitStillProbesItsRunScopedPidFile(
      @TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(600).toString();
    var session = sessionRow("", "running", null, startedAt, null);
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "4242\n")
            .onOk("kill -0 4242", "")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"ad-hoc fix\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "0\n")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file");

    var report =
        new AgentReporter(shell).generate(CONTAINER, buildConfig(), List.of(), session, stateDir);

    assertEquals("Running", report.sessionStatus());
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("cat " + RUN_UNIT.pidPath())),
        "a foreground run's blank unit still probes through the run-scoped pid file");
  }

  @Test
  void guardrailTriggeredSession(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(3600 * 5).toString();
    var session = sessionRow(RUN_UNIT.unitName(), "stopped", null, startedAt, null);
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"implement API\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"sail/api\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}")
            .onOk(
                "cat /home/dev/guardrail-triggered.yaml",
                "reason: max_duration\naction: snapshot-and-stop\n")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "0\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "45\n");

    var config = buildConfig();
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, List.of(), session, stateDir);

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
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "0\n");

    var config = buildConfig();
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, List.of(), null, stateDir);

    assertEquals("No session", report.sessionStatus());
    assertFalse(report.guardrailTriggered());
    assertFalse(report.rolledBack());
    assertTrue(report.specs().isEmpty());
    assertTrue(
        shell.invocations().stream().noneMatch(c -> c.contains("agent.pid")),
        "a null session row probes no pid file at all");
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

    var session = sessionRow(RUN_UNIT.unitName(), "stopped", null, startedAt, null);
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"implement auth\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"sail/snap\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "10\n");

    var config = buildConfig();
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, List.of(), session, stateDir);

    assertEquals("Rolled back", report.sessionStatus());
    assertTrue(report.rolledBack());
    assertEquals("pre-agent-20260302", report.rollbackSnapshot());
    assertNotNull(report.endedAt());
  }

  @Test
  void specsWithDependenciesIncluded(@TempDir java.nio.file.Path stateDir) throws Exception {
    var startedAt = Instant.now().minusSeconds(3600).toString();
    var specs =
        List.of(
            new Spec("auth", "test", "Build auth", SpecStatus.DONE, null, List.of(), null),
            new Spec(
                "docs", "test", "Update docs", SpecStatus.PENDING, null, List.of("auth"), null));
    var session = sessionRow(RUN_UNIT.unitName(), "completed", 0, startedAt, null);
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat " + RUN_UNIT.pidPath(), "12345\n")
            .onFail("kill -0 12345", "No such process")
            .onOk(
                "cat " + RUN_UNIT.sessionPath(),
                "{\"task\":\"build auth\",\"started_at\":\""
                    + startedAt
                    + "\",\"branch\":\"\",\"log_path\":\""
                    + RUN_UNIT.logPath()
                    + "\"}")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "8\n")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file");

    var config = buildConfig();
    var reporter = new AgentReporter(shell);
    var report = reporter.generate(CONTAINER, config, specs, session, stateDir);

    assertEquals(2, report.specs().size());
    assertEquals("Build auth", report.specs().getFirst().title());
    assertEquals("Update docs", report.specs().get(1).title());
    assertEquals(List.of("auth"), report.specs().get(1).dependsOn());
  }

  @Test
  void durationUsesTheRealStartAndEndFromTheDatabaseSession(@TempDir java.nio.file.Path stateDir)
      throws Exception {
    var start = Instant.now().minusSeconds(7200);
    var end = Instant.now().minusSeconds(3600);
    var session = sessionRow(RUN_UNIT.unitName(), "completed", 0, start.toString(), end.toString());
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat " + RUN_UNIT.pidPath(), "No such file")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "0\n");

    var report =
        new AgentReporter(shell).generate(CONTAINER, buildConfig(), List.of(), session, stateDir);

    assertEquals(start.toString(), report.startedAt());
    assertEquals(end.toString(), report.endedAt());
    assertTrue(report.duration().startsWith("1h"), "duration must be run-time, not since-dispatch");
  }

  @Test
  void aNonZeroExitIsReportedAsFailed(@TempDir java.nio.file.Path stateDir) throws Exception {
    var start = Instant.now().minusSeconds(120);
    var session =
        sessionRow(RUN_UNIT.unitName(), "stopped", 137, start.toString(), Instant.now().toString());
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat " + RUN_UNIT.pidPath(), "No such file")
            .onFail("cat /home/dev/guardrail-triggered.yaml", "No such file")
            .onOk("git -C /home/dev/workspace log -1 --format=%ct", "\n")
            .onOk("git -C /home/dev/workspace rev-list --count", "0\n");

    var report =
        new AgentReporter(shell).generate(CONTAINER, buildConfig(), List.of(), session, stateDir);

    assertEquals("Failed (exit 137)", report.sessionStatus());
    assertEquals(137, report.exitCode());
    assertEquals(137, report.toMap().get("exit_code"));
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
                    "auth", "acme", "Implement JWT", SpecStatus.DONE, null, List.of(), null)),
            18,
            47,
            false,
            null,
            null,
            false,
            null,
            0);

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
    assertEquals(0, map.get("exit_code"));
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
            null,
            null);

    var map = report.toMap();

    assertEquals(true, map.get("guardrail_triggered"));
    assertEquals("max_duration", map.get("guardrail_reason"));
    assertEquals("snapshot-and-stop", map.get("guardrail_action"));
    assertFalse(map.containsKey("last_commit_minutes_ago"));
  }

  private static SailYaml buildConfig() {
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
        new SailYaml.Agent("claude-code", true, "sail/", true, null, null, null, null, null, null),
        null,
        new SailYaml.Ssh("dev", null));
  }
}
