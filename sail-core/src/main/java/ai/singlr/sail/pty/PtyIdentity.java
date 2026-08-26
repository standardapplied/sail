/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;

/**
 * The authenticated FDE behind a pty-host connection. Identity is resolved from a verifiable
 * credential by the host's {@link Resolver} — never from anything the client claims: a session
 * token minted by the gateway, or the box's ambient owner when the connection carries none.
 */
public record PtyIdentity(String fde, boolean admin) {

  public interface Resolver {
    PtyIdentity resolve(String token) throws IOException;
  }
}
