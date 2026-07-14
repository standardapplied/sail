/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MissedStopsTest {

  private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");
  private static final Duration GRACE = Duration.ofMinutes(2);

  private static RunStore.RunRow session(String status, Integer exitCode, String startedAt) {
    return new RunStore.RunRow(
        "s-auth",
        "acme",
        "auth",
        "node-a",
        "build",
        "claude-code",
        null,
        null,
        null,
        null,
        status,
        exitCode,
        null,
        "sail-agent-run",
        startedAt,
        null,
        java.util.List.of());
  }

  private static MissedStops.Outcome assess(RunStore.RunRow session, boolean observed) {
    var coverage =
        observed
            ? new MissedStops.StopCoverage(NOW.minus(GRACE).minusSeconds(1), true)
            : MissedStops.StopCoverage.none();
    return MissedStops.assess(session, coverage, NOW, GRACE);
  }

  private static MissedStops.Outcome assessDropped(
      RunStore.RunRow session, java.time.Instant observedAt) {
    return MissedStops.assess(session, new MissedStops.StopCoverage(observedAt, false), NOW, GRACE);
  }

  @Test
  void replaysACleanlyStoppedSessionWithItsExitCode() {
    var outcome = assess(session("stopped", 0, "2026-07-06T11:00:00Z"), false);

    var replay = assertInstanceOf(MissedStops.Outcome.ReplayStop.class, outcome);
    assertEquals(0, replay.exitCode());
  }

  @Test
  void carriesTheExitCodeOfACrashedSession() {
    var outcome = assess(session("stopped", 137, "2026-07-06T11:00:00Z"), false);

    assertEquals(137, assertInstanceOf(MissedStops.Outcome.ReplayStop.class, outcome).exitCode());
  }

  @Test
  void treatsCompletedAsTerminalAndToleratesAMissingExitCode() {
    var outcome = assess(session("completed", null, "2026-07-06T11:00:00Z"), false);

    assertNull(assertInstanceOf(MissedStops.Outcome.ReplayStop.class, outcome).exitCode());
  }

  @Test
  void anAuthoritativeStopAlreadyRecordedSkipsEvenATerminalSession() {
    var outcome = assess(session("stopped", 137, "2026-07-06T11:00:00Z"), true);

    assertInstanceOf(MissedStops.Outcome.Skip.class, outcome);
  }

  @Test
  void anAuthoritativeStopAlreadyRecordedSkipsARunningSession() {
    var outcome = assess(session("running", null, "2026-07-06T11:00:00Z"), true);

    assertInstanceOf(MissedStops.Outcome.Skip.class, outcome);
  }

  @Test
  void anObservedButUnactedStopOlderThanGraceIsReplayedWithItsExitCode() {
    var outcome =
        assessDropped(
            session("stopped", 0, "2026-07-06T11:00:00Z"), NOW.minus(GRACE).minusSeconds(1));

    var replay = assertInstanceOf(MissedStops.Outcome.ReplayStop.class, outcome);
    assertEquals(0, replay.exitCode());
    assertEquals("authoritative stop was recorded but never acted on", replay.why());
  }

  @Test
  void anObservedButUnactedFailureStopReplaysTheFailureExitCode() {
    var outcome =
        assessDropped(
            session("stopped", 137, "2026-07-06T11:00:00Z"), NOW.minus(GRACE).minusSeconds(1));

    var replay = assertInstanceOf(MissedStops.Outcome.ReplayStop.class, outcome);
    assertEquals(137, replay.exitCode());
  }

  @Test
  void anObservedButUnactedStopStillInsideGraceStaysInFlight() {
    var outcome =
        assessDropped(session("stopped", 0, "2026-07-06T11:00:00Z"), NOW.minusSeconds(30));

    assertInstanceOf(MissedStops.Outcome.Skip.class, outcome);
  }

  @Test
  void anObservedUnactedStopOnANonTerminalSessionIsNeverReplayed() {
    var outcome =
        assessDropped(
            session("running", null, "2026-07-06T11:00:00Z"), NOW.minus(GRACE).minusSeconds(1));

    assertInstanceOf(MissedStops.Outcome.Skip.class, outcome);
  }

  @Test
  void aRunningSessionPastTheGracePeriodIsProbed() {
    var outcome = assess(session("running", null, "2026-07-06T11:57:59Z"), false);

    assertInstanceOf(MissedStops.Outcome.ProbeUnit.class, outcome);
  }

  @Test
  void aRunningSessionInsideTheGracePeriodIsLeftAlone() {
    var outcome = assess(session("running", null, "2026-07-06T11:59:50Z"), false);

    assertInstanceOf(MissedStops.Outcome.Skip.class, outcome);
  }

  @Test
  void aSessionExactlyAtTheGraceBoundaryIsProbed() {
    var outcome = assess(session("running", null, "2026-07-06T11:58:00Z"), false);

    assertInstanceOf(MissedStops.Outcome.ProbeUnit.class, outcome);
  }

  @Test
  void aRunningSessionWithAnUnparseableStartIsTreatedAsFreshlyLaunched() {
    var outcome = assess(session("running", null, "not-a-timestamp"), false);

    assertInstanceOf(MissedStops.Outcome.Skip.class, outcome);
  }

  @Test
  void aFailedStatusSessionIsNeverReplayed() {
    var outcome = assess(session("failed", 1, "2026-07-06T11:00:00Z"), false);

    assertInstanceOf(MissedStops.Outcome.Skip.class, outcome);
  }

  @Test
  void parseOrFallsBackOnNullBlankAndGarbage() {
    var fallback = Instant.EPOCH;
    assertEquals(fallback, MissedStops.parseOr(null, fallback));
    assertEquals(fallback, MissedStops.parseOr("  ", fallback));
    assertEquals(fallback, MissedStops.parseOr("garbage", fallback));
    assertEquals(NOW, MissedStops.parseOr("2026-07-06T12:00:00Z", fallback));
  }
}
