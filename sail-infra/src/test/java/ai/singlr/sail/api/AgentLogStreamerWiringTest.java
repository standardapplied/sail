/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@code GET /v1/runs/{id}/stream} is mounted: the real {@link SailApiServer} wiring routes
 * it to {@link AgentLogStreamer} rather than to the generic router, and it rejects an
 * unauthenticated caller exactly as {@code /v1/events/stream} does. The tail itself is not
 * exercised here (no live container); the request lands on the handler, which — finding no such run
 * — answers with the streamer's own {@code run_not_found}, distinguishing it from the router's
 * generic {@code not_found}.
 */
class AgentLogStreamerWiringTest {

  private static HttpRequest streamRequest(int port, String token) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/runs/nope/stream"))
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(15))
            .GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder.build();
  }

  private static SailApiServer server(Path tmp) throws Exception {
    return new SailApiServer(
        "127.0.0.1",
        0,
        new SailOperations(),
        new FixedTokenTestAuth("tok"),
        new EventBus(),
        new AuditPersister(tmp.resolve("events.jsonl"), 8),
        tmp.resolve("api.sock"));
  }

  @Test
  void authenticatedStreamRequestReachesTheRunStreamer(@TempDir Path tmp) throws Exception {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          try (var server = server(tmp)) {
            server.start();
            var response =
                HttpClient.newHttpClient()
                    .send(
                        streamRequest(server.port(), "tok"), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
            assertTrue(
                response.body().contains("run_not_found"),
                "the stream path must reach AgentLogStreamer, not the generic router: "
                    + response.body());
          }
        });
  }

  @Test
  void unauthenticatedStreamRequestIsRejected(@TempDir Path tmp) throws Exception {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          try (var server = server(tmp)) {
            server.start();
            var response =
                HttpClient.newHttpClient()
                    .send(
                        streamRequest(server.port(), null),
                        HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
              assertEquals(401, response.statusCode());
            }
          }
        });
  }
}
