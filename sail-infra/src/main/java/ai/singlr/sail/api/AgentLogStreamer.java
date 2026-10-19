/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.authority.RunAuthority;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * SSE handler that streams a single run's log output. The address is {@code /v1/runs/{id}/stream}:
 * it resolves the run, which a caller whose scope does not admit its project is told does not
 * exist, applies the same provenance guard as the buffered log/stop endpoints — a run whose {@code
 * node} is not this box is refused with a structured {@code run_on_other_node} error rather than
 * tailing a foreign box's local file — then applies the same run-owner rule ({@link
 * RunAuthority#access}) the buffered log endpoint does (the run's spec assignee or an admin, else
 * {@code forbidden_not_assignee}), and finally tails the run's own {@code
 * ~/.sail/runs/<id>/agent.log} via {@code incus exec}, sending each line as an SSE {@code data:}
 * event. Because REST and SSE call the one pure policy, they return an identical verdict for an
 * identical caller. {@code ?since=N} replays from a line number for client reconnection.
 *
 * <p>Mounted by {@link ApiRouter}, which delegates the stream path straight to this handler so the
 * long-lived connection never runs through the buffered request/response path. It is not registered
 * as its own {@code createContext} because the path carries a variable run id, which
 * prefix-matching cannot isolate from the rest of {@code /v1/runs/...}.
 *
 * <p>Authentication is the {@code Authorization: Bearer} header only — the same gate {@code
 * /v1/events/stream} uses. A browser {@code EventSource} cannot set headers and is unsupported; a
 * native client that sets the header (a GUI client's Rust core, {@code sail agent stream})
 * authenticates normally.
 */
public final class AgentLogStreamer implements HttpHandler {

  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
  private static final long HEARTBEAT_INTERVAL_NANOS = Duration.ofSeconds(15).toNanos();

  private final ApiAuth auth;
  private final Function<String, Optional<RunStore.RunRow>> runLookup;
  private final Function<String, Optional<SpecStore.SpecRow>> specLookup;
  private final Supplier<String> localHandle;
  private final InstantSource clock;
  private final LongAdder activeStreams = new LongAdder();

  public AgentLogStreamer(ApiAuth auth) {
    this(auth, AgentLogStreamer::lookupFromDb, AgentLogStreamer::specFromDb, NodeIdentity::handle);
  }

  AgentLogStreamer(
      ApiAuth auth,
      Function<String, Optional<RunStore.RunRow>> runLookup,
      Function<String, Optional<SpecStore.SpecRow>> specLookup,
      Supplier<String> localHandle) {
    this(auth, runLookup, specLookup, localHandle, InstantSource.system());
  }

  /** As above, timing heartbeats and re-checks by {@code clock}. */
  AgentLogStreamer(
      ApiAuth auth,
      Function<String, Optional<RunStore.RunRow>> runLookup,
      Function<String, Optional<SpecStore.SpecRow>> specLookup,
      Supplier<String> localHandle,
      InstantSource clock) {
    this.auth = auth;
    this.runLookup = runLookup;
    this.specLookup = specLookup;
    this.localHandle = localHandle;
    this.clock = clock;
  }

  /** Whether the caller may read a run's log. */
  private sealed interface Admission {}

  /** It may: the run is {@code run}. */
  private record Admitted(RunStore.RunRow run) implements Admission {}

  /** It may not, and the response that says why. */
  private record Refused(int status, String code, String message, String extra)
      implements Admission {

    ApiError error() {
      return new ApiError(code, message, null);
    }
  }

  private static Optional<RunStore.RunRow> lookupFromDb(String runId) {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return new RunStore(db).findById(runId);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Optional<SpecStore.SpecRow> specFromDb(String specId) {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return new SpecStore(db).findById(specId);
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
      var admission = admit(exchange, runId);
      if (admission instanceof Refused refused) {
        sendError(exchange, refused.status(), refused.code(), refused.message(), refused.extra());
        return;
      }
      var run = ((Admitted) admission).run();
      if (Strings.isBlank(run.logPath())) {
        sendError(exchange, 404, "run_not_found", "This run has no log file.", null);
        return;
      }
      var since = parseSince(exchange.getRequestURI().getQuery());
      String[] tailCommand;
      try {
        tailCommand = buildTailCommand(run.project(), run.id(), run.logPath(), since);
      } catch (IllegalArgumentException e) {
        sendError(exchange, 400, "invalid_request", e.getMessage(), null);
        return;
      }
      streamLog(exchange, run.project(), tailCommand, since, recheckOf(exchange, runId));
    } finally {
      exchange.close();
    }
  }

  /**
   * The run {@code runId} when the authenticated caller may read its log, else why not: a run its
   * scope does not admit does not exist for it, a run of another box is that box's to stream, and
   * the run-owner rule ({@link RunAuthority#access}) decides the rest.
   */
  private Admission admit(HttpExchange exchange, String runId) {
    var actor = ApiRouter.actorOf(exchange);
    var run = Scoped.row(actor, runLookup.apply(runId), RunStore.RunRow::project).orElse(null);
    if (run == null) {
      return new Refused(404, "run_not_found", "No run '" + runId + "'.", null);
    }
    if (SailOperations.isForeign(run, localHandle.get())) {
      return foreign(run);
    }
    var refusal =
        RunAuthority.access(
            actor,
            run.id(),
            StopOperations.specIdOf(run),
            RunAuthority.owners(
                run, spec -> StopOperations.ownerSeen(actor, specLookup.apply(spec))));
    if (refusal.isPresent()) {
      var refused = refusal.get();
      var code = Refusals.code(refused.kind());
      var fix =
          Strings.isBlank(refused.fix())
              ? null
              : ", \"fix\": \"" + jsonEscape(refused.fix()) + "\"";
      return new Refused(code.httpCode(), code.code(), refused.message(), fix);
    }
    return new Admitted(run);
  }

  /** What asks again whether {@code exchange}'s caller may still read {@code runId}'s log. */
  StreamRecheck recheckOf(HttpExchange exchange, String runId) {
    return new StreamRecheck(() -> refusal(exchange, runId), clock);
  }

  /** Why the stream of {@code runId} may no longer go on, asked as it was opened. */
  private Optional<ApiError> refusal(HttpExchange exchange, String runId) {
    try {
      auth.require(exchange);
    } catch (ApiException e) {
      return Optional.of(e.error());
    }
    return admit(exchange, runId) instanceof Refused refused
        ? Optional.of(refused.error())
        : Optional.empty();
  }

  private void streamLog(
      HttpExchange exchange, String project, String[] tailCommand, int since, StreamRecheck recheck)
      throws IOException {
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
      pump(tailProcess, reader, out, since, recheck);
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
   * a dead pipe forever, holding the connection open and never closing the stream. A process seen
   * gone is read to the end of its pipe first, so what it wrote between a {@code ready()} that saw
   * nothing and its exit is still relayed. Before each line and each heartbeat the caller is asked
   * again whether it may still read the run ({@code recheck}); once it may not, the stream ends
   * with an {@code error} event saying why.
   */
  static void pump(
      Process tailProcess,
      BufferedReader reader,
      OutputStream out,
      int since,
      StreamRecheck recheck)
      throws IOException, InterruptedException {
    var lineNumber = since > 0 ? since : 1;
    var lastHeartbeat = System.nanoTime();
    try {
      while (true) {
        if (reader.ready() || !tailProcess.isAlive()) {
          var line = reader.readLine();
          if (line == null) {
            break;
          }
          recheck.recheck();
          writeSseData(out, lineNumber, line);
          lineNumber++;
          lastHeartbeat = System.nanoTime();
        } else {
          if (System.nanoTime() - lastHeartbeat > HEARTBEAT_INTERVAL_NANOS) {
            recheck.recheck();
            out.write(HEARTBEAT);
            out.flush();
            lastHeartbeat = System.nanoTime();
          }
          Thread.sleep(100);
        }
      }
    } catch (StreamRecheck.Ended ended) {
      StreamRecheck.writeEnded(out, ended);
    }
  }

  public long activeStreamCount() {
    return activeStreams.sum();
  }

  /**
   * Tails the run's own log file, touched first so a stream opened before the agent has written any
   * output starts clean and empty — waiting for lines — rather than emitting tail's "cannot open"
   * error onto the stream.
   *
   * <p>The path is derived from the validated run id, never from the run's persisted {@code
   * log_path}: run rows arrive over sync from writable peers, so a replicated {@code log_path} is
   * untrusted input that could name an arbitrary file or carry shell metacharacters. {@code
   * recordedLogPath} only tells a pre-upgrade review's {@code review.log} from an {@code agent.log}
   * ({@link AgentUnit#readableLogPath}). The derived path is then passed as a positional shell
   * argument ({@code "$1"}) rather than interpolated into the script, so it can never be
   * interpreted as shell syntax.
   */
  static String[] buildTailCommand(
      String project, String runId, String recordedLogPath, int since) {
    var logPath = AgentUnit.readableLogPath(runId, recordedLogPath);
    var script =
        since > 0
            ? "touch -- \"$1\" 2>/dev/null; exec tail -n \"+$2\" -f -- \"$1\""
            : "touch -- \"$1\" 2>/dev/null; exec tail -f -- \"$1\"";
    return ContainerExec.asDevUser(
            project, List.of("bash", "-c", script, "sail-run-tail", logPath, String.valueOf(since)))
        .toArray(String[]::new);
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

  private static Refused foreign(RunStore.RunRow run) {
    var node = Strings.isBlank(run.node()) ? "an unknown node" : run.node();
    var extra =
        ", \"node\": \""
            + jsonEscape(Objects.toString(run.node(), ""))
            + "\", \"spec\": \""
            + jsonEscape(Objects.toString(run.specId(), ""))
            + "\", \"project\": \""
            + jsonEscape(Objects.toString(run.project(), ""))
            + "\"";
    return new Refused(
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
