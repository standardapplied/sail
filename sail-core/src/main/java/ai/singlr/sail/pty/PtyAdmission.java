/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;

/**
 * Where the host lets a person open a terminal. A project and a room a client names are claims
 * until this gate has checked them: the project must be one the caller holds on this box — its
 * catalog holds it and the caller's scope admits it — before the session runs in its container, and
 * a room must exist in the session's project with the caller holding its post right — the same gate
 * a room message passes — before it may ride into the child as {@code SAIL_ROOM_ID} or onto the
 * room's event history.
 */
public interface PtyAdmission {

  /**
   * A host that keeps no projects and no rooms: every project-bound and room-bound create is
   * refused.
   */
  PtyAdmission NONE =
      new PtyAdmission() {
        @Override
        public void project(String project, PtyIdentity who) throws IOException {
          throw new IOException("This host keeps no projects, so no session can be opened in one.");
        }

        @Override
        public void room(String room, String project, PtyIdentity who) throws IOException {
          throw new IOException("This host keeps no rooms, so no session can be pinned to one.");
        }
      };

  /** Throws, with the reason, when {@code who} may not open a session in {@code project}. */
  void project(String project, PtyIdentity who) throws IOException;

  /**
   * Throws, with the reason, when {@code who} may not pin a session in {@code project} to {@code
   * room}.
   */
  void room(String room, String project, PtyIdentity who) throws IOException;
}
