/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import com.sun.net.httpserver.HttpExchange;
import java.util.Objects;

/**
 * Accepts two kinds of bearer credential. A token prefixed {@code sess_} is a login session minted
 * by the passkey/OIDC flow: it is resolved against the {@link AuthSessionStore}, mapped to its FDE,
 * and the FDE's role is stamped on the exchange so {@link Authorizer} governs it exactly like a
 * token's role. Anything else is delegated to the wrapped {@link ApiAuth} (the machine/CI {@code
 * api_tokens} path). The exchange attributes ({@code token.name}, {@code token.fde}, {@code
 * token.role}) are identical in shape across both paths, so every downstream consumer —
 * attribution, authorization, {@code --assignee me} — is unaffected by which credential
 * authenticated the call.
 */
public final class SessionAwareAuth implements ApiAuth {

  private static final String BEARER = "Bearer ";
  private static final String SESSION_PREFIX = "sess_";

  private final AuthSessionStore sessions;
  private final FdeStore fdes;
  private final ApiAuth tokenAuth;

  public SessionAwareAuth(AuthSessionStore sessions, FdeStore fdes, ApiAuth tokenAuth) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.tokenAuth = Objects.requireNonNull(tokenAuth, "tokenAuth");
  }

  @Override
  public void require(HttpExchange exchange) {
    var headers = exchange.getRequestHeaders().get("Authorization");
    if (headers != null && headers.size() == 1) {
      var header = headers.getFirst();
      if (header.startsWith(BEARER + SESSION_PREFIX)) {
        authenticateSession(exchange, header.substring(BEARER.length()));
        return;
      }
    }
    tokenAuth.require(exchange);
  }

  private void authenticateSession(HttpExchange exchange, String token) {
    var fde =
        sessions
            .validate(token)
            .flatMap(session -> fdes.byId(session.fdeId()))
            .filter(f -> "active".equals(f.status()))
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.INVALID_BEARER_TOKEN, "Session token is invalid or expired."));
    exchange.setAttribute("token.name", fde.handle());
    exchange.setAttribute("token.fde", fde.handle());
    exchange.setAttribute("token.role", fde.role());
  }
}
