/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SailApiClientTest {

  private SailApiServer server;
  private SailApiClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new SailApiServer("127.0.0.1", 0, new TestOperations(), "test-token");
    server.start();
    client = new SailApiClient("http://127.0.0.1:" + server.port(), "test-token");
  }

  @AfterEach
  void tearDown() {
    if (client != null) client.close();
    if (server != null) server.close();
  }

  @Test
  void getHealth() throws IOException {
    var result = client.get("/v1/health");
    assertEquals("ok", result.get("status"));
  }

  @Test
  void getSpecBoard() throws IOException {
    var result = client.get("/v1/specs/board");
    assertNotNull(result.get("pending"));
  }

  @Test
  void postCreateSpec() throws IOException {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", "test-spec");
    body.put("title", "Test Spec");
    body.put("status", "draft");
    var result = client.post("/v1/specs", body);
    assertNotNull(result.get("spec"));
  }

  @Test
  void putUpdateSpec() throws IOException {
    var body = new LinkedHashMap<String, Object>();
    body.put("title", "Updated Title");
    var result = client.put("/v1/specs/test-spec", body);
    assertNotNull(result.get("spec"));
  }

  @Test
  void deleteSpec() throws IOException {
    var result = client.delete("/v1/specs/test-spec");
    assertEquals(true, result.get("deleted"));
  }

  @Test
  void invalidTokenThrowsIOException() {
    try (var badClient = new SailApiClient("http://127.0.0.1:" + server.port(), "wrong-token")) {
      assertThrows(IOException.class, () -> badClient.get("/v1/specs/board"));
    }
  }

  @Test
  void trailingSlashInBaseUrlIsHandled() throws IOException {
    try (var slashClient =
        new SailApiClient("http://127.0.0.1:" + server.port() + "/", "test-token")) {
      var result = slashClient.get("/v1/health");
      assertEquals("ok", result.get("status"));
    }
  }

  @Test
  void putSpecContent() throws IOException {
    var body = new LinkedHashMap<String, Object>();
    body.put("body", "# Spec content");
    body.put("plan", "## Plan");
    var result = client.put("/v1/specs/test-spec/content", body);
    assertEquals("# Spec content", result.get("body"));
  }

  @Test
  void fromConfigThrowsWithoutToken() {
    assertThrows(IOException.class, SailApiClient::fromConfig);
  }

  @Test
  void fromConfigSucceedsWithSystemProperties() throws IOException {
    System.setProperty("SAIL_SERVER", "http://127.0.0.1:" + server.port());
    System.setProperty("SAIL_TOKEN", "test-token");
    try (var fromConfig = SailApiClient.fromConfig()) {
      var result = fromConfig.get("/v1/health");
      assertEquals("ok", result.get("status"));
    } finally {
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
  }

  @Test
  void notFoundReturnsGenericMessageWhenNoErrorField() throws Exception {
    try (var noErrorServer =
            new SailApiServer(
                "127.0.0.1",
                0,
                new TestOperations() {
                  @Override
                  public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
                    return Result.failure(ErrorCode.SPEC_NOT_FOUND, "Not found.");
                  }
                },
                "test-token");
        var testClient =
            new SailApiClient("http://127.0.0.1:" + noErrorServer.port(), "test-token")) {
      noErrorServer.start();
      var ex = assertThrows(IOException.class, () -> testClient.get("/v1/specs/missing"));
      assertTrue(ex.getMessage().contains("Not found"));
    }
  }

  @Test
  void closeIsIdempotent() {
    client.close();
    client.close();
    client = null;
  }
}
