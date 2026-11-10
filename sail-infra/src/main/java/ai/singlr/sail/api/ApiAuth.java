/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import com.sun.net.httpserver.HttpExchange;

/**
 * Authenticates a request: finds whom its credential names and asks {@code RoleRule} for the
 * principal it stands for, stamped on the exchange as {@link #ACTOR}, or refuses it. It names no
 * role and decides nothing; the route's gate and the operation do.
 */
@FunctionalInterface
public interface ApiAuth {

  /** The exchange attribute holding the caller's {@link ai.singlr.sail.identity.Actor}. */
  String ACTOR = "token.actor";

  void require(HttpExchange exchange);
}
