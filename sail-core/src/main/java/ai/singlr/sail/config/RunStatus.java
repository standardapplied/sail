/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.Optional;

/**
 * The lifecycle state of a run — the canonical type behind the {@code runs.status} column that the
 * store, the sync narrator, and the missed-stop reconciler each once matched as a bare string. A
 * run begins {@link #RUNNING}, may pass through {@link #STOPPING} while a stop is honored, and
 * settles in one terminal state: {@link #STOPPED}, {@link #COMPLETED}, or {@link #FAILED}. (A
 * cancel records the run as {@code STOPPED} and the spec as cancelled — cancellation is a spec
 * state, never a run one.)
 *
 * <p>{@link #isTerminal} is the one definition of "finished." Note it is broader than the set the
 * missed-stop reconciler replays to <em>advance</em> a spec: a failed run is terminal but its spec
 * surfaces for triage rather than advancing, so that narrower rule lives with the reconciler.
 */
public enum RunStatus {
  RUNNING("running"),
  STOPPING("stopping"),
  STOPPED("stopped"),
  COMPLETED("completed"),
  FAILED("failed");

  private final String wire;

  RunStatus(String wire) {
    this.wire = wire;
  }

  /** The stored/transmitted status string for this state. */
  public String wire() {
    return wire;
  }

  /** The status for a wire string, or empty for an unknown or null value. */
  public static Optional<RunStatus> of(String status) {
    for (var value : values()) {
      if (value.wire.equals(status)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  /** Whether this state is terminal — the run has finished, however it finished. */
  public boolean isTerminal() {
    return this == STOPPED || this == COMPLETED || this == FAILED;
  }

  /** Whether {@code status} names a terminal state — null and unknown values are not terminal. */
  public static boolean isTerminal(String status) {
    return of(status).map(RunStatus::isTerminal).orElse(false);
  }
}
