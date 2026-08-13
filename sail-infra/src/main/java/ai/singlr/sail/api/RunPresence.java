/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import java.time.Duration;
import java.time.Instant;

/**
 * Derives a live run's presence at read time — never stored, so there is nothing to flap,
 * arbitrate, or reconcile. A {@code running} run whose last recorded activity is within {@link
 * #THRESHOLD} of now reads {@code working}; past it, {@code quiet}; any other status — or a run
 * with no activity stamp at all (pre-upgrade rows, agents mid-flight during upgrade) — has no
 * presence, and readers show the plain status rather than guessing.
 */
public final class RunPresence {

  /** A run whose activity is fresher than this reads {@code working}. */
  public static final String WORKING = "working";

  /** A running run that has shown no progress for {@link #THRESHOLD} reads {@code quiet}. */
  public static final String QUIET = "quiet";

  /**
   * How long a running run may go without progress before it reads {@code quiet}. Chosen to fire
   * well before a run's {@code max_idle} stall guardrail (a per-run duration, typically tens of
   * minutes, not a global constant) would kill it: presence is the early "look at me" signal, the
   * guardrail is the late enforcement — the ordering only holds while this stays far under any
   * sensible {@code max_idle}.
   */
  public static final Duration THRESHOLD = Duration.ofSeconds(120);

  private RunPresence() {}

  /**
   * The presence of a run in {@code status} whose last activity was {@code lastActivityAt}
   * (ISO-8601, nullable), against the reader's clock: {@link #WORKING}, {@link #QUIET}, or null
   * when the run has none. An unparseable stamp reads as no presence — never guess. Foreign runs
   * compare their synced stamp against the local clock, so their staleness is dominated by sync
   * cadence, not clock skew — the same freshness contract the board already has.
   */
  public static String of(String status, String lastActivityAt, Instant now) {
    if (!"running".equals(status) || Strings.isBlank(lastActivityAt)) {
      return null;
    }
    Instant at;
    try {
      at = Instant.parse(lastActivityAt);
    } catch (RuntimeException unparseable) {
      return null;
    }
    return at.isBefore(now.minus(THRESHOLD)) ? QUIET : WORKING;
  }
}
