/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionAwareAuthTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private AuthSessionStore sessions;
  private TokenStore tokenStore;
  private SailApiServer server;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    sessions = new AuthSessionStore(db);
    tokenStore = new TokenStore(db);
    var auth = new SessionAwareAuth(sessions, fdes, new TokenAuth(tokenStore));
    server =
        new SailApiServer(
            "127.0.0.1", 0, new TestOperations(), auth, new EventBus(), null, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  private String sessionFor(String handle, String role) {
    var fde = fdes.add(handle, null, null, role);
    return sessions.create(fde.id(), Duration.ofMinutes(30)).token();
  }

  @Test
  void adminSessionCanReadAndWrite() throws Exception {
    var session = sessionFor("admin-fde", "admin");
    assertEquals(200, send("GET", "/v1/specs/board", session).statusCode());
    assertNotEquals(403, send("POST", "/v1/specs", session).statusCode());
  }

  @Test
  void viewerSessionCanReadButNotWrite() throws Exception {
    var session = sessionFor("viewer-fde", "viewer");
    assertEquals(200, send("GET", "/v1/specs/board", session).statusCode());
    assertEquals(403, send("POST", "/v1/specs", session).statusCode());
  }

  @Test
  void apiTokenStillAuthenticatesViaDelegate() throws Exception {
    var token = tokenStore.create("ci-bot", "member").token();
    assertEquals(200, send("GET", "/v1/specs/board", token).statusCode());
  }

  @Test
  void invalidSessionTokenIsRejected() throws Exception {
    assertEquals(403, send("GET", "/v1/specs/board", "sess_bogus").statusCode());
  }

  @Test
  void missingTokenDelegatesAndIsUnauthorized() throws Exception {
    assertEquals(401, send("GET", "/v1/specs/board", null).statusCode());
  }

  @Test
  void duplicateAuthorizationHeadersDelegateAndAreRejected() throws Exception {
    var session = sessionFor("dup-fde", "admin");
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/specs/board"))
            .header("Authorization", "Bearer " + session)
            .header("Authorization", "Bearer " + session)
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(403, response.statusCode());
  }

  private HttpResponse<String> send(String method, String path, String token) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    builder.header("Content-Type", "application/json");
    builder.method(
        method,
        "POST".equals(method)
            ? HttpRequest.BodyPublishers.ofString("{}")
            : HttpRequest.BodyPublishers.noBody());
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
