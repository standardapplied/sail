/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
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
 * before authentication is keyed by remote address (IPv6 grouped by /64, since one host commonly
 * holds a whole /64), the only identity such a caller has. The tradeoff is that clients sharing a
 * NAT or a /64 share one anonymous budget; that is the correct bias for a pre-auth surface, where
 * an over-wide key lets a brute-forcer split its attack across identities it can mint for free.
 * {@link RateLimiter} caps how many keys it will hold, so an attacker cycling addresses cannot grow
 * the bucket map without bound either.
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
    return "address:" + addressKey(exchange.getRemoteAddress());
  }

  /**
   * The budget key for an unauthenticated caller. IPv6 callers are grouped by their /64, because a
   * single host is routinely handed a whole /64 — keying on the full address would let one attacker
   * mint a fresh budget per request by changing source address, which is no throttle at all. IPv4
   * (including its v6-mapped form) is keyed exactly: addresses there are scarce enough that one
   * host cannot rotate through them.
   */
  private static String addressKey(InetSocketAddress remote) {
    var address = remote == null ? null : remote.getAddress();
    if (address == null) {
      return "unknown";
    }
    var bytes = address.getAddress();
    if (!(address instanceof Inet6Address) || isMappedIpv4(bytes)) {
      return address.getHostAddress();
    }
    return HexFormat.ofDelimiter(":").formatHex(bytes, 0, 8) + "::/64";
  }

  private static boolean isMappedIpv4(byte[] bytes) {
    for (var i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return (bytes[10] == 0 && bytes[11] == 0)
        || (bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff);
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
