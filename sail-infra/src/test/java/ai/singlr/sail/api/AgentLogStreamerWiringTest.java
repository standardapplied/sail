/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@code GET /v1/projects/{p}/agent/stream} is mounted: the real {@link SailApiServer}
 * wiring routes it to {@link AgentLogStreamer} rather than returning 404, and it rejects an
 * unauthenticated caller exactly as {@code /v1/events/stream} does. The tail itself is not
 * exercised here (no live container), so the authenticated request lands on the handler and fails
 * to spawn the container tail — the point is that it reaches the handler at all.
 */
class AgentLogStreamerWiringTest {

  private static HttpRequest streamRequest(int port, String token) {
    var builder =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/v1/projects/backend/agent/stream"))
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
        new SailApiOperations(),
        new FixedTokenTestAuth("tok"),
        new EventBus(),
        new AuditPersister(tmp.resolve("events.jsonl"), 8),
        tmp.resolve("api.sock"));
  }

  @Test
  void authenticatedStreamRequestReachesHandlerNot404(@TempDir Path tmp) throws Exception {
    try (var server = server(tmp)) {
      server.start();
      var response =
          HttpClient.newHttpClient()
              .send(streamRequest(server.port(), "tok"), HttpResponse.BodyHandlers.ofString());
      assertNotEquals(404, response.statusCode(), "stream route must be mounted");
      assertNotEquals(401, response.statusCode(), "authenticated caller must pass the auth gate");
      assertNotEquals(403, response.statusCode(), "authenticated caller must pass the auth gate");
    }
  }

  @Test
  void unauthenticatedStreamRequestIsRejected(@TempDir Path tmp) throws Exception {
    try (var server = server(tmp)) {
      server.start();
      var response =
          HttpClient.newHttpClient()
              .send(streamRequest(server.port(), null), HttpResponse.BodyHandlers.ofString());
      assertEquals(401, response.statusCode());
    }
  }
}
