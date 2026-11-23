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
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A loopback HTTP server answering every request with one status and body, counting the connections
 * the requests came on, so a test tells a client held across requests from one made per request; or
 * one whose answer never ends until it closes ({@link #trickling}).
 */
public final class CountingHttpServer implements AutoCloseable {

  private final HttpServer server;
  private final Set<Integer> ports = ConcurrentHashMap.newKeySet();
  private final CountDownLatch asked = new CountDownLatch(1);
  private final CountDownLatch closing = new CountDownLatch(1);

  private CountingHttpServer(int status, String body, boolean trickles) throws IOException {
    var answer = body.getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          ports.add(exchange.getRemoteAddress().getPort());
          exchange.getRequestBody().readAllBytes();
          if (trickles) {
            exchange.sendResponseHeaders(status, 0);
            exchange.getResponseBody().write(answer, 0, 1);
            exchange.getResponseBody().flush();
            asked.countDown();
            awaitClosing();
          } else {
            exchange.sendResponseHeaders(status, answer.length == 0 ? -1 : answer.length);
            exchange.getResponseBody().write(answer);
          }
          exchange.close();
        });
    server.start();
  }

  /** A server, started, answering every request {@code status} with {@code body}. */
  public static CountingHttpServer answering(int status, String body) throws IOException {
    return new CountingHttpServer(status, body, false);
  }

  /**
   * A server, started, that answers a request 200 with the first byte of its body and holds the
   * rest until it closes: an endpoint that trickles its answer.
   */
  public static CountingHttpServer trickling() throws IOException {
    return new CountingHttpServer(200, "{}", true);
  }

  /** Whether a trickling server has been asked and begun its answer within {@code wait}. */
  public boolean asked(Duration wait) throws InterruptedException {
    return asked.await(wait.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void awaitClosing() {
    try {
      closing.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
    closing.countDown();
    server.stop(0);
  }
}
