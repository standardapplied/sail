/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each exchange on a context holds its own attributes, even where the JDK stores them on the
 * context (as 25 does, and as the tests' {@code jdk.httpserver.attributes=context} makes 26 do).
 */
class PrivateAttributesTest {

  private HttpServer server;
  private HttpContext context;
  private final List<String> seen = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    context = PrivateAttributes.context(server, "/", this::handle);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    seen.add(String.valueOf(exchange.getAttribute("token.fde")));
    exchange.setAttribute("token.fde", exchange.getRequestURI().getPath().substring(1));
    exchange.setAttribute("token.role", "member");
    exchange.setAttribute("token.role", null);
    assertNull(exchange.getAttribute("token.role"));
    assertNull(exchange.getPrincipal());
    exchange.setStreams(null, null);
    var echo =
        String.join(
            " ",
            String.valueOf(exchange.getAttribute("token.fde")),
            exchange.getRequestMethod(),
            exchange.getProtocol(),
            exchange.getHttpContext().getPath(),
            String.valueOf(exchange.getLocalAddress().getPort() == server.getAddress().getPort()),
            String.valueOf(exchange.getRemoteAddress() != null),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
            exchange.getRequestHeaders().getFirst("X-Probe"),
            String.valueOf(exchange.getResponseCode()));
    var bytes = echo.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
    exchange.close();
  }

  private String post(String handle) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + handle))
            .header("X-Probe", "probe")
            .POST(HttpRequest.BodyPublishers.ofString("body"))
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return response.body();
  }

  @Test
  void theContextRunsTheFilterFirst() {
    assertEquals(
        List.of("Gives each exchange its own attributes"),
        context.getFilters().stream().map(Filter::description).toList());
  }

  @Test
  void anExchangeSeesOnlyTheAttributesItSetItself() throws Exception {
    assertEquals("ada POST HTTP/1.1 / true true body probe -1", post("ada"));
    assertEquals("bob POST HTTP/1.1 / true true body probe -1", post("bob"));

    assertEquals(List.of("null", "null"), seen, "the second request saw the first's identity");
  }
}
