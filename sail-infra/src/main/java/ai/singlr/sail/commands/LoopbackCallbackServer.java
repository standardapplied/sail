/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A short-lived loopback HTTP listener that receives the session token from a browser passkey
 * login. {@code sail login} opens the browser at the control-plane {@code /login} page passing this
 * listener's {@code /callback} as the redirect target and an opaque {@code state} nonce; the page
 * redirects back with the minted token. The token is accepted only when the returned {@code state}
 * matches the one issued, so a stale or forged callback cannot inject a token. Binds {@code
 * 127.0.0.1} only — never reachable off the machine.
 */
public final class LoopbackCallbackServer implements AutoCloseable {

  private static final String DONE_PAGE =
      """
      <!doctype html><meta charset="utf-8"><title>Sail</title>
      <body style="font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto">
      <h1>Signed in to Sail</h1><p>You can close this tab and return to the terminal.</p></body>
      """;
  private static final String ERROR_PAGE =
      """
      <!doctype html><meta charset="utf-8"><title>Sail</title>
      <body style="font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto">
      <h1>Login could not be completed</h1><p>Return to the terminal and try again.</p></body>
      """;

  private final HttpServer server;
  private final String state;
  private final CompletableFuture<String> token = new CompletableFuture<>();

  public LoopbackCallbackServer(String state) throws IOException {
    this.state = state;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/callback", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  /** The loopback URL the login page must redirect the token back to. */
  public String redirectUri() {
    return "http://127.0.0.1:" + port() + "/callback";
  }

  /**
   * Blocks until a valid callback arrives, or throws {@link TimeoutException} after {@code wait}.
   */
  public String awaitToken(Duration wait)
      throws InterruptedException, ExecutionException, TimeoutException {
    return token.get(wait.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void handle(HttpExchange exchange) throws IOException {
    var query = parseQuery(exchange.getRequestURI().getRawQuery());
    var received = query.get("token");
    var accepted = state.equals(query.get("state")) && Strings.isNotBlank(received);
    var body = accepted ? DONE_PAGE : ERROR_PAGE;
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(accepted ? 200 : 400, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
    exchange.close();
    if (accepted) {
      token.complete(received);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private static Map<String, String> parseQuery(String raw) {
    var values = new LinkedHashMap<String, String>();
    if (Strings.isBlank(raw)) {
      return values;
    }
    for (var part : raw.split("&")) {
      var separator = part.indexOf('=');
      if (separator >= 0) {
        values.put(
            URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8),
            URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8));
      }
    }
    return values;
  }
}
