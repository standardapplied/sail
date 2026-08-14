/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
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
   * the run actually owns instead of re-deriving a name that could drift across releases. Build
   * runs always carry one; foreground review runs intentionally do not use systemd.
   *
   * <p>{@code repos} is the repo set the dispatch reserved at launch — the run's own claim, not a
   * lookup through its spec, so the overlap gate reads a value that existed before the spec was
   * even claimed. Empty means the run works the whole container; a run recorded before repos were
   * persisted also reads as empty, which the gate treats identically (the safe reading).
   *
   * <p>{@code principal} is the run's minted agent-principal handle (e.g. {@code claude/a1b2c3})
   * and {@code owner} the FDE it acts for. Both are attribution stamped at creation and replicate
   * with the run; the run's credential lives in the local-only {@code run_credentials} table and
   * never joins a snapshot. A row that outlives its credential keeps the handle for history.
   *
   * <p>{@code sessionId}, {@code sessionSource}, and {@code transcriptPath} are the hook-reported
   * identity of the run's agent conversation — see {@link #recordSession}. All three are null until
   * a session reports; a run adopted from an old-shape snapshot derives nulls the same way.
   *
   * <p>{@code lastActivityAt} is when the run's agent last showed progress (a tool call or a log
   * chunk) — see {@link #stampActivity}. Null until the first stamp, and on every row adopted from
   * a pre-upgrade snapshot; presence readers treat null as "unknown", never as quiet.
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
      List<String> repos,
      Long pidTicks,
      String principal,
      String owner,
      String sessionId,
      String sessionSource,
      String transcriptPath,
      String lastActivityAt) {

    /** A row without activity — the shape every run has until its first progress stamp. */
    public RunRow(
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
        List<String> repos,
        Long pidTicks,
        String principal,
        String owner,
        String sessionId,
        String sessionSource,
        String transcriptPath) {
      this(
          id,
          project,
          specId,
          node,
          role,
          agent,
          branch,
          task,
          pid,
          watcherPid,
          status,
          exitCode,
          logPath,
          unit,
          startedAt,
          completedAt,
          repos,
          pidTicks,
          principal,
          owner,
          sessionId,
          sessionSource,
          transcriptPath,
          null);
    }

    /** A row without session identity — the shape every run has until its first session report. */
    public RunRow(
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
        List<String> repos,
        Long pidTicks,
        String principal,
        String owner) {
      this(
          id,
          project,
          specId,
          node,
          role,
          agent,
          branch,
          task,
          pid,
          watcherPid,
          status,
          exitCode,
          logPath,
          unit,
          startedAt,
          completedAt,
          repos,
          pidTicks,
          principal,
          owner,
          null,
          null,
          null);
    }

    /** Whether this row is a build attempt of a spec. */
    public boolean buildRole() {
      return "build".equals(role);
    }

    /** Whether this row is an ad-hoc session — an engineer-initiated run that works no spec. */
    public boolean adhocRole() {
      return "adhoc".equals(role);
    }

    /** Whether this row is a room wake — a chat-lane run that answers in its spec's room. */
    public boolean roomRole() {
      return "room".equals(role);
    }

    /**
     * Whether this row is a review execution — the reviewer or its fix agent, which share the one
     * review row. Their own stop must never re-enter the pipeline, so lane-aware reactors consult
     * this as the fallback when a stop signal lost its role marker.
     */
    public boolean reviewRole() {
      return "review".equals(role);
    }

    /**
     * Whether this row is an agent session the run-scoped machinery owns — a build attempt, an
     * ad-hoc run, or a room wake — as opposed to a pipeline-driven review execution. Session rows
     * are the ones the stop, status, log, reaper, and missed-stop lanes address; a room run joins
     * them because it launches through the same systemd unit and must be reaped and stoppable like
     * any other.
     */
    public boolean sessionRole() {
      return buildRole() || adhocRole() || roomRole();
    }
  }

  private static final String SESSION_ROLES = "role IN ('build', 'adhoc', 'room')";

  private static final String COLUMNS =
      "id, project, spec_id, node, role, agent, branch, task, pid, watcher_pid, status,"
          + " exit_code, log_path, unit, started_at, completed_at, repos, pid_ticks,"
          + " principal, owner, session_id, session_source, transcript_path, last_activity_at";

  /**
   * Records a new run in the {@code running} state, journaling a baseline revision so it
   * replicates. The id is minted by the launcher (a UUIDv7) so the run-scoped log directory is
   * addressable before the agent starts; {@code node} is the executing box's FDE handle at launch;
   * {@code owner} is the FDE the run's agent principal acts for. The principal handle and the run's
   * credential are minted inside the same transaction as the row, so a run and its identity are
   * atomic. Returns the id.
   */
  public String create(
      String id,
      String project,
      String specId,
      String node,
      String owner,
      String role,
      String agent,
      String branch,
      String task,
      Integer pid,
      Integer watcherPid,
      String logPath,
      String unit) {
    createReturningCredential(
        id,
        project,
        specId,
        node,
        owner,
        role,
        agent,
        branch,
        task,
        pid,
        watcherPid,
        logPath,
        unit);
    return id;
  }

  private String createReturningCredential(
      String id,
      String project,
      String specId,
      String node,
      String owner,
      String role,
      String agent,
      String branch,
      String task,
      Integer pid,
      Integer watcherPid,
      String logPath,
      String unit) {
    return db.transaction(
        () -> {
          db.execute(
              """
              INSERT INTO runs (id, project, spec_id, node, role, agent, branch, task, pid,
                  watcher_pid, status, started_at, log_path, unit, principal, owner)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'running', ?, ?, ?, ?, ?)""",
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
              unit,
              principalHandle(agent, role, id),
              owner);
          recordPrincipal(id, principalHandle(agent, role, id));
          var credential = mintCredential(id, null);
          recordRevision(id, "local", false);
          return credential;
        });
  }

  /**
   * Records a review negotiation under the review UUID that owns its prompt, session, and log
   * files. Reviewer and fix invocations deliberately share that identity, so one run row remains
   * addressable as {@code ~/.sail/runs/<reviewId>/review.log} throughout the negotiation. {@code
   * unit} is the review's real execution identity ({@code sail-review-<id>}), recorded so a probe
   * of any run row is honest even though reviews execute as blocking foreground work. {@code owner}
   * is the reviewed spec's assignee — the FDE the review principal acts for. Returns the run's
   * plaintext credential, surfaced exactly once so the launched review agent can actually act as
   * the principal this row records; only the hash is at rest.
   */
  public String createReview(
      String reviewId,
      String project,
      String specId,
      String node,
      String owner,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit) {
    return createReturningCredential(
        reviewId, project, specId, node, owner, "review", agent, branch, task, null, null, logPath,
        unit);
  }

  /**
   * Rotates the credential of a live run — the seam the review pipeline's lanes use to rejoin the
   * run after another invocation already created it (the fix lane rejoining its review's still-open
   * negotiation, a later stage's reviewer rejoining after it). The original plaintext is
   * unrecoverable by design, so rejoining means a fresh credential; the run holds exactly one at a
   * time (the schema enforces it), so rotation retires the previous invocation's credential in the
   * same transaction. The rejoining invocation also stamps its own identity: the run row's {@code
   * agent} and {@code principal} become the invocation's ({@code <agent>/fix-<id>} for the fix
   * lane, {@code <agent>/review-<id>} for a reviewer), journaled so the honest attribution
   * replicates — rooms and run views read the principal, and the author must be the lane that
   * wrote. Fails loud on a missing or finished run — a dead run's identity is never resurrected.
   *
   * @param lane {@code "fix"} or {@code "review"} — the invocation rejoining the run, which selects
   *     the principal's marker; the run row's {@code role} stays {@code review}
   */
  public String rotateCredential(String id, String agent, String lane) {
    return db.transaction(
        () -> {
          var run =
              findById(id)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "No run " + id + " to issue a credential for."));
          if (!"running".equals(run.status())) {
            throw new IllegalStateException(
                "Run " + id + " is " + run.status() + "; only a running run can be credentialed.");
          }
          db.execute(
              "UPDATE runs SET agent = ?, principal = ? WHERE id = ?",
              agent,
              principalHandle(agent, lane, id),
              id);
          recordPrincipal(id, principalHandle(agent, lane, id));
          revokeCredential(id);
          var credential = mintCredential(id, null);
          recordRevision(id, "local", false);
          return credential;
        });
  }

  /**
   * What a dispatch reservation produced: the reserved run's plaintext credential (returned exactly
   * once, hashed at rest), or the blocking conflict.
   */
  public sealed interface Reservation {
    record Reserved(String credential) implements Reservation {}

    record Conflicted(DispatchGate.Conflict conflict) implements Reservation {}
  }

  /**
   * Atomically reserves a dispatch: within one {@code BEGIN IMMEDIATE} transaction, checks every
   * running local run of the project for a repo overlap or a same-spec run and inserts the new
   * {@code running} run — with its reserved repos persisted — only when none conflicts. Taking the
   * write lock up front serializes concurrent dispatches, including a CLI dispatch in another
   * process against the same database file, so the second reservation always observes the first's
   * row; a check-then-insert split across transactions could admit both into the same repo. The
   * run's agent principal (handle, {@code owner}) and its credential are minted inside the same
   * transaction, so a reserved run always carries an attributable identity and a refused one mints
   * nothing. Returns the blocking conflict, or the reserved run's credential. A run mid-stop
   * ({@code stopping}) still occupies its repos — its agent is not verified dead until the claim is
   * finalized — so it conflicts exactly like a running one. Any database failure propagates — a
   * dispatch must never launch without the row every later overlap check depends on. {@code
   * maxDuration} is the run's configured hard stop ({@code guardrails.max_duration}): the
   * credential expires that long plus {@link #CREDENTIAL_GRACE} after minting, and a null means no
   * hard stop, so the credential lives until a verified finisher revokes it.
   */
  public Reservation reserveDispatch(
      String id,
      String project,
      String specId,
      String node,
      String owner,
      String role,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit) {
    return reserveDispatch(
        id, project, specId, node, owner, role, repos, agent, branch, task, logPath, unit, null);
  }

  public Reservation reserveDispatch(
      String id,
      String project,
      String specId,
      String node,
      String owner,
      String role,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit,
      Duration maxDuration) {
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
                  role,
                  reserved,
                  running.stream()
                      .map(
                          run ->
                              new DispatchGate.RunningRun(
                                  run.id(), run.specId(), run.role(), run.repos()))
                      .toList());
          if (conflict.isPresent()) {
            return new Reservation.Conflicted(conflict.get());
          }
          db.execute(
              """
              INSERT INTO runs (id, project, spec_id, node, role, agent, branch, task, status,
                  started_at, log_path, unit, repos, principal, owner)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'running', ?, ?, ?, ?, ?, ?)""",
              id,
              project,
              specId,
              node,
              role,
              agent,
              branch,
              task,
              DateTimeUtils.now().toString(),
              logPath,
              unit,
              YamlUtil.dumpJson(reserved),
              principalHandle(agent, role, id),
              owner);
          recordPrincipal(id, principalHandle(agent, role, id));
          var credential = mintCredential(id, maxDuration);
          recordRevision(id, "local", false);
          return new Reservation.Reserved(credential);
        });
  }

  public Optional<RunRow> findById(String id) {
    return db.queryOne("SELECT " + COLUMNS + " FROM runs WHERE id = ?", this::mapRow, id);
  }

  /**
   * Records that the run was shown exactly these spec-room messages, one ledger row per (run,
   * message). Delivery is tracked by identity, never by a high-water id: messages sync between
   * boxes, so an older-id message can land locally after newer messages were already delivered — it
   * simply has no ledger row yet and is still owed a delivery. Idempotent ({@code INSERT OR
   * IGNORE}), so a replayed acknowledgement is a no-op. Node-local operational state mirroring the
   * local-only {@code run_credentials} table: the ledger never joins {@link #comparableSnapshot}, a
   * journaled revision, or a sync write, so delivery bookkeeping can never churn revisions or
   * conflict across boxes.
   */
  public void markDelivered(String id, Collection<String> messageIds) {
    messageIds.forEach(Ids::requireUuid);
    db.transaction(
        () -> {
          for (var messageId : messageIds) {
            db.execute(
                "INSERT OR IGNORE INTO run_delivered_messages (run_id, message_id) VALUES (?, ?)",
                id,
                messageId);
          }
          return null;
        });
  }

  /**
   * Records the hook-reported identity of the run's agent conversation: the session id, the start
   * source ({@code startup}, {@code resume}, {@code clear}, {@code compact}), and the
   * container-side transcript path. Last write wins by design — a clear or compact restart mints a
   * new conversation and re-reports, and on a review run the reviewer and fix invocations share the
   * row, so the recorded session is the most recent invocation's conversation: exactly the attach
   * target a human wants. Authentication is the caller's concern — the run credential gates the
   * write, and credential revocation at run completion is the write gate, so no status check here.
   * Journals a revision so the identity replicates.
   */
  public void recordSession(
      String id, String sessionId, String sessionSource, String transcriptPath) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE runs SET session_id = ?, session_source = ?, transcript_path = ?"
                  + " WHERE id = ?",
              sessionId,
              sessionSource,
              transcriptPath,
              id);
          recordRevision(id, "local", false);
        });
  }

  /**
   * Stamps when the run's agent last showed progress, coalesced to at most one write per {@code
   * floor} window: the write is skipped while the row's {@code last_activity_at} is still within
   * {@code floor} of now, so a continuous {@code agent_log_chunk} stream costs one UPDATE per
   * window instead of one per chunk. Deliberately journals <em>no</em> revision — presence needs
   * ~minute granularity, and a revision per stamp would flood the ChangeLog and fire sync-on-write
   * on every chunk; the value rides along on the run's next real revision instead, so a foreign
   * box's copy is as fresh as the normal sync cadence. Only a {@code running} row is stamped: a
   * late event must never dirty a terminal row whose final revision has already been journaled.
   * Returns whether a write happened.
   */
  public boolean stampActivity(String id, Duration floor) {
    var now = DateTimeUtils.now();
    db.execute(
        "UPDATE runs SET last_activity_at = ? WHERE id = ? AND status = 'running'"
            + " AND (last_activity_at IS NULL OR last_activity_at < ?)",
        now.toString(),
        id,
        now.minus(floor).toString());
    return db.changes() > 0;
  }

  /**
   * Records the room commit guard's launch baseline (per-repo HEAD and worktree state), replacing
   * any earlier one. Host-side storage is the point: the guarded agent runs inside the container
   * and can never reach this row, unlike a file in its own run directory. Local-only bookkeeping —
   * never journaled, never synced.
   */
  public void saveRoomGuardBaseline(String id, String baseline) {
    db.execute("INSERT OR REPLACE INTO room_guard (run_id, baseline) VALUES (?, ?)", id, baseline);
  }

  /**
   * The recorded room-guard baseline, deleted on read so a replayed stop signal checks nothing
   * twice. Empty when no baseline was recorded or a prior stop already consumed it.
   */
  public Optional<String> consumeRoomGuardBaseline(String id) {
    return db.transaction(
        () -> {
          var baseline =
              db.queryOne(
                  "SELECT baseline FROM room_guard WHERE run_id = ?", row -> row.text(0), id);
          baseline.ifPresent(b -> db.execute("DELETE FROM room_guard WHERE run_id = ?", id));
          return baseline;
        });
  }

  /** The run's delivery ledger — see {@link #markDelivered}. */
  public Set<String> deliveredMessageIds(String id) {
    return new LinkedHashSet<>(
        db.query(
            "SELECT message_id FROM run_delivered_messages WHERE run_id = ? ORDER BY message_id",
            row -> row.text(0),
            id));
  }

  /**
   * Headroom a credential keeps past its run's configured hard stop, covering the watcher's kill
   * and the missed-stop reconciler's sweep. Revocation on every run finisher is the real terminator
   * — the expiry only bounds a credential whose run's finishers all failed to fire, and the
   * expired-row sweep collects such stragglers. A run with no configured hard stop mints a
   * credential with no expiry, so a legitimately long-lived agent never loses access mid-run.
   */
  public static final Duration CREDENTIAL_GRACE = Duration.ofHours(1);

  /**
   * Resolves a live run credential to its run row: unknown, revoked, or expired credentials resolve
   * to empty, and an expired row is pruned on lookup like {@link TokenStore#validate}. The
   * plaintext is hashed before comparison — only the hash is ever at rest.
   */
  public Optional<RunRow> findByCredential(String credential) {
    if (Strings.isBlank(credential)) {
      return Optional.empty();
    }
    var hash = TokenStore.sha256(credential);
    var match =
        db.queryOne(
            "SELECT run_id, expires_at FROM run_credentials WHERE credential_hash = ?",
            row -> new String[] {row.text(0), row.text(1)},
            hash);
    if (match.isEmpty()) {
      return Optional.empty();
    }
    var expiresAt = match.get()[1];
    if (expiresAt != null && Instant.parse(expiresAt).isBefore(DateTimeUtils.now())) {
      db.execute("DELETE FROM run_credentials WHERE credential_hash = ?", hash);
      return Optional.empty();
    }
    return findById(match.get()[0]);
  }

  /**
   * The run's minted principal handle: the agent family (the yaml name up to its first dash) over
   * the full run id, with review and fix invocations marked as such — {@code claude/<run-uuid>},
   * {@code claude/review-<run-uuid>}, {@code claude/fix-<run-uuid>}. The whole UUID, never a
   * truncation: the handle is a security identity compared in ownership checks and audit rows, so
   * it must be exactly as collision-proof as the run id itself.
   */
  /**
   * The run's replicated, append-only principal history: every identity a legitimate invocation of
   * this run has ever posted under. The runs row keeps only the current principal for honest live
   * attribution; message authorization on main checks membership here, so a room message authored
   * before a lane rotation still authenticates when it synchronizes late. Never pruned while the
   * run exists; cascades away with it.
   */
  public List<String> principals(String id) {
    return db.query(
        "SELECT principal FROM run_principals WHERE run_id = ? ORDER BY principal",
        row -> row.text(0),
        id);
  }

  private void recordPrincipal(String id, String principal) {
    if (Strings.isBlank(principal)) {
      return;
    }
    db.execute(
        "INSERT OR IGNORE INTO run_principals (run_id, principal) VALUES (?, ?)", id, principal);
  }

  private void recordPrincipals(String id, Map<String, Object> snapshot) {
    stringList(snapshot, "principals").forEach(principal -> recordPrincipal(id, principal));
    recordPrincipal(id, text(snapshot, "principal"));
  }

  private static String principalHandle(String agent, String role, String id) {
    var family = Objects.toString(agent, "");
    var dash = family.indexOf('-');
    var base = dash > 0 ? family.substring(0, dash) : family;
    var runId = Objects.requireNonNull(id, "run id");
    var marker =
        switch (Objects.toString(role, "")) {
          case "review" -> "review-";
          case "fix" -> "fix-";
          case "room" -> "room-";
          default -> "";
        };
    return base + "/" + marker + runId;
  }

  private String mintCredential(String id, Duration maxDuration) {
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    var credential = "sailrun_" + HexFormat.of().formatHex(bytes);
    var now = DateTimeUtils.now();
    var expiresAt =
        maxDuration == null ? null : now.plus(maxDuration).plus(CREDENTIAL_GRACE).toString();
    db.execute(
        "INSERT INTO run_credentials (run_id, credential_hash, created_at, expires_at)"
            + " VALUES (?, ?, ?, ?)",
        id,
        TokenStore.sha256(credential),
        now.toString(),
        expiresAt);
    return credential;
  }

  private void revokeCredential(String id) {
    db.execute("DELETE FROM run_credentials WHERE run_id = ?", id);
  }

  /**
   * The latest agent session (build or ad-hoc) of {@code project} that executed on this box, or
   * empty. Review runs remain in the aggregate but do not replace the session used by agent status,
   * log, and report commands. Ownership is by node: a box with a handle owns exactly the runs
   * stamped with it; a box with no handle owns exactly its own blank-node runs and never a run
   * adopted from another box via sync.
   */
  public Optional<RunRow> latestForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND IFNULL(node, '') = ?"
            + " AND "
            + SESSION_ROLES
            + " ORDER BY started_at DESC"
            + " LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  /**
   * The active agent session (build or ad-hoc) of {@code project} that executed on this box, or
   * empty. {@code stopping} counts as active: an interrupted stop's claim must stay addressable so
   * a project-targeted stop retry resumes it. Node-scoped like {@link #latestForProjectOnNode}.
   */
  public Optional<RunRow> runningForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND status IN ('running', 'stopping')"
            + " AND IFNULL(node, '') = ? AND "
            + SESSION_ROLES
            + " ORDER BY started_at DESC LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  /**
   * Every agent session (build or ad-hoc) holding an unfinished stop claim ({@code stopping}) — a
   * stop that recorded its terminal intent but was interrupted before the halt was verified. The
   * reconciler's interrupted-stop pass finalizes these once their unit is gone.
   */
  public List<RunRow> stopping() {
    return db.query(
        "SELECT " + COLUMNS + " FROM runs WHERE status = 'stopping' AND " + SESSION_ROLES,
        this::mapRow);
  }

  /**
   * Every agent session (build or ad-hoc) still in the {@code running} state, across all projects
   * and nodes — the session reaper's full input. Review executions are foreground work owned and
   * completed by the review controller, so the systemd reaper must not probe them as build units.
   */
  public List<RunRow> running() {
    return db.query(
        "SELECT " + COLUMNS + " FROM runs WHERE status = 'running' AND " + SESSION_ROLES,
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
          if (TERMINAL_STATUSES.contains(status)) {
            revokeCredential(id);
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
                  "SELECT id FROM runs WHERE spec_id = ? AND role = 'build'"
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
          revokeCredential(id);
          recordRevision(id, "local", false);
        });
  }

  /**
   * Stamps the agent process identity and watcher pid on a run once launch has produced them. The
   * run row is created before launch (so terminal hook events can find it), then updated here with
   * what the launch resolved. {@code pidTicks} is the agent process's {@code /proc} start-time
   * fingerprint — pids are reused by the kernel, so the pid alone can later name an unrelated
   * process, and the stop lane refuses to signal a pid whose fingerprint no longer matches.
   * Journals a revision so the identity replicates.
   *
   * <p>Commits only while the run is still {@code running} and returns whether it did. A stop that
   * lands during launch preparation records its terminal intent on the row; the launcher discovers
   * that loss here and must tear down whatever it just started instead of letting an unrecorded
   * agent escape the reservation. {@code BEGIN IMMEDIATE} closes the read-then-write window against
   * the stop's own claim transaction.
   */
  public boolean updateProcess(String id, Integer pid, Long pidTicks, Integer watcherPid) {
    return db.immediateTransaction(
        () -> {
          db.execute(
              "UPDATE runs SET pid = ?, pid_ticks = ?, watcher_pid = ? WHERE id = ?"
                  + " AND status = 'running'",
              pid != null ? pid.longValue() : null,
              pidTicks,
              watcherPid != null ? watcherPid.longValue() : null,
              id);
          if (db.changes() == 0) {
            return false;
          }
          recordRevision(id, "local", false);
          return true;
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

  public Map<String, Object> comparableSnapshot(String id) {
    return findById(id)
        .map(
            run -> {
              var map = snapshotMap(run);
              map.put("principals", principals(id));
              return comparable(map);
            })
        .orElse(null);
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
            recordPrincipals(id, snapshot);
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
            revokeCredential(id);
            db.execute("DELETE FROM runs WHERE id = ?", id);
            return new PushOutcome.Accepted(rev);
          }
          writeRow(rowFrom(id, snapshot));
          recordPrincipals(id, snapshot);
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
    recordPrincipals(id, remote);
    return recordRevision(id, null, "sync", false, true);
  }

  private String adoptBaseDeletion(String id) {
    if (findById(id).isEmpty()) {
      var rev = Revisions.next(currentRev(id), "{}");
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return rev;
    }
    var rev = recordRevision(id, null, "sync", true, false);
    revokeCredential(id);
    db.execute("DELETE FROM runs WHERE id = ?", id);
    return rev;
  }

  private String writeChosen(String id, Map<String, Object> chosen) {
    if (chosen == null) {
      if (findById(id).isEmpty()) {
        return latestRev(id);
      }
      var rev = recordRevision(id, null, "resolve", true, false);
      revokeCredential(id);
      db.execute("DELETE FROM runs WHERE id = ?", id);
      return rev;
    }
    writeRow(rowFrom(id, chosen));
    recordPrincipals(id, chosen);
    return recordRevision(id, null, "resolve", false, false);
  }

  private void adoptDeletion(String id, String rev) {
    if (findById(id).isEmpty()) {
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return;
    }
    recordRevision(id, rev, "sync", true, false);
    revokeCredential(id);
    db.execute("DELETE FROM runs WHERE id = ?", id);
  }

  String recordRevision(String id, String origin, boolean deleted) {
    return recordRevision(id, null, origin, deleted, false);
  }

  private String recordRevision(
      String id, String explicitRev, String origin, boolean deleted, boolean setBaseRev) {
    var run = findById(id).orElse(null);
    if (run == null) {
      return null;
    }
    var map = snapshotMap(run);
    map.put("principals", principals(id));
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
            status, exit_code, log_path, unit, started_at, completed_at, repos, pid_ticks,
            principal, owner, session_id, session_source, transcript_path, last_activity_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET project = excluded.project, spec_id = excluded.spec_id,
            node = excluded.node, role = excluded.role, agent = excluded.agent,
            branch = excluded.branch, task = excluded.task, pid = excluded.pid,
            watcher_pid = excluded.watcher_pid, status = excluded.status,
            exit_code = excluded.exit_code, log_path = excluded.log_path, unit = excluded.unit,
            started_at = excluded.started_at, completed_at = excluded.completed_at,
            repos = excluded.repos, pid_ticks = excluded.pid_ticks,
            principal = excluded.principal, owner = excluded.owner,
            session_id = excluded.session_id, session_source = excluded.session_source,
            transcript_path = excluded.transcript_path,
            last_activity_at = excluded.last_activity_at""",
        row.id(),
        row.project(),
        row.specId(),
        row.node(),
        row.role(),
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
        YamlUtil.dumpJson(Objects.requireNonNullElse(row.repos(), List.of())),
        row.pidTicks(),
        row.principal(),
        row.owner(),
        row.sessionId(),
        row.sessionSource(),
        row.transcriptPath(),
        row.lastActivityAt());
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
    map.put("pid_ticks", run.pidTicks());
    map.put("principal", run.principal());
    map.put("owner", run.owner());
    map.put("session_id", run.sessionId());
    map.put("session_source", run.sessionSource());
    map.put("transcript_path", run.transcriptPath());
    map.put("last_activity_at", run.lastActivityAt());
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
        stringList(snapshot, "repos"),
        longValue(snapshot, "pid_ticks"),
        text(snapshot, "principal"),
        text(snapshot, "owner"),
        text(snapshot, "session_id"),
        text(snapshot, "session_source"),
        text(snapshot, "transcript_path"),
        text(snapshot, "last_activity_at"));
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

  private static Long longValue(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.longValue() : null;
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
        YamlUtil.parseStringList(row.text(16)),
        row.isNull(17) ? null : row.integer(17),
        row.text(18),
        row.text(19),
        row.text(20),
        row.text(21),
        row.text(22),
        row.text(23));
  }
}
