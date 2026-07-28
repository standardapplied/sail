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
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Listens on a Unix domain socket and serves the small local API ({@link LocalApiRouter}) to
 * project containers, which see the same socket via an Incus disk bind-mount. Reachability is gated
 * by filesystem permissions — only sail-provisioned containers see the socket — and every request
 * must additionally present a live run credential as a bearer token, which {@link LocalApiRouter}
 * resolves to the acting run before routing.
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
  private static final Set<String> RETAINED_HEADERS = Set.of(CONTENT_LENGTH, "authorization");

  private final LocalApiHandler handler;
  private final Path socketPath;
  private final BoundedVirtualExecutor acceptExecutor;
  private final LongAdder accepted = new LongAdder();
  private final LongAdder rejectedOverflow = new LongAdder();
  private final LongAdder badRequests = new LongAdder();
  private volatile ServerSocketChannel server;
  private volatile Thread acceptLoop;
  private volatile boolean closed;

  public LocalApiSocket(EventBus bus, Operations operations, Path socketPath) {
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
      makeDirTraversable(socketPath.getParent());
    }
    Files.deleteIfExists(socketPath);
    var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socketPath));
    makeWorldWritable();
    this.server = channel;
    this.acceptLoop = Thread.ofVirtual().name("sail-uds-accept").start(this::runAcceptLoop);
  }

  /**
   * Makes the socket's parent directory world-traversable (0755). The socket dir is bind-mounted
   * into unprivileged project containers, whose host-shifted UID needs {@code o+x} to reach the
   * socket inside it. Previously the systemd unit's {@code RuntimeDirectoryMode=0755} set this;
   * with the socket moved to a persistent directory the server owns that guarantee itself,
   * independent of the process umask.
   */
  private static void makeDirTraversable(Path dir) {
    try {
      Files.setPosixFilePermissions(
          dir,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_EXECUTE));
    } catch (UnsupportedOperationException | IOException permError) {
      System.err.println(
          "  [sail-uds] Warning: could not chmod 0755 "
              + dir
              + " ("
              + permError.getMessage()
              + "). Unprivileged containers may fail to reach the socket.");
    }
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
      var headers = readHeaders(in);
      if (headers == null) {
        badRequests.increment();
        writeStatus(out, 431, null);
        return;
      }
      var contentLength = contentLength(headers);
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
      var response = handler.handle(new LocalApiRequest(method, path, query, headers, body));
      writeResponse(out, response);
    } catch (IOException io) {
      // client disconnected mid-stream
    } catch (RuntimeException unexpected) {
      System.err.println("sail-uds: handler error: " + unexpected.getMessage());
    }
  }

  /**
   * Parses the header block within a single {@link #MAX_HEADER_BYTES} budget shared by every line,
   * retaining only the two headers the server consumes ({@code Content-Length} and {@code
   * Authorization}) so an unauthenticated client cannot grow retained memory by streaming uniquely
   * named headers. Returns null when the block overruns the budget or the stream ends before the
   * terminating blank line.
   */
  private static Map<String, String> readHeaders(InputStream in) throws IOException {
    var headers = new LinkedHashMap<String, String>();
    var remaining = MAX_HEADER_BYTES;
    while (true) {
      var line = readLine(in, remaining);
      if (line == null) {
        return null;
      }
      if (line.isEmpty()) {
        return headers;
      }
      remaining -= line.getBytes(StandardCharsets.UTF_8).length + 2;
      var colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      var name = line.substring(0, colon).toLowerCase();
      if (RETAINED_HEADERS.contains(name)) {
        headers.put(name, line.substring(colon + 1).strip());
      }
    }
  }

  private static int contentLength(Map<String, String> headers) {
    var value = headers.get(CONTENT_LENGTH);
    if (value == null) {
      return 0;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return -1;
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
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 409 -> "Conflict";
      case 413 -> "Payload Too Large";
      case 431 -> "Request Header Fields Too Large";
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
