/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;

/**
 * The API's production authentication over a test database, on a box with no sync handle unless a
 * test names the box's sync configuration.
 */
public final class TestAuth {

  private TestAuth() {}

  /** The role rule over {@code db}'s roster, on a box that operates as no FDE. */
  public static RoleRule roles(Sqlite db) {
    return roles(db, SyncConfig.unset());
  }

  /** The role rule over {@code db}'s roster, on the box {@code box} configures. */
  public static RoleRule roles(Sqlite db, SyncConfig box) {
    return new RoleRule(() -> box, new FdeStore(db));
  }

  /** Bearer-token authentication over {@code db}. */
  public static TokenAuth tokens(Sqlite db) {
    return new TokenAuth(new TokenStore(db), roles(db));
  }

  /** Session-then-token authentication over {@code db}, as {@code sail server start} wires it. */
  public static SessionAwareAuth sessions(Sqlite db) {
    return sessions(db, SyncConfig.unset());
  }

  /** Session-then-token authentication over {@code db} on the box {@code box} configures. */
  public static SessionAwareAuth sessions(Sqlite db, SyncConfig box) {
    var roles = roles(db, box);
    return new SessionAwareAuth(
        new AuthSessionStore(db), roles, new TokenAuth(new TokenStore(db), roles));
  }
}
