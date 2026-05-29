/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spec CRUD on SQLite. Every method maps to a small number of SQL statements. No caching, no lazy
 * loading — the database is fast enough.
 */
public final class SpecStore {

  private final Sqlite db;

  public SpecStore(Sqlite db) {
    this.db = db;
  }

  public record SpecRow(
      String id,
      String project,
      String title,
      String status,
      String assignee,
      String agent,
      String model,
      String reasoningEffort,
      String branch,
      int priority,
      String createdBy,
      String createdAt,
      String updatedAt,
      List<String> dependsOn,
      List<String> repos) {}

  public record SpecContent(String body, String plan, String updatedAt) {}

  public record SpecFilter(
      String project, String status, String assignee, String repo, String search) {
    public static SpecFilter all() {
      return new SpecFilter(null, null, null, null, null);
    }
  }

  public record BoardSummary(
      int draft,
      int pending,
      int inProgress,
      int review,
      int done,
      int archived,
      String nextReadyId) {}

  public void create(SpecRow spec) {
    var now = Instant.now().toString();
    db.transaction(
        () -> {
          db.execute(
              """
              INSERT INTO specs (id, project, title, status, assignee, agent, model,
                  reasoning_effort, branch, priority, created_by, created_at, updated_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
              spec.id(),
              spec.project(),
              spec.title(),
              spec.status(),
              spec.assignee(),
              spec.agent(),
              spec.model(),
              spec.reasoningEffort(),
              spec.branch(),
              spec.priority(),
              spec.createdBy(),
              now,
              now);
          insertDependencies(spec.id(), spec.dependsOn());
          insertRepos(spec.id(), spec.repos());
          db.execute(
              "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, '', '', ?)",
              spec.id(),
              now);
        });
  }

  public Optional<SpecRow> findById(String id) {
    return db.queryOne(
            """
            SELECT id, project, title, status, assignee, agent, model, reasoning_effort,
                branch, priority, created_by, created_at, updated_at
            FROM specs WHERE id = ?""",
            this::mapSpec,
            id)
        .map(this::hydrate);
  }

  public List<SpecRow> list(SpecFilter filter) {
    var sql = new StringBuilder("SELECT DISTINCT s.id, s.project, s.title, s.status, s.assignee,");
    sql.append(
        " s.agent, s.model, s.reasoning_effort, s.branch, s.priority, s.created_by, s.created_at,"
            + " s.updated_at FROM specs s");
    var params = new ArrayList<>();
    var where = new ArrayList<String>();

    if (filter.repo() != null) {
      sql.append(" JOIN spec_repos sr ON sr.spec_id = s.id");
      where.add("sr.repo = ?");
      params.add(filter.repo());
    }
    if (filter.project() != null) {
      where.add("s.project = ?");
      params.add(filter.project());
    }
    if (filter.status() != null) {
      var statuses = filter.status().split(",");
      var placeholders = String.join(",", "?".repeat(statuses.length).split(""));
      where.add("s.status IN (" + placeholders + ")");
      for (var s : statuses) {
        params.add(s.strip());
      }
    }
    if (filter.assignee() != null) {
      where.add("s.assignee = ?");
      params.add(filter.assignee());
    }
    if (filter.search() != null) {
      where.add("(s.id LIKE ? OR s.title LIKE ?)");
      var pattern = "%" + filter.search() + "%";
      params.add(pattern);
      params.add(pattern);
    }
    if (!where.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", where));
    }
    sql.append(" ORDER BY s.project ASC, s.priority DESC, s.created_at ASC");

    return db.query(sql.toString(), this::mapSpec, params.toArray()).stream()
        .map(this::hydrate)
        .toList();
  }

  public void update(SpecRow spec) {
    var now = Instant.now().toString();
    db.transaction(
        () -> {
          db.execute(
              """
              UPDATE specs SET project = ?, title = ?, status = ?, assignee = ?, agent = ?,
                  model = ?, reasoning_effort = ?, branch = ?, priority = ?, updated_at = ?
              WHERE id = ?""",
              spec.project(),
              spec.title(),
              spec.status(),
              spec.assignee(),
              spec.agent(),
              spec.model(),
              spec.reasoningEffort(),
              spec.branch(),
              spec.priority(),
              now,
              spec.id());
          db.execute("DELETE FROM spec_dependencies WHERE spec_id = ?", spec.id());
          db.execute("DELETE FROM spec_repos WHERE spec_id = ?", spec.id());
          insertDependencies(spec.id(), spec.dependsOn());
          insertRepos(spec.id(), spec.repos());
        });
  }

  public void updateStatus(String id, String status) {
    db.execute(
        "UPDATE specs SET status = ?, updated_at = ? WHERE id = ?",
        status,
        Instant.now().toString(),
        id);
  }

  public void delete(String id) {
    db.execute("DELETE FROM specs WHERE id = ?", id);
  }

  public void setContent(String specId, String body, String plan) {
    var now = Instant.now().toString();
    db.execute(
        """
        INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, ?, ?, ?)
        ON CONFLICT(spec_id) DO UPDATE SET body = ?, plan = ?, updated_at = ?""",
        specId,
        body,
        plan,
        now,
        body,
        plan,
        now);
  }

  public Optional<SpecContent> getContent(String specId) {
    return db.queryOne(
        "SELECT body, plan, updated_at FROM spec_content WHERE spec_id = ?",
        row -> new SpecContent(row.text(0), row.text(1), row.text(2)),
        specId);
  }

  public List<SpecRow> readySpecs() {
    return db
        .query(
            """
            SELECT s.id, s.project, s.title, s.status, s.assignee, s.agent, s.model,
                s.reasoning_effort, s.branch, s.priority, s.created_by, s.created_at, s.updated_at
            FROM specs s
            WHERE s.status = 'pending'
            AND NOT EXISTS (
                SELECT 1 FROM spec_dependencies d
                JOIN specs dep ON dep.id = d.depends_on
                WHERE d.spec_id = s.id AND dep.status != 'done'
            )
            ORDER BY s.priority DESC, s.created_at ASC""",
            this::mapSpec)
        .stream()
        .map(this::hydrate)
        .toList();
  }

  public BoardSummary board() {
    return board(null);
  }

  public BoardSummary board(String projectFilter) {
    var sql = "SELECT status, COUNT(*) FROM specs WHERE status != 'archived'";
    var counts =
        projectFilter == null
            ? db.query(sql + " GROUP BY status", row -> new Object[] {row.text(0), row.integer(1)})
            : db.query(
                sql + " AND project = ? GROUP BY status",
                row -> new Object[] {row.text(0), row.integer(1)},
                projectFilter);
    var draft = 0;
    var pending = 0;
    var inProgress = 0;
    var review = 0;
    var done = 0;
    var archived = 0;
    for (var row : counts) {
      var count = (int) (long) row[1];
      switch ((String) row[0]) {
        case "draft" -> draft = count;
        case "pending" -> pending = count;
        case "in_progress" -> inProgress = count;
        case "review" -> review = count;
        case "done" -> done = count;
        case "archived" -> archived = count;
        default -> {}
      }
    }
    var ready = readySpecs();
    var nextReadyId = ready.isEmpty() ? null : ready.getFirst().id();
    return new BoardSummary(draft, pending, inProgress, review, done, archived, nextReadyId);
  }

  private SpecRow mapSpec(Sqlite.Row row) {
    return new SpecRow(
        row.text(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        row.text(8),
        row.isNull(9) ? 0 : (int) row.integer(9),
        row.text(10),
        row.text(11),
        row.text(12),
        List.of(),
        List.of());
  }

  private SpecRow hydrate(SpecRow spec) {
    var deps =
        db.query(
            "SELECT depends_on FROM spec_dependencies WHERE spec_id = ?",
            row -> row.text(0),
            spec.id());
    var repos =
        db.query("SELECT repo FROM spec_repos WHERE spec_id = ?", row -> row.text(0), spec.id());
    return new SpecRow(
        spec.id(),
        spec.project(),
        spec.title(),
        spec.status(),
        spec.assignee(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch(),
        spec.priority(),
        spec.createdBy(),
        spec.createdAt(),
        spec.updatedAt(),
        deps,
        repos);
  }

  /**
   * Inserts dependency edges for an already-persisted spec. Used by bulk import, which inserts all
   * spec rows before wiring dependencies so forward references within a batch don't violate the
   * {@code depends_on} foreign key. Callers must ensure each target spec already exists.
   */
  public void addDependencies(String specId, List<String> deps) {
    db.transaction(() -> insertDependencies(specId, deps));
  }

  private void insertDependencies(String specId, List<String> deps) {
    for (var dep : deps) {
      db.execute("INSERT INTO spec_dependencies (spec_id, depends_on) VALUES (?, ?)", specId, dep);
    }
  }

  private void insertRepos(String specId, List<String> repos) {
    for (var repo : repos) {
      db.execute("INSERT INTO spec_repos (spec_id, repo) VALUES (?, ?)", specId, repo);
    }
  }
}
