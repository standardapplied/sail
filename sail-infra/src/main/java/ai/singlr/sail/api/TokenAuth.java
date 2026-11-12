/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.identity.RoleRule.Credential;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpExchange;
import java.util.Objects;

/**
 * Validates bearer tokens against the SQLite-backed {@link TokenStore} and stamps the principal
 * {@link RoleRule} resolves for the token on the exchange. A token bound to an FDE acts as that
 * FDE, capped by the token's minted role, and is refused when the FDE is disabled. The host CLI's
 * token ({@link HostToken}) acts as the box's person, whichever FDE the box's sync handle names
 * when it is used. Any other token that names no FDE is a machine token: it acts as no one, with
 * the role and scope of the box's FDE. On a node that cannot place its own FDE — its roster does
 * not know it yet, has disabled it, or the node names none — a token naming no FDE is refused as a
 * conflict to resolve ({@link CliOperator#unplaced}), not as a bad credential. Authentication only:
 * the route's gate and the operation decide what the principal may do. The loopback bind is an
 * additional network boundary, not a substitute for either.
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
    var info =
        tokenStore
            .validate(token)
            .orElseThrow(
                () -> new ApiException(ErrorCode.INVALID_BEARER_TOKEN, "Bearer token is invalid."));
    exchange.setAttribute("token.name", info.name());
    exchange.setAttribute(ApiAuth.CREDENTIAL, token);
    exchange.setAttribute(
        ApiAuth.ACTOR, roles.principal(credential(info)).orElseThrow(() -> refused(info)));
  }

  /**
   * Whom {@code token} names: its FDE, the box's person for the host token, or no one. The terminal
   * door reads an API token through this too ({@code HostAccess}).
   */
  public static Credential credential(TokenStore.TokenInfo token) {
    if (token.fdeHandle() != null) {
      return Credential.token(token.fdeHandle(), token.role());
    }
    return HostToken.NAME.equals(token.name())
        ? Credential.hostToken(token.role())
        : Credential.machine(token.role());
  }

  /**
   * Why {@code token}'s principal is refused: its FDE is disabled, or the box cannot place its own.
   */
  private ApiException refused(TokenStore.TokenInfo token) {
    var fde = token.fdeHandle();
    return fde != null
        ? new ApiException(
            ErrorCode.INVALID_BEARER_TOKEN,
            "Bearer token's FDE '" + fde + "' is disabled.",
            "Ask an admin to re-enable '" + fde + "'.")
        : CliOperator.unplaced(roles.boxFde().orElse(null));
  }
}
