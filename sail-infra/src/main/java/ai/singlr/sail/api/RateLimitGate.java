/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * The one rate-limit budget in front of every TCP context. {@link SailApiServer} owns a single gate
 * and applies it to the API router, the passkey ceremony endpoints, the login/enroll pages, and SSE
 * connection establishment, so no HTTP surface is unthrottled — the unauthenticated ceremony routes
 * ({@code /v1/auth/login/start|finish}, {@code /v1/auth/register/finish}) most of all, since an
 * attacker reaches them with no credential at all.
 *
 * <p>Authenticated requests are keyed by credential: the router charges the budget after {@link
 * ApiAuth#require}, so a token's identity — not its network path — bounds it. Everything reached
 * before authentication is keyed by remote address, the only identity such a caller has. The
 * tradeoff is that clients sharing a NAT share one anonymous budget; that is the correct bias for a
 * pre-auth surface, where an over-wide key lets a brute-forcer split its attack across identities
 * it can mint for free.
 *
 * <p>The gate meters requests, not bytes or time: an SSE stream is charged once when it is
 * established and never again, so a long-lived subscriber is unaffected. The Unix-socket lane
 * ({@link LocalApiSocket}) is deliberately exempt — it is credential-gated and already bounded by
 * its in-flight cap.
 */
public final class RateLimitGate {

  static final int DEFAULT_PERMITS_PER_MINUTE = 600;
  static final int DEFAULT_BURST = 600;

  private final RateLimiter limiter;

  /** A gate with the default budget: {@value #DEFAULT_PERMITS_PER_MINUTE} requests per minute. */
  public RateLimitGate() {
    this(RateLimiter.perMinute(DEFAULT_PERMITS_PER_MINUTE, DEFAULT_BURST));
  }

  public RateLimitGate(RateLimiter limiter) {
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  /** Takes one token for the caller; throws {@code 429} when their budget is exhausted. */
  public void require(HttpExchange exchange) {
    if (!limiter.tryAcquire(keyOf(exchange))) {
      throw new ApiException(
          ErrorCode.RATE_LIMITED,
          "Rate limit exceeded. Slow down and retry shortly.",
          "This client exceeded " + DEFAULT_PERMITS_PER_MINUTE + " requests per minute.");
    }
  }

  /**
   * Wraps {@code delegate} so the budget is charged before dispatch. An over-budget caller gets the
   * router's problem shape — the same status, media type, and JSON error body — without the
   * delegate ever seeing the request.
   */
  public HttpHandler wrap(HttpHandler delegate) {
    Objects.requireNonNull(delegate, "delegate");
    return exchange -> {
      try {
        require(exchange);
      } catch (ApiException e) {
        try {
          writeError(exchange, e);
        } finally {
          exchange.close();
        }
        return;
      }
      delegate.handle(exchange);
    };
  }

  private static String keyOf(HttpExchange exchange) {
    var credential = exchange.getAttribute("token.name");
    if (credential != null) {
      return "credential:" + credential;
    }
    var remote = exchange.getRemoteAddress();
    return remote == null ? "address:unknown" : "address:" + remote.getHostString();
  }

  private static void writeError(HttpExchange exchange, ApiException error) throws IOException {
    var response = ApiResponse.error(error.failure());
    var body =
        YamlUtil.dumpJson(new LinkedHashMap<>(response.body())).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(response.status(), body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }
}
