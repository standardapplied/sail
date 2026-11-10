/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The production {@link PtyAdmission}, read from the control plane, the caller always the
 * authenticated person the host resolved — with the role and scope the roster gives them, never a
 * client claim. A project must be one the caller holds on this box: its catalog holds it and the
 * caller's scope admits it, whatever the caller's role. A room must sit in the session's project,
 * and the caller must be allowed to post in the room's conversation ({@link Access}, {@link
 * Action#POST}), exactly as a room message is. Only then may the session run in the project's
 * container, or the room id enter the child's environment or the room's event history.
 */
final class PtyHostAdmission implements PtyAdmission {

  private final Path dbPath;

  PtyHostAdmission() {
    this(SailPaths.controlPlaneDb());
  }

  PtyHostAdmission(Path dbPath) {
    this.dbPath = dbPath;
  }

  @Override
  public void project(String project, PtyIdentity who) throws IOException {
    try (var operations = OperationsFactory.open(dbPath)) {
      operations.pty().admitProject(project, who);
    }
  }

  @Override
  public void room(String roomId, String project, PtyIdentity who) throws IOException {
    try (var operations = OperationsFactory.open(dbPath)) {
      operations.pty().admitRoom(roomId, project, who);
    }
  }
}
