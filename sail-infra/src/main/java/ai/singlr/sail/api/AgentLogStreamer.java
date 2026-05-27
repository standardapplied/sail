/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.ShellExec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

/**
 * SSE handler that streams agent log output from a container. Tails the agent log file via {@code
 * incus exec} and sends each line as an SSE {@code data:} event. Supports {@code ?since=N} to
 * replay from a specific line number for client reconnection.
 */
public final class AgentLogStreamer implements HttpHandler {

  private static final String LOG_PATH = "/home/dev/.sail/agent.log";
  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
  private static final long HEARTBEAT_INTERVAL_MS = 15_000;

  private final ApiAuth auth;
  private final ShellExec shell;
  private final LongAdder activeStreams = new LongAdder();

  public AgentLogStreamer(ApiAuth auth, ShellExec shell) {
    this.auth = auth;
    this.shell = shell;
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
      var lineNumber = since > 0 ? since : 1;
      var lastHeartbeat = System.currentTimeMillis();

      while (true) {
        if (reader.ready()) {
          var line = reader.readLine();
          if (line == null) break;
          writeSseData(out, lineNumber, line);
          lineNumber++;
          lastHeartbeat = System.currentTimeMillis();
        } else {
          if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_INTERVAL_MS) {
            out.write(HEARTBEAT);
            out.flush();
            lastHeartbeat = System.currentTimeMillis();
          }
          Thread.sleep(100);
        }
      }
    } catch (IOException e) {
      // Client disconnected — expected during streaming
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      tailProcess.destroyForcibly();
      activeStreams.decrement();
    }
  }

  public long activeStreamCount() {
    return activeStreams.sum();
  }

  static String[] buildTailCommand(String project, int since) {
    var tailArgs = since > 0 ? "tail -n +" + since + " -f" : "tail -f";
    return new String[] {
      "incus",
      "exec",
      project,
      "--user",
      "1000",
      "--group",
      "1000",
      "--",
      "bash",
      "-c",
      tailArgs + " " + LOG_PATH
    };
  }

  static String extractProject(String path) {
    var segments = path.split("/");
    if (segments.length >= 5 && "projects".equals(segments[2])) {
      return segments[3];
    }
    return null;
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
