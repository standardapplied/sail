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
import ai.singlr.sail.engine.AgentSession;
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
  private static final String R2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
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
  void stopCancelsTheSpecBeforeHaltingAndReleasesTheRunAfterTheVerifiedKill() throws Exception {
    var shell = liveAgentShell();
    var order = new ArrayList<String>();
    var ops =
        stopOps(
            shell,
            (project, unit) -> {
              order.add("halt " + unit.unitName());
              order.add("spec " + specStore.findById("auth").orElseThrow().status().wire());
              order.add("run " + runStore.findById(R1).orElseThrow().status());
              agentDies(shell);
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertTrue(stopped.specCancelled());
    assertTrue(stopped.mutated());
    assertEquals(List.of("halt " + UNIT, "spec cancelled", "run stopping"), order);
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void stopPublishesOperatorCancelEventCarryingTheActingFde() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
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
    var shell = liveAgentShell();
    var ops =
        stopOps(
            shell,
            (project, unit) -> {
              halts.add(unit.unitName());
              agentDies(shell);
            },
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
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.REVIEW, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertTrue(assertInstanceOf(StopOperations.Stopped.class, outcome).specCancelled());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRunWithNoRecordedPidIsStillKilledThroughItsRunScopedPidFile() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(null, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertTrue(stopped.specCancelled());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void aForegroundRunWithABlankUnitIsProbedThroughItsRunScopedPidFile() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, null);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void aDeadForegroundRunWithABlankUnitGetsTheStrandedRescue() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, null);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertTrue(notRunning.specCancelled());
    assertTrue(notRunning.runReleased());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void sessionResolutionPrefersTheActiveRunScopedUnit() throws Exception {
    var shell =
        shell()
            .on("cat " + RUN_PID_FILE, "123")
            .on("kill -0 123", "")
            .on(
                "cat /home/dev/.sail/runs/" + R1 + "/agent-session.json",
                "{\"task\":\"run scoped\"}");
    stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedRun(123, UNIT);

    var info = StopOperations.resolveSession(shell, runStore, "acme", LOCAL_HANDLE);

    assertEquals(new AgentSession.SessionInfo(true, 123, "run scoped", "", "", RUN_LOG), info);
  }

  @Test
  void sessionResolutionIsNullWhenNoRunIsActive() throws Exception {
    var shell = shell();
    stopOps(shell, failingHalter(), StopOperations.Listener.NONE);

    assertNull(StopOperations.resolveSession(shell, runStore, "acme", LOCAL_HANDLE));
  }

  @Test
  void sessionResolutionSeesAnAdhocRunLikeAnyOtherSession() throws Exception {
    var shell =
        shell()
            .on("cat " + RUN_PID_FILE, "88")
            .on("kill -0 88", "")
            .on("cat /home/dev/.sail/runs/" + R1 + "/agent-session.json", "{\"task\":\"ad hoc\"}");
    stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedAdhocRun(88, UNIT);

    var info = StopOperations.resolveSession(shell, runStore, "acme", LOCAL_HANDLE);

    assertEquals(new AgentSession.SessionInfo(true, 88, "ad hoc", "", "", RUN_LOG), info);
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
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, "raj");
    seedRun(123, UNIT);

    var assignee = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    var outcome = ops.stop(new StopOperations.RunTarget(R1), assignee, LOCAL_HANDLE, false);

    assertInstanceOf(StopOperations.Stopped.class, outcome);
  }

  @Test
  void aRunWithoutASpecStillStopsButCancelsNothing() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
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
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
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
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.ProjectTarget("acme"), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(R1, stopped.runId());
    assertTrue(stopped.specCancelled());
  }

  @Test
  void projectTargetResolvesTheAdhocRunAndStopsIt() throws Exception {
    var haltedUnits = new ArrayList<String>();
    var shell = liveAgentShell();
    var ops =
        stopOps(
            shell,
            (project, unit) -> {
              haltedUnits.add(unit.unitName());
              agentDies(shell);
            },
            StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.ProjectTarget("acme"), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(R1, stopped.runId());
    assertNull(stopped.specId());
    assertEquals(123, stopped.pid());
    assertFalse(stopped.specCancelled());
    assertEquals(List.of(UNIT), haltedUnits);
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertEquals(1, events.size());
    assertNull(events.getFirst().spec());
    assertEquals(R1, events.getFirst().data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void anInterruptedAdhocStopResumesWithoutAnySpecWrite() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);
    assertTrue(runStore.transition(R1, "running", "stopping"));

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(R1, stopped.runId());
    assertNull(stopped.specId());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void anAdhocHaltThatLeavesTheAgentAliveFailsInsteadOfReportingStopped() throws Exception {
    var halts = new ArrayList<String>();
    var ops =
        stopOps(
            liveAgentShell(),
            (project, unit) -> halts.add(unit.unitName()),
            StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.ProjectTarget("acme"), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertEquals(1, halts.size());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aMemberMayStopTheAdhocRunTheirOwnBoxLaunched() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var owner = new Actor(LOCAL_HANDLE, Role.MEMBER, Actor.Lane.API);
    var outcome = ops.stop(new StopOperations.RunTarget(R1), owner, LOCAL_HANDLE, false);

    assertInstanceOf(StopOperations.Stopped.class, outcome);
  }

  @Test
  void aMemberWhoDidNotLaunchTheAdhocRunIsRefused() throws Exception {
    var ops = stopOps(liveAgentShell(), failingHalter(), StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var other = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), other, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refusal.failure().errorCode());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void aDeadRunReportsTheStrandedRescueFromTheProjectTarget() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.ProjectTarget("acme"), ADMIN, LOCAL_HANDLE, false);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertEquals(R1, notRunning.runId());
    assertTrue(notRunning.specCancelled());
    assertTrue(notRunning.runReleased());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
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
  void aKillFailureRestoresTheSpecAndLeavesTheRunReconcilable() throws Exception {
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
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aHaltThatLeavesTheAgentAliveRestoresTheSpecAndFails() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> {}, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aReplacementPidAfterTheHaltFailsTheStopAndRestoresEverything() throws Exception {
    var shell = liveAgentShell();
    var ops =
        stopOps(
            shell,
            (project, unit) -> {
              shell.on("cat " + RUN_PID_FILE, "456");
              shell.on("kill -0 456", "");
              shell.on("kill -0 123", new ShellExec.Result(1, "", ""));
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aRunCompletedBetweenProbeAndClaimRefusesWithAConflict() throws Exception {
    var listener =
        new StopOperations.Listener() {
          @Override
          public void halting(String project, String unit, Integer pid) {
            runStore.complete(R1, "completed", 0);
          }
        };
    var ops = stopOps(liveAgentShell(), failingHalter(), listener);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("completed", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aSpecTransitionBetweenProbeAndClaimRollsTheWholeClaimBack() throws Exception {
    var listener =
        new StopOperations.Listener() {
          @Override
          public void halting(String project, String unit, Integer pid) {
            specStore.updateStatus("auth", SpecStatus.REVIEW);
          }
        };
    var ops = stopOps(liveAgentShell(), failingHalter(), listener);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aStopRetryFinalizesAnInterruptedClaimWhoseAgentDied() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat " + RUN_PID_FILE, "123")
            .on("kill -0 123", new ShellExec.Result(1, "", ""));
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    interruptStop();

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertEquals(R1, notRunning.runId());
    assertTrue(notRunning.runReleased());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals(1, events.size());
    assertEquals(Event.WellKnownTypes.AGENT_CANCELLED, events.getFirst().type());
  }

  @Test
  void aStopRetryKillsALiveAgentStillUnderAnInterruptedClaim() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    interruptStop();

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals(1, events.size());
  }

  @Test
  void aDryRunOverAnInterruptedClaimProbesButWritesNothing() throws Exception {
    var ops = stopOps(liveAgentShell(), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    interruptStop();

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, true);

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertEquals("stopping", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aFinishThatLostTheClaimToAConcurrentFinalizerPublishesNoSecondEvent() throws Exception {
    var shell = liveAgentShell();
    var ops =
        stopOps(
            shell,
            (project, unit) -> {
              agentDies(shell);
              runStore.transition(R1, "stopping", "stopped");
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void stoppingAnOlderTerminalRunCannotCancelANewerActiveRun() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.complete(R1, "completed", 0);
    seedNewerRun();

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var terminal = assertInstanceOf(StopOperations.AlreadyTerminal.class, outcome);
    assertEquals(R1, terminal.runId());
    assertFalse(terminal.mutated());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R2).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aDryRunOverAnOlderTerminalRunPreviewsAlreadyTerminal() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.complete(R1, "completed", 0);
    seedNewerRun();

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, true);

    assertInstanceOf(StopOperations.AlreadyTerminal.class, outcome);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void stoppingAnOlderLiveRunHaltsItWithoutCancellingTheNewerAttempt() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    seedNewerRun();

    var preview = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, true);
    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertFalse(assertInstanceOf(StopOperations.Stopped.class, preview).specCancelled());
    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertFalse(stopped.specCancelled());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertEquals("running", runStore.findById(R2).orElseThrow().status());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void anOlderDeadRunIsReleasedWithoutCancellingTheNewerAttempt() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    seedNewerRun();

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertFalse(notRunning.specCancelled());
    assertTrue(notRunning.runReleased());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertEquals("running", runStore.findById(R2).orElseThrow().status());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aWatcherCompletionDuringTheDeadRunRescueIsNeverOverwritten() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .hookOn("cat " + RUN_PID_FILE, () -> runStore.complete(R1, "completed", 0));
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals("completed", runStore.findById(R1).orElseThrow().status());
    assertEquals(0, runStore.findById(R1).orElseThrow().exitCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void outcomeReasonsShareOneWireVocabulary() {
    assertNull(new StopOperations.Stopped(R1, "auth", 1, true).reason());
    assertEquals(
        "no_agent_running", new StopOperations.NotRunning(R1, "auth", false, true).reason());
    assertEquals(
        "run_not_running", new StopOperations.NotRunning(R1, "auth", false, false).reason());
    assertEquals(
        "run_not_running", new StopOperations.AlreadyTerminal(R1, "auth", "stopped").reason());
  }

  @Test
  void aNewerReviewRowDoesNotBlockTheOperatorCancel() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.REVIEW, LOCAL_HANDLE);
    seedRun(123, UNIT);
    seedReviewRun();

    var outcome = ops.stop(new StopOperations.RunTarget(R1), ADMIN, LOCAL_HANDLE, false);

    assertTrue(assertInstanceOf(StopOperations.Stopped.class, outcome).specCancelled());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void stoppingAReviewRunIsRefusedWithInvalidRole() throws Exception {
    var ops = stopOps(liveAgentShell(), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.REVIEW, LOCAL_HANDLE);
    seedRun(123, UNIT);
    seedReviewRun();

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.stop(new StopOperations.RunTarget(R2), ADMIN, LOCAL_HANDLE, false));

    assertEquals(ErrorCode.INVALID_ROLE, refusal.failure().errorCode());
    assertEquals("running", runStore.findById(R2).orElseThrow().status());
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertTrue(events.isEmpty());
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
            .on("cat " + RUN_PID_FILE, "77")
            .on("kill 77", "")
            .on("sleep 3", "")
            .on("kill -0 77", new ShellExec.Result(1, "", ""))
            .on("rm -f " + RUN_PID_FILE, "");

    StopOperations.sessionHalter(shell).halt("acme", AgentUnit.forRun(R1));

    assertTrue(shell.invocations().stream().anyMatch(cmd -> cmd.contains("kill 77")));
  }

  private StopOperations.AgentHalter killingHalter(FakeShell shell) {
    return (project, unit) -> agentDies(shell);
  }

  private static void agentDies(FakeShell shell) {
    shell.on("kill -0 123", new ShellExec.Result(1, "", ""));
  }

  private void interruptStop() {
    assertTrue(
        runStore.transition(
            R1,
            "running",
            "stopping",
            () ->
                specStore.compareAndSetStatus(
                    "auth", SpecStatus.IN_PROGRESS, SpecStatus.CANCELLED)),
        "seeding the claim an interrupted stop leaves behind");
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
    return stopOps(shell, halter, listener, events::add);
  }

  private StopOperations stopOps(
      FakeShell shell,
      StopOperations.AgentHalter halter,
      StopOperations.Listener listener,
      DispatchOperations.EventSink sink)
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
        """);
    var db = Sqlite.open(tempDir.resolve("stop-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    runStore = new RunStore(db);
    return new StopOperations(shell, yaml.toString(), specStore, runStore, sink, halter, listener);
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

  private void seedAdhocRun(Integer pid, String unit) {
    runStore.reserveDispatch(
        R1, "acme", "", LOCAL_HANDLE, "adhoc", List.of(), "codex", null, "do it", RUN_LOG, unit);
    if (pid != null) {
      runStore.updateProcess(R1, pid, null);
    }
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

  private void seedReviewRun() {
    runStore.createReview(
        R2,
        "acme",
        "auth",
        LOCAL_HANDLE,
        "codex",
        "feat/auth",
        "review",
        RUN_LOG,
        "sail-review-" + R2);
  }

  private void seedNewerRun() {
    runStore.create(
        R2,
        "acme",
        "auth",
        LOCAL_HANDLE,
        "build",
        "codex",
        "feat/auth",
        "do it",
        456,
        null,
        RUN_LOG,
        "sail-agent-" + R2);
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
    private final Map<String, Runnable> hooks = new LinkedHashMap<>();
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

    FakeShell hookOn(String pattern, Runnable action) {
      hooks.put(pattern, action);
      return this;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      var joined = String.join(" ", command);
      invocations.add(joined);
      for (var entry : hooks.entrySet()) {
        if (joined.contains(entry.getKey())) {
          entry.getValue().run();
        }
      }
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
