/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Test-only {@link ApiAuth} that accepts a single fixed bearer token via constant-time comparison.
 * Production servers use {@link TokenAuth} backed by SQLite. Tests of {@link SseHandler} or {@link
 * ApiRouter} that don't care about token rotation can use this to avoid spinning up a SQLite store.
 */
final class FixedTokenTestAuth implements ApiAuth {

  private final byte[] expected;

  FixedTokenTestAuth(String token) {
    this.expected = Objects.requireNonNull(token, "token").getBytes(StandardCharsets.UTF_8);
  }

  @Override
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
    var actual = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new ApiException(ErrorCode.INVALID_BEARER_TOKEN, "Bearer token is invalid.");
    }
    exchange.setAttribute("token.name", "admin");
    exchange.setAttribute("token.role", "admin");
  }
}
