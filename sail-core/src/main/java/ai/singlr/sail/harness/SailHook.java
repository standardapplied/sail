/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The things sail runs at a harness's events. A harness declares, as data, which of its events run
 * which of these; the first six are {@link #required} of every harness, and a hook file missing one
 * cannot be built.
 */
public enum SailHook {
  /** Announces a session's beginning, exactly once. */
  SESSION_STARTED,
  /** Records the resumable conversation on every start source, so a restart overwrites the row. */
  SESSION_REPORT,
  /** A tool call started: the watcher's liveness signal. */
  TOOL_STARTED,
  /** A tool call ended, whether it succeeded or failed. */
  TOOL_FINISHED,
  /** Delivers the room's new messages into the running session. */
  ROOM_RELAY,
  /** Decides whether a stop stands, and publishes the stop or the nudge. */
  STOP_GATE,
  /** The main agent's batch of calls resolved, which closes a denied or lost call. */
  BATCH_RESOLVED,
  /** The session ended. */
  SESSION_ENDED;

  private static final Set<SailHook> REQUIRED =
      Collections.unmodifiableSet(
          EnumSet.of(
              SESSION_STARTED, SESSION_REPORT, TOOL_STARTED, TOOL_FINISHED, ROOM_RELAY, STOP_GATE));

  /** The hooks every harness must run at one of its events. */
  public static Set<SailHook> required() {
    return REQUIRED;
  }
}
