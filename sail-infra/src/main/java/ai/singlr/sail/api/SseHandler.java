/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * Server-Sent Events handler for {@code GET /v1/events/stream}.
 *
 * <p>Each accepted connection subscribes to the {@link EventBus} (optionally filtered by {@code
 * project} / {@code type} query parameters) and streams matching events to the client until the
 * client disconnects or the server shuts down. A semaphore caps concurrent SSE connections —
 * over-capacity requests get HTTP 503 with {@code Retry-After}.
 *
 * <p>Wire format follows the SSE spec: each event is emitted as
 *
 * <pre>
 *   id: 42
 *   data: {"v":1,"id":42,...}
 *   [blank line]
 * </pre>
 *
 * The {@code id} field lets clients resume via {@code Last-Event-ID} (best-effort; events the
 * subscriber dropped while behind are reconcilable via {@code GET /v1/events/recent}).
 */
public final class SseHandler implements HttpHandler {

  /** Default cap on concurrent SSE connections. */
  public static final int DEFAULT_MAX_CONNECTIONS = 64;

  /** Per-connection queue capacity (events buffered while writing). */
  public static final int DEFAULT_PER_CONNECTION_CAPACITY = 256;

  /** Heartbeat interval. SSE comments keep idle connections alive through proxies. */
  public static final long HEARTBEAT_MILLIS = 15_000L;

  private static final long HEARTBEAT_NANOS = HEARTBEAT_MILLIS * 1_000_000L;

  /** Poll timeout when draining the per-connection queue. */
  static final long POLL_MILLIS = 1_000L;

  private final EventBus bus;
  private final ApiAuth auth;
  private final Semaphore connectionPermits;
  private final int maxConnections;
  private final LongAdder rejected = new LongAdder();

  public SseHandler(EventBus bus, ApiAuth auth) {
    this(bus, auth, DEFAULT_MAX_CONNECTIONS);
  }

  public SseHandler(EventBus bus, ApiAuth auth, int maxConnections) {
    this.bus = Objects.requireNonNull(bus, "bus");
    this.auth = Objects.requireNonNull(auth, "auth");
    if (maxConnections <= 0) {
      throw new IllegalArgumentException("maxConnections must be positive");
    }
    this.maxConnections = maxConnections;
    this.connectionPermits = new Semaphore(maxConnections);
  }

  /** Total SSE connection attempts rejected due to the connection cap. */
  public long rejectedCount() {
    return rejected.sum();
  }

  /** Current open SSE connections. */
  public int openConnections() {
    return maxConnections - connectionPermits.availablePermits();
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        sendMethodNotAllowed(exchange);
        return;
      }
      try {
        auth.require(exchange);
      } catch (ApiException e) {
        sendError(exchange, e);
        return;
      }
      if (!connectionPermits.tryAcquire()) {
        rejected.increment();
        sendRetryLater(exchange);
        return;
      }
      try {
        runStream(exchange);
      } finally {
        connectionPermits.release();
      }
    } finally {
      exchange.close();
    }
  }

  private void runStream(HttpExchange exchange) throws IOException {
    var filter = parseFilter(exchange.getRequestURI());
    var streamSubscriber = new StreamSubscriber(filter);
    var subscription = bus.subscribe(streamSubscriber, DEFAULT_PER_CONNECTION_CAPACITY);
    if (subscription == null) {
      sendRetryLater(exchange);
      return;
    }
    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/event-stream; charset=utf-8");
    headers.set("Cache-Control", "no-cache");
    headers.set("Connection", "keep-alive");
    headers.set("X-Accel-Buffering", "no");
    exchange.sendResponseHeaders(200, 0);
    try (var out = exchange.getResponseBody()) {
      writeComment(out, "subscribed");
      var lastHeartbeat = System.nanoTime();
      while (true) {
        Event event;
        try {
          event = streamSubscriber.queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        if (event != null) {
          writeEvent(out, event);
        }
        if (System.nanoTime() - lastHeartbeat > HEARTBEAT_NANOS) {
          writeComment(out, "keepalive");
          lastHeartbeat = System.nanoTime();
        }
      }
    } catch (IOException disconnected) {
      // client closed connection; that's the normal exit path
    } finally {
      subscription.close();
    }
  }

  private static void writeEvent(OutputStream out, Event event) throws IOException {
    var buf = new StringBuilder(256);
    buf.append("id: ").append(event.id()).append('\n');
    buf.append("data: ").append(event.toJsonLine()).append("\n\n");
    out.write(buf.toString().getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void writeComment(OutputStream out, String text) throws IOException {
    out.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void sendError(HttpExchange exchange, ApiException error) throws IOException {
    var body = error.failure().fullError();
    var bytes =
        (body == null ? error.failure().errorMessage() : body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(error.failure().errorCode().httpCode(), bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Allow", "GET");
    exchange.sendResponseHeaders(405, -1);
  }

  private static void sendRetryLater(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Retry-After", "1");
    exchange.sendResponseHeaders(503, -1);
  }

  static Predicate<Event> parseFilter(URI uri) {
    var query = uri.getRawQuery();
    if (Strings.isBlank(query)) {
      return EventSubscriber.all();
    }
    var values = parseQuery(query);
    Predicate<Event> result = EventSubscriber.all();
    var projectFilter = values.get("project");
    if (Strings.isNotBlank(projectFilter)) {
      result = result.and(EventSubscriber.byProject(projectFilter));
    }
    var typeFilter = values.get("type");
    if (Strings.isNotBlank(typeFilter)) {
      var types = Set.of(typeFilter.split(","));
      result = result.and(e -> types.contains(e.type()));
    }
    return result;
  }

  private static Map<String, String> parseQuery(String query) {
    var out = new LinkedHashMap<String, String>();
    for (var part : query.split("&")) {
      var sep = part.indexOf('=');
      var name = sep >= 0 ? part.substring(0, sep) : part;
      var value = sep >= 0 ? part.substring(sep + 1) : "";
      out.put(decode(name), decode(value));
    }
    return out;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static final class StreamSubscriber implements EventSubscriber {
    private final Predicate<Event> filter;
    final ArrayBlockingQueue<Event> queue =
        new ArrayBlockingQueue<>(DEFAULT_PER_CONNECTION_CAPACITY);

    StreamSubscriber(Predicate<Event> filter) {
      this.filter = filter;
    }

    @Override
    public String name() {
      return "sse-stream";
    }

    @Override
    public Predicate<Event> filter() {
      return filter;
    }

    @Override
    public void onEvent(Event event) {
      queue.offer(event);
    }
  }
}
