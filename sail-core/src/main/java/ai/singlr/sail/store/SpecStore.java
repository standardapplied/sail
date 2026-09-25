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
import ai.singlr.sail.identity.Actor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
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
public final class SpecStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "spec";

  private final Sqlite db;
  private final BlobStore blobs;
  private final ChangeLog changeLog;
  private final RevisionJournal journal;

  public SpecStore(Sqlite db) {
    this.db = db;
    this.blobs = new BlobStore(db);
    this.changeLog = new ChangeLog(db);
    this.journal = new RevisionJournal(db, changeLog, new SpecSchema());
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
      List<String> repos,
      String roomId) {

    /** A row with no room attachment yet — the shape a spec has at creation time. */
    public SpecRow(
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
      this(
          id,
          project,
          title,
          status,
          assignee,
          agent,
          model,
          reasoningEffort,
          branch,
          priority,
          createdBy,
          createdAt,
          updatedAt,
          updatedBy,
          dependsOn,
          repos,
          null);
    }

    /** This row with {@code roomId} replaced — the create-time attachment to its room. */
    public SpecRow withRoomId(String roomId) {
      return new SpecRow(
          id,
          project,
          title,
          status,
          assignee,
          agent,
          model,
          reasoningEffort,
          branch,
          priority,
          createdBy,
          createdAt,
          updatedAt,
          updatedBy,
          dependsOn,
          repos,
          roomId);
    }

    /** The room this spec lives in — its own id for every spec born before rooms decoupled. */
    public String roomIdOrIdentity() {
      return roomId == null || roomId.isBlank() ? id : roomId;
    }

    /**
     * Whether this spec is assigned to the box whose handle is {@code localHandle}. Unlike run
     * ownership, a blank handle owns nothing: an FDE-bound box serves only its assignee's specs,
     * and an unbound box drives no spec's room lane at all.
     */
    public boolean assignedTo(String localHandle) {
      return Strings.isNotBlank(localHandle) && localHandle.equals(assignee);
    }

    /** Projects this stored row onto the storage-agnostic {@link Spec} value type. */
    /** The row as the canonical aggregate — every persisted field, nothing dropped. */
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
          branch,
          priority,
          createdBy,
          createdAt,
          updatedAt,
          updatedBy,
          roomIdOrIdentity());
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
      int awaitingMerge,
      int done,
      int cancelled,
      int archived,
      String nextReadyId) {}

  /**
   * Creates {@code spec}, created and last updated by the bound {@link Actor} whatever the row
   * names.
   */
  public void create(SpecRow spec) {
    var now = DateTimeUtils.now().toString();
    db.transaction(
        () -> {
          var author = author();
          db.execute(
              """
              INSERT INTO specs (id, project, title, status, assignee, agent, model,
                  reasoning_effort, branch, priority, created_by, created_at, updated_at,
                  updated_by, room_id)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
              author,
              now,
              now,
              author,
              spec.roomIdOrIdentity());
          insertDependencies(spec.id(), spec.dependsOn());
          insertRepos(spec.id(), spec.repos());
          db.execute(
              "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, '', '', ?)",
              spec.id(),
              now);
          setHashes(spec.id(), "", "");
          recordRevision(spec.id(), "local", false);
        });
  }

  /** Every spec attached to {@code roomId}, oldest first — the room's work-items. */
  public List<SpecRow> listByRoom(String roomId) {
    return db.query(
        """
        SELECT id, project, title, status, assignee, agent, model, reasoning_effort,
            branch, priority, created_by, created_at, updated_at, updated_by, room_id
        FROM specs WHERE room_id = ? ORDER BY created_at, id""",
        this::mapSpec,
        roomId);
  }

  public Optional<SpecRow> findById(String id) {
    return db.queryOne(
            """
            SELECT id, project, title, status, assignee, agent, model, reasoning_effort,
                branch, priority, created_by, created_at, updated_at, updated_by, room_id
            FROM specs WHERE id = ?""",
            this::mapSpec,
            id)
        .map(this::hydrate);
  }

  public List<SpecRow> list(SpecFilter filter) {
    var sql = new StringBuilder("SELECT DISTINCT s.id, s.project, s.title, s.status, s.assignee,");
    sql.append(
        " s.agent, s.model, s.reasoning_effort, s.branch, s.priority, s.created_by, s.created_at,"
            + " s.updated_at, s.updated_by, s.room_id FROM specs s");
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

  /**
   * Moves every spec from project {@code old} to {@code renamed} when a project is renamed locally.
   * A spec's change-log identity is its own id, so only the {@code project} column moves, and each
   * spec is journaled so the new project reaches every peer. Idempotent.
   */
  public void reproject(String old, String renamed) {
    if (old.equals(renamed)) {
      return;
    }
    db.transaction(
        () -> {
          var ids = db.query("SELECT id FROM specs WHERE project = ?", row -> row.text(0), old);
          db.execute(
              "UPDATE specs SET project = ?, updated_by = ? WHERE project = ?",
              renamed,
              author(),
              old);
          ids.forEach(id -> recordRevision(id, "local", false));
        });
  }

  /** Rewrites {@code spec}'s fields, as last updated by the bound {@link Actor}. */
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
              author(),
              spec.id());
          db.execute("DELETE FROM spec_dependencies WHERE spec_id = ?", spec.id());
          db.execute("DELETE FROM spec_repos WHERE spec_id = ?", spec.id());
          insertDependencies(spec.id(), spec.dependsOn());
          insertRepos(spec.id(), spec.repos());
          recordRevision(spec.id(), "local", false);
        });
  }

  /**
   * Composes writes across stores sharing this database into one atomic transaction — nested store
   * transactions join the outermost scope (see {@link Sqlite#transaction}), the same seam {@code
   * StoreReplica.atomically} rides. Lets a caller pair a room write with its spec mirror so a crash
   * can never persist one half. Takes the write lock up front ({@link Sqlite#transaction}) because
   * every composed scope is a check-then-write: under WAL a deferred begin would let another
   * process commit between the check and the write.
   */
  public <T> T atomically(java.util.function.Supplier<T> work) {
    return db.transaction(work);
  }

  public void updateStatus(String id, SpecStatus status) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE specs SET status = ?, updated_at = ?, updated_by = ? WHERE id = ?",
              status.wire(),
              DateTimeUtils.now().toString(),
              author(),
              id);
          recordRevision(id, "local", false);
        });
  }

  /**
   * Status transition that commits only if the spec still holds {@code expected}, returning whether
   * it did. The check and the write are one statement under the write lock ({@code BEGIN
   * IMMEDIATE}), so a lifecycle writer racing another transition — above all an operator's {@code
   * cancelled}, which must stay terminal — fails cleanly on its stale read instead of overwriting
   * the transition that won. Every automated status writer must use this; the unconditional {@link
   * #updateStatus} is for deliberate operator edits.
   */
  public boolean compareAndSetStatus(String id, SpecStatus expected, SpecStatus status) {
    return db.transaction(
        () -> {
          db.execute(
              """
              UPDATE specs SET status = ?, updated_at = ?, updated_by = ?
              WHERE id = ? AND status = ?""",
              status.wire(),
              DateTimeUtils.now().toString(),
              author(),
              id,
              expected.wire());
          if (db.changes() == 0) {
            return false;
          }
          recordRevision(id, "local", false);
          return true;
        });
  }

  /**
   * Status transition that also persists the spec's resolved target repos and its dispatch branch
   * in the same transaction. Dispatch resolves repo overrides and computes the branch name at
   * launch time; recording them here keeps later store reads (the review pipeline builds its prompt
   * from the stored repos and branch) aligned with the checkouts the agent actually worked in —
   * without this the review prompt fell back to "branch main" and reviewed the wrong scope. A blank
   * {@code branch} (auto-branching disabled) leaves any previously stored value untouched.
   */
  public void updateReposAndStatus(
      String id, List<String> repos, SpecStatus status, String branch) {
    db.transaction(
        () -> {
          db.execute(
              """
              UPDATE specs SET status = ?, branch = COALESCE(?, branch), updated_at = ?,
                  updated_by = ?
              WHERE id = ?""",
              status.wire(),
              Strings.isBlank(branch) ? null : branch,
              DateTimeUtils.now().toString(),
              author(),
              id);
          db.execute("DELETE FROM spec_repos WHERE spec_id = ?", id);
          insertRepos(id, repos);
          recordRevision(id, "local", false);
        });
  }

  public void delete(String id) {
    db.transaction(
        () -> {
          stampAuthor(id);
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
          setHashes(specId, body, plan);
          stampAuthor(specId);
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
   * is itself reversible. Re-creates the spec if it had been deleted, from any revision history
   * still keeps — its tombstone included. A pruned spec has no history left to restore from, and
   * says so; a revision compaction dropped says that too.
   */
  public void restore(String id, String rev) {
    var entry =
        changeLog
            .at(ENTITY, id, rev)
            .filter(found -> found.kind() != ChangeLog.Kind.ERASURE)
            .orElseThrow(() -> new IllegalArgumentException(unrestorable(id, rev)));
    var snapshot = YamlUtil.parseMap(entry.snapshot());
    db.transaction(
        () -> {
          applySnapshot(id, snapshot);
          stampAuthor(id);
          recordRevision(id, "restore", false);
        });
  }

  String recordRevision(String id, String origin, boolean deleted) {
    return journal.recordRevision(id, origin, deleted);
  }

  private void stampAuthor(String id) {
    db.execute("UPDATE specs SET updated_by = ? WHERE id = ?", author(), id);
  }

  private static String author() {
    return Actor.current().handle();
  }

  /** Why {@code rev} of spec {@code id} cannot be restored, naming what can be done instead. */
  public String unrestorable(String id, String rev) {
    var head = changeLog.head(ENTITY, id).orElse(null);
    if (head == null) {
      return "No revision '" + rev + "' recorded for spec '" + id + "'.";
    }
    if (head.kind() == ChangeLog.Kind.ERASURE) {
      return pruned(id);
    }
    return "Revision '"
        + rev
        + "' of spec '"
        + id
        + "' is not in its retained history: history keeps the newest "
        + ChangeLog.HISTORY_REVISIONS
        + " revisions of each spec and every deletion. Pick one of those from 'sail spec history "
        + id
        + "'.";
  }

  /** Why pruned spec {@code id} is gone for good: who pruned it and when. */
  public String pruned(String id) {
    var erasure = changeLog.erasure(ENTITY, id).orElseThrow();
    return "Spec '"
        + id
        + "' was pruned"
        + (erasure.actor() == null ? "" : " by " + erasure.actor())
        + " at "
        + erasure.recordedAt()
        + ": it is erased everywhere with its history, so nothing is left to restore.";
  }

  /**
   * A spec as this box last knew it: its live row, or the state its tombstone kept. What deciding
   * who owns it, whether it may be pruned and which room it lives in reads, the same way for a
   * deleted spec as for a live one. The tombstone of a spec this box only heard was deleted keeps
   * nothing, so every field but the id may be null.
   */
  public record LastKnown(
      String id,
      SpecStatus status,
      String assignee,
      String createdBy,
      String roomId,
      boolean live) {

    /** Its owner: the assignee, or the creator while unassigned; blank when neither is known. */
    public String owner() {
      return ownerOf(assignee, createdBy);
    }

    public String roomIdOrIdentity() {
      return Strings.isBlank(roomId) ? id : roomId;
    }

    /**
     * Archive keeps, delete restores, prune erases: a spec is pruned from archived, cancelled or
     * deleted, never from work still on the board.
     */
    public boolean prunable() {
      return !live || status == SpecStatus.ARCHIVED || status == SpecStatus.CANCELLED;
    }
  }

  /** A tombstone of a spec this box never held as a row keeps no state, so no status. */
  private static SpecStatus statusOf(String wire) {
    return wire == null ? null : SpecStatus.fromWire(wire);
  }

  /** The one rule for who owns a spec: its assignee, or its creator while it is unassigned. */
  public static String ownerOf(String assignee, String createdBy) {
    return Strings.isNotBlank(assignee) ? assignee : Objects.toString(createdBy, "");
  }

  /** Spec {@code id} as this box last knew it, live or deleted; empty when it holds neither. */
  public Optional<LastKnown> lastKnown(String id) {
    var live =
        db.queryOne(
            "SELECT status, assignee, created_by, room_id FROM specs WHERE id = ?",
            row ->
                new LastKnown(
                    id,
                    SpecStatus.fromWire(row.text(0)),
                    row.text(1),
                    row.text(2),
                    row.text(3),
                    true),
            id);
    if (live.isPresent()) {
      return live;
    }
    return changeLog
        .head(ENTITY, id)
        .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
        .map(head -> YamlUtil.parseMap(head.snapshot()))
        .map(
            last ->
                new LastKnown(
                    id,
                    statusOf(Snapshots.text(last, "status")),
                    Snapshots.text(last, "assignee"),
                    Snapshots.text(last, "created_by"),
                    Snapshots.text(last, "room_id"),
                    false));
  }

  /**
   * Up to {@code limit} specs a prune policy selects: in one of {@code statuses} since before
   * {@code cutoff} (when this box saw the spec enter it), in {@code project} when one is named, and
   * with no run still going in it or its room — a spec still at work is never swept away.
   */
  public List<String> prunableSince(
      List<SpecStatus> statuses, String project, Instant cutoff, int limit) {
    var unfinished = RunStore.unfinishedStatuses();
    var parameters = new ArrayList<Object>(statuses.stream().map(SpecStatus::wire).toList());
    parameters.add(project);
    parameters.add(project);
    parameters.add(cutoff.toString());
    parameters.addAll(unfinished);
    parameters.add(limit);
    return db.query(
        "SELECT id FROM specs s WHERE status IN ("
            + placeholders(statuses.size())
            + ") AND (? IS NULL OR project = ?)"
            + " AND julianday(CASE status WHEN 'archived' THEN archived_at"
            + " ELSE cancelled_at END) < julianday(?)"
            + " AND NOT EXISTS (SELECT 1 FROM runs r WHERE (r.spec_id = s.id OR r.room_id = s.id)"
            + " AND r.status IN ("
            + placeholders(unfinished.size())
            + ")) ORDER BY id LIMIT ?",
        row -> row.text(0),
        parameters.toArray());
  }

  private static String placeholders(int count) {
    return String.join(", ", Collections.nCopies(count, "?"));
  }

  /**
   * For each of {@code roomIds}, the specs, live or deleted and restorable, that converse in it:
   * the one that minted it and every one born into it. One read of the deletions, however many
   * rooms.
   */
  public Map<String, Set<String>> inRooms(Collection<String> roomIds) {
    var rooms = List.copyOf(new LinkedHashSet<>(roomIds));
    var conversing = new LinkedHashMap<String, Set<String>>();
    rooms.forEach(room -> conversing.put(room, new LinkedHashSet<>()));
    for (var from = 0; from < rooms.size(); from += 500) {
      var batch = rooms.subList(from, Math.min(rooms.size(), from + 500));
      var marks = placeholders(batch.size());
      var parameters = new ArrayList<Object>(batch);
      parameters.addAll(batch);
      for (var row :
          db.query(
              "SELECT COALESCE(NULLIF(room_id, ''), id), id FROM specs WHERE room_id IN ("
                  + marks
                  + ") OR (id IN ("
                  + marks
                  + ") AND (room_id IS NULL OR room_id = ''))",
              r -> Map.entry(r.text(0), r.text(1)),
              parameters.toArray())) {
        conversing.get(row.getKey()).add(row.getValue());
      }
    }
    for (var deleted : changeLog.tombstonedBy(ENTITY, "room_id").entrySet()) {
      for (var id : deleted.getValue()) {
        var room = deleted.getKey().isBlank() ? id : deleted.getKey();
        if (conversing.containsKey(room)) {
          conversing.get(room).add(id);
        }
      }
    }
    return conversing;
  }

  /** The latest entry of spec {@code id} — a revision, its tombstone, or its erasure — if any. */
  public Optional<ChangeLog.Entry> head(String id) {
    return changeLog.head(ENTITY, id);
  }

  private Map<String, Object> snapshotMap(SpecRow spec) {
    var hashes =
        db.queryOne(
                "SELECT body_hash, plan_hash FROM specs WHERE id = ?",
                row -> {
                  requireMigrated(row.text(0), spec.id());
                  requireMigrated(row.text(1), spec.id());
                  return List.of(row.text(0), row.text(1));
                },
                spec.id())
            .orElseThrow();
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
    map.put("room_id", spec.roomIdOrIdentity());
    map.put("body_hash", hashes.get(0));
    map.put("plan_hash", hashes.get(1));
    return map;
  }

  private void applySnapshot(String id, Map<String, Object> snapshot) {
    var bodyHash = Snapshots.text(snapshot, "body_hash");
    var planHash = Snapshots.text(snapshot, "plan_hash");
    requireMigrated(bodyHash, id);
    requireMigrated(planHash, id);
    blobs.requireHeld(bodyHash);
    blobs.requireHeld(planHash);
    var body = blobs.text(bodyHash);
    var plan = blobs.text(planHash);
    var spec = specFromSnapshot(snapshot);
    var now = DateTimeUtils.now().toString();
    if (findById(id).isPresent()) {
      db.execute(
          """
          UPDATE specs SET project = ?, title = ?, status = ?, assignee = ?, agent = ?, model = ?,
              reasoning_effort = ?, branch = ?, priority = ?, updated_at = ?, updated_by = ?,
              room_id = ?
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
          spec.roomIdOrIdentity(),
          id);
      db.execute("DELETE FROM spec_dependencies WHERE spec_id = ?", id);
      db.execute("DELETE FROM spec_repos WHERE spec_id = ?", id);
    } else {
      db.execute(
          """
          INSERT INTO specs (id, project, title, status, assignee, agent, model, reasoning_effort,
              branch, priority, created_by, created_at, updated_at, updated_by, room_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
          spec.updatedBy(),
          spec.roomIdOrIdentity());
    }
    insertDependencies(id, spec.dependsOn());
    insertRepos(id, spec.repos());
    db.execute(
        """
        INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, ?, ?, ?)
        ON CONFLICT(spec_id) DO UPDATE SET body = ?, plan = ?, updated_at = ?""",
        id,
        body,
        plan,
        now,
        body,
        plan,
        now);
    db.execute(
        "UPDATE specs SET body_hash = ?, plan_hash = ? WHERE id = ?", bodyHash, planHash, id);
  }

  private static String roomIdOf(Map<String, Object> s) {
    var roomId = Snapshots.text(s, "room_id");
    return roomId != null ? roomId : Snapshots.text(s, "id");
  }

  @SuppressWarnings("unchecked")
  private static SpecRow specFromSnapshot(Map<String, Object> s) {
    var priority = s.get("priority");
    return new SpecRow(
        Snapshots.text(s, "id"),
        Snapshots.text(s, "project"),
        Snapshots.text(s, "title"),
        SpecStatus.fromWire(Snapshots.text(s, "status")),
        Snapshots.text(s, "assignee"),
        Snapshots.text(s, "agent"),
        Snapshots.text(s, "model"),
        Snapshots.text(s, "reasoning_effort"),
        Snapshots.text(s, "branch"),
        priority instanceof Number n ? n.intValue() : 0,
        Snapshots.text(s, "created_by"),
        Snapshots.text(s, "created_at"),
        Snapshots.text(s, "updated_at"),
        Snapshots.text(s, "updated_by"),
        (List<String>) s.getOrDefault("depends_on", List.of()),
        (List<String>) s.getOrDefault("repos", List.of()),
        roomIdOf(s));
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
          "body_hash",
          "plan_hash",
          "room_id");

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
      m.put(Snapshots.ACTOR, author);
    }
    return m;
  }

  /** Comparable snapshot of the current state, or null if the spec is absent/deleted. */
  @Override
  public String entityType() {
    return ENTITY;
  }

  @Override
  public Set<String> contentFields() {
    return Set.of("body_hash", "plan_hash");
  }

  @Override
  public Set<String> liveContentHashes() {
    var hashes =
        new LinkedHashSet<>(
            db.query(
                "SELECT body_hash FROM specs UNION SELECT plan_hash FROM specs",
                row -> row.text(0)));
    hashes.remove(null);
    return hashes;
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return journal.comparableSnapshot(id);
  }

  /** Comparable snapshot recorded at a given revision (the merge base), or null if not recorded. */
  public Map<String, Object> comparableAtRev(String id, String rev) {
    return journal.comparableAtRev(id, rev);
  }

  public String revOf(String id) {
    return journal.revOf(id);
  }

  /** The latest revision recorded for an entity, including a tombstone; null if never recorded. */
  public String latestRev(String id) {
    return journal.latestRev(id);
  }

  /**
   * The revision this row last synced from main. For a live row it is the {@code base_rev} column;
   * for a locally deleted entity the row is gone, so it is recovered from the {@code _base_rev}
   * embedded in the tombstone — without which a local delete could not be told apart from a
   * delete-vs-edit conflict.
   */
  public String baseRevOf(String id) {
    return journal.baseRevOf(id);
  }

  /** Every entity id this replica knows of, including those only present as a tombstone. */
  public Set<String> syncEntityIds() {
    return journal.entityIds();
  }

  public Set<String> dirtyIds() {
    return journal.dirtyIds();
  }

  /** Attributes a spec solely for the retained versioned 0.14 data migration. */
  public boolean assignMigrationProject(String id, String project) {
    return db.transaction(
        () -> {
          db.execute(
              """
              UPDATE specs SET project = ?, updated_by = ?
              WHERE id = ? AND (project IS NULL OR project = 'unassigned')""",
              project,
              author(),
              id);
          if (db.changes() == 0) {
            return false;
          }
          recordRevision(id, "migration", false);
          return true;
        });
  }

  /**
   * Writes an authoritative state from main at its exact revision (no minting), marking it the new
   * synced ancestor ({@code base_rev = rev}). A null snapshot adopts a deletion. Used by the sync
   * engine; the revision is journaled with origin {@code sync}.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    journal.applyRevision(id, snapshot, rev);
  }

  @Override
  public void eraseRow(String id) {
    journal.eraseRow(id);
  }

  /**
   * Compare-and-set commit as main: mints a new authoritative rev only if {@code expectedRev} still
   * equals the entity's current rev (a brand-new entity expects {@code null}); otherwise returns
   * {@link PushOutcome.Stale} with main's present state, never overwriting a concurrent change. A
   * null snapshot commits a deletion. The check and the write share one transaction, so two nodes
   * pushing the same row can never both win. Used by the sync engine on the main side.
   */
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return journal.commitRevision(id, snapshot, expectedRev);
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
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote) {
    return journal.resolveConflict(id, chosen, remote);
  }

  private static Map<String, Object> withSync(String id, Map<String, Object> snapshot) {
    var full = new LinkedHashMap<>(snapshot);
    full.put("id", id);
    full.put("updated_by", Snapshots.actor(snapshot));
    return full;
  }

  /** The spec's store-specific half of the shared {@link RevisionJournal} sync protocol. */
  private final class SpecSchema implements EntitySchema {

    @Override
    public String entityType() {
      return ENTITY;
    }

    @Override
    public String table() {
      return "specs";
    }

    @Override
    public boolean exists(String id) {
      return findById(id).isPresent();
    }

    @Override
    public Map<String, Object> snapshotMap(String id) {
      return findById(id).map(SpecStore.this::snapshotMap).orElse(null);
    }

    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      applySnapshot(id, withSync(id, snapshot));
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      return SpecStore.comparable(full);
    }

    @Override
    public void deleteRow(String id) {
      db.execute("DELETE FROM specs WHERE id = ?", id);
    }
  }

  public Optional<SpecContent> getContent(String specId) {
    return db.queryOne(
        "SELECT c.body, c.plan, c.updated_at, s.body_hash, s.plan_hash FROM spec_content c JOIN specs s ON s.id = c.spec_id WHERE c.spec_id = ?",
        row -> {
          requireMigrated(row.text(3), specId);
          requireMigrated(row.text(4), specId);
          return new SpecContent(row.text(0), row.text(1), row.text(2));
        },
        specId);
  }

  private void setHashes(String id, String body, String plan) {
    db.execute(
        "UPDATE specs SET body_hash = ?, plan_hash = ? WHERE id = ?",
        blobs.putText(body),
        blobs.putText(plan),
        id);
  }

  private static void requireMigrated(String hash, String id) {
    if (hash == null)
      throw new IllegalStateException(
          "Spec " + id + " content migration is incomplete; run sail migrate");
  }

  public List<SpecRow> readySpecs() {
    return readySpecs(null);
  }

  public List<SpecRow> readySpecs(String projectFilter) {
    return db
        .query(
            """
            SELECT s.id, s.project, s.title, s.status, s.assignee, s.agent, s.model,
                s.reasoning_effort, s.branch, s.priority, s.created_by, s.created_at, s.updated_at,
                s.updated_by, s.room_id
            FROM specs s
            WHERE s.status = 'pending'
            AND (? IS NULL OR s.project = ?)
            AND NOT EXISTS (
                SELECT 1 FROM spec_dependencies d
                JOIN specs dep ON dep.id = d.depends_on
                WHERE d.spec_id = s.id AND dep.status != 'done'
            )
            ORDER BY s.priority DESC, s.created_at ASC""",
            this::mapSpec,
            projectFilter,
            projectFilter)
        .stream()
        .map(this::hydrate)
        .toList();
  }

  public BoardSummary board() {
    return board(null);
  }

  public BoardSummary board(String projectFilter) {
    var counts =
        projectFilter == null
            ? db.query(
                "SELECT status, COUNT(*) FROM specs GROUP BY status",
                row -> new Object[] {row.text(0), row.integer(1)})
            : db.query(
                "SELECT status, COUNT(*) FROM specs WHERE project = ? GROUP BY status",
                row -> new Object[] {row.text(0), row.integer(1)},
                projectFilter);
    var byStatus = new EnumMap<SpecStatus, Integer>(SpecStatus.class);
    for (var status : SpecStatus.values()) {
      byStatus.put(status, 0);
    }
    for (var row : counts) {
      byStatus.put(SpecStatus.fromWire((String) row[0]), (int) (long) row[1]);
    }
    var ready = readySpecs(projectFilter);
    var nextReadyId = ready.isEmpty() ? null : ready.getFirst().id();
    return new BoardSummary(
        byStatus.get(SpecStatus.DRAFT),
        byStatus.get(SpecStatus.PENDING),
        byStatus.get(SpecStatus.IN_PROGRESS),
        byStatus.get(SpecStatus.REVIEW),
        byStatus.get(SpecStatus.AWAITING_MERGE),
        byStatus.get(SpecStatus.DONE),
        byStatus.get(SpecStatus.CANCELLED),
        byStatus.get(SpecStatus.ARCHIVED),
        nextReadyId);
  }

  private SpecRow mapSpec(Sqlite.Row row) {
    return new SpecRow(
        row.text(0),
        row.text(1),
        row.text(2),
        SpecStatus.fromWire(row.text(3)),
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
        List.of(),
        row.text(14));
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
        repos,
        spec.roomId());
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
