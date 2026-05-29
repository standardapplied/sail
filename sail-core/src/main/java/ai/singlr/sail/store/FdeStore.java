/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Forward Deployed Engineers — the human principals that own API tokens and are attributed on the
 * specs, reviews, and events they act on. The handle is the stable, human-facing identity (used as
 * a spec assignee); the surrogate id is the foreign key tokens and other records point at.
 */
public final class FdeStore {

  private final Sqlite db;

  public FdeStore(Sqlite db) {
    this.db = db;
  }

  public record Fde(
      String id,
      String handle,
      String displayName,
      String email,
      String status,
      String createdAt) {}

  /**
   * Creates an FDE with a generated id and {@code active} status. Throws if the handle is taken.
   */
  public Fde add(String handle, String displayName, String email) {
    var fde = new Fde(generateId(), handle, displayName, email, "active", Instant.now().toString());
    db.execute(
        "INSERT INTO fdes (id, handle, display_name, email, status, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        fde.id(),
        fde.handle(),
        fde.displayName(),
        fde.email(),
        fde.status(),
        fde.createdAt());
    return fde;
  }

  public Optional<Fde> byHandle(String handle) {
    return db.queryOne(SELECT + " WHERE handle = ?", FdeStore::map, handle);
  }

  public Optional<Fde> byId(String id) {
    return db.queryOne(SELECT + " WHERE id = ?", FdeStore::map, id);
  }

  public List<Fde> list() {
    return db.query(SELECT + " ORDER BY handle", FdeStore::map);
  }

  private static final String SELECT =
      "SELECT id, handle, display_name, email, status, created_at FROM fdes";

  private static Fde map(Sqlite.Row row) {
    return new Fde(row.text(0), row.text(1), row.text(2), row.text(3), row.text(4), row.text(5));
  }

  private static String generateId() {
    var bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return "fde_" + HexFormat.of().formatHex(bytes);
  }
}
