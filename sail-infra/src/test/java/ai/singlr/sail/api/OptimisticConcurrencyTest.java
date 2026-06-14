/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptimisticConcurrencyTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SailApiServer server;
  private SpecStore specStore;
  private String token;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    var tokenStore = new TokenStore(db);
    token = tokenStore.create("test", "admin").token();
    specStore = new SpecStore(db);
    var eventStore = new EventStore(db);
    var bus = new EventBus();
    var persister = new SpecStoreAuditPersister(eventStore);
    var operations =
        new SailApiOperations(new ShellExecutor(false), "sail.yaml", bus, persister, specStore);
    server =
        new SailApiServer(
            "127.0.0.1", 0, operations, tokenStore, bus, persister, tempDir.resolve("api.sock"));
    server.start();
    specStore.create(
        new SpecStore.SpecRow(
            "auth",
            "manatee",
            "Auth flow",
            SpecStatus.DRAFT,
            null,
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
            List.of()));
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  @Test
  void getReturnsEtagHeader() throws Exception {
    var response = get("/v1/specs/auth");

    assertEquals(200, response.statusCode());
    var etag = response.headers().firstValue("ETag").orElse(null);
    assertNotNull(etag, "ETag header must be present");
    assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "ETag must be quoted: " + etag);
  }

  @Test
  void putWithoutIfMatchSucceedsLastWriteWins() throws Exception {
    var response = putJson("/v1/specs/auth", "{\"title\":\"Updated\"}", null);
    assertEquals(200, response.statusCode());
  }

  @Test
  void putWithMatchingIfMatchSucceeds() throws Exception {
    var etag = get("/v1/specs/auth").headers().firstValue("ETag").orElseThrow();

    var response = putJson("/v1/specs/auth", "{\"title\":\"Updated\"}", etag);
    assertEquals(200, response.statusCode());
  }

  @Test
  void putWithStaleIfMatchReturns412() throws Exception {
    var staleEtag = get("/v1/specs/auth").headers().firstValue("ETag").orElseThrow();
    putJson("/v1/specs/auth", "{\"title\":\"First write\"}", staleEtag);

    var response = putJson("/v1/specs/auth", "{\"title\":\"Second write\"}", staleEtag);
    assertEquals(412, response.statusCode());
    assertTrue(response.body().contains("precondition_failed"));
  }

  @Test
  void putWithWildcardIfMatchAlwaysSucceeds() throws Exception {
    var response = putJson("/v1/specs/auth", "{\"title\":\"Forced\"}", "*");
    assertEquals(200, response.statusCode());
  }

  @Test
  void deleteWithStaleIfMatchReturns412() throws Exception {
    var staleEtag = get("/v1/specs/auth").headers().firstValue("ETag").orElseThrow();
    putJson("/v1/specs/auth", "{\"title\":\"Move the etag\"}", null);

    var response =
        send(
            HttpRequest.newBuilder(uri("/v1/specs/auth"))
                .header("Authorization", "Bearer " + token)
                .header("If-Match", staleEtag)
                .DELETE()
                .build());
    assertEquals(412, response.statusCode());
  }

  @Test
  void contentPutWithStaleIfMatchReturns412() throws Exception {
    var staleEtag = get("/v1/specs/auth").headers().firstValue("ETag").orElseThrow();
    putJson("/v1/specs/auth", "{\"title\":\"Bump etag\"}", null);

    var response = putJson("/v1/specs/auth/content", "{\"body\":\"# Spec\"}", staleEtag);
    assertEquals(412, response.statusCode());
  }

  @Test
  void putAgainstMissingSpecWithIfMatchReturnsSpecNotFound() throws Exception {
    var response = putJson("/v1/specs/nope", "{\"title\":\"x\"}", "\"any\"");
    assertEquals(404, response.statusCode());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(
        HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build());
  }

  private HttpResponse<String> putJson(String path, String json, String ifMatch) throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json));
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    return send(builder.build());
  }

  private static HttpResponse<String> send(HttpRequest request) throws Exception {
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
