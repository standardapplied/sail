/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every write names who is acting, through the door it came in by: the journal's author and the
 * row's {@code updated_by} agree, and both are the identity that door bound — never the previous
 * editor, and never a string a caller threaded through.
 */
class WriteAuthorshipTest {

  private static final ShellExec SHELL =
      new ShellExec() {
        public Result exec(List<String> command) {
          return new Result(0, "[]", "");
        }

        public Result exec(List<String> command, Path workDir, Duration timeout) {
          return exec(command);
        }

        public boolean isDryRun() {
          return false;
        }
      };

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specs;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void anHttpWriteIsAuthoredByTheTokensFdeThroughStatusAndContentOnlyUpdates() throws Exception {
    try (var operations = operations(() -> SyncConfig.unset());
        var server = server(operations)) {
      var uday = token("uday", "member");
      var mady = token("mady", "admin");

      var created =
          send(
              server,
              "POST",
              "/v1/specs",
              uday,
              "{\"id\": \"auth\", \"project\": \"acme\", \"title\": \"Auth\"}");
      assertEquals(201, created.statusCode(), created.body());
      assertAuthoredBy("auth", "uday");

      var status = send(server, "PUT", "/v1/specs/auth", mady, "{\"status\": \"pending\"}");
      assertEquals(200, status.statusCode(), status.body());
      assertAuthoredBy("auth", "mady");

      var content = send(server, "PUT", "/v1/specs/auth/content", uday, "{\"body\": \"# Auth\"}");
      assertEquals(200, content.statusCode(), content.body());
      assertAuthoredBy("auth", "uday");
    }
  }

  @Test
  void aSocketWriteIsAuthoredByTheRunsPrincipal() {
    try (var operations = operations(() -> SyncConfig.unset());
        var bus = new EventBus()) {
      Acting.as("uday", () -> specs.create(spec("auth", "uday")));
      var runs = new RunStore(db);
      var credential =
          Acting.system(
              () ->
                  runs.createReview(
                      "review-1",
                      "acme",
                      "auth",
                      "node-a",
                      "uday",
                      "claude-code",
                      "main",
                      "review it",
                      "/tmp/review.log",
                      "sail-review-1"));
      var principal = runs.findByCredential(credential).orElseThrow().principal();
      var router = new LocalApiRouter(bus, operations);

      var status = router.handle(put("/v1/specs/auth", credential, "status=pending"));
      assertEquals(200, status.status(), status.body().toString());
      assertAuthoredBy("auth", principal);

      var content = router.handle(put("/v1/specs/auth/content", credential, "body=reviewed"));
      assertEquals(200, content.status(), content.body().toString());
      assertAuthoredBy("auth", principal);
    }
  }

  @Test
  void aNodesCliConflictResolutionActsAsItsFdeWithTheSyncedRole() {
    var node = new SyncConfig("node", "sail@main", "mady", "mady-box");
    try (var operations = operations(() -> node)) {
      Acting.as("uday", () -> specs.create(spec("auth", "mady")));
      var snapshot = YamlUtil.dumpJson(specs.comparableSnapshot("auth"));
      new ai.singlr.sail.store.SyncConflicts(db)
          .record("spec", "auth", null, snapshot, snapshot, List.of("title"));
      var fdes = new FdeStore(db);
      fdes.add("mady", null, null, "viewer");

      var refused =
          assertThrows(
              ApiException.class,
              () -> operations.resolveConflict("spec", "auth", mine()),
              "a viewer node is refused, as main would refuse it — never a hard-coded admin");
      assertEquals(ErrorCode.FORBIDDEN, refused.failure().errorCode());

      fdes.update("mady", null, null, "member");
      operations.resolveConflict("spec", "auth", mine());

      assertAuthoredBy("auth", "mady");
    }
  }

  private void assertAuthoredBy(String id, String author) {
    assertEquals(author, specs.findById(id).orElseThrow().updatedBy(), "the row's updated_by");
    assertEquals(
        author, new ChangeLog(db).head("spec", id).orElseThrow().actor(), "the journal's author");
  }

  private static Resolution mine() {
    return new Resolution(Resolution.Strategy.MINE, null);
  }

  private SailOperations operations(java.util.function.Supplier<SyncConfig> config) {
    return OperationsFactory.create(
            db, SHELL, "sail.yaml", null, null, SyncScheduler.disabled(), SessionYield.NONE)
        .useControlPlane(
            db,
            tempDir,
            new SyncOperations(
                db,
                "box",
                tempDir,
                config,
                target -> {
                  throw new IOException("main unavailable");
                }));
  }

  private SailApiServer server(SailOperations operations) throws IOException {
    var auth =
        new SessionAwareAuth(
            new AuthSessionStore(db), new FdeStore(db), new TokenAuth(new TokenStore(db)));
    var server =
        new SailApiServer("127.0.0.1", 0, operations, auth, new EventBus(), null, null, null);
    server.start();
    return server;
  }

  private String token(String handle, String role) {
    var fde = new FdeStore(db).add(handle, null, null, role);
    return new TokenStore(db).create(handle, role, fde.id(), null).token();
  }

  private static HttpResponse<String> send(
      SailApiServer server, String method, String path, String token, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private static LocalApiRequest put(String path, String credential, String form) {
    return new LocalApiRequest(
        "PUT",
        path,
        Map.of(),
        Map.of("authorization", "Bearer " + credential),
        form.getBytes(StandardCharsets.UTF_8));
  }

  private static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Auth",
        SpecStatus.DRAFT,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }
}
