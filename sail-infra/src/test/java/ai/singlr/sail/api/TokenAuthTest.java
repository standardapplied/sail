/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class TokenAuthTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private TokenStore tokenStore;
  private SailApiServer server;
  private String validToken;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    tokenStore = new TokenStore(db);
    validToken = tokenStore.create("test-admin", "admin").token();
    server = new SailApiServer("127.0.0.1", 0, new FakeOps(), tokenStore, new EventBus(), null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  @Test
  void validTokenSucceeds() throws Exception {
    var response = get("/v1/specs/board", validToken);
    assertEquals(200, response.statusCode());
  }

  @Test
  void invalidTokenReturns403() throws Exception {
    var response = get("/v1/specs/board", "sail_bogus_token_value_here");
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("invalid_bearer_token"));
  }

  @Test
  void missingTokenReturns401() throws Exception {
    var response = get("/v1/specs/board", null);
    assertEquals(401, response.statusCode());
    assertTrue(response.body().contains("missing_bearer_token"));
  }

  @Test
  void revokedTokenReturns403() throws Exception {
    tokenStore.revoke("test-admin");
    var response = get("/v1/specs/board", validToken);
    assertEquals(403, response.statusCode());
  }

  @Test
  void constructorRejectsNullStore() {
    assertThrows(NullPointerException.class, () -> new TokenAuth(null));
  }

  @Test
  void duplicateAuthorizationHeadersAreRejected() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/specs/board"));
    request.header("Authorization", "Bearer " + validToken);
    request.header("Authorization", "Bearer " + validToken);
    var response =
        HttpClient.newHttpClient()
            .send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("invalid_bearer_token"));
  }

  @Test
  void multipleTokensWorkIndependently() throws Exception {
    var secondToken = tokenStore.create("second", "member").token();
    assertEquals(200, get("/v1/specs/board", validToken).statusCode());
    assertEquals(200, get("/v1/specs/board", secondToken).statusCode());

    tokenStore.revoke("test-admin");
    assertEquals(403, get("/v1/specs/board", validToken).statusCode());
    assertEquals(200, get("/v1/specs/board", secondToken).statusCode());
  }

  @Test
  void tokenOwnedByFdeAuthenticates() throws Exception {
    var fde = new ai.singlr.sail.store.FdeStore(db).add("uday", null, null);
    var token = tokenStore.create("uday-laptop", "admin", fde.id()).token();
    assertEquals(200, get("/v1/specs/board", token).statusCode());
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static final class FakeOps extends TestOperations {}
}
