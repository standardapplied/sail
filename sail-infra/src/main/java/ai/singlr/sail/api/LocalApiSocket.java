/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Listens on a Unix domain socket and serves the small local API ({@link LocalApiRouter}) to
 * project containers, which see the same socket via an Incus disk bind-mount. Filesystem
 * permissions are the authentication — a container that can {@code write(2)} the socket is trusted,
 * no bearer token.
 *
 * <p>Implements a minimal HTTP/1.1 server: it parses the request line, the {@code Content-Length},
 * and the body, hands a {@link LocalApiRequest} to the {@link LocalApiHandler}, and serializes the
 * {@link ApiResponse} back as JSON. We do not embed {@code com.sun.net.httpserver.HttpServer}
 * because it cannot bind a {@link UnixDomainSocketAddress}; a small ad-hoc parser is simpler than a
 * third-party HTTP library.
 *
 * <p>Each connection runs on a bounded virtual thread. Excess connections are rejected with {@code
 * 503 Service Unavailable} so a buggy client cannot exhaust file descriptors.
 */
public final class LocalApiSocket implements AutoCloseable {

  private static final int MAX_HEADER_BYTES = 8 * 1024;
  private static final int MAX_BODY_BYTES = 1024 * 1024;
  private static final int DEFAULT_MAX_IN_FLIGHT = 64;
  private static final String CONTENT_LENGTH = "content-length";

  private final LocalApiHandler handler;
  private final Path socketPath;
  private final BoundedVirtualExecutor acceptExecutor;
  private final LongAdder accepted = new LongAdder();
  private final LongAdder rejectedOverflow = new LongAdder();
  private final LongAdder badRequests = new LongAdder();
  private volatile ServerSocketChannel server;
  private volatile Thread acceptLoop;
  private volatile boolean closed;

  public LocalApiSocket(EventBus bus, ApiOperations operations, Path socketPath) {
    this(new LocalApiRouter(bus, operations), socketPath, DEFAULT_MAX_IN_FLIGHT);
  }

  LocalApiSocket(LocalApiHandler handler, Path socketPath, int maxInFlight) {
    this.handler = Objects.requireNonNull(handler, "handler");
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
    makeWorldWritable();
    this.server = channel;
    this.acceptLoop = Thread.ofVirtual().name("sail-uds-accept").start(this::runAcceptLoop);
  }

  private void makeWorldWritable() {
    // Unprivileged Incus containers connect from inside, where their UID is host-shifted (host uid
    // 1000000 maps to container root). Linux requires WRITE permission on the socket file to
    // connect(), so the default 0755 blocks every container process that isn't host-root. 0666 is
    // the standard answer: access is gated by the bind-mount (only sail-provisioned containers see
    // the socket) and at the application layer by the small route surface the router serves.
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

  /** Requests rejected because they were malformed (bad HTTP, missing length, etc). */
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
          writeStatus(sock, 503, "Retry-After: 1\r\n");
        } catch (IOException ignored) {
          // client closed
        }
      }
    }
  }

  private void handleConnection(SocketChannel client) {
    try (client;
        var in = Channels.newInputStream(client);
        var out = Channels.newOutputStream(client)) {
      var requestLine = readLine(in, MAX_HEADER_BYTES);
      if (requestLine == null) {
        badRequests.increment();
        writeStatus(out, 400, null);
        return;
      }
      var parts = requestLine.split(" ");
      if (parts.length < 3) {
        badRequests.increment();
        writeStatus(out, 400, null);
        return;
      }
      var method = parts[0].toUpperCase();
      var target = parts[1];
      var contentLength = readContentLength(in);
      if (contentLength > MAX_BODY_BYTES) {
        badRequests.increment();
        writeStatus(out, 413, null);
        return;
      }

      byte[] body = contentLength <= 0 ? new byte[0] : in.readNBytes(contentLength);
      if (body.length != Math.max(0, contentLength)) {
        badRequests.increment();
        writeStatus(out, 400, null);
        return;
      }

      var queryStart = target.indexOf('?');
      var path = queryStart >= 0 ? target.substring(0, queryStart) : target;
      var query =
          queryStart >= 0
              ? LocalApiRequest.decode(target.substring(queryStart + 1))
              : Map.<String, String>of();
      var response = handler.handle(new LocalApiRequest(method, path, query, body));
      writeResponse(out, response);
    } catch (IOException io) {
      // client disconnected mid-stream
    } catch (RuntimeException unexpected) {
      System.err.println("sail-uds: handler error: " + unexpected.getMessage());
    }
  }

  private static int readContentLength(InputStream in) throws IOException {
    var length = 0;
    while (true) {
      var line = readLine(in, MAX_HEADER_BYTES);
      if (line == null || line.isEmpty()) {
        return length;
      }
      var colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      if (!CONTENT_LENGTH.equals(line.substring(0, colon).toLowerCase())) {
        continue;
      }
      try {
        length = Integer.parseInt(line.substring(colon + 1).strip());
      } catch (NumberFormatException ignored) {
        length = -1;
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

  private static void writeStatus(SocketChannel channel, int status, String extraHeaders)
      throws IOException {
    try (var out = Channels.newOutputStream(channel)) {
      writeStatus(out, status, extraHeaders);
    }
  }

  private static void writeStatus(OutputStream out, int status, String extraHeaders)
      throws IOException {
    var headers = extraHeaders == null ? "" : extraHeaders;
    var response =
        "HTTP/1.1 "
            + status
            + " "
            + reason(status)
            + "\r\nContent-Length: 0\r\n"
            + headers
            + "\r\n";
    out.write(response.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void writeResponse(OutputStream out, ApiResponse response) throws IOException {
    var json = YamlUtil.dumpJson(new LinkedHashMap<>(response.body()));
    var body = json.getBytes(StandardCharsets.UTF_8);
    var head =
        "HTTP/1.1 "
            + response.status()
            + " "
            + reason(response.status())
            + "\r\nContent-Type: application/json\r\nContent-Length: "
            + body.length
            + "\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }

  private static String reason(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 202 -> "Accepted";
      case 400 -> "Bad Request";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 409 -> "Conflict";
      case 413 -> "Payload Too Large";
      case 500 -> "Internal Server Error";
      case 503 -> "Service Unavailable";
      default -> "Status";
    };
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
