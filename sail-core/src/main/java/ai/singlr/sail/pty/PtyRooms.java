/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;

/**
 * The host's door into rooms. A room id a client names is a claim until this gate has checked it:
 * the room must exist in the session's project and the authenticated caller must hold its post
 * right — the same gate a room message passes — before it may ride into the child as {@code
 * SAIL_ROOM_ID} or onto the room's event history.
 */
@FunctionalInterface
public interface PtyRooms {

  /** A host that keeps no rooms: every room-bound create is refused. */
  PtyRooms NONE =
      (room, project, who) -> {
        throw new IOException("This host keeps no rooms, so no session can be pinned to one.");
      };

  /**
   * Throws, with the reason, when {@code who} may not pin a session in {@code project} to {@code
   * room}.
   */
  void admit(String room, String project, PtyIdentity who) throws IOException;
}
