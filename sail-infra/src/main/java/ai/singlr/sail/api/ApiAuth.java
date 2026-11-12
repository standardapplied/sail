/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Actor;
import com.sun.net.httpserver.HttpExchange;

/**
 * Authenticates a request: finds whom its credential names and asks {@code RoleRule} for the
 * principal it stands for, stamped on the exchange as {@link #ACTOR}, or refuses it. It names no
 * role and decides nothing; the route's gate and the operation do.
 */
@FunctionalInterface
public interface ApiAuth {

  /** The exchange attribute holding the caller's {@link Actor}. */
  String ACTOR = "token.actor";

  /**
   * The exchange attribute holding the bearer credential the caller was authenticated by, which a
   * terminal ticket is issued for.
   */
  String CREDENTIAL = "token.credential";

  void require(HttpExchange exchange);

  /** The caller {@link #require} stamped on {@code exchange}; a door that stamped none fails. */
  static Actor actorOf(HttpExchange exchange) {
    if (exchange.getAttribute(ACTOR) instanceof Actor actor) {
      return actor;
    }
    throw new IllegalStateException("No credential was resolved for this request.");
  }

  /** The bearer credential {@link #require} authenticated {@code exchange} by. */
  static String credentialOf(HttpExchange exchange) {
    if (exchange.getAttribute(CREDENTIAL) instanceof String credential) {
      return credential;
    }
    throw new IllegalStateException("No credential was resolved for this request.");
  }
}
