/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhoamiTest {

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

  @Test
  void adminPasskeySessionSeesFullIdentity() throws Exception {
    var fde = fdes.add("uday", "Uday K", "uday@singlr.ai", "admin");
    var session = sessions.create(fde.id(), Duration.ofMinutes(30)).token();
    var response = get(session);
    assertEquals(200, response.statusCode());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
    var body = YamlUtil.parseMap(response.body());
    assertEquals(1, body.get("schema_version"));
    assertEquals("uday", body.get("fde"));
    assertEquals("Uday K", body.get("display_name"));
    assertEquals("uday@singlr.ai", body.get("email"));
    assertEquals("admin", body.get("role"));
    assertEquals(List.of("read", "write", "admin"), body.get("capabilities"));
  }

  @Test
  void passkeySessionOmitsUnsetNameAndEmail() throws Exception {
    var fde = fdes.add("uday", null, null, "member");
    var session = sessions.create(fde.id(), Duration.ofMinutes(30)).token();
    var body = YamlUtil.parseMap(get(session).body());
    assertEquals("uday", body.get("fde"));
    assertFalse(body.containsKey("display_name"), body.toString());
    assertFalse(body.containsKey("email"), body.toString());
  }

  @Test
  void fdeOwnedTokenCarriesFdeAndTokenName() throws Exception {
    var fde = fdes.add("uday", null, null, "member");
    var token = tokenStore.create("uday-laptop", "member", fde.id(), null).token();
    var body = YamlUtil.parseMap(get(token).body());
    assertEquals("uday", body.get("fde"));
    assertEquals("uday-laptop", body.get("name"));
    assertEquals("member", body.get("role"));
    assertEquals(List.of("read", "write"), body.get("capabilities"));
  }

  @Test
  void machineTokenOmitsFdeAndLacksAdminCapability() throws Exception {
    var token = tokenStore.create("ci-bot", "member").token();
    var body = YamlUtil.parseMap(get(token).body());
    assertFalse(body.containsKey("fde"), body.toString());
    assertEquals("ci-bot", body.get("name"));
    assertEquals("member", body.get("role"));
    assertEquals(List.of("read", "write"), body.get("capabilities"));
  }

  @Test
  void viewerTokenGetsReadOnlyCapabilities() throws Exception {
    var token = tokenStore.create("dashboard", "viewer").token();
    var body = YamlUtil.parseMap(get(token).body());
    assertEquals("viewer", body.get("role"));
    assertEquals(List.of("read"), body.get("capabilities"));
  }

  @Test
  void missingBearerIsUnauthorized() throws Exception {
    var response = get(null);
    assertEquals(401, response.statusCode());
    assertTrue(response.body().contains("missing_bearer_token"), response.body());
  }

  @Test
  void invalidTokenIsForbidden() throws Exception {
    var response = get("sail_bogus");
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("invalid_bearer_token"), response.body());
  }

  @Test
  void expiredSessionIsForbidden() throws Exception {
    var fde = fdes.add("uday", null, null, "admin");
    var session = sessions.create(fde.id(), Duration.ofMinutes(-1)).token();
    var response = get(session);
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("invalid_bearer_token"), response.body());
  }

  private HttpResponse<String> get(String token) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/whoami"));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient()
        .send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
