/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent session CRUD on SQLite. Tracks when agents start and stop, which spec they worked on, and
 * their final status. Used by {@code SessionTracker} to persist lifecycle events and by the API to
 * query session history.
 */
public final class SessionStore {

  private final Sqlite db;

  public SessionStore(Sqlite db) {
    this.db = db;
  }

  public record SessionRow(
      String id,
      String project,
      String specId,
      String agent,
      String branch,
      String task,
      Integer pid,
      String status,
      String startedAt,
      String completedAt) {}

  public String create(
      String project, String specId, String agent, String branch, String task, Integer pid) {
    var id = DateTimeUtils.newId().toString();
    db.execute(
        """
        INSERT INTO agent_sessions (id, project, spec_id, agent, branch, task, pid, status, started_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'running', ?)""",
        id,
        project,
        specId,
        agent,
        branch,
        task,
        pid != null ? pid.longValue() : null,
        Instant.now().toString());
    return id;
  }

  public Optional<SessionRow> findById(String id) {
    return db.queryOne(
        """
        SELECT id, project, spec_id, agent, branch, task, pid, status, started_at, completed_at
        FROM agent_sessions WHERE id = ?""",
        this::mapSession,
        id);
  }

  public Optional<SessionRow> latestForProject(String project) {
    return db.queryOne(
        """
        SELECT id, project, spec_id, agent, branch, task, pid, status, started_at, completed_at
        FROM agent_sessions WHERE project = ? ORDER BY started_at DESC LIMIT 1""",
        this::mapSession,
        project);
  }

  public Optional<SessionRow> runningForProject(String project) {
    return db.queryOne(
        """
        SELECT id, project, spec_id, agent, branch, task, pid, status, started_at, completed_at
        FROM agent_sessions WHERE project = ? AND status = 'running' ORDER BY started_at DESC LIMIT 1""",
        this::mapSession,
        project);
  }

  public List<SessionRow> listForProject(String project) {
    return db.query(
        """
        SELECT id, project, spec_id, agent, branch, task, pid, status, started_at, completed_at
        FROM agent_sessions WHERE project = ? ORDER BY started_at DESC""",
        this::mapSession,
        project);
  }

  public List<SessionRow> listForSpec(String specId) {
    return db.query(
        """
        SELECT id, project, spec_id, agent, branch, task, pid, status, started_at, completed_at
        FROM agent_sessions WHERE spec_id = ? ORDER BY started_at DESC""",
        this::mapSession,
        specId);
  }

  public void complete(String id, String status) {
    db.execute(
        "UPDATE agent_sessions SET status = ?, completed_at = ? WHERE id = ?",
        status,
        Instant.now().toString(),
        id);
  }

  private SessionRow mapSession(Sqlite.Row row) {
    return new SessionRow(
        row.text(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.isNull(6) ? null : (int) row.integer(6),
        row.text(7),
        row.text(8),
        row.text(9));
  }
}
