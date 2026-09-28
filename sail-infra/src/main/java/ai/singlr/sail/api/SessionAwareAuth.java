/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import com.sun.net.httpserver.HttpExchange;
import java.util.Objects;

/**
 * Accepts two kinds of bearer credential. A token prefixed {@code sess_} is a login session minted
 * by the passkey/OIDC flow: it is resolved against the {@link AuthSessionStore}, mapped to its FDE,
 * and the role {@link RoleRule} gives that FDE is stamped on the exchange so {@link Authorizer}
 * governs it exactly like a token's role; a disabled FDE's session is refused. Anything else is
 * delegated to the wrapped {@link ApiAuth} (the machine/CI {@code api_tokens} path). The exchange
 * attributes ({@code token.name}, {@code token.fde}, {@code token.role}) are identical in shape
 * across both paths, so every downstream consumer — attribution, authorization, {@code --assignee
 * me} — is unaffected by which credential authenticated the call.
 */
public final class SessionAwareAuth implements ApiAuth {

  private static final String BEARER = "Bearer ";
  private static final String SESSION_PREFIX = "sess_";

  private final AuthSessionStore sessions;
  private final FdeStore roster;
  private final RoleRule roles;
  private final ApiAuth tokenAuth;

  public SessionAwareAuth(
      AuthSessionStore sessions, FdeStore roster, RoleRule roles, ApiAuth tokenAuth) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.roster = Objects.requireNonNull(roster, "roster");
    this.roles = Objects.requireNonNull(roles, "roles");
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
            .flatMap(session -> roster.byId(session.fdeId()))
            .orElseThrow(SessionAwareAuth::invalid);
    var role = roles.roleOf(fde.handle(), Role.ADMIN).orElseThrow(SessionAwareAuth::invalid);
    exchange.setAttribute("token.name", fde.handle());
    exchange.setAttribute("token.fde", fde.handle());
    exchange.setAttribute("token.role", role.attribute());
    if (fde.displayName() != null) {
      exchange.setAttribute("token.displayName", fde.displayName());
    }
    if (fde.email() != null) {
      exchange.setAttribute("token.email", fde.email());
    }
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.INVALID_BEARER_TOKEN, "Session token is invalid or expired.");
  }
}
