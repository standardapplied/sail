/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client that publishes a single {@link Event} to the running sail-api server. Mirrors {@link
 * EventStreamClient} on the consume side: same host/port resolution, same bearer-token auth via
 * {@link ServerConnectionConfig}, same minimal surface. Used by orchestrator-side code paths (CLI
 * {@code sail spec dispatch}, future spec lifecycle commands) where the agent hooks inside
 * containers cannot emit the event because the change is happening on the host.
 *
 * <p>The publisher is intentionally small: one {@link #publish} method that raises {@link
 * IOException} on any non-2xx response or transport failure. Callers decide their own failure
 * policy — the dispatch hot path wraps in try/catch so a sail-api outage doesn't block the dispatch
 * itself.
 */
public final class SailEventPublisher {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI uri;
  private final String token;
  private final HttpClient client;

  public SailEventPublisher(String host, int port, String token) {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(token, "token");
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("port must be 1..65535, got " + port);
    }
    this.uri = URI.create("http://" + host + ":" + port + "/v1/events");
    this.token = token;
    this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  /**
   * Builds a publisher pointing at the server resolved by {@link ServerConnectionConfig#resolve()}.
   * Reads the token (and optional URL) from flags, env vars, or {@code ~/.sail/config.yaml}.
   */
  public static SailEventPublisher localDefault() throws IOException {
    var config = ServerConnectionConfig.resolve();
    var uri = URI.create(config.serverUrl());
    var port = uri.getPort() == -1 ? 7070 : uri.getPort();
    return new SailEventPublisher(uri.getHost(), port, config.token());
  }

  /**
   * Publishes the event over HTTP and returns the bus-stamped version (with the id assigned by the
   * server). Throws if the server rejects the request or the connection fails.
   */
  public Event publish(Event event) throws IOException, InterruptedException {
    Objects.requireNonNull(event, "event");
    var request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(event.toJsonLine()))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException(
          "POST "
              + uri
              + " returned HTTP "
              + response.statusCode()
              + (response.body().isEmpty() ? "" : ": " + response.body()));
    }
    return parseStamped(response.body());
  }

  /**
   * Parses the {@code EventPublishResponse} JSON body and returns the stamped {@link Event}
   * embedded in its {@code event} field. Visible for tests so the parser can be exercised without
   * spinning up an HTTP server.
   */
  @SuppressWarnings("unchecked")
  static Event parseStamped(String responseBody) {
    var root = YamlUtil.parseMap(responseBody);
    var stamped = (Map<String, Object>) root.get("event");
    if (stamped == null) {
      throw new IllegalStateException(
          "publish response missing 'event' field: " + truncate(responseBody, 200));
    }
    return Event.fromMap(stamped);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }
}
