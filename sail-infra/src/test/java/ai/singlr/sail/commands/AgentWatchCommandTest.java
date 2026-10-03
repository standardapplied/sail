/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AgentWatchCommandTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  @Test
  void helpTextIncludes() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("watch"));
    assertTrue(usage.contains("guardrails"));
    assertTrue(usage.contains("--run"));
    assertTrue(usage.contains("--unit"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--host"));
    assertTrue(usage.contains("--port"));
    assertFalse(
        usage.contains("--interval"),
        "polling was removed; --interval should no longer be advertised");
  }

  @Test
  void refusesToStartWithoutARunId() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var exitCode = cmd.execute("acme");

    assertNotEquals(0, exitCode);
  }

  @Test
  void parseStartedAtRoundtripsValidIso() {
    var iso = "2026-05-21T12:00:00Z";

    var parsed = AgentWatchCommand.parseStartedAt(iso);

    assertEquals(Instant.parse(iso), parsed);
  }

  @Test
  void parseStartedAtFallsBackToNowOnNullOrBlank() {
    var before = Instant.now();
    var parsedNull = AgentWatchCommand.parseStartedAt(null);
    var parsedBlank = AgentWatchCommand.parseStartedAt("  ");
    var after = Instant.now();

    assertFalse(parsedNull.isBefore(before));
    assertFalse(parsedNull.isAfter(after));
    assertFalse(parsedBlank.isBefore(before));
    assertFalse(parsedBlank.isAfter(after));
  }

  @Test
  void parseStartedAtFallsBackToNowOnGarbage() {
    var parsed = AgentWatchCommand.parseStartedAt("not-a-timestamp");

    assertTrue(parsed.isAfter(Instant.EPOCH));
  }

  @Test
  void computeDeadlineUsesMaxDuration() {
    var startedAt = Instant.parse("2026-05-21T12:00:00Z");

    var deadline = AgentWatchCommand.computeDeadline(startedAt, "30m");

    assertEquals(startedAt.plusSeconds(30 * 60), deadline);
  }

  @Test
  void computeDeadlineFallsBackToMaxWhenMissing() {
    var startedAt = Instant.parse("2026-05-21T12:00:00Z");

    assertEquals(Instant.MAX, AgentWatchCommand.computeDeadline(startedAt, null));
    assertEquals(Instant.MAX, AgentWatchCommand.computeDeadline(startedAt, "not-a-duration"));
  }

  @Test
  void waitMsUntilReturnsRemainingMillisBeforeDeadline() {
    var deadline = Instant.now().plusSeconds(2);

    var waitMs = AgentWatchCommand.waitMsUntil(deadline, false);

    assertTrue(waitMs > 500, "expected ~2s of remaining time, got " + waitMs);
    assertTrue(waitMs <= 2000);
  }

  @Test
  void waitMsUntilReturnsZeroWhenDeadlinePassed() {
    var deadline = Instant.now().minusSeconds(60);

    assertEquals(0, AgentWatchCommand.waitMsUntil(deadline, false));
  }

  @Test
  void waitMsUntilReturnsMaxWhenGuardrailAlreadyFired() {
    assertEquals(
        Long.MAX_VALUE, AgentWatchCommand.waitMsUntil(Instant.now().plusSeconds(60), true));
  }

  @Test
  void waitMsUntilReturnsMaxWhenNoDeadline() {
    assertEquals(Long.MAX_VALUE, AgentWatchCommand.waitMsUntil(Instant.MAX, false));
  }

  @Test
  void isProgressEventDetectsToolAndLogActivity() {
    assertTrue(
        AgentWatchCommand.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_TOOL_STARTED)));
    assertTrue(
        AgentWatchCommand.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_TOOL_FINISHED)));
    assertTrue(
        AgentWatchCommand.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_LOG_CHUNK)));
    assertFalse(
        AgentWatchCommand.isProgressEvent(sampleEvent(Event.WellKnownTypes.SNAPSHOT_CREATED)));
  }

  @Test
  void anotherRunsHeartbeatNeverResetsThisRunsStallTimer() {
    var mine = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    var other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    var otherEvent =
        Event.of(
            "acme",
            "auth",
            Event.WellKnownTypes.AGENT_TOOL_STARTED,
            "claude-code",
            "host",
            Map.of(Event.WellKnownData.RUN_ID, other));
    var myEvent =
        Event.of(
            "acme",
            "auth",
            Event.WellKnownTypes.AGENT_TOOL_STARTED,
            "claude-code",
            "host",
            Map.of(Event.WellKnownData.RUN_ID, mine));

    assertFalse(AgentWatchCommand.matchesRun(otherEvent, mine));
    assertTrue(AgentWatchCommand.matchesRun(myEvent, mine));
  }

  @Test
  void anUnstampedEventNeverResetsARunScopedStallTimer() {
    var event = sampleEvent(Event.WellKnownTypes.AGENT_TOOL_STARTED);

    assertFalse(AgentWatchCommand.matchesRun(event, RUN_ID));
  }

  @Test
  void toolProgressEventsTheWatcherResetsOnAreActuallyEmittedByTheHookConfig() {
    var hookJson = ai.singlr.sail.engine.ClaudeCodeHookConfig.render();
    assertTrue(
        hookJson.contains(Event.WellKnownTypes.AGENT_TOOL_STARTED)
            && AgentWatchCommand.isProgressEvent(
                sampleEvent(Event.WellKnownTypes.AGENT_TOOL_STARTED)),
        "the stall watcher resets on agent_tool_started, so the hook config must emit it — "
            + "otherwise a busy agent is killed at max_idle");
    assertTrue(
        hookJson.contains(Event.WellKnownTypes.AGENT_TOOL_FINISHED)
            && AgentWatchCommand.isProgressEvent(
                sampleEvent(Event.WellKnownTypes.AGENT_TOOL_FINISHED)),
        "the stall watcher resets on agent_tool_finished, so the hook config must emit it");
  }

  @Test
  void earlierReturnsTheSoonerInstant() {
    var soon = java.time.Instant.now();
    var later = soon.plusSeconds(3600);

    assertEquals(soon, AgentWatchCommand.earlier(soon, later));
    assertEquals(soon, AgentWatchCommand.earlier(later, soon));
  }

  @Test
  void syntheticStopCarriesExitCodeSpecAndAgent() {
    var exit = new AgentSession.ExitState(false, 137, "scrum-12", "claude-code", "run-12", "build");

    var event = AgentWatchCommand.stop("acme", exit, null);

    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertEquals("acme", event.project());
    assertEquals("scrum-12", event.spec());
    assertEquals("claude-code", event.agent());
    assertEquals(137, event.data().get("exit_code"));
    assertEquals("watcher", event.data().get("source"));
    assertEquals("run-12", event.data().get("run_id"));
    assertEquals("build", event.data().get("run_role"));
  }

  @Test
  void syntheticStopCarriesTheRoomRoleSoThePipelineCanIgnoreTheChat() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-12", "claude-code", "run-12", "room");

    var event = AgentWatchCommand.stop("acme", exit, null);

    assertEquals("room", event.data().get("run_role"));
  }

  @Test
  void syntheticStopOmitsRunIdWhenTheSessionHadNone() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-12", "claude-code", "", "");

    var event = AgentWatchCommand.stop("acme", exit, null);

    assertNull(event.data().get("run_id"));
    assertNull(event.data().get("run_role"));
  }

  @Test
  void syntheticStopFallsBackToSailAgentWhenTypeUnknown() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-12", "", "run-12", "");

    var event = AgentWatchCommand.stop("acme", exit, null);

    assertEquals(Event.SAIL_AGENT, event.agent());
    assertEquals(0, event.data().get("exit_code"));
  }

  @Test
  void onTimeoutSurfacesADeadUnitRegardlessOfEverythingElse() {
    for (var fired : new boolean[] {true, false}) {
      for (var reached : new boolean[] {true, false}) {
        assertEquals(
            AgentWatchCommand.TimeoutDecision.SYNTHESIZE_STOP,
            AgentWatchCommand.onTimeout(false, fired, reached),
            "a dead unit must always be surfaced");
      }
    }
  }

  @Test
  void onTimeoutChecksGuardrailsOnlyAtTheDeadline() {
    assertEquals(
        AgentWatchCommand.TimeoutDecision.CHECK_GUARDRAILS,
        AgentWatchCommand.onTimeout(true, false, true));
    assertEquals(
        AgentWatchCommand.TimeoutDecision.KEEP_WAITING,
        AgentWatchCommand.onTimeout(true, false, false),
        "the 15s liveness poll must not turn into a 15s guardrail poll");
  }

  @Test
  void onTimeoutStopsCheckingOnceAGuardrailHasFired() {
    assertEquals(
        AgentWatchCommand.TimeoutDecision.KEEP_WAITING,
        AgentWatchCommand.onTimeout(true, true, true));
  }

  @Test
  void emitSyntheticStopPublishesWhenASpecIsKnown() throws Exception {
    var captured = new java.util.concurrent.atomic.AtomicReference<Event>();
    var exit = new AgentSession.ExitState(false, 2, "scrum-7", "codex", "run-7", "build");

    AgentWatchCommand.emitStop(captured::set, "acme", exit, null);

    assertEquals("scrum-7", captured.get().spec());
    assertEquals(2, captured.get().data().get("exit_code"));
  }

  @Test
  void emitSyntheticStopSkipsOnlyWhenNeitherSpecNorRunIdIsKnown() throws Exception {
    var captured = new java.util.concurrent.atomic.AtomicReference<Event>();
    var exit = new AgentSession.ExitState(false, 0, "", "codex", "", "");

    AgentWatchCommand.emitStop(captured::set, "acme", exit, null);

    assertNull(captured.get());
  }

  @Test
  void emitSyntheticStopPublishesARunAddressedStopForABlankSpec() throws Exception {
    var captured = new java.util.concurrent.atomic.AtomicReference<Event>();
    var exit = new AgentSession.ExitState(false, 3, "", "codex", RUN_ID, "");

    AgentWatchCommand.emitStop(captured::set, "acme", exit, null);

    var event = captured.get();
    assertNotNull(event, "a run-addressed session with no spec must still publish its stop");
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertNull(event.spec());
    assertEquals(RUN_ID, event.data().get("run_id"));
    assertEquals(3, event.data().get("exit_code"));
    assertEquals("watcher", event.data().get("source"));
  }

  @Test
  void watcherProducesASpecAttributedStopWhenTheUnitWasAlreadyCollected() throws Exception {
    var unit = AgentUnit.forRun(RUN_ID);
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show " + unit.service(),
                """
                ActiveState=inactive
                ExecMainStatus=0
                Environment=
                """)
            .onOk(
                "cat " + unit.sessionPath(),
                """
                {"task":"t","branch":"b","spec_id":"auth","agent_type":"claude-code","run_id":"%s","started_at":"2026-06-30T16:55:14Z","log_path":"%s"}
                """
                    .formatted(RUN_ID, unit.logPath()));
    var exit = new AgentSession(shell).queryExitStatus("acme", unit);

    var captured = new java.util.concurrent.atomic.AtomicReference<Event>();
    AgentWatchCommand.emitStop(captured::set, "acme", exit, null);

    var event = captured.get();
    assertNotNull(
        event, "a clean exit on a collected unit must still publish a spec-attributed stop");
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertEquals("auth", event.spec());
    assertEquals("claude-code", event.agent());
    assertEquals(0, event.data().get("exit_code"));
    assertEquals(RUN_ID, event.data().get("run_id"));
  }

  @Test
  void emitSyntheticStopIsANoOpWithoutAPublisher() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-7", "codex", "run-7", "build");

    assertDoesNotThrow(() -> AgentWatchCommand.emitStop(null, "acme", exit, null));
  }

  @Test
  void emitSyntheticStopSwallowsPublisherFailures() {
    var exit = new AgentSession.ExitState(false, 1, "scrum-7", "codex", "run-7", "build");

    assertDoesNotThrow(
        () ->
            AgentWatchCommand.emitStop(
                e -> {
                  throw new RuntimeException("network down");
                },
                "acme",
                exit,
                null));
  }

  private static Event sampleEvent(String type) {
    return Event.of("test-project", "test-spec", type, "claude-code", "host-01", Map.of());
  }

  private static final GuardrailChecker.GuardrailResult.Triggered TIME_LIMIT =
      new GuardrailChecker.GuardrailResult.Triggered(
          "max_duration", "Agent running for 46m (limit: 45m)", "stop", "45m");

  private static AgentSession.ExitState running(String role) {
    return new AgentSession.ExitState(true, 0, "auth", "claude-code", RUN_ID, role);
  }

  @Test
  void aTrippedRunIsKilledThenItsTriggerRecordedBesideItThenItsStopPublishedWithTheReason()
      throws Exception {
    var unit = AgentUnit.forRun(RUN_ID);
    var shell = new ScriptedShellExecutor().onFail("is-active " + unit.service(), "");
    var published = new java.util.ArrayList<Event>();

    var reaped =
        AgentWatchCommand.reap("acme", unit, TIME_LIMIT, running("fix"), shell, published::add);

    assertTrue(reaped);
    var kill = indexOf(shell, "kill --kill-who=all --signal=SIGTERM " + unit.service());
    var trigger = indexOf(shell, unit.guardrailTriggerPath());
    assertTrue(kill >= 0, "the unit's whole cgroup is signalled: " + shell.invocations());
    assertTrue(
        trigger > kill,
        "the trigger is recorded beside the run, after the kill: " + shell.invocations());
    assertTrue(shell.invocations().get(trigger).contains("reason: max_duration"));
    var stop = published.getFirst();
    assertEquals(1, published.size());
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, stop.type());
    assertEquals("time limit (45m)", stop.data().get("reason"));
    assertEquals("watcher", stop.data().get("source"));
    assertEquals(RUN_ID, stop.data().get("run_id"));
    assertEquals("fix", stop.data().get("run_role"));
    assertNull(
        stop.data().get("exit_code"),
        "a run the watcher killed has no exit code of its own; the reason says why it ended");
  }

  @Test
  void aRunThatSurvivesTheKillIsNeverReportedEnded() throws Exception {
    var unit = AgentUnit.forRun(RUN_ID);
    var shell =
        new ScriptedShellExecutor()
            .onOk("is-active " + unit.service())
            .onOk("--signal=SIGKILL " + unit.service());
    var published = new java.util.ArrayList<Event>();

    var reaped =
        AgentWatchCommand.reap("acme", unit, TIME_LIMIT, running("build"), shell, published::add);

    assertFalse(reaped, "the unit is still active: the watcher tries again at its next poll");
    assertTrue(published.isEmpty(), "no stop while the agent can still push work");
    assertEquals(-1, indexOf(shell, unit.guardrailTriggerPath()));
    assertTrue(indexOf(shell, "--signal=SIGKILL " + unit.service()) >= 0);
  }

  @Test
  void aStallNamesTheStallWindowAsItsCause() {
    var stalled =
        new GuardrailChecker.GuardrailResult.Triggered(
            "stall", "No progress for 21m (limit: 20m)", "stop", "20m");

    assertEquals("stall (20m)", stalled.cause());
    assertEquals("time limit (45m)", TIME_LIMIT.cause());
  }

  @Test
  void theWatchersLimitsComeFromItsCommandLineAndAnInvalidOneIsRefusedNamingTheForms() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new java.io.StringWriter();
    cmd.setErr(new java.io.PrintWriter(err));

    var help = cmd.getUsageMessage();

    assertTrue(help.contains("--max-duration"), help);
    assertTrue(help.contains("--max-idle"), help);
    assertTrue(help.contains("--action"), help);
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> ai.singlr.sail.config.Guardrails.of("45 minutes", "20m", "stop"));
    assertTrue(refused.getMessage().contains("4h, 90m, 30s"), refused.getMessage());
  }

  @Test
  void aStopEndsTheRunTheWatcherWasStartedForWhateverTheSessionFileNames() {
    var forged =
        new AgentSession.ExitState(false, 0, "auth", "claude-code", "another-run", "review");

    var addressed = AgentWatchCommand.addressedTo(RUN_ID, forged);

    assertEquals(RUN_ID, addressed.runId(), "the session file is the agent's to write");
    assertEquals(RUN_ID, AgentWatchCommand.stop("acme", addressed, null).data().get("run_id"));
  }

  @Test
  void anAgentThatDiedBeforeItsWatcherAttachedHasItsStopPublishedWithTheCodeItsUnitHolds()
      throws Exception {
    var unit = AgentUnit.forRun(RUN_ID);
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show " + unit.service(),
                """
                ActiveState=failed
                ExecMainStatus=127
                Environment=SAIL_SPEC_ID=auth SAIL_AGENT=codex SAIL_RUN_ID=%s SAIL_RUN_ROLE=fix
                """
                    .formatted(RUN_ID));
    var published = new java.util.ArrayList<Event>();

    var stopped =
        AgentWatchCommand.stopIfAlreadyEnded(
            "acme", RUN_ID, unit, new AgentSession(shell), published::add);

    assertTrue(stopped);
    assertEquals(127, published.getFirst().data().get("exit_code"));
    assertEquals("fix", published.getFirst().data().get("run_role"));
    assertEquals(RUN_ID, published.getFirst().data().get("run_id"));
  }

  @Test
  void aWatcherStartedForARunThatNeverLaunchedPublishesNothing() throws Exception {
    var unit = AgentUnit.forRun(RUN_ID);
    var published = new java.util.ArrayList<Event>();

    var stopped =
        AgentWatchCommand.stopIfAlreadyEnded(
            "acme", RUN_ID, unit, new AgentSession(new ScriptedShellExecutor()), published::add);

    assertFalse(stopped, "no unit and no session file name this run");
    assertTrue(published.isEmpty());
  }

  private static int indexOf(ScriptedShellExecutor shell, String fragment) {
    var invocations = shell.invocations();
    for (var i = 0; i < invocations.size(); i++) {
      if (invocations.get(i).contains(fragment)) {
        return i;
      }
    }
    return -1;
  }
}
