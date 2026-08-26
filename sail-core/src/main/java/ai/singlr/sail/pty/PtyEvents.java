/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

/**
 * The record-class facts a session emits — started, attached, ended — with the existing source
 * discipline: observational only, nothing here drives run or spec state.
 */
public interface PtyEvents {

  PtyEvents NONE =
      new PtyEvents() {
        @Override
        public void sessionStarted(String session, String project, String fde) {}

        @Override
        public void sessionAttached(String session, String project, String fde) {}

        @Override
        public void sessionEnded(String session, String project, String reason) {}
      };

  void sessionStarted(String session, String project, String fde);

  void sessionAttached(String session, String project, String fde);

  void sessionEnded(String session, String project, String reason);
}
