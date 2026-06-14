/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Streaming consumer of the sail-api {@code /v1/events/stream} SSE endpoint. The factory opens the
 * HTTP connection synchronously (so caller errors surface immediately), then spawns one virtual
 * thread that reads SSE lines, parses each {@code data:} payload into an {@link Event}, and pushes
 * it onto the caller-supplied {@link BlockingQueue}.
 *
 * <p>Designed for callers that need to merge live events with their own deadlines or timers: {@code
 * queue.poll(timeout)} on a single queue is much simpler than juggling SSE callbacks plus scheduled
 * tasks.
 *
 * <p>Close the client to stop the reader thread and release the underlying HTTP body stream.
 * Failures inside the reader (stream closed by server, parse errors, etc.) are logged to stderr and
 * end the thread quietly — the queue simply stops receiving new events, and the main loop is
 * expected to notice via its own timeout.
 */
public final class EventStreamClient implements AutoCloseable {

  private static final String DATA_PREFIX = "data: ";
  private static final String SUBSCRIBED_LINE = ": subscribed";
  private static final Duration SUBSCRIBED_TIMEOUT = Duration.ofSeconds(5);

  private final Stream<String> lineStream;
  private final Thread reader;
  private final CountDownLatch subscribedLatch = new CountDownLatch(1);
  private volatile boolean stopped;

  private EventStreamClient(
      HttpResponse<Stream<String>> response, BlockingQueue<Event> queue, String project) {
    this.lineStream = response.body();
    this.reader =
        Thread.ofVirtual()
            .name("sail-event-stream-" + Objects.requireNonNullElse(project, "all"))
            .start(() -> readLoop(queue));
  }

  /**
   * Opens an SSE connection to {@code http://<host>:<port>/v1/events/stream} (optionally filtered
   * by {@code projectFilter}) and starts the background reader. Blocks until the server emits its
   * {@code : subscribed} hello line so callers can publish events immediately on return without
   * racing the subscription. Returns the live client; close it with try-with-resources.
   */
  public static EventStreamClient subscribe(
      String host, int port, String token, String projectFilter, BlockingQueue<Event> queue)
      throws IOException, InterruptedException {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(queue, "queue");
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var request =
        HttpRequest.newBuilder(streamUri(host, port, projectFilter))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofHours(24))
            .GET()
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofLines());
    if (response.statusCode() != 200) {
      throw new IOException(
          "Event stream request returned HTTP " + response.statusCode() + " from " + request.uri());
    }
    var stream = new EventStreamClient(response, queue, projectFilter);
    if (!stream.subscribedLatch.await(SUBSCRIBED_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      stream.close();
      throw new IOException(
          "Did not receive subscribed hello from "
              + request.uri()
              + " within "
              + SUBSCRIBED_TIMEOUT);
    }
    return stream;
  }

  private void readLoop(BlockingQueue<Event> queue) {
    try {
      lineStream.forEach(
          line -> {
            if (stopped) {
              throw new StopReadingException();
            }
            if (line != null && line.startsWith(SUBSCRIBED_LINE)) {
              subscribedLatch.countDown();
              return;
            }
            if (!processLine(line, queue)) {
              throw new StopReadingException();
            }
          });
    } catch (StopReadingException ignored) {
      // close() was called or thread was interrupted; expected.
    } catch (Exception e) {
      if (!stopped) {
        System.err.println("  [event-stream] Stream ended: " + e.getMessage());
      }
    }
  }

  /**
   * Parses one SSE line and pushes the resulting event onto {@code queue}. Returns {@code false}
   * iff the current thread was interrupted (so the caller knows to stop reading). Visible for tests
   * so the parser can be exercised without spinning up an HTTP server.
   */
  static boolean processLine(String line, BlockingQueue<Event> queue) {
    if (line == null || !line.startsWith(DATA_PREFIX)) {
      return true;
    }
    try {
      queue.put(Event.fromJsonLine(line.substring(DATA_PREFIX.length())));
      return true;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception parseError) {
      System.err.println(
          "  [event-stream] Warning: dropping malformed event: " + parseError.getMessage());
      return true;
    }
  }

  @Override
  public void close() {
    stopped = true;
    try {
      lineStream.close();
    } catch (Exception ignored) {
      // best effort
    }
    reader.interrupt();
  }

  private static URI streamUri(String host, int port, String project) {
    var sb =
        new StringBuilder("http://")
            .append(host)
            .append(':')
            .append(port)
            .append("/v1/events/stream");
    var params = new LinkedHashMap<String, String>();
    if (Strings.isNotBlank(project)) {
      params.put("project", project);
    }
    if (!params.isEmpty()) {
      sb.append('?');
      var first = true;
      for (var entry : params.entrySet()) {
        if (!first) {
          sb.append('&');
        }
        sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
            .append('=')
            .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        first = false;
      }
    }
    return URI.create(sb.toString());
  }

  private static final class StopReadingException extends RuntimeException {
    StopReadingException() {
      super(null, null, false, false);
    }
  }
}
