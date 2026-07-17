/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StopOperationsTest {

  private static final String R1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String UNIT = "sail-agent-" + R1;
  private static final String RUN_LOG = "/home/dev/.sail/runs/" + R1 + "/agent.log";
  private static final String RUN_PID_FILE = "/home/dev/.sail/runs/" + R1 + "/agent.pid";
  private static final String LOCAL_HANDLE = "me";
  private static final Actor ADMIN = new Actor(LOCAL_HANDLE, Role.ADMIN, Actor.Lane.API);

  private static final String RUNNING_JSON =
      """
      [{"name": "acme", "status": "Running", "state": {}}]
      """;

  @TempDir Path tempDir;

  private SpecStore specStore;
  private RunStore runStore;
  private final List<Event> events = new ArrayList<>();

  @Test
  void stopRecordsTerminalIntentBeforeHalting() throws Exception {
    var shell = liveAgentShell();
    var order = new ArrayList<String>();
    var ops =
        stopOps(
            shell,
            (project, unit) -> {
              order.add("halt " + unit.unitName());
              order.add("spec " + specStore.findById("auth").orElseThrow().status().wire());
              order.add("run " + runStore.findById(R1).orElseThrow().status());
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertTrue(stopped.specCancelled());
    assertTrue(stopped.mutated());
    assertEquals(List.of("halt " + UNIT, "spec cancelled", "run stopped"), order);
  }

  @Test
  void stopPublishesOperatorCancelEventCarryingTheActingFde() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> {}, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertEquals(1, events.size());
    var event = events.getFirst();
    assertEquals(Event.WellKnownTypes.AGENT_CANCELLED, event.type());
    assertEquals("auth", event.spec());
    assertEquals(LOCAL_HANDLE, event.agent());
    assertEquals(Event.WellKnownData.SOURCE_OPERATOR, event.data().get(Event.WellKnownData.SOURCE));
    assertEquals(R1, event.data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void aSecondStopIsAlreadyTerminalAndSignalsNothing() throws Exception {
    var halts = new ArrayList<String>();
    var ops =
        stopOps(
            liveAgentShell(),
            (project, unit) -> halts.add(unit.unitName()),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);
    var second = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var terminal = assertInstanceOf(StopOperations.AlreadyTerminal.class, second);
    assertEquals(R1, terminal.runId());
    assertEquals("stopped", terminal.runStatus());
    assertEquals("auth", terminal.specId());
    assertFalse(terminal.mutated());
    assertEquals(1, halts.size());
    assertEquals(1, events.size());
  }

  @Test
  void aDeadAgentStillGetsItsIntentRecorded() throws Exception {
    var halts = new ArrayList<String>();
    var shell = shell().on("incus list ^acme$", RUNNING_JSON);
    var ops =
        stopOps(shell, (project, unit) -> halts.add(unit.unitName()), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertEquals(R1, notRunning.runId());
    assertEquals("auth", notRunning.specId());
    assertTrue(notRunning.specCancelled());
    assertTrue(notRunning.runReleased());
    assertTrue(notRunning.mutated());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertTrue(halts.isEmpty());
    assertEquals(1, events.size());
  }

  @Test
  void aTerminalRunWithAnActiveSpecCancelsTheStrandedSpec() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.complete(R1, "completed", 0);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertTrue(notRunning.specCancelled());
    assertFalse(notRunning.runReleased());
    assertTrue(notRunning.mutated());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals("completed", runStore.findById(R1).orElseThrow().status());
    assertEquals(1, events.size());
  }

  @Test
  void aReviewSpecIsCancelledToo() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> {}, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.REVIEW, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertTrue(assertInstanceOf(StopOperations.Stopped.class, outcome).specCancelled());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aPidMismatchKillsNothingAndWritesNothing() throws Exception {
    var halts = new ArrayList<String>();
    var ops =
        stopOps(
            liveAgentShell(999),
            (project, unit) -> halts.add(unit.unitName()),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var notActive = assertInstanceOf(StopOperations.NotActive.class, outcome);
    assertEquals(999, notActive.livePid());
    assertEquals(R1, notActive.runId());
    assertEquals("auth", notActive.specId());
    assertFalse(notActive.mutated());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(halts.isEmpty());
    assertTrue(events.isEmpty());
  }

  @Test
  void aRunWithNoRecordedPidIsNeverKilled() throws Exception {
    var ops = stopOps(liveAgentShell(), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(null, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertInstanceOf(StopOperations.NotActive.class, outcome);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRunWithABlankUnitFallsBackToTheAdHocIdentity() throws Exception {
    var haltedUnits = new ArrayList<String>();
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/agent.pid", "123")
            .on("kill -0 123", "")
            .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}");
    var ops =
        stopOps(
            shell,
            (project, unit) -> haltedUnits.add(unit.unitName()),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, null);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(List.of(AgentUnit.BUILD.unitName()), haltedUnits);
  }

  @Test
  void anUnknownRunIsRefusedWithRunNotFound() throws Exception {
    var ops = stopOps(shell(), failingHalter(), StopOperations.Listener.NONE);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.RUN_NOT_FOUND, refusal.failure().errorCode());
  }

  @Test
  void aForeignRunIsRefusedWithRunOnOtherNode() throws Exception {
    var ops = stopOps(shell(), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, "sumesh", false));

    assertEquals(ErrorCode.RUN_ON_OTHER_NODE, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aBlankNodeRunFailsClosedToForeignForAHandledBox() throws Exception {
    var ops = stopOps(shell(), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    runStore.create(
        R1, "acme", "auth", null, "build", "codex", "feat/auth", "do it", 123, null, RUN_LOG, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.RUN_ON_OTHER_NODE, refusal.failure().errorCode());
  }

  @Test
  void aMemberWhoIsNotTheAssigneeIsRefused() throws Exception {
    var ops = stopOps(liveAgentShell(), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var other = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), other, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void theAssigneeMemberMayStopTheirOwnRun() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> {}, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, "raj");
    seedRun(123, UNIT);

    var assignee = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    var outcome = ops.stop(new StopOperations.RunTarget(R1), assignee, LOCAL_HANDLE, false);

    assertInstanceOf(StopOperations.Stopped.class, outcome);
  }

  @Test
  void aRunWithoutASpecStillStopsButCancelsNothing() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> {}, StopOperations.Listener.NONE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertFalse(stopped.specCancelled());
    assertEquals("auth", stopped.specId());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertEquals(1, events.size());
  }

  @Test
  void aRunWithNoSpecIdAtAllStillStops() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> {}, StopOperations.Listener.NONE);
    runStore.create(
        R1,
        "acme",
        null,
        LOCAL_HANDLE,
        "build",
        "codex",
        "feat/x",
        "do it",
        123,
        null,
        RUN_LOG,
        UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertFalse(stopped.specCancelled());
    assertNull(stopped.specId());
    assertEquals(1, events.size());
    assertNull(events.getFirst().spec());
  }

  @Test
  void projectTargetResolvesTheActiveRunAndStopsIt() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> {}, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.ProjectTarget("acme"), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(R1, stopped.runId());
    assertTrue(stopped.specCancelled());
  }

  @Test
  void projectTargetWithoutARunStopsTheAdHocSession() throws Exception {
    var haltedUnits = new ArrayList<String>();
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/agent.pid", "55")
            .on("kill -0 55", "")
            .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"ad hoc\"}");
    var ops =
        stopOps(
            shell,
            (project, unit) -> haltedUnits.add(unit.unitName()),
            StopOperations.Listener.NONE);

    var outcome = ops.stop(new StopOperations.ProjectTarget("acme"), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(55, stopped.pid());
    assertNull(stopped.runId());
    assertFalse(stopped.specCancelled());
    assertEquals(List.of(AgentUnit.BUILD.unitName()), haltedUnits);
    assertTrue(events.isEmpty());
  }

  @Test
  void projectTargetWithNothingRunningIsAQuietNoOp() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);

    var outcome = ops.stop(new StopOperations.ProjectTarget("acme"), ADMIN, LOCAL_HANDLE, false);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertFalse(notRunning.specCancelled());
    assertFalse(notRunning.runReleased());
    assertFalse(notRunning.mutated());
  }

  @Test
  void aDryRunResolvesAndProbesButWritesAndSignalsNothing() throws Exception {
    var halted = new ArrayList<String>();
    var announced = new ArrayList<String>();
    var listener =
        new StopOperations.Listener() {
          @Override
          public void halting(String project, String unit, Integer pid) {
            announced.add(project + "/" + unit + "/" + pid);
          }
        };
    var ops = stopOps(liveAgentShell(), (project, unit) -> halted.add(unit.unitName()), listener);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, true);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertTrue(stopped.specCancelled());
    assertEquals(List.of("acme/" + UNIT + "/123"), announced);
    assertTrue(halted.isEmpty());
    assertTrue(events.isEmpty());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void aDryRunOverADeadAgentReportsTheRescueWithoutWriting() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, true);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertTrue(notRunning.specCancelled());
    assertTrue(notRunning.runReleased());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aTerminalRunWithATerminalSpecIsUntouchedOnADryRun() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.complete(R1, "completed", 0);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, true);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertTrue(notRunning.specCancelled());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aKillFailureMapsToAgentStopFailedWithTheIntentAlreadyRecorded() throws Exception {
    var ops =
        stopOps(
            liveAgentShell(),
            (project, unit) -> {
              throw new IOException("permission denied");
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void aProbeFailureMapsToAgentStatusFailed() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .throwOn("cat " + RUN_PID_FILE, new IOException("container unreachable"));
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.AGENT_STATUS_FAILED, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void sessionHalterKillsThroughTheAgentSession() throws Exception {
    var shell =
        shell()
            .on("cat /home/dev/.sail/agent.pid", "77")
            .on("kill 77", "")
            .on("sleep 3", "")
            .on("kill -0 77", new ShellExec.Result(1, "", ""))
            .on("rm -f /home/dev/.sail/agent.pid", "");

    StopOperations.sessionHalter(shell).halt("acme", AgentUnit.BUILD);

    assertTrue(shell.invocations().stream().anyMatch(cmd -> cmd.contains("kill 77")));
  }

  private FakeShell liveAgentShell() {
    return liveAgentShell(123);
  }

  private FakeShell liveAgentShell(int pid) {
    return shell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("cat " + RUN_PID_FILE, String.valueOf(pid))
        .on("kill -0 " + pid, "")
        .on("cat /home/dev/.sail/runs/" + R1 + "/agent-session.json", "{\"task\": \"work\"}");
  }

  private StopOperations stopOps(
      FakeShell shell, StopOperations.AgentHalter halter, StopOperations.Listener listener)
      throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(
        yaml,
        """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
        """);
    var db = Sqlite.open(tempDir.resolve("stop-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    runStore = new RunStore(db);
    return new StopOperations(
        shell, yaml.toString(), specStore, runStore, events::add, halter, listener);
  }

  private void seedSpec(String id, SpecStatus status, String assignee) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "Spec " + id,
            status,
            assignee,
            "codex",
            null,
            null,
            null,
            0,
            "me",
            null,
            null,
            "me",
            List.of(),
            List.of()));
  }

  private void seedRun(Integer pid, String unit) {
    runStore.create(
        R1,
        "acme",
        "auth",
        LOCAL_HANDLE,
        "build",
        "codex",
        "feat/auth",
        "do it",
        pid,
        null,
        RUN_LOG,
        unit);
  }

  private StopOperations.AgentHalter failingHalter() {
    return (project, unit) -> {
      throw new AssertionError("the halter must not be invoked");
    };
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
