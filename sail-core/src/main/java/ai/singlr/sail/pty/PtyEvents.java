/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

/**
 * The record-class facts a session emits — started, attached, ended — with the existing source
 * discipline: observational only, nothing here drives run or spec state. Each fact carries the
 * session's {@link PtySession.Origin}, so a room-bound session's room travels with it.
 *
 * <p>Emission is fail-open on both sides of this seam: {@link PtySession} swallows whatever an
 * implementation throws, and an implementation is still expected to swallow (and measure) its own
 * failures — a session must never die or stall because an event could not be recorded.
 */
public interface PtyEvents {

  PtyEvents NONE =
      new PtyEvents() {
        @Override
        public void sessionStarted(PtySession.Origin origin) {}

        @Override
        public void sessionAttached(PtySession.Origin origin, String fde) {}

        @Override
        public void sessionEnded(PtySession.Origin origin, String reason) {}
      };

  void sessionStarted(PtySession.Origin origin);

  void sessionAttached(PtySession.Origin origin, String fde);

  void sessionEnded(PtySession.Origin origin, String reason);
}
