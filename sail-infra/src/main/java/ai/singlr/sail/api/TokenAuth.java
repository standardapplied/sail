/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpExchange;
import java.util.Objects;

/**
 * Validates bearer tokens against the SQLite-backed {@link TokenStore} and stamps the token's
 * identity and role on the exchange. A token bound to an FDE acts as that FDE, with the role {@link
 * RoleRule} gives it capped by the token's minted role, and is refused when the FDE is disabled.
 * The host CLI's token ({@link HostToken}) names no FDE: it acts as the box's FDE, whichever the
 * box's sync handle names when it is used. Any other token that names no FDE is a machine token: it
 * acts as no FDE, with the role of the box's FDE ({@link RoleRule#roleOfUnbound}). On a node that
 * cannot place its own FDE — its roster does not know it yet, has disabled it, or the node names
 * none — such a token is refused as a conflict to resolve ({@link CliOperator#unplaced}), not as a
 * bad credential. Authentication only — {@link Authorizer} is the authorization tier and enforces
 * {@code token.role} per route (it runs immediately after this in {@code ApiRouter}). The loopback
 * bind is an additional network boundary, not a substitute for the role check.
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
    var fde = fdeOf(info);
    exchange.setAttribute("token.name", info.name());
    exchange.setAttribute("token.role", roleOf(info, fde).attribute());
    if (fde != null) {
      exchange.setAttribute("token.fde", fde);
    }
  }

  /** The FDE {@code token} acts as: the one it is bound to, or the box's for the host token. */
  private String fdeOf(TokenStore.TokenInfo token) {
    if (token.fdeHandle() != null) {
      return token.fdeHandle();
    }
    return HostToken.NAME.equals(token.name()) ? roles.boxFde().orElse(null) : null;
  }

  private Role roleOf(TokenStore.TokenInfo token, String fde) {
    var minted = Role.fromAttribute(token.role());
    if (token.fdeHandle() != null) {
      return roles
          .roleOf(fde, minted)
          .orElseThrow(
              () ->
                  new ApiException(
                      ErrorCode.INVALID_BEARER_TOKEN,
                      "Bearer token's FDE '" + fde + "' is disabled.",
                      "Ask an admin to re-enable '" + fde + "'."));
    }
    return roles
        .roleOfUnbound(minted)
        .orElseThrow(() -> CliOperator.unplaced(roles.boxFde().orElse(null)));
  }
}
