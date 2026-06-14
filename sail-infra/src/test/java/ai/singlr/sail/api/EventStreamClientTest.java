/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventStreamClientTest {

  @Test
  void subscribeThrowsOnInvalidHost() {
    var queue = new LinkedBlockingQueue<Event>();

    assertThrows(
        IOException.class, () -> EventStreamClient.subscribe("127.0.0.1", 1, "tok", "any", queue));
  }

  @Test
  void subscribeRejectsNullArguments() {
    var queue = new LinkedBlockingQueue<Event>();

    assertThrows(
        NullPointerException.class,
        () -> EventStreamClient.subscribe(null, 7070, "tok", "p", queue));
    assertThrows(
        NullPointerException.class,
        () -> EventStreamClient.subscribe("127.0.0.1", 7070, null, "p", queue));
    assertThrows(
        NullPointerException.class,
        () -> EventStreamClient.subscribe("127.0.0.1", 7070, "tok", "p", null));
  }

  @Test
  void deliversPublishedEventsIntoQueue(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 32);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailApiOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();
        var queue = new LinkedBlockingQueue<Event>();

        try (var client =
            EventStreamClient.subscribe("127.0.0.1", server.port(), "tok", "light-grid", queue)) {
          var published =
              bus.publish(
                  Event.of(
                      "light-grid",
                      "oauth-flow",
                      Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                      "claude-code",
                      "host-01"));

          var received = queue.poll(5, TimeUnit.SECONDS);

          assertNotNull(received, "client should deliver the published event");
          assertEquals(published.id(), received.id());
          assertEquals("light-grid", received.project());
          assertEquals("oauth-flow", received.spec());
          assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, received.type());
        }
      }
    }
  }

  @Test
  void closeStopsTheReader(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 8);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailApiOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();
        var queue = new LinkedBlockingQueue<Event>();
        var client = EventStreamClient.subscribe("127.0.0.1", server.port(), "tok", "any", queue);
        client.close();

        bus.publish(
            Event.of(
                "any", null, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host"));
        var received = queue.poll(500, TimeUnit.MILLISECONDS);

        assertNull(received, "no events should arrive after close()");
      }
    }
  }

  @Test
  void subscribeThrowsOnNon200Response(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 8);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailApiOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();
        var queue = new LinkedBlockingQueue<Event>();

        var ex =
            assertThrows(
                IOException.class,
                () ->
                    EventStreamClient.subscribe(
                        "127.0.0.1", server.port(), "wrong-token", "any", queue));

        assertTrue(
            ex.getMessage().contains("HTTP 403") || ex.getMessage().contains("HTTP 401"),
            "should surface auth failure: " + ex);
      }
    }
  }

  @Test
  void processLineIgnoresNonDataLines() {
    var queue = new LinkedBlockingQueue<Event>();

    assertTrue(EventStreamClient.processLine(null, queue));
    assertTrue(EventStreamClient.processLine("", queue));
    assertTrue(EventStreamClient.processLine(": subscribed", queue));
    assertTrue(EventStreamClient.processLine("event: ping", queue));
    assertTrue(queue.isEmpty(), "no event should be queued for non-data lines");
  }

  @Test
  void processLineDeliversWellFormedEvent() throws Exception {
    var queue = new LinkedBlockingQueue<Event>();
    var ts = "2026-05-21T12:34:56Z";
    var json =
        "{\"v\":1,\"id\":7,\"ts\":\""
            + ts
            + "\",\"project\":\"p\",\"type\":\"t\",\"agent\":\"a\",\"host\":\"h\"}";

    var ok = EventStreamClient.processLine("data: " + json, queue);

    assertTrue(ok);
    var event = queue.poll(100, TimeUnit.MILLISECONDS);
    assertNotNull(event);
    assertEquals(7L, event.id());
  }

  @Test
  void processLineDropsMalformedPayloadButKeepsReading() {
    var queue = new LinkedBlockingQueue<Event>();

    var ok = EventStreamClient.processLine("data: {not-json", queue);

    assertTrue(ok, "parse failure must not stop the reader");
    assertTrue(queue.isEmpty());
  }

  @Test
  void processLineReturnsFalseWhenInterrupted() throws Exception {
    var queue = new java.util.concurrent.ArrayBlockingQueue<Event>(1);
    queue.put(Event.of("p", null, "t", "a", "h"));
    var ts = "2026-05-21T12:34:56Z";
    var json =
        "{\"v\":1,\"ts\":\""
            + ts
            + "\",\"project\":\"p\",\"type\":\"t\",\"agent\":\"a\",\"host\":\"h\"}";

    var task =
        new java.util.concurrent.FutureTask<Boolean>(
            () -> EventStreamClient.processLine("data: " + json, queue));
    var thread = new Thread(task);
    thread.start();
    while (thread.getState() != Thread.State.WAITING
        && thread.getState() != Thread.State.TIMED_WAITING) {
      Thread.onSpinWait();
    }
    thread.interrupt();

    var result = task.get(2, TimeUnit.SECONDS);
    assertFalse(result, "interrupt should make processLine return false");
  }

  @Test
  void filtersByProject(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailApiOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();
        var queue = new LinkedBlockingQueue<Event>();

        try (var ignored =
            EventStreamClient.subscribe("127.0.0.1", server.port(), "tok", "light-grid", queue)) {
          bus.publish(
              Event.of(
                  "other-project",
                  null,
                  Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                  "claude-code",
                  "host"));
          var match =
              bus.publish(
                  Event.of(
                      "light-grid",
                      null,
                      Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                      "claude-code",
                      "host"));

          var received = queue.poll(5, TimeUnit.SECONDS);

          assertNotNull(received);
          assertEquals(match.id(), received.id(), "filter must drop the other-project event");
          assertEquals("light-grid", received.project());
        }
      }
    }
  }
}
