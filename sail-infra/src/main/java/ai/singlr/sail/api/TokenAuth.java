/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpExchange;
import java.util.Objects;

/**
 * Validates bearer tokens against the SQLite-backed {@link TokenStore} and stamps the token's
 * identity and role on the exchange. A token bound to an FDE acts with the role {@link RoleRule}
 * gives that FDE, capped by the token's minted role, and is refused when the FDE is disabled; a
 * machine token that names no FDE acts with its minted role. Authentication only — {@link
 * Authorizer} is the authorization tier and enforces {@code token.role} per route (it runs
 * immediately after this in {@code ApiRouter}). The loopback bind is an additional network
 * boundary, not a substitute for the role check.
 */
public final class TokenAuth implements ApiAuth {

  private final TokenStore tokenStore;
  private final RoleRule roles;

  public TokenAuth(TokenStore tokenStore, RoleRule roles) {
    this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
    this.roles = Objects.requireNonNull(roles, "roles");
  }

  public void require(HttpExchange exchange) {
    var headers = exchange.getRequestHeaders().get("Authorization");
    if (headers != null && headers.size() > 1) {
      throw new ApiException(ErrorCode.INVALID_BEARER_TOKEN, "Bearer token is invalid.");
    }
    var header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      throw new ApiException(
          ErrorCode.MISSING_BEARER_TOKEN,
          "Missing bearer token.",
          "Send Authorization: Bearer <token>.");
    }
    var token = header.substring("Bearer ".length());
    var info = tokenStore.validate(token);
    if (info.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_BEARER_TOKEN, "Bearer token is invalid.");
    }
    var role = roleOf(info.get());
    exchange.setAttribute("token.name", info.get().name());
    exchange.setAttribute("token.role", role.attribute());
    if (info.get().fdeHandle() != null) {
      exchange.setAttribute("token.fde", info.get().fdeHandle());
    }
  }

  private Role roleOf(TokenStore.TokenInfo token) {
    var minted = Role.fromAttribute(token.role());
    if (token.fdeHandle() == null) {
      return minted;
    }
    return roles
        .roleOf(token.fdeHandle(), minted)
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.INVALID_BEARER_TOKEN,
                    "Bearer token's FDE '" + token.fdeHandle() + "' is disabled.",
                    "Ask an admin to re-enable '" + token.fdeHandle() + "'."));
  }
}
