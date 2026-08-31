/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtySessionHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The production {@link SessionYield}: the box's own sail process speaks to its pty session host as
 * the host's dispatch authority — the credential the host minted at start, owner-only beside the
 * socket — and yields the named sessions one by one, whichever FDE opened them. No socket means no
 * host and therefore no sessions: nothing to end, not an error. A socket with no credential beside
 * it is a host too old to be told — that is an error, reported. Every name is attempted before any
 * failure is raised, so one refusal never leaves a later displaced session alive. The claim lock
 * lives beside the control-plane database, the one place every sail process on the box already
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
    var failures = new ArrayList<String>();
    try (var client = SessionClient.connect(socket, dispatchCredential())) {
      for (var name : sessions) {
        try {
          client.yieldSession(name, reason);
        } catch (IOException refused) {
          failures.add(name + ": " + refused.getMessage());
        }
      }
    }
    if (!failures.isEmpty()) {
      throw new IOException(String.join("; ", failures));
    }
  }

  private String dispatchCredential() throws IOException {
    var path = PtySessionHost.dispatchCredentialOf(socket);
    try {
      return Files.readString(path).strip();
    } catch (NoSuchFileException missing) {
      throw new IOException(
          "The session host at "
              + socket
              + " minted no dispatch credential ("
              + path
              + "); restart the sail-pty-host service on this sail version.",
          missing);
    }
  }
}
