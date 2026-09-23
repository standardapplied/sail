/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import java.util.List;
import java.util.Objects;

/**
 * A node's prunes waiting for main. Main is the only author of an erasure, so a prune on a node is
 * recorded here and offered to main at the start of the entity type's next round; the node erases
 * locally only once main has answered with the erasure's rev ({@link Erasure#adopt} clears the
 * request), or drops the request when main refuses it. Local to the box: never journaled, never
 * synced.
 */
public final class EraseRequests {

  private final Sqlite db;

  public EraseRequests(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** Records that {@code actor} asked to erase {@code type} {@code id}; asking twice is one ask. */
  public void request(String type, String id, String actor) {
    Strings.requireNonBlank(type, "An erase request needs a type");
    Strings.requireNonBlank(id, "An erase request needs an id");
    db.execute(
        """
        INSERT INTO erase_requests (entity_type, entity_id, actor, requested_at)
        VALUES (?, ?, ?, ?) ON CONFLICT(entity_type, entity_id) DO NOTHING""",
        type,
        id,
        actor,
        DateTimeUtils.now().toString());
  }

  /** The ids of {@code type} waiting for main, oldest ask first. */
  public List<String> pending(String type) {
    return db.query(
        "SELECT entity_id FROM erase_requests WHERE entity_type = ? ORDER BY requested_at, rowid",
        row -> row.text(0),
        type);
  }

  /** Forgets one request, answered or refused. */
  public void drop(String type, String id) {
    db.execute("DELETE FROM erase_requests WHERE entity_type = ? AND entity_id = ?", type, id);
  }
}
