/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.NameValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * SSE handler that streams agent log output from a container. Tails the agent log file via {@code
 * incus exec} and sends each line as an SSE {@code data:} event. Supports {@code ?since=N} to
 * replay from a specific line number for client reconnection.
 *
 * <p>Mounted by {@link ApiRouter}, which delegates {@code GET /v1/projects/{p}/agent/stream}
 * straight to this handler so the long-lived connection never runs through the buffered
 * request/response path. It is not registered as its own {@link
 * com.sun.net.httpserver.HttpServer#createContext} because the path carries a variable project
 * segment, which prefix-matching cannot isolate from the rest of {@code /v1/projects/...}.
 *
 * <p>Authentication is the {@code Authorization: Bearer} header only — the same gate {@code
 * /v1/events/stream} uses (accepting {@code sess_} login sessions via {@link SessionAwareAuth}).
 * There is deliberately no query-parameter token: a browser {@code EventSource} cannot set headers
 * and is unsupported, but a native client that sets the header (Mast's Rust core, {@code sail agent
 * stream}) authenticates normally.
 *
 * <p>The tail runs as a raw long-lived {@link Process} rather than through {@link
 * ai.singlr.sail.engine.ShellExec}: {@code tail -f} never terminates, so the streaming loop needs
 * the live process handle and incremental stdout, not a run-to-completion executor. The command
 * itself is built through {@link ContainerExec#asDevUser} for parity with every other in-container
 * invocation.
 */
public final class AgentLogStreamer implements HttpHandler {

  private static final String LOG_PATH = "/home/dev/.sail/agent.log";
  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
  private static final long HEARTBEAT_INTERVAL_NANOS = Duration.ofSeconds(15).toNanos();

  private final ApiAuth auth;
  private final LongAdder activeStreams = new LongAdder();

  public AgentLogStreamer(ApiAuth auth) {
    this.auth = auth;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        sendError(exchange, 405, "Method not allowed");
        return;
      }
      try {
        auth.require(exchange);
      } catch (ApiException e) {
        sendError(exchange, e.status(), e.getMessage());
        return;
      }

      var project = extractProject(exchange.getRequestURI().getPath());
      if (project == null) {
        sendError(exchange, 400, "Missing project name");
        return;
      }
      try {
        NameValidator.requireValidProjectName(project);
      } catch (IllegalArgumentException e) {
        sendError(exchange, 400, "Invalid project name");
        return;
      }

      var since = parseSince(exchange.getRequestURI().getQuery());
      streamLog(exchange, project, since);
    } finally {
      exchange.close();
    }
  }

  private void streamLog(HttpExchange exchange, String project, int since) throws IOException {
    var tailCommand = buildTailCommand(project, since);
    Process tailProcess;
    try {
      tailProcess = new ProcessBuilder(tailCommand).redirectErrorStream(true).start();
    } catch (IOException e) {
      sendError(exchange, 502, "Failed to start log tail: " + e.getMessage());
      return;
    }

    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/event-stream; charset=utf-8");
    headers.set("Cache-Control", "no-cache");
    headers.set("Connection", "keep-alive");
    headers.set("X-Accel-Buffering", "no");
    exchange.sendResponseHeaders(200, 0);

    activeStreams.increment();
    try (var out = exchange.getResponseBody();
        var reader =
            new BufferedReader(
                new InputStreamReader(tailProcess.getInputStream(), StandardCharsets.UTF_8))) {
      writeComment(out, "streaming " + project);
      pump(tailProcess, reader, out, since);
    } catch (IOException e) {
      // Client disconnected — expected during streaming
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      tailProcess.destroyForcibly();
      activeStreams.decrement();
    }
  }

  /**
   * Relays the tail's output as SSE until the client disconnects or the tail process exits. Ending
   * on process death is the load-bearing part: {@code tail -f} in a container that never existed
   * (bad project) or whose agent has stopped exits, and without this the loop would heartbeat onto
   * a dead pipe forever, holding the connection open and never closing the stream. Buffered lines
   * are drained through the ready() branch before the exit is observed, so no output is lost.
   */
  static void pump(Process tailProcess, BufferedReader reader, OutputStream out, int since)
      throws IOException, InterruptedException {
    var lineNumber = since > 0 ? since : 1;
    var lastHeartbeat = System.nanoTime();
    while (true) {
      if (reader.ready()) {
        var line = reader.readLine();
        if (line == null) {
          break;
        }
        writeSseData(out, lineNumber, line);
        lineNumber++;
        lastHeartbeat = System.nanoTime();
      } else if (!tailProcess.isAlive()) {
        break;
      } else {
        if (System.nanoTime() - lastHeartbeat > HEARTBEAT_INTERVAL_NANOS) {
          out.write(HEARTBEAT);
          out.flush();
          lastHeartbeat = System.nanoTime();
        }
        Thread.sleep(100);
      }
    }
  }

  public long activeStreamCount() {
    return activeStreams.sum();
  }

  static String[] buildTailCommand(String project, int since) {
    var tail = since > 0 ? "tail -n +" + since + " -f " : "tail -f ";
    return ContainerExec.asDevUser(project, List.of("bash", "-c", tail + LOG_PATH))
        .toArray(String[]::new);
  }

  static String extractProject(String path) {
    var segments = path.split("/");
    if (segments.length >= 5 && "projects".equals(segments[2])) {
      return segments[3];
    }
    return null;
  }

  /** True when {@code path} is exactly {@code /v1/projects/{project}/agent/stream}. */
  static boolean isStreamPath(String path) {
    var segments = path.split("/");
    return segments.length == 6
        && "v1".equals(segments[1])
        && "projects".equals(segments[2])
        && "agent".equals(segments[4])
        && "stream".equals(segments[5]);
  }

  static int parseSince(String query) {
    if (query == null) return 0;
    for (var part : query.split("&")) {
      if (part.startsWith("since=")) {
        try {
          return Integer.parseInt(part.substring(6));
        } catch (NumberFormatException e) {
          return 0;
        }
      }
    }
    return 0;
  }

  private static void writeSseData(OutputStream out, int lineNumber, String line)
      throws IOException {
    out.write(("id: " + lineNumber + "\ndata: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void writeComment(OutputStream out, String comment) throws IOException {
    out.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void sendError(HttpExchange exchange, int code, String message)
      throws IOException {
    var body = ("{\"error\": \"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
