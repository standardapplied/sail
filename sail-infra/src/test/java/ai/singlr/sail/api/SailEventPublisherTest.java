/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailEventPublisherTest {

  @Test
  void constructorRejectsNullHost() {
    assertThrows(NullPointerException.class, () -> new SailEventPublisher(null, 7070, "tok"));
  }

  @Test
  void constructorRejectsNullToken() {
    assertThrows(NullPointerException.class, () -> new SailEventPublisher("127.0.0.1", 7070, null));
  }

  @Test
  void constructorRejectsInvalidPort() {
    assertThrows(
        IllegalArgumentException.class, () -> new SailEventPublisher("127.0.0.1", 0, "tok"));
    assertThrows(
        IllegalArgumentException.class, () -> new SailEventPublisher("127.0.0.1", 70_000, "tok"));
  }

  @Test
  void publishRejectsNullEvent() {
    var publisher = new SailEventPublisher("127.0.0.1", 7070, "tok");
    assertThrows(NullPointerException.class, () -> publisher.publish(null));
  }

  @Test
  void publishStampsAndReturnsEventThroughLiveServer(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 32);
      var operations =
          new SailApiOperations(
              new ShellExecutor(true), tmp.resolve("sail.yaml").toString(), bus, persister);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              operations,
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        server.start();
        var publisher = new SailEventPublisher("127.0.0.1", server.port(), "tok");

        var stamped =
            publisher.publish(
                Event.of(
                    "light-grid",
                    "oauth-flow",
                    Event.WellKnownTypes.SPEC_DISPATCHED,
                    Event.SAIL_AGENT,
                    "host-01",
                    Map.of("mode", "background")));

        assertNotNull(stamped);
        assertTrue(stamped.id() > 0, "bus must assign a positive id");
        assertEquals("light-grid", stamped.project());
        assertEquals("oauth-flow", stamped.spec());
        assertEquals(Event.WellKnownTypes.SPEC_DISPATCHED, stamped.type());
        assertEquals("background", stamped.data().get("mode"));
      }
    }
  }

  @Test
  void publishThrowsOnAuthFailure(@TempDir Path tmp) throws Exception {
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
        var publisher = new SailEventPublisher("127.0.0.1", server.port(), "wrong-token");

        var ex =
            assertThrows(
                IOException.class,
                () ->
                    publisher.publish(
                        Event.of(
                            "p",
                            null,
                            Event.WellKnownTypes.SPEC_DISPATCHED,
                            Event.SAIL_AGENT,
                            "h")));

        assertTrue(
            ex.getMessage().contains("HTTP 401") || ex.getMessage().contains("HTTP 403"),
            "should surface the auth status code: " + ex.getMessage());
      }
    }
  }

  @Test
  void publishThrowsOnConnectionRefused() {
    var publisher = new SailEventPublisher("127.0.0.1", 1, "tok");

    assertThrows(
        IOException.class,
        () ->
            publisher.publish(
                Event.of("p", null, Event.WellKnownTypes.SPEC_DISPATCHED, Event.SAIL_AGENT, "h")));
  }

  @Test
  void parseStampedReadsTheEmbeddedEvent() {
    var json =
        """
        {
          "schema_version": 1,
          "id": 7,
          "event": {
            "v": 1,
            "id": 7,
            "ts": "2026-05-22T12:00:00Z",
            "project": "light-grid",
            "type": "spec_dispatched",
            "agent": "sail",
            "host": "h"
          }
        }
        """;

    var event = SailEventPublisher.parseStamped(json);

    assertEquals(7L, event.id());
    assertEquals("light-grid", event.project());
    assertEquals(Event.WellKnownTypes.SPEC_DISPATCHED, event.type());
  }

  @Test
  void parseStampedThrowsWhenEventFieldMissing() {
    var json = "{\"schema_version\": 1, \"id\": 7}";

    assertThrows(IllegalStateException.class, () -> SailEventPublisher.parseStamped(json));
  }

  @Test
  void localDefaultBuildsLocalhostPublisher() throws Exception {
    System.setProperty("SAIL_TOKEN", "test-token");
    System.setProperty("SAIL_SERVER", "http://127.0.0.1:7070");
    try {
      var publisher = SailEventPublisher.localDefault();
      assertNotNull(publisher);
    } finally {
      System.clearProperty("SAIL_TOKEN");
      System.clearProperty("SAIL_SERVER");
    }
  }
}
