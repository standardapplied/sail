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
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
      var event =
          Event.of(
              "light-grid", "oauth", Event.WellKnownTypes.AGENT_SESSION_STARTED, "sail", "host-01");
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
  void missingCredentialReturns401(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();
      var response = send(listener.socketPath(), "GET /v1/specs HTTP/1.1\r\n\r\n");
      assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"), response);
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
  void oversizedHeaderBlockReturns431(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();
      var flood = new StringBuilder("GET /v1/specs HTTP/1.1\r\n");
      for (var i = 0; i < 300; i++) {
        flood.append("x-flood-").append(i).append(": ").append("x".repeat(40)).append("\r\n");
      }
      flood.append(AUTH).append("\r\n");
      var response = send(listener.socketPath(), flood.toString());
      assertTrue(response.startsWith("HTTP/1.1 431"), response);
      assertEquals(1, listener.badRequestCount());
    }
  }

  @Test
  void truncatedHeaderBlockReturns431(@TempDir Path dir) throws Exception {
    try (var listener = socket(dir)) {
      listener.start();
      var response = send(listener.socketPath(), "GET /v1/specs HTTP/1.1\r\nx-partial: yes");
      assertTrue(response.startsWith("HTTP/1.1 431"), response);
      assertEquals(1, listener.badRequestCount());
    }
  }

  @Test
  void retainsOnlyTheConsumedHeaders(@TempDir Path dir) throws Exception {
    var seen = new AtomicReference<Map<String, String>>();
    try (var listener =
        new LocalApiSocket(
            request -> {
              seen.set(request.headers());
              return new ApiResponse(200, Map.of());
            },
            dir.resolve("api.sock"),
            4)) {
      listener.start();
      var request =
          "GET /v1/specs HTTP/1.1\r\nUser-Agent: sail-test\r\nX-Extra: 1\r\n"
              + AUTH
              + "Content-Length: 0\r\n\r\n";
      var response = send(listener.socketPath(), request);
      assertTrue(response.startsWith("HTTP/1.1 200"), response);
      assertEquals(Set.of("authorization", "content-length"), seen.get().keySet());
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
  void startCreatesTheSocketDirTraversableForUnprivilegedContainers(@TempDir Path dir)
      throws Exception {
    var runDir = dir.resolve("run");
    try (var listener =
        new LocalApiSocket(new EventBus(), new TestOperations(), runDir.resolve("api.sock"))) {
      listener.start();
      assertTrue(Files.isDirectory(runDir), "the socket dir is created");
      assertEquals(
          PosixFilePermissions.fromString("rwxr-xr-x"),
          Files.getPosixFilePermissions(runDir),
          "containers' host-shifted UID needs o+rx to traverse to the socket");
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

  private static final String AUTH =
      "Authorization: Bearer " + TestOperations.RUN_CREDENTIAL + "\r\n";

  private static String post(String path, String body) {
    return "POST "
        + path
        + " HTTP/1.1\r\n"
        + AUTH
        + "Content-Length: "
        + body.length()
        + "\r\n\r\n"
        + body;
  }

  private static String form(String method, String path, String body) {
    return method
        + " "
        + path
        + " HTTP/1.1\r\n"
        + AUTH
        + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: "
        + body.length()
        + "\r\n\r\n"
        + body;
  }

  private static String get(String path) {
    return "GET " + path + " HTTP/1.1\r\n" + AUTH + "\r\n";
  }

  private static String send(Path socketPath, String request) throws Exception {
    try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.connect(UnixDomainSocketAddress.of(socketPath));
      writeToleratingEarlyRefusal(channel, request);
      var out = new ByteArrayOutputStream();
      try (var in = Channels.newInputStream(channel)) {
        var buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
        }
      } catch (IOException serverClosedEarly) {
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }

  /**
   * Writes the request, tolerating the server refusing mid-write: a listener that answers 431 and
   * closes while the client is still sending surfaces as EPIPE on macOS, and that early cut-off is
   * exactly the behavior under test — the queued response is then read back as usual.
   */
  private static void writeToleratingEarlyRefusal(SocketChannel channel, String request) {
    try {
      channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
      channel.shutdownOutput();
    } catch (IOException serverRefusedMidWrite) {
    }
  }
}
