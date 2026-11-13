/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * The pty host's door to the control plane: whom a connection stands for, and where it may open a
 * session, each read afresh through this box's operations.
 *
 * <p>A gateway-minted session token resolves through the sessions table to its FDE; a blank token
 * is the box's own person (its sync handle). A ticket's credential, named by its hash, resolves to
 * the FDE of a login session or an API token. A terminal opens only on a box its caller operates.
 * Never a client claim: the token is validated, the box's person read from this box's own
 * configuration, and the role and scope from the FDE roster.
 *
 * <p>A project must be one the caller holds on this box: its catalog holds it and the caller's
 * scope admits it, whatever the caller's role. A room must sit in the session's project, and the
 * caller must be allowed to post in the room's conversation ({@link Access}, {@link Action#POST}),
 * exactly as a room message is. Only then may the session run in the project's container, or the
 * room id enter the child's environment or the room's event history.
 */
final class PtyHostAccess implements PtyIdentity.Resolver, PtyIdentity.Holders, PtyAdmission {

  private final Supplier<HostOperations> operations;

  /** The door over the operations {@code operations} opens for each question. */
  PtyHostAccess(Supplier<HostOperations> operations) {
    this.operations = operations;
  }

  /** The door over this box's control plane. */
  static PtyHostAccess here() {
    return new PtyHostAccess(() -> OperationsFactory.open(SailPaths.controlPlaneDb()));
  }

  @Override
  public PtyIdentity.Person resolve(String token) throws IOException {
    try (var opened = operations.get()) {
      return opened.pty().identity(token);
    }
  }

  @Override
  public PtyIdentity.Person holder(String credentialHash) throws IOException {
    try (var opened = operations.get()) {
      return opened.pty().holder(credentialHash);
    }
  }

  @Override
  public void box(PtyIdentity.Person who) throws IOException {
    try (var opened = operations.get()) {
      opened.pty().admitBox(who);
    }
  }

  @Override
  public void project(String project, PtyIdentity.Person who) throws IOException {
    try (var opened = operations.get()) {
      opened.pty().admitProject(project, who);
    }
  }

  @Override
  public void instance(String project) throws IOException {
    try (var opened = operations.get()) {
      opened.pty().admitInstance(project);
    }
  }

  @Override
  public void room(String roomId, String project, PtyIdentity.Person who) throws IOException {
    try (var opened = operations.get()) {
      opened.pty().admitRoom(roomId, project, who);
    }
  }
}
