/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Listens on a Unix domain socket and accepts {@code POST /v1/events} requests from project
 * containers (which see the same socket via an Incus disk bind-mount). Filesystem permissions are
 * the authentication — clients that can {@code write(2)} the socket can publish events, no bearer
 * token required.
 *
 * <p>Implements a minimal HTTP/1.1 server scoped to a single endpoint. We do not embed {@code
 * com.sun.net.httpserver.HttpServer} because it cannot bind a {@link UnixDomainSocketAddress};
 * keeping a small ad-hoc parser is simpler than dragging in a third-party HTTP library.
 *
 * <p>Each connection is handled on a bounded virtual thread. Excess connections are rejected with
 * {@code HTTP/1.1 503 Service Unavailable} so a buggy client cannot exhaust file descriptors.
 */
public final class UnixSocketEventsListener implements AutoCloseable {

  private static final int MAX_HEADER_BYTES = 8 * 1024;
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private static final int DEFAULT_MAX_IN_FLIGHT = 64;
  private static final String EVENTS_PATH = "/v1/events";
  private static final String CONTENT_LENGTH = "content-length";

  private final EventBus bus;
  private final Path socketPath;
  private final BoundedVirtualExecutor acceptExecutor;
  private final LongAdder accepted = new LongAdder();
  private final LongAdder rejectedOverflow = new LongAdder();
  private final LongAdder badRequests = new LongAdder();
  private volatile ServerSocketChannel server;
  private volatile Thread acceptLoop;
  private volatile boolean closed;

  public UnixSocketEventsListener(EventBus bus, Path socketPath) {
    this(bus, socketPath, DEFAULT_MAX_IN_FLIGHT);
  }

  public UnixSocketEventsListener(EventBus bus, Path socketPath, int maxInFlight) {
    this.bus = Objects.requireNonNull(bus, "bus");
    this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
    this.acceptExecutor = new BoundedVirtualExecutor(maxInFlight);
  }

  /** Binds the socket and starts the accept loop. Idempotent: re-calling is a no-op. */
  public void start() throws IOException {
    if (server != null) {
      return;
    }
    if (socketPath.getParent() != null) {
      Files.createDirectories(socketPath.getParent());
    }
    Files.deleteIfExists(socketPath);
    var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socketPath));
    // Unprivileged Incus containers bind-mount this socket and connect from inside, where their
    // UID is host-shifted (e.g. host uid 1000000 maps to container root). Linux requires WRITE
    // permission on the socket file to connect(), so the default umask-derived 0755 blocks every
    // container process that isn't host-root. World-writable (0666) is the standard answer:
    // access is gated by the bind-mount itself (only sail-provisioned containers see the socket)
    // and at the application layer by the events-only HTTP routes the listener serves.
    try {
      Files.setPosixFilePermissions(
          socketPath,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_WRITE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_WRITE));
    } catch (UnsupportedOperationException | IOException permError) {
      System.err.println(
          "  [sail-uds] Warning: could not chmod 0666 "
              + socketPath
              + " ("
              + permError.getMessage()
              + "). Unprivileged containers may fail to connect.");
    }
    this.server = channel;
    this.acceptLoop = Thread.ofVirtual().name("sail-uds-accept").start(this::runAcceptLoop);
  }

  /** Absolute path of the bound socket. */
  public Path socketPath() {
    return socketPath;
  }

  /** Total connections accepted. */
  public long acceptedCount() {
    return accepted.sum();
  }

  /** Connections rejected because the executor cap was hit. */
  public long rejectedOverflowCount() {
    return rejectedOverflow.sum();
  }

  /** Requests rejected because they were malformed (bad HTTP, bad JSON, etc). */
  public long badRequestCount() {
    return badRequests.sum();
  }

  @Override
  public void close() {
    closed = true;
    closeQuietly(server);
    var loop = acceptLoop;
    if (loop != null) {
      try {
        loop.join(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    acceptExecutor.close();
    try {
      Files.deleteIfExists(socketPath);
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }

  private void runAcceptLoop() {
    while (!closed) {
      SocketChannel client;
      try {
        client = server.accept();
      } catch (IOException e) {
        if (closed) {
          return;
        }
        System.err.println("sail-uds: accept failed: " + e.getMessage());
        continue;
      }
      accepted.increment();
      var pending = client;
      var submitted = acceptExecutor.tryRun(() -> handleConnection(pending));
      if (!submitted) {
        rejectedOverflow.increment();
        try (var sock = pending) {
          writeStatus(sock, "503 Service Unavailable", "Retry-After: 1\r\n");
        } catch (IOException ignored) {
          // client closed
        }
      }
    }
  }

  private static void writeStatus(SocketChannel channel, String status, String extraHeaders)
      throws IOException {
    try (var out = Channels.newOutputStream(channel)) {
      writeStatus(out, status, extraHeaders);
    }
  }

  private void handleConnection(SocketChannel client) {
    try (client;
        var in = Channels.newInputStream(client);
        var out = Channels.newOutputStream(client)) {
      var requestLine = readLine(in, MAX_HEADER_BYTES);
      if (requestLine == null) {
        badRequests.increment();
        writeStatus(out, "400 Bad Request", null);
        return;
      }
      var parts = requestLine.split(" ");
      if (parts.length < 3) {
        badRequests.increment();
        writeStatus(out, "400 Bad Request", null);
        return;
      }
      var method = parts[0];
      var path = parts[1];
      if (!"POST".equalsIgnoreCase(method)) {
        writeStatus(out, "405 Method Not Allowed", "Allow: POST\r\n");
        return;
      }
      var queryStart = path.indexOf('?');
      var rawPath = queryStart >= 0 ? path.substring(0, queryStart) : path;
      if (!EVENTS_PATH.equals(rawPath)) {
        writeStatus(out, "404 Not Found", null);
        return;
      }

      var contentLength = readContentLength(in);
      if (contentLength < 0) {
        badRequests.increment();
        writeStatus(out, "411 Length Required", null);
        return;
      }
      if (contentLength > MAX_BODY_BYTES) {
        badRequests.increment();
        writeStatus(out, "413 Payload Too Large", null);
        return;
      }

      var body = in.readNBytes(contentLength);
      if (body.length != contentLength) {
        badRequests.increment();
        writeStatus(out, "400 Bad Request", null);
        return;
      }

      Event event;
      try {
        event = Event.fromJsonLine(new String(body, StandardCharsets.UTF_8));
      } catch (Exception parseFailure) {
        badRequests.increment();
        writeStatus(out, "400 Bad Request", null);
        return;
      }

      var stamped = bus.publish(event);
      var responseBody = "{\"id\":" + stamped.id() + "}";
      writeResponse(out, "202 Accepted", responseBody);
    } catch (IOException io) {
      // client disconnected mid-stream — best-effort, nothing to do
    } catch (RuntimeException unexpected) {
      System.err.println("sail-uds: handler error: " + unexpected.getMessage());
    }
  }

  private static int readContentLength(InputStream in) throws IOException {
    int total = -1;
    while (true) {
      var line = readLine(in, MAX_HEADER_BYTES);
      if (line == null) {
        return -1;
      }
      if (line.isEmpty()) {
        return total;
      }
      var colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      var name = line.substring(0, colon).toLowerCase();
      if (!CONTENT_LENGTH.equals(name)) {
        continue;
      }
      try {
        total = Integer.parseInt(line.substring(colon + 1).strip());
      } catch (NumberFormatException ignored) {
        return -1;
      }
    }
  }

  private static String readLine(InputStream in, int maxBytes) throws IOException {
    var buf = new ByteArrayOutputStream(128);
    var seenCr = false;
    while (buf.size() < maxBytes) {
      var b = in.read();
      if (b < 0) {
        return null;
      }
      if (seenCr && b == '\n') {
        var bytes = buf.toByteArray();
        return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
      }
      seenCr = b == '\r';
      buf.write(b);
    }
    return null;
  }

  private static void writeStatus(OutputStream out, String status, String extraHeaders)
      throws IOException {
    var headers = extraHeaders == null ? "" : extraHeaders;
    var response = "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\n" + headers + "\r\n";
    out.write(response.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void writeResponse(OutputStream out, String status, String jsonBody)
      throws IOException {
    var body = jsonBody.getBytes(StandardCharsets.UTF_8);
    var response =
        "HTTP/1.1 "
            + status
            + "\r\nContent-Type: application/json\r\nContent-Length: "
            + body.length
            + "\r\n\r\n";
    out.write(response.getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }

  private static void closeQuietly(AutoCloseable resource) {
    if (resource != null) {
      try {
        resource.close();
      } catch (Exception ignored) {
        // best-effort
      }
    }
  }
}
