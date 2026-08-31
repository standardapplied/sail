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
 * The production implementation talks to the host over its socket; a box without a host ends
 * nothing.
 */
@FunctionalInterface
public interface SessionYield {

  SessionYield NONE = (sessions, reason) -> {};

  /** The host session {@code sail agent attach} opens for a run's resumed conversation. */
  static String resumeSession(String runId) {
    return "resume-" + runId;
  }

  /**
   * Ends every live session among {@code sessions} with {@code reason}; names with no live session
   * are skipped. Throws when the host refused or could not be reached while sessions may still be
   * live.
   */
  void end(List<String> sessions, String reason) throws IOException;
}
