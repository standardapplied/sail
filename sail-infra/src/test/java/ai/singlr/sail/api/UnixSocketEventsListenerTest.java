/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnixSocketEventsListenerTest {

  @Test
  void constructorRejectsNullBus(@TempDir Path dir) {
    assertThrows(
        NullPointerException.class,
        () -> new UnixSocketEventsListener(null, dir.resolve("api.sock")));
  }

  @Test
  void constructorRejectsNullPath() {
    try (var bus = new EventBus()) {
      assertThrows(NullPointerException.class, () -> new UnixSocketEventsListener(bus, null));
    }
  }

  @Test
  void postEventPublishesToBus(@TempDir Path dir) throws Exception {
    try (var bus = new EventBus()) {
      var received = new ArrayList<Event>();
      var latch = new CountDownLatch(1);
      bus.subscribe(
          subscriber(
              EventSubscriber.all(),
              e -> {
                synchronized (received) {
                  received.add(e);
                }
                latch.countDown();
              }));

      try (var listener = new UnixSocketEventsListener(bus, dir.resolve("api.sock"))) {
        listener.start();
        var event = Event.of("light-grid", "oauth-flow", "spec_dispatched", "sail", "host-01");
        var response = postEvent(listener.socketPath(), event.toJsonLine());

        assertTrue(response.startsWith("HTTP/1.1 202 Accepted"), response);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "subscriber should receive event");
        assertEquals(1, received.size());
        assertEquals("spec_dispatched", received.getFirst().type());
        assertTrue(received.getFirst().id() > 0);
        assertEquals(1, listener.acceptedCount());
        assertEquals(0, listener.badRequestCount());
      }
    }
  }

  @Test
  void wrongMethodReturns405(@TempDir Path dir) throws Exception {
    try (var bus = new EventBus();
        var listener = new UnixSocketEventsListener(bus, dir.resolve("api.sock"))) {
      listener.start();
      var response = sendRaw(listener.socketPath(), "GET /v1/events HTTP/1.1\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 405"));
    }
  }

  @Test
  void wrongPathReturns404(@TempDir Path dir) throws Exception {
    try (var bus = new EventBus();
        var listener = new UnixSocketEventsListener(bus, dir.resolve("api.sock"))) {
      listener.start();
      var response =
          sendRaw(listener.socketPath(), "POST /v1/wrong HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 404"));
    }
  }

  @Test
  void missingContentLengthReturns411(@TempDir Path dir) throws Exception {
    try (var bus = new EventBus();
        var listener = new UnixSocketEventsListener(bus, dir.resolve("api.sock"))) {
      listener.start();
      var response = sendRaw(listener.socketPath(), "POST /v1/events HTTP/1.1\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 411"));
      assertEquals(1, listener.badRequestCount());
    }
  }

  @Test
  void oversizedBodyReturns413(@TempDir Path dir) throws Exception {
    try (var bus = new EventBus();
        var listener = new UnixSocketEventsListener(bus, dir.resolve("api.sock"))) {
      listener.start();
      var response =
          sendRaw(
              listener.socketPath(),
              "POST /v1/events HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 413"));
    }
  }

  @Test
  void invalidJsonReturns400(@TempDir Path dir) throws Exception {
    try (var bus = new EventBus();
        var listener = new UnixSocketEventsListener(bus, dir.resolve("api.sock"))) {
      listener.start();
      var body = "{not even json}";
      var response =
          sendRaw(
              listener.socketPath(),
              "POST /v1/events HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
      assertTrue(response.startsWith("HTTP/1.1 400"));
      assertEquals(1, listener.badRequestCount());
    }
  }

  @Test
  void startIsIdempotent(@TempDir Path dir) throws Exception {
    try (var bus = new EventBus();
        var listener = new UnixSocketEventsListener(bus, dir.resolve("api.sock"))) {
      listener.start();
      listener.start();
      assertTrue(Files.exists(listener.socketPath()));
    }
  }

  @Test
  void closeRemovesSocketFile(@TempDir Path dir) throws Exception {
    var listener = new UnixSocketEventsListener(new EventBus(), dir.resolve("api.sock"));
    listener.start();
    assertTrue(Files.exists(listener.socketPath()));
    listener.close();
    assertFalse(Files.exists(listener.socketPath()));
  }

  private static String postEvent(Path socketPath, String body) throws Exception {
    var request =
        "POST /v1/events HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    return sendRaw(socketPath, request);
  }

  private static String sendRaw(Path socketPath, String request) throws Exception {
    try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.connect(UnixDomainSocketAddress.of(socketPath));
      channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
      channel.shutdownOutput();
      var out = new java.io.ByteArrayOutputStream();
      try (var in = Channels.newInputStream(channel)) {
        var buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
        }
      } catch (java.io.IOException ignored) {
        // server may close before we finish reading short error responses
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }

  private static EventSubscriber subscriber(
      Predicate<Event> filter, java.util.function.Consumer<Event> sink) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "test";
      }

      @Override
      public Predicate<Event> filter() {
        return filter;
      }

      @Override
      public void onEvent(Event event) {
        sink.accept(event);
      }
    };
  }

  static {
    // ensure List import is retained for the assertions above
    assert List.of() != null;
  }
}
