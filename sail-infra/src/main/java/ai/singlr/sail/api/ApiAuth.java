/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import com.sun.net.httpserver.HttpExchange;

@FunctionalInterface
public interface ApiAuth {

  /**
   * The exchange attribute holding the caller's {@link ai.singlr.sail.identity.ProjectScope}, set
   * beside {@code token.fde} and {@code token.role} for a credential that names an FDE.
   */
  String SCOPE = "token.scope";

  void require(HttpExchange exchange);
}
