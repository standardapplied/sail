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

import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ReviewRuns;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@ActingAs
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

  private static final AgentSession.Halt ENDED = new AgentSession.Halt.Ended();
  private static final AgentSession.Halt SURVIVED = new AgentSession.Halt.Survived();

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
              return agentDies(shell);
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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
              return agentDies(shell);
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));
    var second =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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
    var ops = stopOps(shell, recordingHalter(halts, SURVIVED), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertEquals(R1, notRunning.runId());
    assertEquals("auth", notRunning.specId());
    assertTrue(notRunning.specCancelled());
    assertTrue(notRunning.runReleased());
    assertTrue(notRunning.mutated());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertTrue(
        runStore.findById(R1).orElseThrow().stoppedByOperator(),
        "the dead run's own stop may still be on its way: it must read as the operator's");
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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertTrue(assertInstanceOf(StopOperations.Stopped.class, outcome).specCancelled());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aStalePidFileNamingAReusedPidRefusesWithoutSignalling() throws Exception {
    var ops = stopOps(liveAgentShell(999), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void anUnreadableFingerprintOnALivePidRefusesWithoutSignalling() throws Exception {
    var shell = liveAgentShell().throwOn("cat /proc/123/stat", new IOException("exec timed out"));
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.updateProcess(R1, 123, 555L, null);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aReusedPidWithAMismatchedFingerprintRefusesWithoutSignalling() throws Exception {
    var shell = liveAgentShell().on("cat /proc/123/stat", statLine(999));
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.updateProcess(R1, 123, 555L, null);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void anUnreadableLiveFingerprintRefusesRatherThanKillOnFaith() throws Exception {
    var ops = stopOps(liveAgentShell(), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.updateProcess(R1, 123, 555L, null);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void aMatchingFingerprintProvesOwnershipAndTheStopProceeds() throws Exception {
    var shell = liveAgentShell().on("cat /proc/123/stat", statLine(555));
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.updateProcess(R1, 123, 555L, null);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(123, stopped.pid());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  private static String statLine(long startTicks) {
    return "123 (bash) S 1 123 123 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0 " + startTicks + " 0 0";
  }

  @Test
  void aResumedStopAppliesTheSamePidOwnershipGuard() throws Exception {
    var ops = stopOps(liveAgentShell(999), failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    interruptStop();

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals("stopping", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aRunWithNoRecordedPidIsStillKilledThroughItsRunScopedPidFile() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(null, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

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
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), "sumesh", false)));

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
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.RUN_ON_OTHER_NODE, refusal.failure().errorCode());
  }

  @Test
  void aRunStampedBeforeTheBoxHadAHandleStopsOnceTheHandleChangeStampsIt() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, (project, unit) -> agentDies(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    Acting.system(
        () ->
            runStore.create(
                R1,
                "acme",
                "auth",
                null,
                "build",
                "codex",
                "feat/auth",
                "do it",
                123,
                null,
                RUN_LOG,
                UNIT));
    runStore.stamp(LOCAL_HANDLE, runStore.unacknowledged());

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
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
            () ->
                Actor.call(
                    other, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

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
    var outcome =
        Actor.call(assignee, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(StopOperations.Stopped.class, outcome);
  }

  @Test
  void theCreatorOfAnUnassignedSpecMayStopItsRunAndAnotherMemberMayNot() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, null);
    seedRun(123, UNIT);

    var other = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    other, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refusal.failure().errorCode());
    assertTrue(refusal.getMessage().contains("owned by 'me'"), refusal.getMessage());

    var creator = new Actor("me", Role.MEMBER, Actor.Lane.API);
    var outcome =
        Actor.call(creator, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(StopOperations.Stopped.class, outcome);
  }

  @Test
  void theFdeWhoseBoxRunsAnAgentMayStopItAfterItsSpecMoved() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, "raj");
    seedRun(123, UNIT);
    var member = new Actor(LOCAL_HANDLE, Role.MEMBER, Actor.Lane.CLI);

    var outcome =
        Actor.call(member, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertFalse(stopped.specCancelled(), "the spec is its new owner's to change");
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void aTerminalRunOfASpecThatMovedLeavesTheSpecToItsNewOwner() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, "raj");
    seedRun(123, UNIT);
    runStore.complete(R1, "completed", 0);
    var member = new Actor(LOCAL_HANDLE, Role.MEMBER, Actor.Lane.CLI);

    var outcome =
        Actor.call(member, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(StopOperations.AlreadyTerminal.class, outcome);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRunWithoutASpecStillStopsButCancelsNothing() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedRun(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(
            ADMIN, () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, false));

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
              return agentDies(shell);
            },
            StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var outcome =
        Actor.call(
            ADMIN, () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertEquals(R1, stopped.runId());
    assertNull(stopped.specId());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void anAdhocHaltThatLeavesTheAgentAliveFailsInsteadOfReportingStopped() throws Exception {
    var halts = new ArrayList<String>();
    var ops =
        stopOps(liveAgentShell(), recordingHalter(halts, SURVIVED), StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN,
                    () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertEquals(1, halts.size());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aStopDuringLaunchPreparationRecordsTheCancelAndTheLauncherMustYield() throws Exception {
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedAdhocRun(null, UNIT);

    var outcome =
        Actor.call(
            ADMIN, () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, false));

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertTrue(notRunning.runReleased());
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertFalse(
        runStore.updateProcess(R1, 999, 1L, null),
        "a launch that lost to the cancel must be refused its process stamp");

    var second =
        Actor.call(
            ADMIN, () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, false));
    assertFalse(second.mutated(), "a repeated stop after the prep-window cancel writes nothing");
  }

  @Test
  void aMemberMayStopTheAdhocRunTheirOwnBoxLaunched() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var owner = new Actor(LOCAL_HANDLE, Role.MEMBER, Actor.Lane.API);
    var outcome =
        Actor.call(owner, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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
            () ->
                Actor.call(
                    other, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

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

    var outcome =
        Actor.call(
            ADMIN, () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(
            ADMIN, () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, false));

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertFalse(notRunning.specCancelled());
    assertFalse(notRunning.runReleased());
    assertFalse(notRunning.mutated());
  }

  @Test
  void aDryRunOverALiveAdhocRunReportsANullSpecLikeTheRealStop() throws Exception {
    var ops = stopOps(liveAgentShell(), failingHalter(), StopOperations.Listener.NONE);
    seedAdhocRun(123, UNIT);

    var outcome =
        Actor.call(
            ADMIN, () -> ops.stop(new StopOperations.ProjectTarget("acme"), LOCAL_HANDLE, true));

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertNull(stopped.specId(), "an ad-hoc dry run must normalize the blank spec id");
    assertFalse(stopped.specCancelled());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
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
    var ops = stopOps(liveAgentShell(), recordingHalter(halted, SURVIVED), listener);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, true));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, true));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, true));

    var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertTrue(notRunning.specCancelled());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aHaltTheAgentSurvivedRestoresTheSpecAndLeavesTheRunReconcilable() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> SURVIVED, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertTrue(refusal.getMessage().contains("PID 123 is still running"), refusal.getMessage());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertFalse(
        runStore.findById(R1).orElseThrow().stoppedByOperator(),
        "the stop did not happen: the run's own end is again its own");
    assertTrue(events.isEmpty());
  }

  @Test
  void anOperatorsStopTheManagerDoesNotAnswerFailsAndKeepsTheClaimForARetryToFinish()
      throws Exception {
    var shell =
        liveAgentShell()
            .on("--signal=SIGTERM", "")
            .on("sleep 3", "")
            .on("systemctl --user show", new ShellExec.Result(1, "", "Failed to connect to bus"));
    var ops = stopOps(shell, StopOperations.sessionHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertTrue(refusal.getMessage().contains("did not answer"), refusal.getMessage());
    var claimed = runStore.findById(R1).orElseThrow();
    assertEquals(
        StopOperations.STOPPING,
        claimed.status(),
        "an unanswered question is never an answer: nothing is finalized, and nothing given back");
    assertTrue(claimed.stoppedByOperator());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertTrue(events.isEmpty(), "the cancel is announced once the stop is finalized");
    assertTrue(
        shell.invocations().stream().noneMatch(cmd -> cmd.contains("rm -f " + RUN_PID_FILE)),
        "the pid file is what still says the agent may be alive");

    shell.on("systemctl --user show", "ActiveState=inactive\nExecMainStatus=0\n");
    var retried =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(StopOperations.Stopped.class, retried);
    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertEquals(
        List.of(Event.WellKnownTypes.AGENT_CANCELLED), events.stream().map(Event::type).toList());
  }

  @Test
  void aHaltThatThrewKeepsTheClaimRatherThanHandTheRunBack() throws Exception {
    var ops =
        stopOps(
            liveAgentShell(),
            (project, unit) -> {
              throw new IOException("incus exec: websocket closed after the signal landed");
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    var claimed = runStore.findById(R1).orElseThrow();
    assertEquals(
        StopOperations.STOPPING,
        claimed.status(),
        "a signal may have landed before the halt failed: a run given back now would have its"
            + " agent's death read as the run ending on its own");
    assertTrue(claimed.stoppedByOperator());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertTrue(events.isEmpty(), "the cancel is announced once the stop is finalized");
  }

  @Test
  void aResumedStopTheAgentSurvivesFailsAndKeepsTheClaim() throws Exception {
    var ops = stopOps(liveAgentShell(), (project, unit) -> SURVIVED, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    interruptStop();

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refusal.failure().errorCode());
    assertEquals(
        StopOperations.STOPPING,
        runStore.findById(R1).orElseThrow().status(),
        "the operator's intent still stands: a resumed stop never gives the run back");
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
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

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
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.CONFLICT, refusal.failure().errorCode());
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
    assertTrue(events.isEmpty());
  }

  @Test
  void aStopWhoseContainerGivesNoAnswerAboutTheAgentFailsWithNothingWritten() throws Exception {
    var halts = new ArrayList<String>();
    var shell = silentShell().on("incus list ^acme$", RUNNING_JSON);
    var ops = stopOps(shell, recordingHalter(halts, ENDED), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STATUS_FAILED, refusal.failure().errorCode());
    assertEquals(
        "running",
        runStore.findById(R1).orElseThrow().status(),
        "incus lists the container running and it ran no command: a live agent and a dead one"
            + " read alike, so the run is not recorded as either");
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(events.isEmpty(), "no cancel is published for a run nobody could ask about");
    assertTrue(halts.isEmpty());
  }

  @Test
  void aRetriedStopWhoseContainerStillGivesNoAnswerKeepsItsClaim() throws Exception {
    var shell = silentShell().on("incus list ^acme$", RUNNING_JSON);
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    interruptStop();

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STATUS_FAILED, refusal.failure().errorCode());
    assertEquals(
        StopOperations.STOPPING,
        runStore.findById(R1).orElseThrow().status(),
        "the claim waits for a container that answers; it is not finalized over a silent one");
    assertTrue(events.isEmpty());
  }

  @Test
  void oneLostLivenessCommandNeverRecordsALiveAgentAsGone() throws Exception {
    var halts = new ArrayList<String>();
    var shell = liveAgentShell();
    shell
        .on("kill -0 123", new ShellExec.Result(1, "", "websocket: close 1006"))
        .on("printf gone", "alive")
        .hookOn("printf gone", () -> shell.on("kill -0 123", ""));
    var ops = stopOps(shell, recordingHalter(halts, ENDED), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(
        StopOperations.Stopped.class,
        outcome,
        "the agent was asked after again once the container answered, found alive, and halted");
    assertEquals(1, halts.size());
  }

  @Test
  void anAgentInAStoppedContainerIsRecordedGone() throws Exception {
    var shell =
        silentShell()
            .on(
                "incus list ^acme$",
                """
                [{"name": "acme", "status": "Stopped", "state": {}}]
                """);
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertEquals(
        "stopped",
        runStore.findById(R1).orElseThrow().status(),
        "a stopped container runs no agent: that is incus's answer, and it is one");
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, true));

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
              runStore.transition(R1, "stopping", "stopped");
              return agentDies(shell);
            },
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, true));

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

    var preview =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, true));
    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

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
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

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
    var ops =
        stopOps(
            shell().on("incus list ^acme$", RUNNING_JSON),
            failingHalter(),
            StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.REVIEW, LOCAL_HANDLE);
    seedRun(123, UNIT);
    runStore.complete(R1, "completed", 0);
    seedReviewRun();

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertTrue(
        assertInstanceOf(StopOperations.NotRunning.class, outcome).specCancelled(),
        "the reviewer that came after is not a newer attempt: the build is still the spec's to"
            + " cancel through");
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
    assertEquals("completed", runStore.findById(R1).orElseThrow().status());
  }

  @Test
  void stoppingAReviewerRunHaltsItLikeAnySessionAndLeavesItsSpecToTheLoop() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/runs/" + R2 + "/agent.pid", "456")
            .on("kill -0 456", "")
            .on("cat /home/dev/.sail/runs/" + R2 + "/agent-session.json", "{\"task\": \"review\"}");
    var ops = stopOps(shell, (project, unit) -> ENDED, StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.REVIEW, LOCAL_HANDLE);
    seedReviewRun();

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R2), LOCAL_HANDLE, false));

    var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
    assertFalse(
        stopped.specCancelled(),
        "a reviewer is not its spec's build attempt: stopping it cancels no spec, and the review"
            + " loop escalates the review it served");
    assertEquals("stopped", runStore.findById(R2).orElseThrow().status());
    assertTrue(
        runStore.findById(R2).orElseThrow().stoppedByOperator(),
        "the mark outlives the finalized claim, so the watcher's stop of the halted unit never"
            + " reads as the reviewer's own end");
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertEquals(
        List.of(Event.WellKnownTypes.AGENT_CANCELLED), events.stream().map(Event::type).toList());
    assertEquals(R2, events.getFirst().data().get(Event.WellKnownData.RUN_ID));
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
            () ->
                Actor.call(
                    ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STATUS_FAILED, refusal.failure().errorCode());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void sessionHalterKillsTheWholeUnitCgroupThroughTheAgentSession() throws Exception {
    var shell = shell().on("systemctl", "").on("sleep 3", "").on("rm -f " + RUN_PID_FILE, "");

    StopOperations.sessionHalter(shell).halt("acme", AgentUnit.forRun(R1));

    var service = AgentUnit.forRun(R1).service();
    assertTrue(
        shell.invocations().stream()
            .anyMatch(cmd -> cmd.contains("--kill-who=all --signal=SIGTERM " + service)),
        "a unit-owning run is halted through its cgroup, never a bare pid");
    assertTrue(shell.invocations().stream().noneMatch(cmd -> cmd.contains("kill 77")));
  }

  private StopOperations.AgentHalter killingHalter(FakeShell shell) {
    return (project, unit) -> agentDies(shell);
  }

  private static AgentSession.Halt agentDies(FakeShell shell) {
    shell.on("kill -0 123", new ShellExec.Result(1, "", ""));
    return ENDED;
  }

  private static StopOperations.AgentHalter recordingHalter(
      List<String> halts, AgentSession.Halt answer) {
    return (project, unit) -> {
      halts.add(unit.unitName());
      return answer;
    };
  }

  private void interruptStop() {
    assertTrue(
        runStore.claimStop(
            R1,
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
    Acting.as(
        "me",
        () ->
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
                    List.of())));
  }

  private void seedAdhocRun(Integer pid, String unit) {
    Acting.system(
        () -> {
          runStore.reserveDispatch(
              R1,
              "acme",
              "",
              LOCAL_HANDLE,
              "adhoc",
              List.of(),
              "codex",
              null,
              "do it",
              RUN_LOG,
              unit);
          if (pid != null) {
            runStore.updateProcess(R1, pid, null, null);
          }
        });
  }

  private void seedRun(Integer pid, String unit) {
    Acting.system(
        () -> {
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
        });
  }

  private void seedReviewRun() {
    ReviewRuns.reserve(
        runStore, R2, R2, "acme", "auth", LOCAL_HANDLE, Lane.REVIEW, "codex", List.of());
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

  /**
   * A container that answers: it runs a command, and its unit manager says the run's unit has no
   * process unless a test says otherwise.
   */
  private static FakeShell shell() {
    return new FakeShell(true);
  }

  /** A container no command runs in, whatever incus lists it as. */
  private static FakeShell silentShell() {
    return new FakeShell(false);
  }

  private static final class FakeShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final Map<String, Exception> failures = new LinkedHashMap<>();
    private final Map<String, Runnable> hooks = new LinkedHashMap<>();
    private final List<String> invocations = new ArrayList<>();
    private final boolean reachable;

    FakeShell(boolean reachable) {
      this.reachable = reachable;
    }

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
      return Optional.of(joined)
          .filter(asked -> reachable)
          .flatMap(ScriptedShellExecutor::reachableContainer)
          .orElseGet(() -> new Result(1, "", "no script for " + joined));
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

  private String seedRunWithCredential(Integer pid, String unit) {
    return Acting.system(
        () -> {
          var reservation =
              (RunStore.Reservation.Reserved)
                  runStore.reserveDispatch(
                      R1,
                      "acme",
                      "auth",
                      LOCAL_HANDLE,
                      "build",
                      List.of(),
                      "codex",
                      "feat/auth",
                      "do it",
                      RUN_LOG,
                      unit);
          if (pid != null) {
            runStore.updateProcess(R1, pid, null, null);
          }
          return reservation.credential();
        });
  }

  @Test
  void aVerifiedStopRevokesTheRunCredential() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    var credential = seedRunWithCredential(123, UNIT);

    Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertEquals("stopped", runStore.findById(R1).orElseThrow().status());
    assertTrue(
        runStore.findByCredential(credential).isEmpty(),
        "the operator cancel's verified finish revokes the run credential");
  }

  @Test
  void aDeadProbeRescueRevokesTheRunCredential() throws Exception {
    var shell = shell().on("incus list ^acme$", RUNNING_JSON);
    var ops = stopOps(shell, failingHalter(), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    var credential = seedRunWithCredential(123, UNIT);

    var outcome =
        Actor.call(ADMIN, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false));

    assertInstanceOf(StopOperations.NotRunning.class, outcome);
    assertTrue(
        runStore.findByCredential(credential).isEmpty(),
        "recording terminal intent for a dead agent revokes the run credential");
  }

  @Test
  void theAgentLaneIsRefusedOutrightOnStop() throws Exception {
    var shell = liveAgentShell();
    var ops = stopOps(shell, killingHalter(shell), StopOperations.Listener.NONE);
    seedSpec("auth", SpecStatus.IN_PROGRESS, LOCAL_HANDLE);
    seedRun(123, UNIT);
    var agent = Actor.agentPrincipal("claude/a1b2c3", LOCAL_HANDLE);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    agent, () -> ops.stop(new StopOperations.RunTarget(R1), LOCAL_HANDLE, false)));

    assertEquals(ErrorCode.AGENT_LANE_FORBIDDEN, refusal.failure().errorCode());
    assertEquals("running", runStore.findById(R1).orElseThrow().status());
  }
}
