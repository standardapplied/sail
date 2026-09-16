/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.EventStore;
import java.io.IOException;

/** What the pty host asks of the control plane: who a session is, and what it may join. */
public interface HostPty {
  PtyIdentity identity(String token, String boxHandle) throws IOException;

  void admitRoom(String room, String project, PtyIdentity identity) throws IOException;

  void recordEvent(EventStore.EventRow event);
}
