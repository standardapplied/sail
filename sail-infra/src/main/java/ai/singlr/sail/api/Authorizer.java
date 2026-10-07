/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Capability;
import ai.singlr.sail.identity.Role;
import com.sun.net.httpserver.HttpExchange;
import java.util.Locale;

/**
 * Enforces a route's tier at the API boundary. {@link ApiAuth} authenticates the request and stamps
 * the principal's role on the exchange ({@code token.role}); this checks that role against the
 * {@link Capability} a route requires, throwing {@link ApiException} with {@link
 * ErrorCode#FORBIDDEN} otherwise.
 *
 * <p>Every route needs {@code READ}, and a route that administers the control plane needs {@link
 * Capability#ADMIN}. No route is tiered for {@code WRITE}: who may write a row is its type's rule,
 * decided as the journal records the revision, and an operation that changes this box without
 * writing one admits itself, so a read-only credential is refused with one refusal at every door.
 */
public final class Authorizer {

  private Authorizer() {}

  /** Throws {@link ErrorCode#FORBIDDEN} if the request's role does not grant {@code capability}. */
  public static void require(HttpExchange exchange, Capability capability) {
    var role = Role.fromAttribute(exchange.getAttribute("token.role"));
    if (!role.allows(capability)) {
      throw new ApiException(
          ErrorCode.FORBIDDEN,
          "Your role is not permitted to perform this action.",
          "This credential's role lacks the '"
              + capability.name().toLowerCase(Locale.ROOT)
              + "' capability.");
    }
  }
}
