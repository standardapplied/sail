/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;

/**
 * The authenticated principal behind a pty-host connection. Identity is resolved from a verifiable
 * credential by the host's {@link Resolver} — never from anything the client claims: a session
 * token minted by the gateway, or the box's ambient owner when the connection carries none. The one
 * non-FDE principal is {@link #DISPATCH}: the host's own dispatch authority, admitted by the
 * credential the host mints at start, allowed exactly one verb — yielding a session a reservation
 * displaced, whoever opened it — and nothing an FDE may do.
 */
public record PtyIdentity(String fde, boolean admin, boolean dispatchAuthority) {

  public static final PtyIdentity DISPATCH = new PtyIdentity("dispatch", false, true);

  public PtyIdentity(String fde, boolean admin) {
    this(fde, admin, false);
  }

  public interface Resolver {
    PtyIdentity resolve(String token) throws IOException;
  }
}
