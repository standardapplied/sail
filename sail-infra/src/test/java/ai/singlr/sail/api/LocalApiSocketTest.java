/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalApiSocketTest {

  private LocalApiSocket socket(Path dir) {
    return new LocalApiSocket(new EventBus(), new TestOperations(), dir.resolve("api.sock"));
  }

  @Test
  void rejectsNullHandlerAndNullPath(@TempDir Path dir) {
    assertThrows(
        NullPointerException.class, () -> new LocalApiSocket(null, dir.resolve("s.sock"), 4));
    assertThrows(
        NullPointerException.class,
        () -> new LocalApiSocket(new EventBus(), new TestOperations(), null));
  }

  @Test
  void postEventReturns202(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();
      var event = Event.of("light-grid", "oauth", "spec_dispatched", "sail", "host-01");
      var response = send(listener.socketPath(), post("/v1/events", event.toJsonLine()));
      assertTrue(response.startsWith("HTTP/1.1 202 Accepted"), response);
      assertEquals(1, listener.acceptedCount());
      assertEquals(0, listener.badRequestCount());
    }
  }

  @Test
  void createsAndListsSpecsOverTheSocket(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();

      var created =
          send(listener.socketPath(), form("POST", "/v1/specs", "id=oauth&title=OAuth%20Flow"));
      assertTrue(created.startsWith("HTTP/1.1 201 Created"), created);
      assertTrue(created.contains("oauth"));

      var listed = send(listener.socketPath(), get("/v1/specs?project=acme&status=pending"));
      assertTrue(listed.startsWith("HTTP/1.1 200 OK"), listed);
    }
  }

  @Test
  void unknownRouteAndWrongMethodComeFromTheRouter(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();
      assertTrue(send(listener.socketPath(), get("/v1/widgets")).startsWith("HTTP/1.1 404"));
      assertTrue(send(listener.socketPath(), get("/v1/events")).startsWith("HTTP/1.1 405"));
    }
  }

  @Test
  void malformedRequestLineReturns400(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();
      var response = send(listener.socketPath(), "GET\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 400"));
      assertEquals(1, listener.badRequestCount());
    }
  }

  @Test
  void oversizedBodyReturns413(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();
      var response =
          send(
              listener.socketPath(), "POST /v1/specs HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 413"));
    }
  }

  @Test
  void startIsIdempotentAndCloseRemovesTheSocket(@TempDir Path dir) throws Exception {
    var listener = socket(dir);
    listener.start();
    listener.start();
    assertTrue(Files.exists(listener.socketPath()));
    listener.close();
    assertFalse(Files.exists(listener.socketPath()));
  }

  private static String post(String path, String body) {
    return "POST " + path + " HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
  }

  private static String form(String method, String path, String body) {
    return method
        + " "
        + path
        + " HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: "
        + body.length()
        + "\r\n\r\n"
        + body;
  }

  private static String get(String path) {
    return "GET " + path + " HTTP/1.1\r\n\r\n";
  }

  private static String send(Path socketPath, String request) throws Exception {
    try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.connect(UnixDomainSocketAddress.of(socketPath));
      channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
      channel.shutdownOutput();
      var out = new ByteArrayOutputStream();
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
}
