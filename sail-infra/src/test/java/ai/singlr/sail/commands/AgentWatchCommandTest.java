/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.engine.AgentSession;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AgentWatchCommandTest {

  @Test
  void helpTextIncludes() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("watch"));
    assertTrue(usage.contains("guardrails"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--host"));
    assertTrue(usage.contains("--port"));
    assertFalse(
        usage.contains("--interval"),
        "polling was removed; --interval should no longer be advertised");
  }

  @Test
  void requiresProjectName() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var exitCode = cmd.execute();

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
  void isAgentExitDetectsStoppedAndCompleted() {
    var stopped = sampleEvent(Event.WellKnownTypes.AGENT_SESSION_STOPPED);
    var completed = sampleEvent(Event.WellKnownTypes.AGENT_SESSION_COMPLETED);

    assertTrue(AgentWatchCommand.isAgentExit(stopped));
    assertTrue(AgentWatchCommand.isAgentExit(completed));
  }

  @Test
  void isAgentExitIgnoresUnrelatedTypes() {
    var started = sampleEvent(Event.WellKnownTypes.AGENT_SESSION_STARTED);
    var snapshot = sampleEvent(Event.WellKnownTypes.SNAPSHOT_CREATED);

    assertFalse(AgentWatchCommand.isAgentExit(started));
    assertFalse(AgentWatchCommand.isAgentExit(snapshot));
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
  void earlierReturnsTheSoonerInstant() {
    var soon = java.time.Instant.now();
    var later = soon.plusSeconds(3600);

    assertEquals(soon, AgentWatchCommand.earlier(soon, later));
    assertEquals(soon, AgentWatchCommand.earlier(later, soon));
  }

  @Test
  void syntheticStopCarriesExitCodeSpecAndAgent() {
    var exit = new AgentSession.ExitState(false, 137, "scrum-12", "claude-code");

    var event = AgentWatchCommand.syntheticStop("acme", exit);

    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertEquals("acme", event.project());
    assertEquals("scrum-12", event.spec());
    assertEquals("claude-code", event.agent());
    assertEquals(137, event.data().get("exit_code"));
    assertEquals("watcher", event.data().get("source"));
  }

  @Test
  void syntheticStopFallsBackToSailAgentWhenTypeUnknown() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-12", "");

    var event = AgentWatchCommand.syntheticStop("acme", exit);

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
    var exit = new AgentSession.ExitState(false, 2, "scrum-7", "codex");

    AgentWatchCommand.emitSyntheticStop(captured::set, "acme", exit);

    assertEquals("scrum-7", captured.get().spec());
    assertEquals(2, captured.get().data().get("exit_code"));
  }

  @Test
  void emitSyntheticStopSkipsAnAdHocSessionWithNoSpec() throws Exception {
    var captured = new java.util.concurrent.atomic.AtomicReference<Event>();
    var exit = new AgentSession.ExitState(false, 0, "", "codex");

    AgentWatchCommand.emitSyntheticStop(captured::set, "acme", exit);

    assertNull(captured.get());
  }

  @Test
  void emitSyntheticStopIsANoOpWithoutAPublisher() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-7", "codex");

    assertDoesNotThrow(() -> AgentWatchCommand.emitSyntheticStop(null, "acme", exit));
  }

  @Test
  void emitSyntheticStopSwallowsPublisherFailures() {
    var exit = new AgentSession.ExitState(false, 1, "scrum-7", "codex");

    assertDoesNotThrow(
        () ->
            AgentWatchCommand.emitSyntheticStop(
                e -> {
                  throw new RuntimeException("network down");
                },
                "acme",
                exit));
  }

  private static Event sampleEvent(String type) {
    return Event.of("test-project", "test-spec", type, "claude-code", "host-01", Map.of());
  }
}
