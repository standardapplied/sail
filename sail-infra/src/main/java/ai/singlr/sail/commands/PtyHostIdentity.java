/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyIdentity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The production {@link PtyIdentity.Resolver}: a gateway-minted session token resolves through the
 * control-plane sessions table to its FDE; a blank token is the box's ambient owner (the sync
 * handle). Never a client claim — the token is validated, the handle read from this box's own
 * configuration, and the admin bit from the FDE roster.
 */
final class PtyHostIdentity implements PtyIdentity.Resolver {

  private final Supplier<String> boxHandle;
  private final Path dbPath;
  private final OperatorCommand operator;

  PtyHostIdentity(OperatorCommand operator) {
    this(HostSync::handle, SailPaths.controlPlaneDb(), operator);
  }

  PtyHostIdentity(Supplier<String> boxHandle, Path dbPath, OperatorCommand operator) {
    this.boxHandle = boxHandle;
    this.dbPath = dbPath;
    this.operator = operator;
  }

  @Override
  public PtyIdentity resolve(String token) throws IOException {
    try (var operations = OperationsFactory.open(dbPath, operator)) {
      return operations.pty().identity(token, boxHandle.get());
    }
  }
}
