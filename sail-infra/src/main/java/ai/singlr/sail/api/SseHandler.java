/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * project} / {@code room} / {@code type} query parameters; a room's events are those whose {@code
 * spec} is the room id, which is what its messages and its live member's stream carry) and streams
 * matching events to the client until the client disconnects or the server shuts down. A semaphore
 * caps concurrent SSE connections — over-capacity requests get HTTP 503 with {@code Retry-After}.
 *
 * <p>Wire format follows the SSE spec: each event is emitted as
 *
 * <pre>
 *   id: 42
 *   data: {"v":1,"id":42,...}
 *   [blank line]
 * </pre>
 *
 * The {@code id} field lets clients resume via {@code Last-Event-ID} (best-effort). A stream that
 * falls behind far enough for the bus to drop one of its events is ended: its client reconnects,
 * and reconciles what it missed through {@code GET /v1/events/recent}.
 *
 * <p>A stream with a {@code room} filter is first sent where the room's live member stands ({@link
 * Event.WellKnownTypes#AGENT_TURN_STATE}), with no {@code id}: its turn events are never stored, so
 * that frame is what a reader who connects mid-turn, or reconnects after missing a turn's end, is
 * told. A stream the bus drops an event for is ended, so its client reconnects and is told again.
 */
public final class SseHandler implements HttpHandler {

  /** Default cap on concurrent SSE connections. */
  public static final int DEFAULT_MAX_CONNECTIONS = 64;

  /** Per-connection queue capacity (events buffered while writing). */
  public static final int DEFAULT_PER_CONNECTION_CAPACITY = 256;

  /** Heartbeat interval. SSE comments keep idle connections alive through proxies. */
  public static final long HEARTBEAT_MILLIS = 15_000L;

  /** Poll timeout when draining the per-connection queue. */
  static final long POLL_MILLIS = 1_000L;

  /** Where a room's live member stands, as a room's stream is first told it. */
  @FunctionalInterface
  public interface RoomTurns {

    /** Knows no room: a stream is told nothing first. */
    RoomTurns NONE = roomId -> Optional.empty();

    /** The state of {@code roomId}'s live member; empty for a room this box does not know. */
    Optional<Event> stateOf(String roomId);
  }

  private final EventBus bus;
  private final ApiAuth auth;
  private final RoomTurns turns;
  private final Semaphore connectionPermits;
  private final int maxConnections;
  private final InstantSource clock;
  private final LongAdder rejected = new LongAdder();

  public SseHandler(EventBus bus, ApiAuth auth, RoomTurns turns) {
    this(bus, auth, turns, DEFAULT_MAX_CONNECTIONS, InstantSource.system());
  }

  SseHandler(EventBus bus, ApiAuth auth, int maxConnections) {
    this(bus, auth, RoomTurns.NONE, maxConnections, InstantSource.system());
  }

  /** As above, timing heartbeats and re-checks by {@code clock}. */
  SseHandler(EventBus bus, ApiAuth auth, RoomTurns turns, int maxConnections, InstantSource clock) {
    this.bus = Objects.requireNonNull(bus, "bus");
    this.auth = Objects.requireNonNull(auth, "auth");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.clock = Objects.requireNonNull(clock, "clock");
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
        ApiResponse.sendError(exchange, e);
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

  /**
   * Streams until the client goes or its caller no longer authenticates. The bus queues the events
   * the query asks for; which of them the caller's scope admits is decided here, as each is sent,
   * by the filter the last check built ({@link StreamRecheck}), so a grant or a revoke made while
   * the stream is open holds from the next event on.
   */
  private void runStream(HttpExchange exchange) throws IOException {
    var uri = exchange.getRequestURI();
    var streamSubscriber = new StreamSubscriber(queryFilter(uri));
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
    var recheck = new StreamRecheck(() -> refusal(exchange), clock);
    var filter = parseFilter(uri, ApiRouter.actorOf(exchange));
    try (var out = exchange.getResponseBody()) {
      writeComment(out, "subscribed");
      var state = roomOf(uri).flatMap(turns::stateOf);
      if (state.isPresent() && filter.test(state.get())) {
        writeEvent(out, state.get());
      }
      var lastHeartbeat = clock.instant();
      try {
        while (true) {
          Event event;
          try {
            event = streamSubscriber.queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          if (streamSubscriber.missed) {
            break;
          }
          if (event != null) {
            if (recheck.recheck()) {
              filter = parseFilter(uri, ApiRouter.actorOf(exchange));
            }
            if (filter.test(event)) {
              writeEvent(out, event);
            }
          }
          if (Duration.between(lastHeartbeat, clock.instant()).toMillis() > HEARTBEAT_MILLIS) {
            if (recheck.recheck()) {
              filter = parseFilter(uri, ApiRouter.actorOf(exchange));
            }
            writeComment(out, "keepalive");
            lastHeartbeat = clock.instant();
          }
        }
      } catch (StreamRecheck.Ended ended) {
        StreamRecheck.writeEnded(out, ended);
      }
    } catch (IOException disconnected) {
      // client closed connection; that's the normal exit path
    } finally {
      subscription.close();
    }
  }

  /** Why {@code exchange}'s caller may no longer stream, asked through the door it opened with. */
  private Optional<ApiError> refusal(HttpExchange exchange) {
    try {
      auth.require(exchange);
      return Optional.empty();
    } catch (ApiException e) {
      return Optional.of(e.error());
    }
  }

  private static void writeEvent(OutputStream out, Event event) throws IOException {
    var buf = new StringBuilder(256);
    if (event.id() > 0) {
      buf.append("id: ").append(event.id()).append('\n');
    }
    buf.append("data: ").append(event.toJsonLine()).append("\n\n");
    out.write(buf.toString().getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void writeComment(OutputStream out, String text) throws IOException {
    out.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Allow", "GET");
    exchange.sendResponseHeaders(405, -1);
  }

  private static void sendRetryLater(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Retry-After", "1");
    exchange.sendResponseHeaders(503, -1);
  }

  /**
   * The events a subscriber is sent: those of the projects {@code actor}'s scope admits, narrowed
   * by the query. An event of the box itself, a sync that degraded, is of no project in the catalog
   * ({@link Event#SAIL_AGENT} stands in for one), so it reaches an unrestricted subscriber alone.
   */
  static Predicate<Event> parseFilter(URI uri, Actor actor) {
    Predicate<Event> scoped = event -> actor.scope().admits(event.project());
    return scoped.and(queryFilter(uri));
  }

  /** The room the query narrows the stream to, if it does. */
  private static Optional<String> roomOf(URI uri) {
    var query = uri.getRawQuery();
    return Strings.isBlank(query)
        ? Optional.empty()
        : Optional.ofNullable(parseQuery(query).get("room")).filter(Strings::isNotBlank);
  }

  /** The events the query asks for, whoever asks. */
  private static Predicate<Event> queryFilter(URI uri) {
    Predicate<Event> result = event -> true;
    var query = uri.getRawQuery();
    if (Strings.isBlank(query)) {
      return result;
    }
    var values = parseQuery(query);
    var projectFilter = values.get("project");
    if (Strings.isNotBlank(projectFilter)) {
      result = result.and(EventSubscriber.byProject(projectFilter));
    }
    var roomFilter = roomOf(uri);
    if (roomFilter.isPresent()) {
      result = result.and(e -> roomFilter.get().equals(e.spec()));
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

    /** An event was dropped for this stream: what follows would mislead, so the stream ends. */
    volatile boolean missed;

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
      if (!queue.offer(event)) {
        missed();
      }
    }

    @Override
    public void missed() {
      missed = true;
    }
  }
}
