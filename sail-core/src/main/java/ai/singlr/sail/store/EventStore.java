/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists events to SQLite. Replaces the file-based JSONL audit log with a queryable table. */
public final class EventStore {

  private final Sqlite db;

  public EventStore(Sqlite db) {
    this.db = db;
  }

  public record EventRow(
      long id,
      String timestamp,
      String type,
      String project,
      String specId,
      String agent,
      String host,
      String data) {}

  public long insert(EventRow event) {
    db.execute(
        """
        INSERT INTO events (timestamp, type, project, spec_id, agent, host, data)
        VALUES (?, ?, ?, ?, ?, ?, ?)""",
        event.timestamp(),
        event.type(),
        event.project(),
        event.specId(),
        event.agent(),
        event.host(),
        event.data());
    return db.queryOne("SELECT last_insert_rowid()", row -> row.integer(0)).orElse(0L);
  }

  public List<EventRow> recent(int limit) {
    return db.query(
        """
        SELECT id, timestamp, type, project, spec_id, agent, host, data
        FROM events ORDER BY id DESC LIMIT ?""",
        this::mapEvent,
        limit);
  }

  public List<EventRow> forSpec(String specId) {
    return db.query(
        """
        SELECT id, timestamp, type, project, spec_id, agent, host, data
        FROM events WHERE spec_id = ? ORDER BY id ASC""",
        this::mapEvent,
        specId);
  }

  /**
   * The bounded, ascending history window for one spec: at most {@code limit} rows with {@code id >
   * afterId} when {@code afterId} is non-null, otherwise the newest {@code limit} rows. Rows whose
   * type is in {@code excludedTypes} never occupy the window. Both shapes range-scan {@code
   * idx_events_spec} (spec_id plus the implicit rowid), so a large events table is never scanned.
   */
  public List<EventRow> forSpec(String specId, Long afterId, int limit, Set<String> excludedTypes) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
    var exclusion =
        excludedTypes.isEmpty()
            ? ""
            : " AND type NOT IN ("
                + String.join(", ", Collections.nCopies(excludedTypes.size(), "?"))
                + ")";
    var parameters = new ArrayList<Object>();
    parameters.add(specId);
    if (afterId != null) {
      parameters.add(afterId);
    }
    parameters.addAll(excludedTypes);
    parameters.add(limit);
    var sql =
        "SELECT id, timestamp, type, project, spec_id, agent, host, data FROM events"
            + " WHERE spec_id = ?"
            + (afterId != null ? " AND id > ?" : "")
            + exclusion
            + " ORDER BY id "
            + (afterId != null ? "ASC" : "DESC")
            + " LIMIT ?";
    var rows = db.query(sql, this::mapEvent, parameters.toArray());
    return afterId != null ? rows : rows.reversed();
  }

  public List<EventRow> forSpecAndType(String specId, String type) {
    return db.query(
        """
        SELECT id, timestamp, type, project, spec_id, agent, host, data
        FROM events WHERE spec_id = ? AND type = ? ORDER BY id ASC""",
        this::mapEvent,
        specId,
        type);
  }

  public List<EventRow> since(long afterId, int limit) {
    return db.query(
        """
        SELECT id, timestamp, type, project, spec_id, agent, host, data
        FROM events WHERE id > ? ORDER BY id ASC LIMIT ?""",
        this::mapEvent,
        afterId,
        limit);
  }

  public Map<String, Long> stats() {
    var total = db.queryOne("SELECT COUNT(*) FROM events", row -> row.integer(0)).orElse(0L);
    var types =
        db.query(
            "SELECT type, COUNT(*) FROM events GROUP BY type ORDER BY COUNT(*) DESC",
            row -> new Object[] {row.text(0), row.integer(1)});
    var typeMap = new LinkedHashMap<String, Long>();
    typeMap.put("total", total);
    for (var row : types) {
      typeMap.put((String) row[0], (long) row[1]);
    }
    return Map.copyOf(typeMap);
  }

  public int pruneBefore(String before, Set<String> types, int batchSize) {
    if (types.isEmpty()) {
      return 0;
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    var placeholders = String.join(", ", Collections.nCopies(types.size(), "?"));
    var sql =
        "DELETE FROM events WHERE id IN (SELECT id FROM events WHERE timestamp < ? AND type IN ("
            + placeholders
            + ") ORDER BY id LIMIT ?)";
    var parameters = new ArrayList<Object>();
    parameters.add(before);
    parameters.addAll(types);
    parameters.add((long) batchSize);
    var total = 0;
    while (true) {
      var deleted =
          db.transaction(
              () -> {
                db.execute(sql, parameters.toArray());
                return db.changes();
              });
      total += deleted;
      if (deleted < batchSize) {
        return total;
      }
      Thread.yield();
    }
  }

  private EventRow mapEvent(Sqlite.Row row) {
    return new EventRow(
        row.integer(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7));
  }
}
