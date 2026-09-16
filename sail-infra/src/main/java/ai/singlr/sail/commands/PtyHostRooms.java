/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SpecPolicy;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyRooms;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The production {@link PtyRooms}: the room is read from the control plane, must sit in the
 * session's project, and the caller — the authenticated FDE, with the role the roster gives them,
 * never a client claim — must pass the room's post right ({@link SpecPolicy#post}), exactly the
 * gate a room message passes. Only then may the room id enter the child's environment or the room's
 * event history.
 */
final class PtyHostRooms implements PtyRooms {

  private final Path dbPath;

  PtyHostRooms() {
    this(SailPaths.controlPlaneDb());
  }

  PtyHostRooms(Path dbPath) {
    this.dbPath = dbPath;
  }

  @Override
  public void admit(String roomId, String project, PtyIdentity who) throws IOException {
    try (var operations = OperationsFactory.open(dbPath)) {
      operations.pty().admitRoom(roomId, project, who);
    }
  }
}
