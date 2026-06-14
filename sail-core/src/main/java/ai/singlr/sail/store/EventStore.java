/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
