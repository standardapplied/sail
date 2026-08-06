/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the contexts that used to sit outside the limiter: the passkey ceremony endpoints, the
 * ceremony pages, and SSE connection establishment. Each server runs with a one-token budget so a
 * "burst" is two requests rather than six hundred.
 */
class RateLimitGateTest {

  private static final ApiAuth AUTH = new FixedTokenTestAuth("tok");

  private static RateLimitGate oneRequest() {
    return new RateLimitGate(new RateLimiter(1, 0d, () -> 0L));
  }

  @Test
  void ceremonyBurstIsThrottledWhileHealthStaysAvailable(@TempDir Path tmp) throws Exception {
    var passkeys = new WebauthnAuthHandler(null, null, AUTH, null);
    try (var server = server(tmp, passkeys, null, oneRequest())) {
      server.start();

      var first = post(server, "/v1/auth/login/start");
      assertNotEquals(429, first.statusCode());

      var throttled = post(server, "/v1/auth/login/start");
      assertEquals(429, throttled.statusCode());
      assertEquals(
          "application/json; charset=utf-8",
          throttled.headers().firstValue("Content-Type").orElseThrow());
      var error = (Map<?, ?>) YamlUtil.parseMap(throttled.body()).get("error");
      assertEquals("rate_limited", error.get("code"));
      assertTrue(error.get("action").toString().contains("requests per minute"));

      assertEquals(429, get(server, "/login").statusCode());
      assertEquals(429, get(server, "/enroll").statusCode());
      assertEquals(200, get(server, "/v1/health").statusCode());
    }
  }

  @Test
  void throttledRequestNeverReachesTheHandler(@TempDir Path tmp) throws Exception {
    var reached = new AtomicInteger();
    HttpHandler passkeys =
        exchange -> {
          reached.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        };
    try (var server = server(tmp, passkeys, null, oneRequest())) {
      server.start();

      assertEquals(204, post(server, "/v1/auth/login/finish").statusCode());
      assertEquals(429, post(server, "/v1/auth/login/finish").statusCode());
      assertEquals(1, reached.get());
    }
  }

  @Test
  void establishedStreamIsNotMeteredPerEvent(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus();
        var server = server(tmp, null, bus, oneRequest())) {
      server.start();

      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      var stream =
          client.send(streamRequest(server).build(), HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, stream.statusCode());

      var second = client.send(streamRequest(server).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(429, second.statusCode());

      var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
      var delivered = new CountDownLatch(1);
      var frame = new String[1];
      var readerThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      String line;
                      while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                          frame[0] = line;
                          delivered.countDown();
                          return;
                        }
                      }
                    } catch (Exception ignored) {
                      delivered.countDown();
                    }
                  });

      bus.publish(Event.of("light", null, "spec_dispatched", "sail", "h"));
      BusTesting.awaitDelivery(delivered);
      assertTrue(
          frame[0].contains("spec_dispatched"), "stream should keep delivering: " + frame[0]);

      readerThread.interrupt();
      hangUp(stream, client, bus);
    }
  }

  /**
   * Drops the client end of an SSE stream and publishes into the closed socket, so the server's
   * stream loop discovers the disconnect on its next write instead of at the 15s heartbeat — which
   * would hold the server's executor open for that long on close.
   */
  private static void hangUp(HttpResponse<InputStream> stream, HttpClient client, EventBus bus)
      throws Exception {
    stream.body().close();
    client.close();
    bus.publish(Event.of("light", null, "spec_dispatched", "sail", "h"));
    bus.publish(Event.of("light", null, "spec_dispatched", "sail", "h"));
  }

  @Test
  void gateRejectsANullLimiterAndNullHandler() {
    assertThrows(NullPointerException.class, () -> new RateLimitGate(null));
    assertThrows(NullPointerException.class, () -> new RateLimitGate().wrap(null));
  }

  private static SailApiServer server(
      Path tmp, HttpHandler passkeys, EventBus bus, RateLimitGate gate) throws Exception {
    return new SailApiServer(
        "127.0.0.1",
        0,
        new TestOperations(),
        AUTH,
        bus,
        null,
        tmp.resolve("api.sock"),
        passkeys,
        null,
        null,
        gate);
  }

  private static HttpRequest.Builder streamRequest(SailApiServer server) {
    return HttpRequest.newBuilder(uri(server, "/v1/events/stream"))
        .header("Authorization", "Bearer tok")
        .header("Accept", "text/event-stream")
        .timeout(Duration.ofSeconds(10))
        .GET();
  }

  private static HttpResponse<String> post(SailApiServer server, String path) throws Exception {
    return send(
        HttpRequest.newBuilder(uri(server, path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")));
  }

  private static HttpResponse<String> get(SailApiServer server, String path) throws Exception {
    return send(HttpRequest.newBuilder(uri(server, path)).GET());
  }

  private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(SailApiServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }
}
