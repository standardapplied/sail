/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebauthnPageHandlerTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SailApiServer server;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    var tokenStore = new TokenStore(db);
    var passkeyHandler = new WebauthnAuthHandler(null, null, new TokenAuth(tokenStore), null);
    server =
        new SailApiServer(
            "127.0.0.1", 0, new TestOperations(), tokenStore, new EventBus(), null, passkeyHandler);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  private HttpResponse<String> request(String method, String path) throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void loginPageIsServedAsHtml() throws Exception {
    var response = request("GET", "/login");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    assertTrue(response.body().contains("Sign in to Sail"));
    assertTrue(response.body().contains("navigator.credentials.get"));
    assertTrue(response.body().contains("/v1/auth/login/finish"));
  }

  @Test
  void enrollPageIsServedAsHtml() throws Exception {
    var response = request("GET", "/enroll");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("navigator.credentials.create"));
    assertTrue(response.body().contains("X-Enrollment-Ticket"));
    assertTrue(response.body().contains("/v1/auth/register/finish"));
  }

  @Test
  void nonGetIsMethodNotAllowed() throws Exception {
    assertEquals(405, request("POST", "/login").statusCode());
  }

  @Test
  void unknownSubPathIsNotFound() throws Exception {
    assertEquals(404, request("GET", "/login/extra").statusCode());
  }
}
