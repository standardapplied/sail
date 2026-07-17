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
   *
   * <p>{@code repos} is the repo set the dispatch reserved at launch — the run's own claim, not a
   * lookup through its spec, so the overlap gate reads a value that existed before the spec was
   * even claimed. Empty means the run works the whole container; a run recorded before repos were
   * persisted also reads as empty, which the gate treats identically (the safe reading).
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
      String completedAt,
      List<String> repos) {

    /** Whether this row is a build attempt; a null role predates roles and always meant build. */
    public boolean buildRole() {
      return "build".equals(Objects.requireNonNullElse(role, "build"));
    }
  }

  private static final String COLUMNS =
      "id, project, spec_id, node, role, agent, branch, task, pid, watcher_pid, status,"
          + " exit_code, log_path, unit, started_at, completed_at, repos";

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

  /**
   * Records a review negotiation under the review UUID that owns its prompt, session, and log
   * files. Reviewer and fix invocations deliberately share that identity, so one run row remains
   * addressable as {@code ~/.sail/runs/<reviewId>/review.log} throughout the negotiation.
   */
  public String createReview(
      String reviewId,
      String project,
      String specId,
      String node,
      String agent,
      String branch,
      String task,
      String logPath) {
    return create(
        reviewId, project, specId, node, "review", agent, branch, task, null, null, logPath, "");
  }

  /**
   * Atomically reserves a dispatch: within one {@code BEGIN IMMEDIATE} transaction, checks every
   * running local run of the project for a repo overlap or a same-spec run and inserts the new
   * {@code running} run — with its reserved repos persisted — only when none conflicts. Taking the
   * write lock up front serializes concurrent dispatches, including a CLI dispatch in another
   * process against the same database file, so the second reservation always observes the first's
   * row; a check-then-insert split across transactions could admit both into the same repo. Returns
   * the blocking conflict, or empty when the run was reserved. A run mid-stop ({@code stopping})
   * still occupies its repos — its agent is not verified dead until the claim is finalized — so it
   * conflicts exactly like a running one. Any database failure propagates — a dispatch must never
   * launch without the row every later overlap check depends on.
   */
  public Optional<DispatchGate.Conflict> reserveDispatch(
      String id,
      String project,
      String specId,
      String node,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit) {
    var reserved = Objects.requireNonNullElse(repos, List.<String>of());
    return db.immediateTransaction(
        () -> {
          var running =
              db.query(
                  "SELECT "
                      + COLUMNS
                      + " FROM runs WHERE project = ? AND status IN ('running', 'stopping')"
                      + " AND IFNULL(node, '') = ?",
                  this::mapRow,
                  project,
                  ownerKey(node));
          var conflict =
              DispatchGate.decide(
                  specId,
                  reserved,
                  running.stream()
                      .map(run -> new DispatchGate.RunningRun(run.id(), run.specId(), run.repos()))
                      .toList());
          if (conflict.isPresent()) {
            return conflict;
          }
          db.execute(
              """
              INSERT INTO runs (id, project, spec_id, node, role, agent, branch, task, status,
                  started_at, log_path, unit, repos)
              VALUES (?, ?, ?, ?, 'build', ?, ?, ?, 'running', ?, ?, ?, ?)""",
              id,
              project,
              specId,
              node,
              agent,
              branch,
              task,
              DateTimeUtils.now().toString(),
              logPath,
              unit,
              YamlUtil.dumpJson(reserved));
          recordRevision(id, "local", false);
          return Optional.empty();
        });
  }

  public Optional<RunRow> findById(String id) {
    return db.queryOne("SELECT " + COLUMNS + " FROM runs WHERE id = ?", this::mapRow, id);
  }

  /**
   * The latest build run of {@code project} that executed on this box, or empty. Review runs remain
   * in the aggregate but do not replace the build session used by agent status, log, and report
   * commands. Ownership is by node: a box with a handle owns exactly the runs stamped with it; a
   * box with no handle owns exactly its own blank-node runs and never a run adopted from another
   * box via sync.
   */
  public Optional<RunRow> latestForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND IFNULL(node, '') = ?"
            + " AND IFNULL(role, 'build') = 'build' ORDER BY started_at DESC"
            + " LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  /**
   * The active build run of {@code project} that executed on this box, or empty. {@code stopping}
   * counts as active: an interrupted stop's claim must stay addressable so a project-targeted stop
   * retry resumes it instead of falling through to the ad-hoc identity. Node-scoped like {@link
   * #latestForProjectOnNode}.
   */
  public Optional<RunRow> runningForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND status IN ('running', 'stopping')"
            + " AND IFNULL(node, '') = ? AND IFNULL(role, 'build') = 'build'"
            + " ORDER BY started_at DESC LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  /**
   * Every build run holding an unfinished stop claim ({@code stopping}) — a stop that recorded its
   * terminal intent but was interrupted before the halt was verified. The reconciler's
   * interrupted-stop pass finalizes these once their unit is gone.
   */
  public List<RunRow> stopping() {
    return db.query(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE status = 'stopping' AND IFNULL(role, 'build') = 'build'",
        this::mapRow);
  }

  /**
   * Every build run still in the {@code running} state, across all projects and nodes — the
   * build-session reaper's full input. Review executions are foreground work owned and completed by
   * the review controller, so the systemd reaper must not probe them as build units.
   */
  public List<RunRow> running() {
    return db.query(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE status = 'running' AND IFNULL(role, 'build') = 'build'",
        this::mapRow);
  }

  /** Marks local review executions orphaned by a server restart failed. */
  public int failRunningReviewsOnNode(String localHandle) {
    var ids =
        db.query(
            "SELECT id FROM runs WHERE status = 'running' AND role = 'review'"
                + " AND IFNULL(node, '') = ?",
            row -> row.text(0),
            ownerKey(localHandle));
    ids.forEach(id -> complete(id, "failed", null));
    return ids.size();
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
        "SELECT " + COLUMNS + " FROM runs WHERE spec_id = ? ORDER BY started_at DESC, id DESC",
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

  /**
   * As {@link #complete(String, String, Integer)}, also running {@code alongside} inside the same
   * immediate transaction — the seam for a caller that must drive two aggregates terminal as one
   * intent (a stop cancelling a spec while releasing its run). Any failure rolls back both writes,
   * so a partial terminal state is never exposed.
   */
  public void complete(String id, String status, Integer exitCode, Runnable alongside) {
    db.immediateTransaction(
        () -> {
          alongside.run();
          complete(id, status, exitCode);
          return null;
        });
  }

  /**
   * Status transition that commits only if the run still holds {@code expected}, running {@code
   * alongside} inside the same immediate transaction once the transition has won — the seam a stop
   * claim uses to move its spec terminal in the very same commit. Returns whether the transition
   * happened; a false return wrote nothing and never ran {@code alongside}, and an {@code
   * alongside} that throws rolls the transition back. A terminal target status stamps {@code
   * completed_at}; a non-terminal one clears it.
   */
  public boolean transition(String id, String expected, String status, Runnable alongside) {
    return transition(id, expected, status, null, alongside);
  }

  public boolean transition(String id, String expected, String status) {
    return transition(id, expected, status, null, () -> {});
  }

  /**
   * As {@link #transition(String, String, String, Runnable)}, also stamping {@code exitCode} (when
   * non-null) in the same UPDATE, so a finisher that knows the process outcome commits the terminal
   * status and its exit code as one atomic, single-revision write — a crash or a sync push can
   * never observe the terminal status without its exit code.
   */
  public boolean transition(String id, String expected, String status, Integer exitCode) {
    return transition(id, expected, status, exitCode, () -> {});
  }

  private boolean transition(
      String id, String expected, String status, Integer exitCode, Runnable alongside) {
    return db.immediateTransaction(
        () -> {
          db.execute(
              "UPDATE runs SET status = ?, completed_at = ?, exit_code = COALESCE(?, exit_code)"
                  + " WHERE id = ? AND status = ?",
              status,
              TERMINAL_STATUSES.contains(status) ? DateTimeUtils.now().toString() : null,
              exitCode != null ? exitCode.longValue() : null,
              id,
              expected);
          if (db.changes() == 0) {
            return false;
          }
          alongside.run();
          recordRevision(id, "local", false);
          return true;
        });
  }

  /**
   * Runs {@code work} inside one {@code BEGIN IMMEDIATE} transaction, only if {@code id} is still
   * {@code specId}'s latest <em>build</em> attempt at commit time. Review-lane rows are not
   * attempts — the pipeline mints them for the same spec while it negotiates review, and counting
   * them would make every reviewed spec's build run permanently "stale". Taking the write lock
   * before the check serializes it against {@link #reserveDispatch}, so a restart that reserves a
   * newer attempt either lands before this (the check fails, nothing runs) or waits until after
   * (the newer row sees whatever {@code work} committed) — a check-then-write split across
   * transactions could let a stop aimed at an old run cancel the spec out from under the newer
   * attempt. Ties on {@code started_at} break on the UUIDv7 id, which orders by mint time. Returns
   * whether {@code work} ran; {@code work} throwing rolls the whole transaction back.
   */
  public boolean runIfLatestAttempt(String id, String specId, Runnable work) {
    return db.immediateTransaction(
        () -> {
          var latest =
              db.queryOne(
                  "SELECT id FROM runs WHERE spec_id = ? AND IFNULL(role, 'build') = 'build'"
                      + " ORDER BY started_at DESC, id DESC LIMIT 1",
                  row -> row.text(0),
                  specId);
          if (latest.isEmpty() || !latest.get().equals(id)) {
            return false;
          }
          work.run();
          return true;
        });
  }

  private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "stopped", "failed");

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
            status, exit_code, log_path, unit, started_at, completed_at, repos)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET project = excluded.project, spec_id = excluded.spec_id,
            node = excluded.node, role = excluded.role, agent = excluded.agent,
            branch = excluded.branch, task = excluded.task, pid = excluded.pid,
            watcher_pid = excluded.watcher_pid, status = excluded.status,
            exit_code = excluded.exit_code, log_path = excluded.log_path, unit = excluded.unit,
            started_at = excluded.started_at, completed_at = excluded.completed_at,
            repos = excluded.repos""",
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
        row.completedAt(),
        YamlUtil.dumpJson(Objects.requireNonNullElse(row.repos(), List.of())));
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
    map.put("repos", Objects.requireNonNullElse(run.repos(), List.of()));
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
        text(snapshot, "completed_at"),
        stringList(snapshot, "repos"));
  }

  private static String text(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static List<String> stringList(Map<String, Object> map, String key) {
    return map.get(key) instanceof List<?> list
        ? list.stream().map(String::valueOf).toList()
        : List.of();
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
        row.text(15),
        YamlUtil.parseStringList(row.text(16)));
  }
}
