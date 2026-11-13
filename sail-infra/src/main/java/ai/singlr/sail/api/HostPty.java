/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.EventStore;
import java.io.IOException;

/**
 * What the pty host asks of the control plane: who a session is, who a ticket's credential is,
 * whether a terminal may open on this box at all, and where it may open and what it may join.
 */
public interface HostPty {
  PtyIdentity.Person identity(String token) throws IOException;

  PtyIdentity.Person holder(String credentialHash) throws IOException;

  void admitBox(PtyIdentity.Person who) throws IOException;

  void admitProject(String project, PtyIdentity.Person who) throws IOException;

  void admitInstance(String project) throws IOException;

  void admitRoom(String room, String project, PtyIdentity.Person who) throws IOException;

  void recordEvent(EventStore.EventRow event);
}
