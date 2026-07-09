/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * SSE handler that streams a single run's log output. The address is {@code /v1/runs/{id}/stream}:
 * it resolves the run, applies the same provenance guard as the buffered log/stop endpoints — a run
 * whose {@code node} is not this box is refused with a structured {@code run_on_other_node} error
 * rather than tailing a foreign box's local file — and then tails the run's own {@code
 * ~/.sail/runs/<id>/agent.log} via {@code incus exec}, sending each line as an SSE {@code data:}
 * event. {@code ?since=N} replays from a line number for client reconnection.
 *
 * <p>Mounted by {@link ApiRouter}, which delegates the stream path straight to this handler so the
 * long-lived connection never runs through the buffered request/response path. It is not registered
 * as its own {@code createContext} because the path carries a variable run id, which
 * prefix-matching cannot isolate from the rest of {@code /v1/runs/...}.
 *
 * <p>Authentication is the {@code Authorization: Bearer} header only — the same gate {@code
 * /v1/events/stream} uses. A browser {@code EventSource} cannot set headers and is unsupported; a
 * native client that sets the header (Mast's Rust core, {@code sail agent stream}) authenticates
 * normally.
 */
public final class AgentLogStreamer implements HttpHandler {

  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
  private static final long HEARTBEAT_INTERVAL_NANOS = Duration.ofSeconds(15).toNanos();

  private final ApiAuth auth;
  private final Function<String, Optional<RunStore.RunRow>> runLookup;
  private final Supplier<String> localHandle;
  private final LongAdder activeStreams = new LongAdder();

  public AgentLogStreamer(ApiAuth auth) {
    this(auth, AgentLogStreamer::lookupFromDb, NodeIdentity::handle);
  }

  AgentLogStreamer(
      ApiAuth auth,
      Function<String, Optional<RunStore.RunRow>> runLookup,
      Supplier<String> localHandle) {
    this.auth = auth;
    this.runLookup = runLookup;
    this.localHandle = localHandle;
  }

  private static Optional<RunStore.RunRow> lookupFromDb(String runId) {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return new RunStore(db).findById(runId);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        sendError(exchange, 405, "method_not_allowed", "Method not allowed", null);
        return;
      }
      try {
        auth.require(exchange);
      } catch (ApiException e) {
        sendError(exchange, e.status(), "unauthorized", e.getMessage(), null);
        return;
      }

      var runId = extractRunId(exchange.getRequestURI().getPath());
      if (runId == null) {
        sendError(exchange, 400, "invalid_request", "Missing run id", null);
        return;
      }
      var run = runLookup.apply(runId).orElse(null);
      if (run == null) {
        sendError(exchange, 404, "run_not_found", "No run '" + runId + "'.", null);
        return;
      }
      if (SailApiOperations.isForeign(run, localHandle.get())) {
        sendForeign(exchange, run);
        return;
      }
      if (Strings.isBlank(run.logPath())) {
        sendError(exchange, 404, "run_not_found", "This run has no log file.", null);
        return;
      }
      var since = parseSince(exchange.getRequestURI().getQuery());
      streamLog(exchange, run.project(), run.logPath(), since);
    } finally {
      exchange.close();
    }
  }

  private void streamLog(HttpExchange exchange, String project, String logPath, int since)
      throws IOException {
    var tailCommand = buildTailCommand(project, logPath, since);
    Process tailProcess;
    try {
      tailProcess = new ProcessBuilder(tailCommand).redirectErrorStream(true).start();
    } catch (IOException e) {
      sendError(exchange, 502, "internal", "Failed to start log tail: " + e.getMessage(), null);
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

  /**
   * Tails the run's own log file, touched first so a stream opened before the agent has written any
   * output starts clean and empty — waiting for lines — rather than emitting tail's "cannot open"
   * error onto the stream.
   */
  static String[] buildTailCommand(String project, String logPath, int since) {
    var tail = since > 0 ? "tail -n +" + since + " -f " : "tail -f ";
    var script = "touch " + logPath + " 2>/dev/null; " + tail + logPath;
    return ContainerExec.asDevUser(project, List.of("bash", "-c", script)).toArray(String[]::new);
  }

  /** The run id in {@code /v1/runs/{id}/stream}, or null when the path is not that shape. */
  static String extractRunId(String path) {
    var segments = path.split("/");
    if (segments.length >= 4 && "runs".equals(segments[2])) {
      return segments[3];
    }
    return null;
  }

  /** True when {@code path} is exactly {@code /v1/runs/{id}/stream}. */
  static boolean isStreamPath(String path) {
    var segments = path.split("/");
    return segments.length == 5
        && "v1".equals(segments[1])
        && "runs".equals(segments[2])
        && "stream".equals(segments[4]);
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

  private void sendForeign(HttpExchange exchange, RunStore.RunRow run) throws IOException {
    var node = Strings.isBlank(run.node()) ? "an unknown node" : run.node();
    var extra =
        ", \"node\": \""
            + jsonEscape(Objects.toString(run.node(), ""))
            + "\", \"spec\": \""
            + jsonEscape(Objects.toString(run.specId(), ""))
            + "\", \"project\": \""
            + jsonEscape(Objects.toString(run.project(), ""))
            + "\"";
    sendError(
        exchange,
        409,
        "run_on_other_node",
        "Run " + run.id() + " executed on " + node + "; its logs live there, not on this box.",
        extra);
  }

  private static void sendError(
      HttpExchange exchange, int code, String errorCode, String message, String extra)
      throws IOException {
    var body =
        ("{\"code\": \""
                + errorCode
                + "\", \"message\": \""
                + jsonEscape(message)
                + "\""
                + Objects.toString(extra, "")
                + "}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
