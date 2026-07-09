/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Decides whether an {@code in_progress} spec's latest agent session needs a stop replayed — the
 * agent already finished but no live observer advanced the spec, so without intervention it strands
 * until the very-long-horizon stuck-spec alarm. The session, not the spec, is the anchor: a
 * terminal session means the run ended and the loop never reacted; a still-{@code running} session
 * may hide an agent that died without any stop signal at all (watcher killed by a daemon restart,
 * hook never fired), which only a systemd liveness probe can confirm.
 *
 * <p>A session already covered by an <em>authoritative</em> stop — one carrying a {@code source},
 * i.e. from the watcher or an earlier reconciler pass — is always skipped: either the pipeline
 * consumed it (a failure verdict deliberately leaves the spec {@code in_progress}, and a fix
 * iteration legitimately re-enters {@code in_progress} mid-review), or it is still in flight.
 * Replaying over it would duplicate failure events or spawn a competing review. A raw turn-end hook
 * stop carries no {@code source} and never blocks a replay — losing exactly the authoritative
 * signal is the failure being repaired.
 */
public final class MissedStops {

  private static final Set<String> TERMINAL = Set.of("stopped", "completed");

  /** What one reconciliation pass should do for a spec's latest session. */
  public sealed interface Outcome {

    /** Replay the stop the loop missed, carrying the exit code the session recorded (nullable). */
    record ReplayStop(Integer exitCode, String why) implements Outcome {}

    /**
     * The session still claims to be running: probe the agent's systemd unit and synthesize a stop
     * only if the unit is inactive or absent. The exit code of a vanished transient unit is
     * unrecoverable, so the synthesized stop carries none — the same decision {@link ReplayStop}
     * expresses for a terminal session without a recorded exit code.
     */
    record ProbeUnit(String why) implements Outcome {}

    /** Leave the session alone. */
    record Skip(String why) implements Outcome {}
  }

  private MissedStops() {}

  /**
   * Assesses the latest session of an {@code in_progress} spec. Callers must pass the spec's most
   * recent session — superseded sessions from restarts are never replayed — and whether an
   * authoritative ({@code source}-carrying) stop has been recorded since the session started.
   *
   * <p>{@code grace} shields the dispatch launch window: dispatch claims the spec seconds before
   * the systemd unit exists, so a young running session is never probed, let alone declared dead.
   */
  public static Outcome assess(
      RunStore.RunRow session, boolean authoritativeStopObserved, Instant now, Duration grace) {
    if (authoritativeStopObserved) {
      return new Outcome.Skip("an authoritative stop is already recorded");
    }
    if (TERMINAL.contains(session.status())) {
      return new Outcome.ReplayStop(session.exitCode(), "finished session never advanced its spec");
    }
    if (!"running".equals(session.status())) {
      return new Outcome.Skip("session status is " + session.status());
    }
    var age = Duration.between(parseOr(session.startedAt(), now), now);
    if (age.compareTo(grace) < 0) {
      return new Outcome.Skip("session is inside the launch grace period");
    }
    return new Outcome.ProbeUnit(
        "running session with no stop signal after " + age.toMinutes() + "m");
  }

  /** Parses an ISO instant, falling back when the value is missing or malformed. */
  public static Instant parseOr(String iso, Instant fallback) {
    if (iso == null || iso.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(iso);
    } catch (DateTimeParseException e) {
      return fallback;
    }
  }
}
