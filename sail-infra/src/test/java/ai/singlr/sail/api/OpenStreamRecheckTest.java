/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An event stream open on main while an admin changes its caller: a grant, a revoke, a removal or a
 * disable made after it opened holds from the first event a second or more later, as it would for
 * the caller's next request. The clock is moved by hand; events are published on the real bus.
 */
class OpenStreamRecheckTest {

  private static final SyncConfig MAIN = new SyncConfig("main", null, "mady", "main-box");
  private static final String END = "end of stream";

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private FdeStore.Fde client;
  private EventBus bus;
  private HttpServer server;
  private ManualClock clock;
  private final AtomicInteger checks = new AtomicInteger();
  private final List<InputStream> bodies = new CopyOnWriteArrayList<>();
  private final CountDownLatch clientChecked = new CountDownLatch(1);
  private volatile CountDownLatch holdClient;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    fdes.add("mady", null, null, "admin");
    client = fdes.add("client", null, null, "member");
    fdes.restrict("client");
    fdes.grant("client", "acme");
    bus = new EventBus();
    clock = new ManualClock(Instant.parse("2026-10-19T10:00:00Z"), Duration.ofMinutes(1));
    var sessions = TestAuth.sessions(db, MAIN);
    ApiAuth counted =
        exchange -> {
          checks.incrementAndGet();
          sessions.require(exchange);
          holdIfClient(exchange);
        };
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    PrivateAttributes.context(server, "/v1/events/stream", new SseHandler(bus, counted, 4, clock));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /** Holds {@code client}'s check, once asked to, between its authentication and its read. */
  private void holdIfClient(HttpExchange exchange) {
    var hold = holdClient;
    if (hold == null || !"client".equals(exchange.getAttribute("token.fde"))) {
      return;
    }
    holdClient = null;
    clientChecked.countDown();
    try {
      hold.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @AfterEach
  void tearDown() throws IOException {
    for (var body : bodies) {
      body.close();
    }
    server.stop(0);
    bus.close();
    db.close();
  }

  /**
   * Opens {@code client}'s stream and answers what it is sent: each event's project, the data of an
   * {@code error} event prefixed {@code error }, and {@link #END} when the server closes it.
   */
  private BlockingQueue<String> open() throws Exception {
    return open(client);
  }

  private BlockingQueue<String> open(FdeStore.Fde caller) throws Exception {
    var token = new AuthSessionStore(db).create(caller.id(), Duration.ofHours(1)).token();
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events/stream"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    var response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    var body = response.body();
    bodies.add(body);
    var frames = new LinkedBlockingQueue<String>();
    var subscribed = new LinkedBlockingQueue<Boolean>();
    var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var error = false;
                String line;
                while ((line = reader.readLine()) != null) {
                  if (line.startsWith(": subscribed")) {
                    subscribed.add(true);
                  } else if (line.equals("event: error")) {
                    error = true;
                  } else if (line.startsWith("data: ")) {
                    var data = line.substring("data: ".length());
                    frames.add(
                        error
                            ? "error " + data
                            : YamlUtil.parseMap(data).get("project").toString());
                  }
                }
                frames.add(END);
              } catch (IOException closedByTheTest) {
                frames.add(END);
              }
            });
    assertEquals(true, subscribed.poll(10, TimeUnit.SECONDS), "the stream never subscribed");
    checks.set(0);
    return frames;
  }

  private static String next(BlockingQueue<String> frames) throws InterruptedException {
    var frame = frames.poll(10, TimeUnit.SECONDS);
    if (frame == null) {
      throw new AssertionError("the stream sent nothing within 10s");
    }
    return frame;
  }

  private void publish(String project) {
    bus.publish(Event.of(project, null, "spec_dispatched", "sail", "h"));
  }

  @Test
  void aProjectGrantedWhileTheStreamIsOpenIsSentFromTheNextEventASecondLater() throws Exception {
    var frames = open();
    publish("beta");
    publish("acme");
    assertEquals("acme", next(frames), "beta was not granted yet");

    fdes.grant("client", "beta");
    clock.advance(Duration.ofSeconds(1));
    publish("beta");

    assertEquals("beta", next(frames));
    assertEquals(1, checks.get());
  }

  @Test
  void aProjectRevokedWhileTheStreamIsOpenIsNoLongerSent() throws Exception {
    fdes.grant("client", "beta");
    var frames = open();

    fdes.revoke("client", "acme");
    clock.advance(Duration.ofSeconds(1));
    publish("acme");
    publish("beta");

    assertEquals("beta", next(frames));
    publish("acme");
    publish("beta");
    assertEquals("beta", next(frames));
  }

  @Test
  void eventsWithinTheSameSecondShareOneCheckAndTheFilterItBuilt() throws Exception {
    var frames = open();
    fdes.grant("client", "beta");
    clock.advance(Duration.ofSeconds(1));
    publish("beta");
    assertEquals("beta", next(frames));

    fdes.revoke("client", "beta");
    publish("beta");
    assertEquals("beta", next(frames), "within the second, the filter the check built holds");
    assertEquals(1, checks.get());

    clock.advance(Duration.ofSeconds(1));
    publish("beta");
    publish("acme");
    assertEquals("acme", next(frames));
    assertEquals(2, checks.get());
  }

  @Test
  void anAdminAuthenticatingDuringAScopedStreamsCheckLeavesThatStreamItsOwnScope()
      throws Exception {
    var frames = open();
    var hold = new CountDownLatch(1);
    holdClient = hold;
    clock.advance(Duration.ofSeconds(1));
    publish("beta");
    assertTrue(clientChecked.await(10, TimeUnit.SECONDS), "the client's stream never re-checked");

    open(fdes.byHandle("mady").orElseThrow());
    hold.countDown();
    publish("acme");

    assertEquals("acme", next(frames), "beta was never granted to client");
  }

  @Test
  void aRemovedFdesStreamEndsAtTheNextCheckWithItsReason() throws Exception {
    var frames = open();

    fdes.remove(client.id());
    clock.advance(Duration.ofSeconds(1));
    publish("acme");

    assertEquals(
        "error {\"code\": \"invalid_bearer_token\", \"message\": \"Session token is invalid or"
            + " expired.\"}",
        next(frames));
    assertEquals(END, next(frames));
    assertNull(frames.poll(), "nothing after the stream ended");
  }

  @Test
  void aDisabledFdesIdleStreamEndsAtItsNextHeartbeat() throws Exception {
    var frames = open();

    fdes.disableUnlisted(List.of("mady"));
    clock.advance(Duration.ofMillis(SseHandler.HEARTBEAT_MILLIS + 1));

    assertEquals(
        "error {\"code\": \"invalid_bearer_token\", \"message\": \"Session token is invalid or"
            + " expired.\"}",
        next(frames));
    assertEquals(END, next(frames));
  }
}
