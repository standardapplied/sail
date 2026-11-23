/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A loopback HTTP server answering every request with one status and body, counting the connections
 * the requests came on, so a test tells a client held across requests from one made per request.
 */
public final class CountingHttpServer implements AutoCloseable {

  private final HttpServer server;
  private final Set<Integer> ports = ConcurrentHashMap.newKeySet();

  private CountingHttpServer(int status, String body) throws IOException {
    var answer = body.getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          ports.add(exchange.getRemoteAddress().getPort());
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(status, answer.length == 0 ? -1 : answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.start();
  }

  /** A server, started, answering every request {@code status} with {@code body}. */
  public static CountingHttpServer answering(int status, String body) throws IOException {
    return new CountingHttpServer(status, body);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  /** The address of {@code path} on this server. */
  public URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port() + path);
  }

  /** How many connections the requests answered so far came on. */
  public int connections() {
    return ports.size();
  }

  /** Stops the server, closing the connections it still holds. */
  @Override
  public void close() {
    server.stop(0);
  }
}
