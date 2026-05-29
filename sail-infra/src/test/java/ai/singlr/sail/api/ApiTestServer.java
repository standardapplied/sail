/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Test scaffold that wires up a {@link SailApiServer} backed by a real SQLite {@link TokenStore} in
 * a temporary database. Tests get a live server, a valid bearer token, and isolation from the
 * production {@code ~/.sail/sail.db}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * try (var fixture = ApiTestServer.start(tempDir, operations)) {
 *   var port = fixture.port();
 *   var token = fixture.token();
 *   ...
 * }
 * }</pre>
 */
public final class ApiTestServer implements AutoCloseable {

  private final Sqlite db;
  private final SailApiServer server;
  private final TokenStore tokenStore;
  private final String token;

  private ApiTestServer(Sqlite db, SailApiServer server, TokenStore tokenStore, String token) {
    this.db = db;
    this.server = server;
    this.tokenStore = tokenStore;
    this.token = token;
  }

  /** Opens an in-tempdir SQLite DB, creates an admin token, and starts the server on port 0. */
  public static ApiTestServer start(Path tempDir, ApiOperations operations) throws IOException {
    return start(tempDir, operations, null, null);
  }

  /** Starts an event-bus-aware server; pass {@code null} for {@code eventBus} to disable events. */
  public static ApiTestServer start(
      Path tempDir, ApiOperations operations, EventBus eventBus, EventSubscriber auditSubscriber)
      throws IOException {
    var db = Sqlite.open(tempDir.resolve("sail-test.db"));
    new SchemaManager(db).migrate();
    var tokenStore = new TokenStore(db);
    var token = tokenStore.create("test-admin", "admin").token();
    var socketPath = tempDir.resolve("api.sock");
    var server =
        new SailApiServer(
            "127.0.0.1", 0, operations, tokenStore, eventBus, auditSubscriber, socketPath);
    server.start();
    return new ApiTestServer(db, server, tokenStore, token);
  }

  public SailApiServer server() {
    return server;
  }

  public int port() {
    return server.port();
  }

  public String token() {
    return token;
  }

  public TokenStore tokenStore() {
    return tokenStore;
  }

  @Override
  public void close() {
    server.close();
    db.close();
  }
}
