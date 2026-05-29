/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import com.sun.net.httpserver.HttpExchange;
import java.util.Locale;

/**
 * Enforces authorization at the API boundary. {@link ApiAuth} authenticates the request and stamps
 * the principal's role on the exchange ({@code token.role}); this checks that role against the
 * {@link Capability} a route requires, throwing {@link ApiException} with {@link
 * ErrorCode#FORBIDDEN} otherwise.
 *
 * <p>The capability is derived from the HTTP method ({@link #capabilityFor}): safe methods need
 * {@code READ}, mutating methods need {@code WRITE}. Routes that administer the control plane will
 * call {@link #require} with {@link Capability#ADMIN} explicitly as they are added.
 */
public final class Authorizer {

  private Authorizer() {}

  /** Maps an HTTP method to the capability a request using it requires. */
  public static Capability capabilityFor(String method) {
    return "GET".equals(method) || "HEAD".equals(method) ? Capability.READ : Capability.WRITE;
  }

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
