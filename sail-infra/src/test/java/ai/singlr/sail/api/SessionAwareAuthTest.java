/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
    var auth = TestAuth.sessions(db);
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
  void aSessionIsItsFdeOnTheApiLaneWithTheRoleAndScopeTheRosterGivesIt() throws Exception {
    var session = sessionFor("mady", "member");
    fdes.restrict("mady");
    fdes.grant("mady", "acme");
    var auth = TestAuth.sessions(db);
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/",
        exchange -> {
          auth.require(exchange);
          var stamped = ApiRouter.actorOf(exchange).toString().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, stamped.length);
          try (var body = exchange.getResponseBody()) {
            body.write(stamped);
          }
        });
    http.start();
    try (var client = HttpClient.newHttpClient()) {
      var stamped =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/"))
                  .header("Authorization", "Bearer " + session)
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(
          new Actor("mady", Role.MEMBER, Actor.Lane.API)
              .withScope(ProjectScope.of(List.of("acme")))
              .toString(),
          stamped.body());
    } finally {
      http.stop(0);
    }
  }

  @Test
  void adminSessionCanReadAndWrite() throws Exception {
    var session = sessionFor("admin-fde", "admin");
    assertEquals(200, send("GET", "/v1/specs/board", session).statusCode());
    assertNotEquals(403, send("POST", "/v1/specs", session).statusCode());
  }

  @Test
  void viewerSessionReadsAndItsWriteIsLeftToTheOperation() throws Exception {
    var session = sessionFor("viewer-fde", "viewer");
    assertEquals(200, send("GET", "/v1/specs/board", session).statusCode());
    assertNotEquals(403, send("POST", "/v1/specs", session).statusCode());
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
