/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The production {@link SessionYield}: the box's own API process speaks to its pty session host
 * over the socket as the box's ambient owner — a blank token, the same identity a local {@code sail
 * session} verb carries — lists what is live, and yields the named sessions one by one. No socket
 * means no host and therefore no sessions: nothing to end, not an error. A refused yield (a session
 * another FDE owns on this box) surfaces as the exception the reservation warns about. The claim
 * lock lives beside the control-plane database, the one place every sail process on the box already
 * reaches.
 */
final class PtyHostYield implements SessionYield {

  private final Path socket;
  private final Path lockDir;

  PtyHostYield() {
    this(SailPaths.ptySocketPath());
  }

  PtyHostYield(Path socket) {
    this(socket, SailPaths.dataDir().resolve("locks"));
  }

  PtyHostYield(Path socket, Path lockDir) {
    this.socket = socket;
    this.lockDir = lockDir;
  }

  @Override
  public Hold lock(String project) throws IOException {
    return SessionDispatchLock.acquire(lockDir, project);
  }

  @Override
  public void end(List<String> sessions, String reason) throws IOException {
    if (!Files.exists(socket)) {
      return;
    }
    try (var client = SessionClient.connect(socket, "")) {
      for (var name : sessions) {
        client.yieldSession(name, reason);
      }
    }
  }
}
