/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SseHandlerTest {

  private static final ApiAuth AUTH = new FixedTokenTestAuth("test-token");
  private static final Actor ANYONE = new Actor(null, Role.ADMIN, Actor.Lane.API);
  private static final Actor SCOPED = ANYONE.withScope(ProjectScope.of(List.of("light")));

  @Test
  void constructorRejectsNullBus() {
    assertThrows(NullPointerException.class, () -> new SseHandler(null, AUTH));
  }

  @Test
  void constructorRejectsNullAuth() {
    try (var bus = new EventBus()) {
      assertThrows(NullPointerException.class, () -> new SseHandler(bus, null));
    }
  }

  @Test
  void constructorRejectsNonPositiveMax() {
    try (var bus = new EventBus()) {
      assertThrows(IllegalArgumentException.class, () -> new SseHandler(bus, AUTH, 0));
      assertThrows(IllegalArgumentException.class, () -> new SseHandler(bus, AUTH, -3));
    }
  }

  @Test
  void initialCountersAreZero() {
    try (var bus = new EventBus()) {
      var handler = new SseHandler(bus, AUTH, 4);
      assertEquals(0L, handler.rejectedCount());
      assertEquals(0, handler.openConnections());
    }
  }

  @Test
  void parseFilterReturnsAllForEmptyQuery() {
    var filter = SseHandler.parseFilter(URI.create("http://x/v1/events/stream"), ANYONE);
    assertTrue(filter.test(Event.of("p", null, "t", "a", "h")));
  }

  @Test
  void parseFilterScopesByProject() {
    var filter =
        SseHandler.parseFilter(URI.create("http://x/v1/events/stream?project=light"), ANYONE);
    assertTrue(filter.test(Event.of("light", null, "t", "a", "h")));
    assertFalse(filter.test(Event.of("dark", null, "t", "a", "h")));
  }

  @Test
  void parseFilterScopesByTypeList() {
    var filter =
        SseHandler.parseFilter(
            URI.create("http://x/v1/events/stream?type=spec_dispatched,agent_session_started"),
            ANYONE);
    assertTrue(filter.test(Event.of("p", null, "spec_dispatched", "a", "h")));
    assertTrue(filter.test(Event.of("p", null, "agent_session_started", "a", "h")));
    assertFalse(filter.test(Event.of("p", null, "snapshot_created", "a", "h")));
  }

  @Test
  void parseFilterCombinesProjectAndType() {
    var filter =
        SseHandler.parseFilter(
            URI.create("http://x/v1/events/stream?project=light&type=spec_dispatched"), ANYONE);
    assertTrue(filter.test(Event.of("light", null, "spec_dispatched", "a", "h")));
    assertFalse(filter.test(Event.of("light", null, "snapshot_created", "a", "h")));
    assertFalse(filter.test(Event.of("dark", null, "spec_dispatched", "a", "h")));
  }

  @Test
  void parseFilterScopesByRoomBesideProjectAndType() {
    var filter =
        SseHandler.parseFilter(
            URI.create(
                "http://x/v1/events/stream?room=lounge"
                    + "&type=agent_text_delta,agent_tool_call,agent_turn_ended"),
            ANYONE);
    assertTrue(filter.test(Event.of("p", "lounge", "agent_text_delta", "a", "h")));
    assertTrue(filter.test(Event.of("p", "lounge", "agent_turn_ended", "a", "h")));
    assertFalse(filter.test(Event.of("p", "lounge", "spec_message_posted", "a", "h")));
    assertFalse(filter.test(Event.of("p", "kitchen", "agent_text_delta", "a", "h")));
    assertFalse(filter.test(Event.of("p", null, "agent_text_delta", "a", "h")));

    var scoped =
        SseHandler.parseFilter(
            URI.create("http://x/v1/events/stream?project=light&room=lounge"), ANYONE);
    assertTrue(scoped.test(Event.of("light", "lounge", "spec_message_posted", "a", "h")));
    assertFalse(scoped.test(Event.of("dark", "lounge", "spec_message_posted", "a", "h")));
    assertFalse(scoped.test(Event.of("light", "kitchen", "spec_message_posted", "a", "h")));
  }

  @Test
  void aScopedSubscriberIsSentOnlyTheEventsOfTheProjectsItsScopeAdmits() {
    var light = Event.of("light", null, "t", "a", "h");
    var dark = Event.of("dark", null, "t", "a", "h");
    var ofTheBox = SyncTransitionEvents.health("main@host", true, null, null, "h");

    var everything = SseHandler.parseFilter(URI.create("http://x/v1/events/stream"), SCOPED);
    var asked =
        SseHandler.parseFilter(URI.create("http://x/v1/events/stream?project=dark"), SCOPED);
    var unrestricted = SseHandler.parseFilter(URI.create("http://x/v1/events/stream"), ANYONE);

    assertEquals(
        List.of(true, false, false),
        List.of(everything.test(light), everything.test(dark), everything.test(ofTheBox)));
    assertEquals(
        List.of(false, false, false),
        List.of(asked.test(light), asked.test(dark), asked.test(ofTheBox)));
    assertEquals(
        List.of(true, true, true),
        List.of(unrestricted.test(light), unrestricted.test(dark), unrestricted.test(ofTheBox)));
  }

  @Test
  void aScopedStreamCarriesItsProjectsEventsAndAnUnrestrictedOneEveryEvent() throws Exception {
    ApiAuth auth =
        exchange -> {
          exchange.setAttribute("token.role", "member");
          if ("Bearer scoped".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.setAttribute("token.fde", "sam");
            exchange.setAttribute(ApiAuth.SCOPE, ProjectScope.of(List.of("light")));
          }
        };
    try (var bus = new EventBus()) {
      var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      httpServer.createContext("/v1/events/stream", new SseHandler(bus, auth));
      httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      httpServer.start();
      try {
        var port = httpServer.getAddress().getPort();

        var everyEvent = streamed(bus, port, "all", 3);
        var scopedEvents = streamed(bus, port, "scoped", 1);

        assertEquals(List.of("dark", "sail", "light"), everyEvent);
        assertEquals(List.of("light"), scopedEvents);
      } finally {
        httpServer.stop(0);
      }
    }
  }

  /**
   * The projects of the first {@code frames} events a subscriber authenticated by {@code token} is
   * sent, in order, of an event of another project, one of the box itself and one of {@code light},
   * published in that order once it has subscribed: a filtered event leaves a later one first.
   */
  private static List<String> streamed(EventBus bus, int port, String token, int frames)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/events/stream"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    var response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    var subscribed = new CountDownLatch(1);
    var received = new CountDownLatch(frames);
    var projects = new CopyOnWriteArrayList<String>();
    var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                String line;
                while ((line = reader.readLine()) != null) {
                  if (line.startsWith(": subscribed")) {
                    subscribed.countDown();
                  } else if (line.startsWith("data: ")) {
                    var event = YamlUtil.parseMap(line.substring("data: ".length()));
                    projects.add(event.get("project").toString());
                    received.countDown();
                  }
                }
              } catch (IOException closedByTheTest) {
                Thread.currentThread().interrupt();
              }
            });
    try {
      BusTesting.awaitDelivery(subscribed);
      bus.publish(Event.of("dark", null, "spec_dispatched", "sail", "h"));
      bus.publish(SyncTransitionEvents.health("main@host", true, null, null, "h"));
      bus.publish(Event.of("light", null, "spec_dispatched", "sail", "h"));
      BusTesting.awaitDelivery(received);
    } finally {
      response.body().close();
    }
    return List.copyOf(projects).subList(0, frames);
  }

  @Test
  void parseFilterIgnoresBlankParameterValues() {
    var filter =
        SseHandler.parseFilter(URI.create("http://x/v1/events/stream?project=&type="), ANYONE);
    assertTrue(filter.test(Event.of("anything", null, "anything", "a", "h")));
  }

  @Test
  void streamDeliversPublishedEventsAndClosesOnClient(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 32);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();

        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var request =
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + server.port() + "/v1/events/stream?project=light"))
                .header("Authorization", "Bearer tok")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());

        var reader =
            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

        var subscribedLatch = new CountDownLatch(1);
        var eventLatch = new CountDownLatch(1);
        var publishedId = new long[1];
        var receivedData = new String[1];

        var readerThread =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                          if (line.startsWith(": subscribed")) {
                            subscribedLatch.countDown();
                          } else if (line.startsWith("data: ")) {
                            receivedData[0] = line.substring("data: ".length());
                            eventLatch.countDown();
                            return;
                          }
                        }
                      } catch (Exception ignored) {
                        // reader closed
                      }
                    });

        BusTesting.awaitDelivery(subscribedLatch);
        var stamped = bus.publish(Event.of("light", null, "spec_dispatched", "sail", "h"));
        publishedId[0] = stamped.id();

        BusTesting.awaitDelivery(eventLatch);
        assertTrue(
            receivedData[0].contains("\"id\": " + publishedId[0]),
            "frame should carry the stamped id: " + receivedData[0]);

        readerThread.interrupt();
        response.body().close();
      }
    }
  }

  @Test
  void streamReturns405ForPostMethod(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 8);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();
        var client = HttpClient.newHttpClient();
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/events/stream"))
                    .header("Authorization", "Bearer tok")
                    .POST(HttpRequest.BodyPublishers.ofString(""))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
      }
    }
  }

  @Test
  void streamRejectsMissingToken(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 8);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();
        var response =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/v1/events/stream"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
      }
    }
  }

  @Test
  void streamReturns503WhenAtConnectionCap(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var handler = new SseHandler(bus, AUTH, 1);

      var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      httpServer.createContext("/v1/events/stream", handler);
      httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      httpServer.start();
      try {
        var port = httpServer.getAddress().getPort();
        var client = HttpClient.newHttpClient();

        var firstReq =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/events/stream"))
                .header("Authorization", "Bearer test-token")
                .GET()
                .build();
        var firstFuture = client.sendAsync(firstReq, HttpResponse.BodyHandlers.ofInputStream());
        var firstResponse = firstFuture.get(5, TimeUnit.SECONDS);
        assertEquals(200, firstResponse.statusCode());
        assertEquals(1, handler.openConnections());

        // Second connection should be rejected
        var secondReq =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/events/stream"))
                .header("Authorization", "Bearer test-token")
                .GET()
                .build();
        var second = client.send(secondReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, second.statusCode());
        assertTrue(handler.rejectedCount() >= 1);

        firstFuture.cancel(true);
      } finally {
        httpServer.stop(0);
      }
    }
  }
}
