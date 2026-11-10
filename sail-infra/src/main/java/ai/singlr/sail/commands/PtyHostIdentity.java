/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyIdentity;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * The production {@link PtyIdentity.Resolver}: a gateway-minted session token resolves through the
 * control-plane sessions table to its FDE; a blank token is the box's own person (its sync handle).
 * Never a client claim — the token is validated, the box's person read from this box's own
 * configuration, and the role and scope from the FDE roster.
 */
final class PtyHostIdentity implements PtyIdentity.Resolver {

  private final Supplier<HostOperations> operations;

  PtyHostIdentity() {
    this(() -> OperationsFactory.open(SailPaths.controlPlaneDb()));
  }

  PtyHostIdentity(Supplier<HostOperations> operations) {
    this.operations = operations;
  }

  @Override
  public PtyIdentity.Person resolve(String token) throws IOException {
    try (var opened = operations.get()) {
      return opened.pty().identity(token);
    }
  }
}
