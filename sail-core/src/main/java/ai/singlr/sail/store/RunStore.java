/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Run aggregate on SQLite: one dispatch execution of a spec. Records where it ran ({@code node}
 * = the executing box's FDE handle at launch), which spec and branch, the agent process and
 * guardrail watcher pids, its final status/exit code, and the run-scoped log path so a log address
 * names exactly one execution.
 *
 * <p><strong>Single-writer by construction.</strong> Only the executing node ever mutates its own
 * runs; every other box is a reader. Sync is therefore conflict-free for runs — the {@code
 * RunReplica} still delegates to the full revision/CAS machinery, but a true same-field conflict
 * can only arise from a corrupted invariant, never from normal operation. Each mutation journals
 * the run's full post-state into the shared {@link ChangeLog} under entity type {@code run} within
 * one transaction, so a run's lifecycle (start → complete/fail/stop) replicates to main and on to
 * every other box, and any box can answer "which node is running spec X".
 */
public final class RunStore implements ConflictResolver {

  private static final String ENTITY = "run";

  private final Sqlite db;
  private final ChangeLog changeLog;

  public RunStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
  }

  /**
   * {@code unit} is the systemd unit the run was launched as — recorded at launch as part of the
   * run's identity, so every later consumer (stop, probe, reconciler, watcher) addresses the unit
   * the run actually owns instead of re-deriving a name that could drift across releases. Blank on
   * runs launched before units were run-scoped; consumers that cannot know that unit's liveness do
   * nothing rather than guess.
   */
  public record RunRow(
      String id,
      String project,
      String specId,
      String node,
      String role,
      String agent,
      String branch,
      String task,
      Integer pid,
      Integer watcherPid,
      String status,
      Integer exitCode,
      String logPath,
      String unit,
      String startedAt,
      String completedAt) {}

  private static final String COLUMNS =
      "id, project, spec_id, node, role, agent, branch, task, pid, watcher_pid, status,"
          + " exit_code, log_path, unit, started_at, completed_at";

  /**
   * Records a new run in the {@code running} state, journaling a baseline revision so it
   * replicates. The id is minted by the launcher (a UUIDv7) so the run-scoped log directory is
   * addressable before the agent starts; {@code node} is the executing box's FDE handle at launch.
   */
  public String create(
      String id,
      String project,
      String specId,
      String node,
      String role,
      String agent,
      String branch,
      String task,
      Integer pid,
      Integer watcherPid,
      String logPath) {
    return create(
        id, project, specId, node, role, agent, branch, task, pid, watcherPid, logPath, null);
  }

  /** As the other overload, also recording the systemd {@code unit} the run was launched as. */
  public String create(
      String id,
      String project,
      String specId,
      String node,
      String role,
      String agent,
      String branch,
      String task,
      Integer pid,
      Integer watcherPid,
      String logPath,
      String unit) {
    db.transaction(
        () -> {
          db.execute(
              """
              INSERT INTO runs (id, project, spec_id, node, role, agent, branch, task, pid,
                  watcher_pid, status, started_at, log_path, unit)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'running', ?, ?, ?)""",
              id,
              project,
              specId,
              node,
              role,
              agent,
              branch,
              task,
              pid != null ? pid.longValue() : null,
              watcherPid != null ? watcherPid.longValue() : null,
              DateTimeUtils.now().toString(),
              logPath,
              unit);
          recordRevision(id, "local", false);
        });
    return id;
  }

  public Optional<RunRow> findById(String id) {
    return db.queryOne("SELECT " + COLUMNS + " FROM runs WHERE id = ?", this::mapRow, id);
  }

  /**
   * The latest run of {@code project} that executed on this box, or empty. Ownership is by node: a
   * box with a handle owns exactly the runs stamped with it; a box with no handle owns exactly its
   * own blank-node runs and never a run adopted from another box via sync. So "the project's latest
   * run" can never resolve to a foreign run — the guarantee the completion and report paths rely on
   * now that the {@code runs} table also holds synced foreign rows.
   */
  public Optional<RunRow> latestForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND IFNULL(node, '') = ? ORDER BY started_at DESC"
            + " LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  /**
   * The running run of {@code project} that executed on this box, or empty. Node-scoped like {@link
   * #latestForProjectOnNode}.
   */
  public Optional<RunRow> runningForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND status = 'running' AND IFNULL(node, '') = ?"
            + " ORDER BY started_at DESC LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  private static String ownerKey(String localHandle) {
    return Strings.isBlank(localHandle) ? "" : localHandle;
  }

  public List<RunRow> listForProject(String project) {
    return db.query(
        "SELECT " + COLUMNS + " FROM runs WHERE project = ? ORDER BY started_at DESC",
        this::mapRow,
        project);
  }

  public List<RunRow> listForSpec(String specId) {
    return db.query(
        "SELECT " + COLUMNS + " FROM runs WHERE spec_id = ? ORDER BY started_at DESC",
        this::mapRow,
        specId);
  }

  /** Runs for a spec, optionally scoped to a project — the read behind {@code GET /v1/runs}. */
  public List<RunRow> list(String project, String specId) {
    if (project != null && specId != null) {
      return db.query(
          "SELECT "
              + COLUMNS
              + " FROM runs WHERE project = ? AND spec_id = ? ORDER BY started_at DESC",
          this::mapRow,
          project,
          specId);
    }
    if (specId != null) {
      return listForSpec(specId);
    }
    if (project != null) {
      return listForProject(project);
    }
    return db.query("SELECT " + COLUMNS + " FROM runs ORDER BY started_at DESC", this::mapRow);
  }

  /** Marks a run finished with its final status and the agent process's exit code (nullable). */
  public void complete(String id, String status, Integer exitCode) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE runs SET status = ?, completed_at = ?, exit_code = ? WHERE id = ?",
              status,
              DateTimeUtils.now().toString(),
              exitCode != null ? exitCode.longValue() : null,
              id);
          recordRevision(id, "local", false);
        });
  }

  /**
   * Stamps the agent and watcher pids on a run once launch has produced them. The run row is
   * created before launch (so terminal hook events can find it), then updated here with the pids
   * the launch resolved. Journals a revision so the pids replicate.
   */
  public void updateProcess(String id, Integer pid, Integer watcherPid) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE runs SET pid = ?, watcher_pid = ? WHERE id = ?",
              pid != null ? pid.longValue() : null,
              watcherPid != null ? watcherPid.longValue() : null,
              id);
          recordRevision(id, "local", false);
        });
  }

  /**
   * Records the exit code on an already-finished run. Used when the authoritative stop (which knows
   * the process exit code) arrives after a turn-end stop has already completed the run without one.
   */
  public void recordExitCode(String id, Integer exitCode) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE runs SET exit_code = ? WHERE id = ?",
              exitCode != null ? exitCode.longValue() : null,
              id);
          recordRevision(id, "local", false);
        });
  }

  /**
   * Stamps {@code node} on every run that has none yet — the backfill that carries pre-Run rows
   * (all of which executed on this box) forward with the local handle so they stop reading as
   * foreign. Idempotent; returns how many were stamped. Journals each so the stamped node
   * replicates.
   */
  public int backfillNode(String handle) {
    if (Strings.isBlank(handle)) {
      return 0;
    }
    var ids = db.query("SELECT id FROM runs WHERE node IS NULL OR node = ''", row -> row.text(0));
    for (var id : ids) {
      db.transaction(
          () -> {
            db.execute("UPDATE runs SET node = ? WHERE id = ?", handle, id);
            recordRevision(id, "local", false);
          });
    }
    return ids.size();
  }

  /**
   * Journals a baseline revision for every run that has none yet, so a run written before this
   * store journaled its mutations becomes visible to sync. Idempotent; returns how many were
   * backfilled.
   */
  public int backfillRevisions() {
    var journaled = syncEntityIds();
    var pending =
        db.query("SELECT id FROM runs", row -> row.text(0)).stream()
            .filter(id -> !journaled.contains(id))
            .toList();
    for (var id : pending) {
      db.transaction(() -> recordRevision(id, "local", false));
    }
    return pending.size();
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return findById(id).map(RunStore::comparable).orElse(null);
  }

  public Map<String, Object> comparableAtRev(String id, String rev) {
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .at(ENTITY, id, rev)
        .map(e -> comparable(YamlUtil.parseMap(e.snapshot())))
        .orElse(null);
  }

  public String latestRev(String id) {
    var history = changeLog.history(ENTITY, id);
    return history.isEmpty() ? null : history.getLast().rev();
  }

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

  public Set<String> syncEntityIds() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT DISTINCT entity_id FROM change_log WHERE entity_type = ?",
            row -> row.text(0),
            ENTITY));
  }

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    db.transaction(
        () -> {
          if (snapshot == null) {
            adoptDeletion(id, rev);
          } else {
            writeRow(rowFrom(id, snapshot));
            recordRevision(id, rev, "sync", false, true);
          }
        });
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return db.immediateTransaction(
        () -> {
          if (!Objects.equals(latestRev(id), expectedRev)) {
            return new PushOutcome.Stale(latestRev(id), comparableSnapshot(id));
          }
          if (snapshot == null) {
            if (findById(id).isEmpty()) {
              return new PushOutcome.Accepted(latestRev(id));
            }
            var rev = recordRevision(id, null, "sync", true, false);
            db.execute("DELETE FROM runs WHERE id = ?", id);
            return new PushOutcome.Accepted(rev);
          }
          writeRow(rowFrom(id, snapshot));
          return new PushOutcome.Accepted(recordRevision(id, null, "sync", false, false));
        });
  }

  /**
   * Resolves an open conflict locally, mirroring {@link SpecStore#resolveConflict}. Runs are
   * single-writer so this is exercised only by the shared machinery's contract, never by normal
   * operation: no two boxes ever edit the same run.
   */
  @Override
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
      return adoptBaseDeletion(id);
    }
    writeRow(rowFrom(id, remote));
    return recordRevision(id, null, "sync", false, true);
  }

  private String adoptBaseDeletion(String id) {
    if (findById(id).isEmpty()) {
      var rev = Revisions.next(currentRev(id), "{}");
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return rev;
    }
    var rev = recordRevision(id, null, "sync", true, false);
    db.execute("DELETE FROM runs WHERE id = ?", id);
    return rev;
  }

  private String writeChosen(String id, Map<String, Object> chosen) {
    if (chosen == null) {
      if (findById(id).isEmpty()) {
        return latestRev(id);
      }
      var rev = recordRevision(id, null, "resolve", true, false);
      db.execute("DELETE FROM runs WHERE id = ?", id);
      return rev;
    }
    writeRow(rowFrom(id, chosen));
    return recordRevision(id, null, "resolve", false, false);
  }

  private void adoptDeletion(String id, String rev) {
    if (findById(id).isEmpty()) {
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return;
    }
    recordRevision(id, rev, "sync", true, false);
    db.execute("DELETE FROM runs WHERE id = ?", id);
  }

  private String recordRevision(String id, String origin, boolean deleted) {
    return recordRevision(id, null, origin, deleted, false);
  }

  private String recordRevision(
      String id, String explicitRev, String origin, boolean deleted, boolean setBaseRev) {
    var run = findById(id).orElse(null);
    if (run == null) {
      return null;
    }
    var map = snapshotMap(run);
    if (deleted) {
      map.put("_base_rev", rawBaseRev(id));
    }
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(currentRev(id), snapshot);
    if (!deleted) {
      if (setBaseRev) {
        db.execute("UPDATE runs SET rev = ?, base_rev = ? WHERE id = ?", rev, rev, id);
      } else {
        db.execute("UPDATE runs SET rev = ? WHERE id = ?", rev, id);
      }
    }
    changeLog.append(ENTITY, id, rev, run.node(), origin, deleted, snapshot);
    return rev;
  }

  private void writeRow(RunRow row) {
    db.execute(
        """
        INSERT INTO runs (id, project, spec_id, node, role, agent, branch, task, pid, watcher_pid,
            status, exit_code, log_path, unit, started_at, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET project = excluded.project, spec_id = excluded.spec_id,
            node = excluded.node, role = excluded.role, agent = excluded.agent,
            branch = excluded.branch, task = excluded.task, pid = excluded.pid,
            watcher_pid = excluded.watcher_pid, status = excluded.status,
            exit_code = excluded.exit_code, log_path = excluded.log_path, unit = excluded.unit,
            started_at = excluded.started_at, completed_at = excluded.completed_at""",
        row.id(),
        row.project(),
        row.specId(),
        row.node(),
        Objects.requireNonNullElse(row.role(), "build"),
        row.agent(),
        row.branch(),
        row.task(),
        row.pid() != null ? row.pid().longValue() : null,
        row.watcherPid() != null ? row.watcherPid().longValue() : null,
        Objects.requireNonNullElse(row.status(), "running"),
        row.exitCode() != null ? row.exitCode().longValue() : null,
        row.logPath(),
        row.unit(),
        Objects.requireNonNullElse(row.startedAt(), DateTimeUtils.now().toString()),
        row.completedAt());
  }

  private static Map<String, Object> snapshotMap(RunRow run) {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", run.id());
    map.put("project", run.project());
    map.put("spec_id", run.specId());
    map.put("node", run.node());
    map.put("role", run.role());
    map.put("agent", run.agent());
    map.put("branch", run.branch());
    map.put("task", run.task());
    map.put("pid", run.pid());
    map.put("watcher_pid", run.watcherPid());
    map.put("status", run.status());
    map.put("exit_code", run.exitCode());
    map.put("log_path", run.logPath());
    map.put("unit", run.unit());
    map.put("started_at", run.startedAt());
    map.put("completed_at", run.completedAt());
    return map;
  }

  private static final Set<String> SURROGATE_FIELDS = Set.of("id");

  /**
   * The subset of a snapshot that carries the run's meaning — everything except the surrogate id,
   * which every replica keys on independently. Runs have no per-replica volatile metadata (no
   * {@code updated_at}), so every remaining field participates.
   */
  private static Map<String, Object> comparable(Map<String, Object> full) {
    if (full == null) {
      return null;
    }
    var m = new LinkedHashMap<String, Object>();
    for (var field : full.keySet()) {
      if (!SURROGATE_FIELDS.contains(field) && !field.startsWith("_")) {
        m.put(field, full.get(field));
      }
    }
    return m;
  }

  private static Map<String, Object> comparable(RunRow run) {
    return comparable(snapshotMap(run));
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

  private static RunRow rowFrom(String id, Map<String, Object> snapshot) {
    return new RunRow(
        Ids.requireUuid(id),
        text(snapshot, "project"),
        text(snapshot, "spec_id"),
        text(snapshot, "node"),
        text(snapshot, "role"),
        text(snapshot, "agent"),
        text(snapshot, "branch"),
        text(snapshot, "task"),
        integer(snapshot, "pid"),
        integer(snapshot, "watcher_pid"),
        text(snapshot, "status"),
        integer(snapshot, "exit_code"),
        text(snapshot, "log_path"),
        text(snapshot, "unit"),
        text(snapshot, "started_at"),
        text(snapshot, "completed_at"));
  }

  private static String text(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static Integer integer(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.intValue() : null;
  }

  private String rawBaseRev(String id) {
    var value =
        db.queryOne("SELECT COALESCE(base_rev, '') FROM runs WHERE id = ?", row -> row.text(0), id)
            .orElse("");
    return value.isBlank() ? null : value;
  }

  private String currentRev(String id) {
    return db.queryOne("SELECT COALESCE(rev, '') FROM runs WHERE id = ?", row -> row.text(0), id)
        .orElse("");
  }

  private RunRow mapRow(Sqlite.Row row) {
    return new RunRow(
        row.text(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        row.isNull(8) ? null : (int) row.integer(8),
        row.isNull(9) ? null : (int) row.integer(9),
        row.text(10),
        row.isNull(11) ? null : (int) row.integer(11),
        row.text(12),
        row.text(13),
        row.text(14),
        row.text(15));
  }
}
