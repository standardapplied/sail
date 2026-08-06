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
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
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

  @Test
  void oneIpv6HostCannotMintAFreshBudgetPerSourceAddress() {
    var gate = oneRequest();

    gate.require(from("2001:db8:1:1::1"));

    assertThrows(
        ApiException.class,
        () -> gate.require(from("2001:db8:1:1::2")),
        "a second address in the same /64 draws on the same budget");
  }

  @Test
  void separateIpv6PrefixesKeepSeparateBudgets() {
    var gate = oneRequest();

    gate.require(from("2001:db8:1:1::1"));
    gate.require(from("2001:db8:1:2::1"));
  }

  @Test
  void ipv4CallersAreKeyedExactlyIncludingTheirV6MappedForm() {
    var gate = oneRequest();

    gate.require(from("198.51.100.7"));
    gate.require(from("::ffff:198.51.100.8"));

    assertThrows(ApiException.class, () -> gate.require(from("::ffff:198.51.100.7")));
  }

  @Test
  void aV4MappedAddressArrivingAsIpv6IsStillKeyedExactly() throws Exception {
    var gate = oneRequest();

    gate.require(mapped("198.51.100.7"));
    gate.require(mapped("198.51.100.8"));

    assertThrows(
        ApiException.class,
        () -> gate.require(mapped("198.51.100.7")),
        "mapped v4 callers must not collapse into one ::/64 budget");
  }

  @Test
  void callersWithNoResolvableAddressShareOneBudget() {
    var gate = oneRequest();

    gate.require(new AddressExchange(null));

    assertThrows(ApiException.class, () -> gate.require(new AddressExchange(null)));
  }

  /**
   * A caller whose v4 address reaches the server in its 16-byte v6-mapped form, which a dual-stack
   * socket can hand back. Grouping those by /64 would put every IPv4 client on the planet in one
   * budget, so the gate must key them exactly.
   */
  private static HttpExchange mapped(String ipv4) throws Exception {
    var bytes = new byte[16];
    bytes[10] = (byte) 0xff;
    bytes[11] = (byte) 0xff;
    System.arraycopy(InetAddress.getByName(ipv4).getAddress(), 0, bytes, 12, 4);
    return new AddressExchange(
        new InetSocketAddress(Inet6Address.getByAddress(null, bytes, 0), 41000));
  }

  private static HttpExchange from(String address) {
    try {
      return new AddressExchange(new InetSocketAddress(InetAddress.getByName(address), 41000));
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(address, e);
    }
  }

  /**
   * An exchange that carries nothing but a remote address — the pre-auth caller's whole identity.
   */
  private static final class AddressExchange extends HttpExchange {
    private final InetSocketAddress remote;
    private final Map<String, Object> attributes = new HashMap<>();

    private AddressExchange(InetSocketAddress remote) {
      this.remote = remote;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return remote;
    }

    @Override
    public Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
      attributes.put(name, value);
    }

    @Override
    public Headers getRequestHeaders() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Headers getResponseHeaders() {
      throw new UnsupportedOperationException();
    }

    @Override
    public URI getRequestURI() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestMethod() {
      throw new UnsupportedOperationException();
    }

    @Override
    public HttpContext getHttpContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getRequestBody() {
      throw new UnsupportedOperationException();
    }

    @Override
    public OutputStream getResponseBody() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendResponseHeaders(int code, long length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getProtocol() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getResponseCode() {
      throw new UnsupportedOperationException();
    }

    @Override
    public HttpPrincipal getPrincipal() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setStreams(InputStream input, OutputStream output) {
      throw new UnsupportedOperationException();
    }
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
