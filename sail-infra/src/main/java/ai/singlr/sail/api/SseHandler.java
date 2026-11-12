/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>Wire format follows the SSE spec. A bus event is a {@code data} line alone; a row change of
 * the change feed is an {@code event: change} whose {@code id} is its position:
 *
 * <pre>
 *   event: change
 *   id: 42
 *   data: {"seq": 42, "type": "spec", "id": "...", "row": {...}}
 *   [blank line]
 * </pre>
 *
 * Only a row change moves a client's cursor. A stream that sends {@code Last-Event-ID} (or names
 * {@code after}) is first sent every change after it its caller may read, then live ones: both are
 * pages of the one feed ({@link ChangeFeed}), read from a cursor each page moves, so nothing is
 * missed and nothing sent twice at the join, a change committed while the replay was read included.
 * A stream that names no position starts at the newest. A position the box cannot answer from is
 * told once as an {@code event: resync} naming where to read afresh from, and the stream goes on
 * from there. A stream that falls behind far enough for the bus to drop one of its events is ended:
 * its client reconnects from its last id.
 *
 * <p>A stream with a {@code room} filter is first sent where the room's live member stands ({@link
 * Event.WellKnownTypes#AGENT_TURN_STATE}), with no {@code id}: its turn events are never stored, so
 * that frame is what a reader who connects mid-turn, or reconnects after missing a turn's end, is
 * told. A stream the bus drops an event for is ended, so its client reconnects and is told again.
 */
public final class SseHandler implements HttpHandler, RouteTable {

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
  private final ChangeFeed changes;
  private final Semaphore connectionPermits;
  private final int maxConnections;
  private final InstantSource clock;
  private final LongAdder rejected = new LongAdder();

  /**
   * A stream of {@code bus}'s events and of {@code changes}' rows, for each caller {@code auth}
   * admits.
   */
  public SseHandler(EventBus bus, ApiAuth auth, RoomTurns turns, ChangeFeed changes) {
    this(bus, auth, turns, changes, DEFAULT_MAX_CONNECTIONS, InstantSource.system());
  }

  SseHandler(EventBus bus, ApiAuth auth, int maxConnections) {
    this(bus, auth, RoomTurns.NONE, ChangeFeed.NONE, maxConnections, InstantSource.system());
  }

  /** As above, timing heartbeats and re-checks by {@code clock}. */
  SseHandler(
      EventBus bus,
      ApiAuth auth,
      RoomTurns turns,
      ChangeFeed changes,
      int maxConnections,
      InstantSource clock) {
    this.bus = Objects.requireNonNull(bus, "bus");
    this.auth = Objects.requireNonNull(auth, "auth");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxConnections <= 0) {
      throw new IllegalArgumentException("maxConnections must be positive");
    }
    this.maxConnections = maxConnections;
    this.connectionPermits = new Semaphore(maxConnections);
  }

  private final List<Route<SseHandler>> routes =
      List.of(new Route<>("GET", "/v1/events/stream", Gate.READS_IN_SCOPE, this));

  /** The event stream, read in the caller's scope. */
  @Override
  public List<Route<?>> routes() {
    return List.copyOf(routes);
  }

  /** Total SSE connection attempts rejected due to the connection cap. */
  public long rejectedCount() {
    return rejected.sum();
  }

  /** Current open SSE connections. */
  public int openConnections() {
    return maxConnections - connectionPermits.availablePermits();
  }

  /**
   * Answers {@code exchange} as the API router answers a request: its caller is authenticated
   * first, then its table serves the path as the router reads it ({@link ApiRouter#canonical}), or
   * says the path or the method is not there.
   */
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      try {
        auth.require(exchange);
        switch (RouteTable.serve(
            routes, exchange.getRequestMethod(), ApiRouter.canonical(exchange.getRequestURI()))) {
          case RouteTable.Served.NotFound<SseHandler> _ -> throw ApiRouter.notFound();
          case RouteTable.Served.MethodNotAllowed<SseHandler> _ -> {
            sendMethodNotAllowed(exchange);
            return;
          }
          case RouteTable.Served.Match<SseHandler>(var route, _) ->
              route.gate().admit(ApiAuth.actorOf(exchange));
        }
      } catch (ApiException e) {
        ApiResponse.sendError(exchange, e);
        return;
      }
      Set<String> rows;
      try {
        rows = rowsAsked(exchange);
      } catch (IllegalArgumentException e) {
        ApiResponse.sendError(exchange, ApiException.of(ErrorCode.INVALID_REQUEST, e));
        return;
      }
      if (!connectionPermits.tryAcquire()) {
        rejected.increment();
        sendRetryLater(exchange);
        return;
      }
      try {
        runStream(exchange, rows);
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
   * by the filter the last check built ({@link StreamRecheck}). Rows are read a page at a time when
   * a change this process commits wakes the stream, or another process announces one ({@link
   * ChangeFeed#wake}), a burst of either waking it once; never on an idle tick, nor on any other
   * event. Each page is read as the caller is now, asked of the door before and again after it is
   * read, so a caller refused before the page is written is ended with none of it, and a caller
   * whose view changed since its position, as a floor raised says, is told to resync and ended: it
   * reads afresh, then resumes.
   */
  private void runStream(HttpExchange exchange, Set<String> rows) throws IOException {
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
    var filter = parseFilter(uri, ApiAuth.actorOf(exchange));
    try (var out = exchange.getResponseBody();
        var watching = changes.watch(streamSubscriber::changed)) {
      var named = named(exchange);
      var cursor = named == null ? Actor.call(ApiAuth.actorOf(exchange), changes::newest) : named;
      var lastHeartbeat = clock.instant();
      writeComment(out, "subscribed");
      var state = roomOf(uri).flatMap(turns::stateOf);
      if (state.isPresent() && filter.test(state.get())) {
        writeEvent(out, state.get());
      }
      try {
        cursor =
            page(out, exchange, streamSubscriber, new ChangeFeed.Ask(cursor, rows, PAGE), true);
        while (true) {
          Event event;
          try {
            event = streamSubscriber.queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          if (streamSubscriber.missed || streamSubscriber.closed) {
            break;
          }
          var told = streamSubscriber.taken(event);
          if (event != null && event != StreamSubscriber.CHANGED) {
            if (recheck.recheck()) {
              filter = parseFilter(uri, ApiAuth.actorOf(exchange));
            }
            if (filter.test(event)) {
              writeEvent(out, event);
            }
          }
          if (Duration.between(lastHeartbeat, clock.instant()).toMillis() > HEARTBEAT_MILLIS) {
            if (recheck.recheck()) {
              filter = parseFilter(uri, ApiAuth.actorOf(exchange));
            }
            writeComment(out, "keepalive");
            lastHeartbeat = clock.instant();
          }
          if (told) {
            filter = parseFilter(uri, ApiAuth.actorOf(exchange));
            cursor =
                page(
                    out,
                    exchange,
                    streamSubscriber,
                    ChangeFeed.Ask.minted(cursor, rows, PAGE),
                    false);
          }
        }
      } catch (StreamRecheck.Ended ended) {
        StreamRecheck.writeEnded(out, ended);
      } catch (Severed severed) {
        var unused = severed;
      }
    } catch (IOException disconnected) {
      // client closed connection; that's the normal exit path
    } finally {
      subscription.close();
    }
  }

  /** The changes a stream reads at a time. */
  static final int PAGE = ChangeFeed.DEFAULT_LIMIT;

  /** A stream whose caller's view changed since its position: told to resync, and ended. */
  private static final class Severed extends Exception {
    Severed() {
      super(null, null, false, false);
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

  /**
   * The rows the stream is asked for: the change feed's {@code types} the query names, every fed
   * type when it names none.
   */
  private static Set<String> rowsAsked(HttpExchange exchange) {
    var query = exchange.getRequestURI().getRawQuery();
    return ChangeFeed.Ask.types(Strings.isBlank(query) ? null : parseQuery(query).get("types"));
  }

  /** The position the client resumes from, by {@code Last-Event-ID} or {@code after}; or null. */
  private static String named(HttpExchange exchange) {
    var named = exchange.getRequestHeaders().getFirst("Last-Event-ID");
    if (Strings.isBlank(named)) {
      var query = exchange.getRequestURI().getRawQuery();
      named = Strings.isBlank(query) ? null : parseQuery(query).get("after");
    }
    return Strings.isBlank(named) ? null : named;
  }

  /**
   * Sends one page of the changes after {@code ask}'s position, read as the caller is now and sent
   * only if the caller is still admitted once it is read, and answers where the next page starts;
   * when more follow, the stream is woken to read them after whatever else it has to send, unless
   * the page did not move the position, which would only read it again: then the next wake reads
   * it. Each page starts where the one before it ended, so a change committed between two of them
   * is in the next. A position the feed refuses is told once, with the newest as its id: on the
   * first page, where the client named it, the stream goes on from there; on any later one, where
   * the caller's view changed under the stream, the stream ends.
   *
   * @throws StreamRecheck.Ended when the caller no longer authenticates
   * @throws Severed when the stream is told to resync after its first page
   */
  private String page(
      OutputStream out,
      HttpExchange exchange,
      StreamSubscriber subscriber,
      ChangeFeed.Ask ask,
      boolean first)
      throws IOException, StreamRecheck.Ended, Severed {
    var refused = refusal(exchange);
    if (refused.isPresent()) {
      throw new StreamRecheck.Ended(refused.get());
    }
    ChangeFeed.Page page;
    try {
      page =
          ScopedValue.where(SyncControl.NO_SYNC, true)
              .call(() -> Actor.call(ApiAuth.actorOf(exchange), () -> changes.page(ask)));
    } catch (ChangeFeed.Resync resync) {
      writeResync(out, resync);
      if (first) {
        return resync.newest();
      }
      throw new Severed();
    }
    if (!page.changes().isEmpty()) {
      refused = refusal(exchange);
      if (refused.isPresent()) {
        throw new StreamRecheck.Ended(refused.get());
      }
      writeChanges(out, page.changes());
    }
    if (page.more() && !page.next().equals(ask.since())) {
      subscriber.changed();
    }
    return page.next();
  }

  private static void writeEvent(OutputStream out, Event event) throws IOException {
    write(out, "data: " + event.toJsonLine() + "\n\n");
  }

  /** Sends {@code changes} as one write: a frame each, flushed once for the page. */
  private static void writeChanges(OutputStream out, List<ChangeFeed.Change> changes)
      throws IOException {
    var frames = new StringBuilder();
    for (var change : changes) {
      frames
          .append("event: change\nid: ")
          .append(change.position())
          .append("\ndata: ")
          .append(YamlUtil.dumpJson(ApiJson.encoded(change)))
          .append("\n\n");
    }
    write(out, frames.toString());
  }

  private static void writeResync(OutputStream out, ChangeFeed.Resync resync) throws IOException {
    var data = new LinkedHashMap<String, Object>();
    data.put("position", resync.newest());
    data.put("message", resync.getMessage());
    data.put("action", ChangeFeed.Resync.ACTION);
    write(
        out,
        "event: resync\nid: " + resync.newest() + "\ndata: " + YamlUtil.dumpJson(data) + "\n\n");
  }

  private static void write(OutputStream out, String frame) throws IOException {
    out.write(frame.getBytes(StandardCharsets.UTF_8));
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
   * The events a subscriber is sent: those of the projects {@code actor} may read, narrowed by the
   * query. An event of the box itself, a sync that degraded, is of no project in the catalog
   * ({@link Event#SAIL_AGENT} stands in for one), so it reaches an unrestricted subscriber alone.
   */
  static Predicate<Event> parseFilter(URI uri, Actor actor) {
    Predicate<Event> scoped = ReadFilter.readable(actor, Event::project);
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
    private static final Event WAKE =
        Event.of("sail", null, "stream_closed", Event.SAIL_AGENT, "local");

    /** What a commit of this process queues for the stream: read the feed. Never sent. */
    static final Event CHANGED = Event.of("sail", null, "rows_changed", Event.SAIL_AGENT, "local");

    /** A {@link #CHANGED} is queued and not yet taken, so a burst of commits queues one. */
    private final AtomicBoolean changing = new AtomicBoolean();

    private final Predicate<Event> filter;
    final ArrayBlockingQueue<Event> queue =
        new ArrayBlockingQueue<>(DEFAULT_PER_CONNECTION_CAPACITY);

    /** An event was dropped for this stream: what follows would mislead, so the stream ends. */
    volatile boolean missed;

    /** The subscription closed, as every one does when the server stops: the stream ends now. */
    volatile boolean closed;

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

    /** A change this process committed: the stream is woken to read the feed, once per burst. */
    void changed() {
      if (changing.compareAndSet(false, true) && !queue.offer(CHANGED)) {
        missed();
      }
    }

    /**
     * Whether {@code event}, just taken from the queue, tells the stream to read the feed: a change
     * this process committed or another announced ({@link ChangeFeed#wake}), however many of them
     * came since the last was taken. Taking it lets the next one wake the stream again.
     */
    boolean taken(Event event) {
      if (event == CHANGED) {
        changing.set(false);
        return true;
      }
      return false;
    }

    @Override
    public void closed() {
      closed = true;
      queue.offer(WAKE);
    }
  }
}
