/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Spec CRUD on SQLite. Every method maps to a small number of SQL statements. No caching, no lazy
 * loading — the database is fast enough.
 *
 * <p>Every mutation journals the spec's full post-state into the {@link ChangeLog} within the same
 * transaction, so history is complete and any revision is restorable (the DB-sync no-lost-work
 * guarantee). {@code specs.rev} tracks the current revision.
 */
public final class SpecStore implements ConflictResolver {

  private static final String ENTITY = "spec";

  private final Sqlite db;
  private final ChangeLog changeLog;

  public SpecStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
  }

  public record SpecRow(
      String id,
      String project,
      String title,
      SpecStatus status,
      String assignee,
      String agent,
      String model,
      String reasoningEffort,
      String branch,
      int priority,
      String createdBy,
      String createdAt,
      String updatedAt,
      String updatedBy,
      List<String> dependsOn,
      List<String> repos) {

    /** Projects this stored row onto the storage-agnostic {@link Spec} value type. */
    public Spec toSpec() {
      return new Spec(
          id,
          project,
          title,
          status,
          assignee,
          dependsOn,
          repos,
          agent,
          model,
          reasoningEffort,
          branch);
    }
  }

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
    var now = DateTimeUtils.now().toString();
    db.transaction(
        () -> {
          db.execute(
              """
              INSERT INTO specs (id, project, title, status, assignee, agent, model,
                  reasoning_effort, branch, priority, created_by, created_at, updated_at, updated_by)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
              spec.id(),
              spec.project(),
              spec.title(),
              spec.status().wire(),
              spec.assignee(),
              spec.agent(),
              spec.model(),
              spec.reasoningEffort(),
              spec.branch(),
              spec.priority(),
              spec.createdBy(),
              now,
              now,
              spec.updatedBy());
          insertDependencies(spec.id(), spec.dependsOn());
          insertRepos(spec.id(), spec.repos());
          db.execute(
              "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, '', '', ?)",
              spec.id(),
              now);
          recordRevision(spec.id(), "local", false);
        });
  }

  public Optional<SpecRow> findById(String id) {
    return db.queryOne(
            """
            SELECT id, project, title, status, assignee, agent, model, reasoning_effort,
                branch, priority, created_by, created_at, updated_at, updated_by
            FROM specs WHERE id = ?""",
            this::mapSpec,
            id)
        .map(this::hydrate);
  }

  public List<SpecRow> list(SpecFilter filter) {
    var sql = new StringBuilder("SELECT DISTINCT s.id, s.project, s.title, s.status, s.assignee,");
    sql.append(
        " s.agent, s.model, s.reasoning_effort, s.branch, s.priority, s.created_by, s.created_at,"
            + " s.updated_at, s.updated_by FROM specs s");
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
        params.add(SpecStatus.fromWire(s.strip()).wire());
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

  /**
   * Every spec bucketed to {@code project}, as storage-agnostic {@link Spec} values. The single
   * seam the CLI, the API, and the agent-facing commands all read project specs through — one
   * source of truth, no container files.
   */
  public List<Spec> projectSpecs(String project) {
    return list(new SpecFilter(project, null, null, null, null)).stream()
        .map(SpecRow::toSpec)
        .toList();
  }

  public void update(SpecRow spec) {
    var now = DateTimeUtils.now().toString();
    db.transaction(
        () -> {
          db.execute(
              """
              UPDATE specs SET project = ?, title = ?, status = ?, assignee = ?, agent = ?,
                  model = ?, reasoning_effort = ?, branch = ?, priority = ?, updated_at = ?,
                  updated_by = ?
              WHERE id = ?""",
              spec.project(),
              spec.title(),
              spec.status().wire(),
              spec.assignee(),
              spec.agent(),
              spec.model(),
              spec.reasoningEffort(),
              spec.branch(),
              spec.priority(),
              now,
              spec.updatedBy(),
              spec.id());
          db.execute("DELETE FROM spec_dependencies WHERE spec_id = ?", spec.id());
          db.execute("DELETE FROM spec_repos WHERE spec_id = ?", spec.id());
          insertDependencies(spec.id(), spec.dependsOn());
          insertRepos(spec.id(), spec.repos());
          recordRevision(spec.id(), "local", false);
        });
  }

  public void updateStatus(String id, SpecStatus status) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE specs SET status = ?, updated_at = ? WHERE id = ?",
              status.wire(),
              DateTimeUtils.now().toString(),
              id);
          recordRevision(id, "local", false);
        });
  }

  public void delete(String id) {
    db.transaction(
        () -> {
          recordRevision(id, "local", true);
          db.execute("DELETE FROM specs WHERE id = ?", id);
        });
  }

  public void setContent(String specId, String body, String plan) {
    var now = DateTimeUtils.now().toString();
    db.transaction(
        () -> {
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
          recordRevision(specId, "local", false);
        });
  }

  /** Every recorded revision of a spec, oldest first. */
  public List<ChangeLog.Entry> history(String id) {
    return changeLog.history(ENTITY, id);
  }

  /**
   * Restores a spec to a prior revision's content, recorded as a NEW revision (origin {@code
   * restore}) — the current state is never discarded, it becomes part of history too, so a restore
   * is itself reversible. Re-creates the spec if it had been deleted.
   */
  public void restore(String id, String rev) {
    var entry =
        changeLog
            .at(ENTITY, id, rev)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No revision '" + rev + "' recorded for spec '" + id + "'."));
    var snapshot = YamlUtil.parseMap(entry.snapshot());
    db.transaction(
        () -> {
          applySnapshot(id, snapshot);
          recordRevision(id, "restore", false);
        });
  }

  private String recordRevision(String id, String origin, boolean deleted) {
    return recordRevision(id, null, origin, deleted, false);
  }

  /**
   * Appends a revision for the current state of {@code id}. With {@code explicitRev} null the rev
   * is minted from the current counter; otherwise the caller-supplied rev is used verbatim (sync
   * adopting main's authoritative rev). {@code setBaseRev} records that this revision is the new
   * synced ancestor — set only when adopting from main, never on a local edit.
   */
  private String recordRevision(
      String id, String explicitRev, String origin, boolean deleted, boolean setBaseRev) {
    var spec = findById(id).orElse(null);
    if (spec == null) {
      return null;
    }
    var map = snapshotMap(spec);
    if (deleted) {
      map.put("_base_rev", rawBaseRev(id));
    }
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(currentRev(id), snapshot);
    if (!deleted) {
      if (setBaseRev) {
        db.execute("UPDATE specs SET rev = ?, base_rev = ? WHERE id = ?", rev, rev, id);
      } else {
        db.execute("UPDATE specs SET rev = ? WHERE id = ?", rev, id);
      }
    }
    changeLog.append(ENTITY, id, rev, spec.updatedBy(), origin, deleted, snapshot);
    return rev;
  }

  private String rawBaseRev(String id) {
    var value =
        db.queryOne("SELECT COALESCE(base_rev, '') FROM specs WHERE id = ?", row -> row.text(0), id)
            .orElse("");
    return value.isBlank() ? null : value;
  }

  private String currentRev(String id) {
    return db.queryOne("SELECT COALESCE(rev, '') FROM specs WHERE id = ?", row -> row.text(0), id)
        .orElse("");
  }

  private String snapshotJson(SpecRow spec) {
    return YamlUtil.dumpJson(snapshotMap(spec));
  }

  private Map<String, Object> snapshotMap(SpecRow spec) {
    var content = getContent(spec.id()).orElse(new SpecContent("", "", null));
    var map = new LinkedHashMap<String, Object>();
    map.put("id", spec.id());
    map.put("project", spec.project());
    map.put("title", spec.title());
    map.put("status", spec.status().wire());
    map.put("assignee", spec.assignee());
    map.put("agent", spec.agent());
    map.put("model", spec.model());
    map.put("reasoning_effort", spec.reasoningEffort());
    map.put("branch", spec.branch());
    map.put("priority", spec.priority());
    map.put("created_by", spec.createdBy());
    map.put("created_at", spec.createdAt());
    map.put("updated_by", spec.updatedBy());
    map.put("updated_at", spec.updatedAt());
    map.put("depends_on", spec.dependsOn());
    map.put("repos", spec.repos());
    map.put("body", content.body());
    map.put("plan", content.plan());
    return map;
  }

  private void applySnapshot(String id, Map<String, Object> snapshot) {
    var spec = specFromSnapshot(snapshot);
    var now = DateTimeUtils.now().toString();
    if (findById(id).isPresent()) {
      db.execute(
          """
          UPDATE specs SET project = ?, title = ?, status = ?, assignee = ?, agent = ?, model = ?,
              reasoning_effort = ?, branch = ?, priority = ?, updated_at = ?, updated_by = ?
          WHERE id = ?""",
          spec.project(),
          spec.title(),
          spec.status().wire(),
          spec.assignee(),
          spec.agent(),
          spec.model(),
          spec.reasoningEffort(),
          spec.branch(),
          spec.priority(),
          now,
          spec.updatedBy(),
          id);
      db.execute("DELETE FROM spec_dependencies WHERE spec_id = ?", id);
      db.execute("DELETE FROM spec_repos WHERE spec_id = ?", id);
    } else {
      db.execute(
          """
          INSERT INTO specs (id, project, title, status, assignee, agent, model, reasoning_effort,
              branch, priority, created_by, created_at, updated_at, updated_by)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
          id,
          spec.project(),
          spec.title(),
          spec.status().wire(),
          spec.assignee(),
          spec.agent(),
          spec.model(),
          spec.reasoningEffort(),
          spec.branch(),
          spec.priority(),
          spec.createdBy(),
          spec.createdAt() == null || spec.createdAt().isBlank() ? now : spec.createdAt(),
          now,
          spec.updatedBy());
    }
    insertDependencies(id, spec.dependsOn());
    insertRepos(id, spec.repos());
    db.execute(
        """
        INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, ?, ?, ?)
        ON CONFLICT(spec_id) DO UPDATE SET body = ?, plan = ?, updated_at = ?""",
        id,
        text(snapshot, "body"),
        text(snapshot, "plan"),
        now,
        text(snapshot, "body"),
        text(snapshot, "plan"),
        now);
  }

  @SuppressWarnings("unchecked")
  private static SpecRow specFromSnapshot(Map<String, Object> s) {
    var priority = s.get("priority");
    return new SpecRow(
        text(s, "id"),
        text(s, "project"),
        text(s, "title"),
        SpecStatus.fromLegacy(text(s, "status")),
        text(s, "assignee"),
        text(s, "agent"),
        text(s, "model"),
        text(s, "reasoning_effort"),
        text(s, "branch"),
        priority instanceof Number n ? n.intValue() : 0,
        text(s, "created_by"),
        text(s, "created_at"),
        text(s, "updated_at"),
        text(s, "updated_by"),
        (List<String>) s.getOrDefault("depends_on", List.of()),
        (List<String>) s.getOrDefault("repos", List.of()));
  }

  private static String text(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static final Set<String> SYNC_FIELDS =
      Set.of(
          "project",
          "title",
          "status",
          "assignee",
          "agent",
          "model",
          "reasoning_effort",
          "branch",
          "priority",
          "depends_on",
          "repos",
          "body",
          "plan");

  /**
   * The subset of a snapshot that carries an FDE's actual work — everything except the surrogate
   * key and the timestamp/attribution metadata that every replica writes locally. Conflict
   * detection compares only these, so two boxes never falsely conflict on {@code updated_at}.
   */
  private static Map<String, Object> comparable(Map<String, Object> full) {
    if (full == null) {
      return null;
    }
    var m = new LinkedHashMap<String, Object>();
    for (var field : full.keySet()) {
      if (SYNC_FIELDS.contains(field)) {
        m.put(field, full.get(field));
      }
    }
    var author = full.get("updated_by");
    if (author != null) {
      m.put(ACTOR, author);
    }
    return m;
  }

  /**
   * Reserved metadata key carrying the author of a revision through the sync protocol. It rides
   * inside the comparable snapshot but {@link ConflictDetector} ignores reserved ({@code
   * _}-prefixed) keys, so attribution propagates without ever causing a false conflict. The
   * receiving side reads it to attribute the synced row to its real author instead of {@code sync}.
   */
  private static final String ACTOR = "_actor";

  private static String authorOf(Map<String, Object> snapshot) {
    var author = snapshot.get(ACTOR);
    return author == null ? "sync" : author.toString();
  }

  /** Comparable snapshot of the current state, or null if the spec is absent/deleted. */
  public Map<String, Object> comparableSnapshot(String id) {
    return findById(id).map(this::snapshotMap).map(SpecStore::comparable).orElse(null);
  }

  /** Comparable snapshot recorded at a given revision (the merge base), or null if not recorded. */
  public Map<String, Object> comparableAtRev(String id, String rev) {
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .at(ENTITY, id, rev)
        .map(e -> comparable(YamlUtil.parseMap(e.snapshot())))
        .orElse(null);
  }

  public String revOf(String id) {
    var rev = currentRev(id);
    return rev.isBlank() ? null : rev;
  }

  /** The latest revision recorded for an entity, including a tombstone; null if never recorded. */
  public String latestRev(String id) {
    var history = changeLog.history(ENTITY, id);
    return history.isEmpty() ? null : history.getLast().rev();
  }

  /**
   * The revision this row last synced from main. For a live row it is the {@code base_rev} column;
   * for a locally deleted entity the row is gone, so it is recovered from the {@code _base_rev}
   * embedded in the tombstone — without which a local delete could not be told apart from a
   * delete-vs-edit conflict.
   */
  public String baseRevOf(String id) {
    if (findById(id).isPresent()) {
      return rawBaseRev(id);
    }
    var tombstone = changeLog.history(ENTITY, id);
    if (tombstone.isEmpty()) {
      return null;
    }
    var baseRev = YamlUtil.parseMap(tombstone.getLast().snapshot()).get("_base_rev");
    return baseRev == null ? null : baseRev.toString();
  }

  /** Every entity id this replica knows of, including those only present as a tombstone. */
  public Set<String> syncEntityIds() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT DISTINCT entity_id FROM change_log WHERE entity_type = ?",
            row -> row.text(0),
            ENTITY));
  }

  /**
   * Records a baseline revision for every spec that has none yet. A spec written before this store
   * journaled its mutations has a row in {@code specs} but no change-log entry, so it is invisible
   * to sync until journaled — the same gap {@link ProjectStore#backfillRevisions()} closes for
   * projects. Idempotent: a spec already in the change log is left untouched. The minted rev is
   * content-addressed, so two boxes backfilling the same pre-journal spec reach the same revision
   * and converge without a conflict. Returns how many were backfilled.
   */
  public int backfillRevisions() {
    var journaled = syncEntityIds();
    var pending = list(SpecFilter.all()).stream().filter(s -> !journaled.contains(s.id())).toList();
    for (var spec : pending) {
      db.transaction(() -> recordRevision(spec.id(), "local", false));
    }
    return pending.size();
  }

  /**
   * Writes an authoritative state from main at its exact revision (no minting), marking it the new
   * synced ancestor ({@code base_rev = rev}). A null snapshot adopts a deletion. Used by the sync
   * engine; the revision is journaled with origin {@code sync}.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    db.transaction(
        () -> {
          if (snapshot == null) {
            if (findById(id).isPresent()) {
              recordRevision(id, rev, "sync", true, false);
              db.execute("DELETE FROM specs WHERE id = ?", id);
            } else {
              changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
            }
          } else {
            var full = new LinkedHashMap<>(snapshot);
            full.put("id", id);
            full.put("updated_by", authorOf(snapshot));
            applySnapshot(id, full);
            recordRevision(id, rev, "sync", false, true);
          }
        });
  }

  /**
   * Compare-and-set commit as main: mints a new authoritative rev only if {@code expectedRev} still
   * equals the entity's current rev (a brand-new entity expects {@code null}); otherwise returns
   * {@link PushOutcome.Stale} with main's present state, never overwriting a concurrent change. A
   * null snapshot commits a deletion. The check and the write share one transaction, so two nodes
   * pushing the same row can never both win. Used by the sync engine on the main side.
   */
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return db.transaction(
        () -> {
          if (!Objects.equals(latestRev(id), expectedRev)) {
            return new PushOutcome.Stale(latestRev(id), comparableSnapshot(id));
          }
          if (snapshot == null) {
            if (findById(id).isEmpty()) {
              return new PushOutcome.Accepted(latestRev(id));
            }
            var rev = recordRevision(id, null, "sync", true, false);
            db.execute("DELETE FROM specs WHERE id = ?", id);
            return new PushOutcome.Accepted(rev);
          }
          var full = new LinkedHashMap<>(snapshot);
          full.put("id", id);
          full.put("updated_by", authorOf(snapshot));
          applySnapshot(id, full);
          return new PushOutcome.Accepted(recordRevision(id, null, "sync", false, false));
        });
  }

  /**
   * Resolves an open conflict locally by rebasing the row onto main's conflicting content {@code
   * remote} — recorded as the new merge base, so the next sync can never re-raise the same conflict
   * (base now equals remote) — and then writing {@code chosen} as the resolved state. When {@code
   * chosen} differs from {@code remote} (keep-mine or a merge) the row becomes a forward local edit
   * the next sync pushes; when they match (take-theirs) the row simply adopts main's value, and the
   * earlier local version is still in the {@link ChangeLog}. A {@code null} side is a deletion.
   * Returns the rev the row now carries. No work is ever lost: every state is journaled.
   */
  public String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote) {
    return db.transaction(
        () -> {
          var baseRev = adoptBase(id, remote);
          if (sameContent(chosen, remote)) {
            return baseRev;
          }
          return writeChosen(id, chosen);
        });
  }

  private String adoptBase(String id, Map<String, Object> remote) {
    if (remote == null) {
      if (findById(id).isPresent()) {
        var rev = recordRevision(id, null, "sync", true, false);
        db.execute("DELETE FROM specs WHERE id = ?", id);
        return rev;
      }
      var rev = Revisions.next(currentRev(id), "{}");
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return rev;
    }
    applySnapshot(id, withSync(id, remote));
    return recordRevision(id, null, "sync", false, true);
  }

  private String writeChosen(String id, Map<String, Object> chosen) {
    if (chosen == null) {
      if (findById(id).isEmpty()) {
        return latestRev(id);
      }
      var rev = recordRevision(id, null, "resolve", true, false);
      db.execute("DELETE FROM specs WHERE id = ?", id);
      return rev;
    }
    applySnapshot(id, withSync(id, chosen));
    return recordRevision(id, null, "resolve", false, false);
  }

  private static Map<String, Object> withSync(String id, Map<String, Object> snapshot) {
    var full = new LinkedHashMap<>(snapshot);
    full.put("id", id);
    full.put("updated_by", authorOf(snapshot));
    return full;
  }

  private static boolean sameContent(Map<String, Object> a, Map<String, Object> b) {
    if (a == null || b == null) {
      return a == b;
    }
    var keys = new LinkedHashSet<String>();
    keys.addAll(a.keySet());
    keys.addAll(b.keySet());
    return keys.stream()
        .filter(key -> !key.startsWith("_"))
        .allMatch(key -> Objects.equals(a.get(key), b.get(key)));
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
                s.reasoning_effort, s.branch, s.priority, s.created_by, s.created_at, s.updated_at,
                s.updated_by
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
        SpecStatus.fromLegacy(row.text(3)),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        row.text(8),
        row.isNull(9) ? 0 : (int) row.integer(9),
        row.text(10),
        row.text(11),
        row.text(12),
        row.text(13),
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
        spec.updatedBy(),
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
