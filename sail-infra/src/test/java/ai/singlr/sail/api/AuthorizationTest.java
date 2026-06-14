/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
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

class AuthorizationTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private TokenStore tokenStore;
  private SailApiServer server;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    tokenStore = new TokenStore(db);
    server = new SailApiServer("127.0.0.1", 0, ops, tokenStore, new EventBus(), null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  @Test
  void viewerCanRead() throws Exception {
    var viewer = tokenStore.create("v", "viewer").token();
    assertEquals(200, send("GET", "/v1/specs/board", viewer, null).statusCode());
  }

  @Test
  void viewerCannotWrite() throws Exception {
    var viewer = tokenStore.create("v", "viewer").token();
    var response = send("POST", "/v1/specs", viewer, "{}");
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("forbidden"), response.body());
  }

  @Test
  void memberCanWrite() throws Exception {
    var member = tokenStore.create("m", "member").token();
    assertNotEquals(403, send("POST", "/v1/specs", member, "{}").statusCode());
  }

  @Test
  void adminCanWrite() throws Exception {
    var admin = tokenStore.create("a", "admin").token();
    assertNotEquals(403, send("POST", "/v1/specs", admin, "{}").statusCode());
  }

  @Test
  void memberCannotDispatch() throws Exception {
    var member = tokenStore.create("m", "member").token();
    var response = send("POST", "/v1/projects/acme/dispatch", member, "{}");
    assertEquals(403, response.statusCode(), response.body());
    assertTrue(response.body().contains("forbidden"), response.body());
  }

  @Test
  void adminCanDispatch() throws Exception {
    var admin = tokenStore.create("a", "admin").token();
    assertNotEquals(403, send("POST", "/v1/projects/acme/dispatch", admin, "{}").statusCode());
  }

  @Test
  void memberCannotApproveAReview() throws Exception {
    var member = tokenStore.create("m", "member").token();
    assertEquals(403, send("POST", "/v1/reviews/r1/approve", member, "{}").statusCode());
  }

  @Test
  void adminCanApproveAReview() throws Exception {
    var admin = tokenStore.create("a", "admin").token();
    assertNotEquals(403, send("POST", "/v1/reviews/r1/approve", admin, "{}").statusCode());
  }

  @Test
  void memberCannotDismissAFinding() throws Exception {
    var member = tokenStore.create("m", "member").token();
    assertEquals(403, send("POST", "/v1/reviews/r1/dismiss/f1", member, "{}").statusCode());
  }

  @Test
  void fdeOwnedTokenCanWrite() throws Exception {
    var fde = new ai.singlr.sail.store.FdeStore(db).add("uday", null, null);
    var token = tokenStore.create("uday-laptop", "admin", fde.id()).token();
    assertNotEquals(403, send("POST", "/v1/specs", token, "{}").statusCode());
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    var publisher =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
    builder.method(method, publisher);
    if (body != null) builder.header("Content-Type", "application/json");
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void assigneeMeResolvesToFdeHandle() throws Exception {
    var fde = new ai.singlr.sail.store.FdeStore(db).add("uday", null, null);
    var token = tokenStore.create("uday-laptop", "member", fde.id()).token();
    send("GET", "/v1/specs?assignee=me", token, null);
    assertEquals("uday", ops.lastAssignee);
  }

  @Test
  void assigneeMeFallsBackToTokenNameWithoutFde() throws Exception {
    var token = tokenStore.create("ci-bot", "member").token();
    send("GET", "/v1/specs?assignee=me", token, null);
    assertEquals("ci-bot", ops.lastAssignee);
  }

  @Test
  void explicitAssigneePassesThrough() throws Exception {
    var token = tokenStore.create("m", "member").token();
    send("GET", "/v1/specs?assignee=alice", token, null);
    assertEquals("alice", ops.lastAssignee);
  }

  private final RecordingOps ops = new RecordingOps();

  private static final class RecordingOps extends TestOperations {
    volatile String lastAssignee;

    @Override
    public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
      lastAssignee = filter.assignee();
      return super.globalSpecs(filter);
    }
  }
}
