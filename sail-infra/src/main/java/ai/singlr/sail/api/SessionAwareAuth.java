/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.AuthSessionStore;
import com.sun.net.httpserver.HttpExchange;
import java.util.Objects;

/**
 * Accepts two kinds of bearer credential. A token prefixed {@code sess_} is a login session minted
 * by the passkey flow or the SSH gateway: it is validated against the {@link AuthSessionStore} and
 * the principal {@link RoleRule} resolves for its FDE is stamped on the exchange, with the FDE's
 * name and email for {@code whoami}; a session whose FDE is gone or disabled is refused. Anything
 * else is delegated to the wrapped {@link ApiAuth} (the machine/CI {@code api_tokens} path). Both
 * stamp the same principal shape, so every downstream consumer — attribution, the route's gate,
 * {@code --assignee me} — is unaffected by which credential authenticated the call.
 */
public final class SessionAwareAuth implements ApiAuth {

  private static final String BEARER = "Bearer ";

  /** The prefix of every login session token. */
  public static final String SESSION_PREFIX = "sess_";

  private final AuthSessionStore sessions;
  private final RoleRule roles;
  private final ApiAuth tokenAuth;

  public SessionAwareAuth(AuthSessionStore sessions, RoleRule roles, ApiAuth tokenAuth) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
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
    var session =
        roles.session(sessions, token, Actor.Lane.API).orElseThrow(SessionAwareAuth::invalid);
    var fde = session.fde();
    var actor = session.principal().orElseThrow(SessionAwareAuth::invalid);
    exchange.setAttribute("token.name", actor.handle());
    exchange.setAttribute(ApiAuth.ACTOR, actor);
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
