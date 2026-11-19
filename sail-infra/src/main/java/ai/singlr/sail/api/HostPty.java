/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.LoginStore;
import java.io.IOException;
import java.util.Optional;

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

  /**
   * Refuses {@code who} entering workspace {@code workspace} of {@code project} unless it is theirs
   * or they are an admin ({@link Action#ENTER_WORKSPACE}); {@link PtyAdmission.Absent} when the
   * project has no such workspace here.
   */
  void admitWorkspace(String project, String workspace, PtyIdentity.Person who) throws IOException;

  /** As {@link #admitWorkspace}, then readies it: started when stopped, given its owner's. */
  void enterWorkspace(String project, String workspace, PtyIdentity.Person who) throws IOException;

  /**
   * {@link PtyAdmission#agent}: the stored login an agent starts with in {@code who}'s workspace.
   */
  Optional<LoginStore.Login> admitAgent(
      String project, String workspace, PtyMessage.Agent agent, PtyIdentity.Person who)
      throws IOException;

  /** {@link PtyAdmission#handOver}. */
  void handOver(String project, String workspace, String file, String lines, PtyIdentity.Person who)
      throws IOException;

  /** {@link PtyAdmission#withdraw}. */
  void withdraw(String instance, String file);

  void admitRoom(String room, String project, PtyIdentity.Person who) throws IOException;

  void recordEvent(EventStore.EventRow event);

  /** Records that {@code person} typed in a terminal of {@code room} this minute. */
  void recordTyping(String person, String room);
}
