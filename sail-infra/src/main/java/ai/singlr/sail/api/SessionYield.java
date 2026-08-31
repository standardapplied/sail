/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.io.IOException;
import java.util.List;

/**
 * The reservation's door onto host-owned terminal sessions. A resumed agent conversation lives in
 * the pty session host under a name derived from its run, so when a dispatch reserves the repos
 * that conversation works in, the reservation names the displaced sessions and this seam ends them
 * — the decision stays with {@link RunReservation}, the pty host only executes the kill it is told.
 *
 * <p>The seam also owns the lock that makes the coupling sound: a claim and a resume session open
 * are each compound operations (read the run rows, then act on the host), and the two must not
 * interleave — a dispatch that scans for sessions before an attach creates one would find nothing
 * to yield, and the resumed agent would then work the repos the dispatch just took. {@link #lock}
 * serializes both sides per project, across processes, and every production lane wires the one
 * implementation that talks to the host. {@link #NONE} is for lanes with no host at all — tests —
 * where there is nothing to end and nothing to race.
 */
public interface SessionYield {

  /** A held per-project claim lock; releasing never throws. */
  interface Hold extends AutoCloseable {
    @Override
    void close();
  }

  Hold NO_HOLD = () -> {};

  SessionYield NONE =
      new SessionYield() {
        @Override
        public Hold lock(String project) {
          return NO_HOLD;
        }

        @Override
        public void end(List<String> sessions, String reason) {}
      };

  /** The host session {@code sail agent attach} opens for a run's resumed conversation. */
  static String resumeSession(String runId) {
    return "resume-" + runId;
  }

  /**
   * Acquires the project's claim lock, blocking until it is free: held by a reservation from its
   * run-row insert through the yield of displaced sessions, and by an attach from its run-row read
   * through the session create.
   */
  Hold lock(String project) throws IOException;

  /**
   * Ends every live session among {@code sessions} with {@code reason}; names with no live session
   * are skipped. Throws when the host refused or could not be reached while sessions may still be
   * live.
   */
  void end(List<String> sessions, String reason) throws IOException;
}
