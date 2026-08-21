/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.RunStatus;
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
 * <p>A session covered by an <em>authoritative</em> stop — one carrying a {@code source}, i.e. from
 * the watcher or an earlier reconciler pass — is skipped only when the pipeline demonstrably ACTED
 * on it: a failure verdict deliberately leaves the spec {@code in_progress}, and a fix iteration
 * legitimately re-enters {@code in_progress} mid-review, and replaying over either would duplicate
 * failure events or spawn a competing review. But "observed" is not "consumed": in the field, a
 * raced SQLite statement killed the review kickoff after the watcher's stop was recorded, stranding
 * the spec with a perfectly delivered stop nobody acted on. The caller therefore passes {@link
 * StopCoverage}: when an authoritative stop exists but no acted-on evidence has appeared since the
 * session started (no {@code agent_failed}, no review stage activity) and the stop is older than
 * the grace window, the stop is replayed — the rescue converges, because the replayed stop's
 * consumption produces exactly the evidence that stops the next sweep from replaying again. A raw
 * turn-end hook stop carries no {@code source} and never blocks a replay.
 */
public final class MissedStops {

  /**
   * The finished states whose missed stop should be replayed to <em>advance</em> the spec.
   * Deliberately narrower than {@link RunStatus#isTerminal}: a {@link RunStatus#FAILED} run is
   * terminal too, but a failed spec surfaces for triage rather than advancing, so replaying its
   * stop would only duplicate the failure — it is excluded here on purpose.
   */
  private static final Set<String> TERMINAL =
      Set.of(RunStatus.STOPPED.wire(), RunStatus.COMPLETED.wire());

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
   * How the latest session's authoritative stop was handled. {@code observedAt} is the timestamp of
   * the newest {@code source}-carrying stop recorded since the session started, or null when none
   * exists. {@code actedOn} is true when the pipeline left evidence of consuming it since the
   * session started: an {@code agent_failed} verdict or any review stage activity.
   */
  public record StopCoverage(Instant observedAt, boolean actedOn) {
    public static StopCoverage none() {
      return new StopCoverage(null, false);
    }
  }

  /**
   * Assesses the latest session of an {@code in_progress} spec. Callers must pass the spec's most
   * recent session — superseded sessions from restarts are never replayed — and the {@link
   * StopCoverage} of authoritative stops recorded since the session started.
   *
   * <p>{@code grace} shields two windows: the dispatch launch window (dispatch claims the spec
   * seconds before the systemd unit exists, so a young running session is never probed) and the
   * consumption window (a just-observed stop may still be in flight through the review pipeline, so
   * a rescue replay waits until the stop is older than {@code grace}).
   */
  public static Outcome assess(
      RunStore.RunRow session, StopCoverage coverage, Instant now, Duration grace) {
    if (coverage.observedAt() != null) {
      if (coverage.actedOn()) {
        return new Outcome.Skip("an authoritative stop was recorded and acted on");
      }
      if (!TERMINAL.contains(session.status())) {
        return new Outcome.Skip("stop observed but session status is " + session.status());
      }
      if (Duration.between(coverage.observedAt(), now).compareTo(grace) < 0) {
        return new Outcome.Skip("an authoritative stop is still in flight");
      }
      return new Outcome.ReplayStop(
          session.exitCode(), "authoritative stop was recorded but never acted on");
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
