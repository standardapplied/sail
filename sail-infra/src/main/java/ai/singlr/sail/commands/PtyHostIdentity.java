/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.FdeStore;
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

  PtyHostIdentity() {
    this(HostSync::handle, SailPaths.controlPlaneDb());
  }

  PtyHostIdentity(Supplier<String> boxHandle, Path dbPath) {
    this.boxHandle = boxHandle;
    this.dbPath = dbPath;
  }

  @Override
  public PtyIdentity resolve(String token) throws IOException {
    try (var operations = OperationsFactory.open(dbPath)) {
      return operations.pty().identity(token, boxHandle.get());
    }
  }

  private static boolean isAdmin(FdeStore fdes, String handle) {
    return fdes.byHandle(handle).map(fde -> "admin".equals(fde.role())).orElse(false);
  }
}
