/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.RunAuthority;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
 * StoreReplica} still delegates to the full revision/CAS machinery, but a true same-field conflict
 * can only arise from a corrupted invariant, never from normal operation. Each mutation journals
 * the run's full post-state into the shared {@link ChangeLog} under entity type {@code run} within
 * one transaction, so a run's lifecycle (start → complete/fail/stop) replicates to main and on to
 * every other box, and any box can answer "which node is running spec X".
 */
public final class RunStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "run";

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final RevisionJournal journal;

  public RunStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
    this.journal = new RevisionJournal(db, changeLog, new RunSchema());
  }

  /**
   * {@code unit} is the systemd unit the run was launched as — recorded at launch as part of the
   * run's identity, so every later consumer (stop, probe, reconciler, watcher) addresses the unit
   * the run actually owns instead of re-deriving a name that could drift across releases.
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
   *
   * <p>{@code reviewId} is the review a reviewer's or a fix agent's run serves, stamped at creation
   * and never changed: the review pipeline reads it to know which review a stop advances. Null for
   * every other lane, and on a row an older box wrote.
   *
   * <p>{@code stopSource} is who ended the run when it did not end on its own: {@link
   * #STOPPED_BY_OPERATOR} from the moment an operator's stop claims it ({@link #claimStop}), kept
   * once that stop is finalized. Null on a run that ended itself or that the watcher ended.
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
      String lastActivityAt,
      String roomId,
      String reviewId,
      String stopSource) {

    /** The conversation this run serves — its spec's room, or the room itself when spec-less. */
    public String conversationId() {
      return specId != null ? specId : roomId;
    }

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
          null,
          null,
          null,
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

    /** This row's lane, or empty for an unrecognized role. */
    public Optional<Lane> lane() {
      return Lane.of(role);
    }

    /**
     * Whether this run's own stop hands its spec to the review pipeline — only a build or ad-hoc
     * does. A row-less or unrecognized role is treated as a normal triggering stop, matching the
     * role-marker path; a retired invite row never triggers.
     */
    public boolean triggersReview() {
      return Lane.triggersReview(role);
    }

    /** Whether this row is a build attempt of a spec. */
    public boolean buildRole() {
      return Lane.BUILD.matches(role);
    }

    /** Whether this row is an ad-hoc session — an engineer-initiated run that works no spec. */
    public boolean adhocRole() {
      return Lane.ADHOC.matches(role);
    }

    /** Whether this row is a room wake — a chat-lane run that answers in its spec's room. */
    public boolean roomRole() {
      return Lane.ROOM.matches(role);
    }

    /**
     * Whether this row is a conversational turn of the room lane, either mode: a wake or an engaged
     * agent's turn. Chat stops never trigger the review pipeline, and one chat turn runs at a time
     * per spec.
     */
    public boolean chatRole() {
      return lane().map(Lane::isChat).orElse(false);
    }

    /**
     * Whether this row runs under the read-only room contract — a room wake: viewer-tier
     * credential, harness tool cut, no repo reservation, worktree-digest guard. The API boundary
     * derives the credential tier from this predicate, never from anything the session says.
     */
    public boolean readOnlyLane() {
      return Lane.readOnly(role);
    }

    /**
     * The actor this run's credential stands for before the role rule weighs it: its principal, for
     * the FDE the run acts for, on the room lane for a read-only lane and the agent lane otherwise.
     */
    public Actor principalActor() {
      return readOnlyLane()
          ? Actor.roomPrincipal(principal, owner)
          : Actor.agentPrincipal(principal, owner);
    }

    /** Whether this row serves a review: a reviewer's run, or the fix agent's that answers it. */
    public boolean servesReview() {
      return lane().map(Lane::servesReview).orElse(false);
    }

    /**
     * Whether an operator's stop holds this run or ended it. The unit dying under that stop is seen
     * by the watcher too, and this is what tells its stop from the run ending on its own, however
     * late it arrives.
     */
    public boolean stoppedByOperator() {
      return STOPPED_BY_OPERATOR.equals(stopSource);
    }

    /**
     * Whether this run belongs to the box whose handle is {@code localHandle} — the one predicate
     * for every run-ownership question (the read guard, the reaper, the reconciler, presence), so
     * they can never disagree about which box owns a run. A handled box owns the runs stamped with
     * its handle (a blank node fails closed); an unhandled box — standalone, not yet bound to an
     * FDE — owns its own blank-node runs.
     */
    public boolean ownedBy(String localHandle) {
      return Strings.isBlank(localHandle) ? Strings.isBlank(node) : localHandle.equals(node);
    }

    /**
     * Whether this run is work still under way on the box whose FDE handle is {@code localHandle}:
     * that box executed it ({@link #ownedBy}) and it has not finished.
     */
    public boolean liveOn(String localHandle) {
      return ownedBy(localHandle) && !RunStatus.isTerminal(status);
    }
  }

  /** The {@code stop_source} of a run an operator's stop claimed. */
  public static final String STOPPED_BY_OPERATOR = "operator";

  private static final String COLUMNS =
      "id, project, spec_id, node, role, agent, branch, task, pid, watcher_pid, status,"
          + " exit_code, log_path, unit, started_at, completed_at, repos, pid_ticks,"
          + " principal, owner, session_id, session_source, transcript_path, last_activity_at,"
          + " room_id, review_id, stop_source";

  /**
   * Records a new run in the {@code running} state, journaling a baseline revision so it
   * replicates. The id is minted by the launcher (a UUIDv7) so the run-scoped log directory is
   * addressable before the agent starts. It is stamped as every run this box executes is ({@link
   * #stamp}), for {@code boxHandle}: that box's FDE executes it and its agent acts for them. The
   * principal handle and the run's credential are minted inside the same transaction as the row, so
   * a run and its identity are atomic. Fails if an exclusive container lease (see {@link
   * #acquireContainerLease}) is held — a run must never start into a container about to be rolled
   * back. Returns the id.
   */
  public String create(
      String id,
      String project,
      String specId,
      String boxHandle,
      String role,
      String agent,
      String branch,
      String task,
      Integer pid,
      Integer watcherPid,
      String logPath,
      String unit) {
    var node = stamp(boxHandle);
    return db.transaction(
        () -> {
          var lease = activeLease(project, node);
          if (lease.isPresent()) {
            throw new IllegalStateException(
                "Refusing to record run "
                    + id
                    + " for project "
                    + project
                    + ": an exclusive "
                    + lease.get()
                    + " operation holds its container. Retry after it completes.");
          }
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
              node);
          recordPrincipal(id, principalHandle(agent, role, id));
          mintCredential(id, null);
          recordRevision(id, ChangeLog.Entry.LOCAL, false);
          return id;
        });
  }

  /**
   * What a dispatch reservation produced: the reserved run's plaintext credential (returned exactly
   * once, hashed at rest), the blocking conflict, or the exclusive container operation (a snapshot
   * restore) that owns the whole container right now.
   */
  public sealed interface Reservation {
    record Reserved(String credential) implements Reservation {}

    record Conflicted(DispatchGate.Conflict conflict) implements Reservation {}

    record LeaseHeld(String action) implements Reservation {}
  }

  /**
   * What acquiring an exclusive container lease produced: the lease, the live run that blocks it,
   * or the lease some other exclusive operation already holds.
   */
  public sealed interface ContainerLease {
    record Acquired() implements ContainerLease {}

    record BlockedByRun(RunRow run) implements ContainerLease {}

    record BlockedByLease(String action) implements ContainerLease {}
  }

  /**
   * How long a container lease is honored before readers treat it as abandoned: the leasing process
   * releases in a {@code finally}, so an expired row only ever means that process died mid-mutation
   * — and the mutation itself (a snapshot restore) completes well inside this bound. Expired rows
   * are pruned on the next acquire or reservation, so a crash never wedges dispatch for good.
   */
  public static final Duration LEASE_TTL = Duration.ofMinutes(30);

  /**
   * Atomically claims the project's container on this box for one exclusive operation (a snapshot
   * restore): within a single {@code BEGIN IMMEDIATE} transaction, refuses if another lease is held
   * or any local run of the project is live ({@code running} or {@code stopping}, every role — even
   * a room wake loses its session when the container is rolled back), then inserts the lease. Every
   * run insert — {@link #reserveDispatch}, {@link #reserveForReview}, {@link #create} — checks this
   * lease inside its own transaction, so the two sides can never interleave: a restore is refused
   * over live work, and no run can start until {@link #releaseContainerLease} runs.
   */
  public ContainerLease acquireContainerLease(String project, String localHandle, String action) {
    return db.transaction(
        () -> {
          var held = activeLease(project, localHandle);
          if (held.isPresent()) {
            return new ContainerLease.BlockedByLease(held.get());
          }
          var live =
              db.queryOne(
                  "SELECT "
                      + COLUMNS
                      + " FROM runs WHERE project = ? AND status IN ('running', 'stopping')"
                      + " AND IFNULL(node, '') = ? ORDER BY started_at LIMIT 1",
                  this::mapRow,
                  project,
                  ownerKey(localHandle));
          if (live.isPresent()) {
            return new ContainerLease.BlockedByRun(live.get());
          }
          db.execute(
              "INSERT INTO container_leases (project, node, action, created_at)"
                  + " VALUES (?, ?, ?, ?)",
              project,
              ownerKey(localHandle),
              action,
              DateTimeUtils.now().toString());
          return new ContainerLease.Acquired();
        });
  }

  /** Up to {@code limit} runs that finished before {@code cutoff}: what run retention erases. */
  public List<String> finishedBefore(Instant cutoff, int limit) {
    var terminal =
        Arrays.stream(RunStatus.values())
            .filter(RunStatus::isTerminal)
            .map(RunStatus::wire)
            .toList();
    var parameters = new ArrayList<Object>(terminal);
    parameters.add(cutoff.toString());
    parameters.add(limit);
    return db.query(
        "SELECT id FROM runs WHERE status IN ("
            + String.join(", ", terminal.stream().map(status -> "?").toList())
            + ") AND completed_at IS NOT NULL AND julianday(completed_at) < julianday(?)"
            + " ORDER BY id LIMIT ?",
        row -> row.text(0),
        parameters.toArray());
  }

  /** The stored statuses of a run that has not finished. */
  static List<String> unfinishedStatuses() {
    return Arrays.stream(RunStatus.values())
        .filter(status -> !status.isTerminal())
        .map(RunStatus::wire)
        .toList();
  }

  /** The runs among {@code ids} that have not finished ({@link RunStatus#isTerminal}). */
  public List<String> unfinished(List<String> ids) {
    var unfinished = new ArrayList<String>();
    for (var from = 0; from < ids.size(); from += 500) {
      var batch = ids.subList(from, Math.min(ids.size(), from + 500));
      for (var row :
          db.query(
              "SELECT id, status FROM runs WHERE id IN ("
                  + String.join(", ", batch.stream().map(id -> "?").toList())
                  + ") ORDER BY id",
              r -> Map.entry(r.text(0), Objects.toString(r.text(1), "")),
              batch.toArray())) {
        if (!RunStatus.isTerminal(row.getValue())) {
          unfinished.add(row.getKey());
        }
      }
    }
    return unfinished;
  }

  /** Drops every box's container lease on an erased project. */
  public void eraseLeases(String project) {
    db.execute("DELETE FROM container_leases WHERE project = ?", project);
  }

  /** Releases the box's container lease on the project. Idempotent. */
  public void releaseContainerLease(String project, String localHandle) {
    db.execute(
        "DELETE FROM container_leases WHERE project = ? AND node = ?",
        project,
        ownerKey(localHandle));
  }

  /**
   * The action of the unexpired container lease on this box's container, or empty. An expired row
   * is pruned on lookup like {@link #findByCredential}'s credential rows.
   */
  private Optional<String> activeLease(String project, String localHandle) {
    var row =
        db.queryOne(
            "SELECT action, created_at FROM container_leases WHERE project = ? AND node = ?",
            r -> new String[] {r.text(0), r.text(1)},
            project,
            ownerKey(localHandle));
    if (row.isEmpty()) {
      return Optional.empty();
    }
    if (Instant.parse(row.get()[1]).plus(LEASE_TTL).isBefore(DateTimeUtils.now())) {
      releaseContainerLease(project, localHandle);
      return Optional.empty();
    }
    return Optional.of(row.get()[0]);
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
   * nothing. An exclusive container lease (a snapshot restore mid-flight, see {@link
   * #acquireContainerLease}) refuses every reservation inside the same transaction, so a run can
   * never start into a container about to be rolled back. Returns the blocking conflict, the held
   * lease, or the reserved run's credential. A run mid-stop ({@code stopping}) still occupies its
   * repos — its agent is not verified dead until the claim is finalized — so it conflicts exactly
   * like a running one. The run is stamped as every run this box executes is ({@link #stamp}), for
   * {@code boxHandle}. Any database failure propagates — a dispatch must never launch without the
   * row every later overlap check depends on. {@code maxDuration} is the run's configured hard stop
   * ({@code guardrails.max_duration}): the credential expires that long plus {@link
   * #CREDENTIAL_GRACE} after minting, and a null means no hard stop, so the credential lives until
   * a verified finisher revokes it.
   */
  public Reservation reserveDispatch(
      String id,
      String project,
      String specId,
      String boxHandle,
      String role,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit) {
    return reserveDispatch(
        id, project, specId, boxHandle, role, repos, agent, branch, task, logPath, unit, null);
  }

  public Reservation reserveDispatch(
      String id,
      String project,
      String specId,
      String boxHandle,
      String role,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit,
      Duration maxDuration) {
    return reserveDispatch(
        id,
        project,
        specId,
        null,
        boxHandle,
        role,
        repos,
        agent,
        branch,
        task,
        logPath,
        unit,
        maxDuration);
  }

  /**
   * Reservation for a chat lane that may serve a room with no spec: {@code roomId} is stored on the
   * run, replicated in its snapshot like the spec it stands in for, and substitutes for the spec in
   * the gate's serialization scope, so two wakes of the same spec-less room still conflict.
   */
  public Reservation reserveDispatch(
      String id,
      String project,
      String specId,
      String roomId,
      String boxHandle,
      String role,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit,
      Duration maxDuration) {
    return reserve(
        id,
        project,
        specId,
        roomId,
        null,
        boxHandle,
        role,
        repos,
        agent,
        branch,
        task,
        logPath,
        unit,
        maxDuration);
  }

  /**
   * Reserves one invocation of the review pipeline — a reviewer or the fix agent that answers it —
   * as its own run naming the review it serves, through the same gate and the same transaction as a
   * dispatch ({@link #reserveDispatch}): a reviewer or fix agent never starts beside a live build
   * of its own spec, a full chat turn or another spec's run over the repos its spec works. Each
   * invocation is a run like any other: its own id, unit, log and principal ({@code
   * <agent>/review-<runId>}, {@code <agent>/fix-<runId>}), so its room posts are attributed to the
   * lane that wrote them and its stop addresses it alone.
   *
   * @param lane {@link Lane#REVIEW} or {@link Lane#FIX}
   */
  public Reservation reserveForReview(
      String id,
      String reviewId,
      String project,
      String specId,
      String boxHandle,
      Lane lane,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit,
      Duration maxDuration) {
    requireServesReview(lane);
    return reserve(
        id,
        project,
        specId,
        null,
        Objects.requireNonNull(reviewId, "reviewId"),
        boxHandle,
        lane.wire(),
        repos,
        agent,
        branch,
        task,
        logPath,
        unit,
        maxDuration);
  }

  private static void requireServesReview(Lane lane) {
    if (!lane.servesReview()) {
      throw new IllegalArgumentException(
          "A run serves a review only in the review or fix lane, not " + lane.wire() + ".");
    }
  }

  private Reservation reserve(
      String id,
      String project,
      String specId,
      String roomId,
      String reviewId,
      String boxHandle,
      String role,
      List<String> repos,
      String agent,
      String branch,
      String task,
      String logPath,
      String unit,
      Duration maxDuration) {
    var reserved = Objects.requireNonNullElse(repos, List.<String>of());
    var node = stamp(boxHandle);
    return db.transaction(
        () -> {
          var lease = activeLease(project, node);
          if (lease.isPresent()) {
            return new Reservation.LeaseHeld(lease.get());
          }
          var conflict =
              DispatchGate.decide(
                  specId != null ? specId : roomId, role, reserved, runningOnNode(project, node));
          if (conflict.isPresent()) {
            return new Reservation.Conflicted(conflict.get());
          }
          db.execute(
              """
              INSERT INTO runs (id, project, spec_id, room_id, review_id, node, role, agent,
                  branch, task, status, started_at, log_path, unit, repos, principal, owner)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'running', ?, ?, ?, ?, ?, ?)""",
              id,
              project,
              specId,
              roomId,
              reviewId,
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
              node);
          recordPrincipal(id, principalHandle(agent, role, id));
          var credential = mintCredential(id, maxDuration);
          recordRevision(id, ChangeLog.Entry.LOCAL, false);
          return new Reservation.Reserved(credential);
        });
  }

  /**
   * What every run a box executes carries as both its {@code node} and its {@code owner}: the box's
   * FDE handle, or null on a box that has none. The one stamp — every reservation, and every
   * re-stamp of a run main has not taken, takes it from here — so a box's runs are its own ({@link
   * RunRow#ownedBy}) and act for its FDE, the run main accepts only from that box.
   */
  private static String stamp(String boxHandle) {
    return Strings.isBlank(boxHandle) ? null : boxHandle;
  }

  /**
   * A run in conversation {@code ?2}: in the room itself, on the spec living in it, or on a spec
   * born in it, which posts there while its runs name only the spec.
   */
  private static final String IN_CONVERSATION =
      "(r.room_id = ?2 OR r.spec_id = ?2 OR r.spec_id IN (SELECT id FROM specs WHERE room_id = ?2))";

  /**
   * A run main holds as this box holds it: taken, with no revision of this box's still to reach it,
   * since main decides what a run's principals wrote by the principals the run names there.
   */
  private static final String HELD_BY_MAIN =
      "(coalesce(r.base_rev, '') <> '' AND coalesce(r.rev, '') = coalesce(r.base_rev, ''))";

  /**
   * A run main holds in a conversation: {@link #HELD_BY_MAIN}, with the spec it belongs to, since
   * main places a run in a spec's conversation through its own copy of that spec, so a run whose
   * spec main has not taken is not yet in any conversation there.
   */
  private static final String HELD_BY_MAIN_IN_CONVERSATION =
      "("
          + HELD_BY_MAIN
          + " AND NOT EXISTS (SELECT 1 FROM specs s"
          + " WHERE s.id = r.spec_id AND coalesce(s.base_rev, '') = ''))";

  /** Whether any run of {@code owner} ran in conversation {@code roomId}, on this box. */
  public boolean ranInConversation(String owner, String roomId) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.owner = ?1 AND " + IN_CONVERSATION + " LIMIT 1",
            row -> true,
            owner,
            roomId)
        .orElse(false);
  }

  /**
   * Whether a run of {@code owner} in conversation {@code roomId} is one main holds ({@code
   * onMain}), or one main has not taken yet: how a node reads what supports a platform post there.
   */
  public boolean ranInConversation(String owner, String roomId, boolean onMain) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.owner = ?1 AND "
                + IN_CONVERSATION
                + " AND "
                + (onMain ? "" : "NOT ")
                + HELD_BY_MAIN_IN_CONVERSATION
                + " LIMIT 1",
            row -> true,
            owner,
            roomId)
        .orElse(false);
  }

  /** Whether main holds run {@code id} as this box holds it, as this node reads it. */
  public boolean heldByMain(String id) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.id = ?1 AND " + HELD_BY_MAIN + " LIMIT 1",
            row -> true,
            id)
        .orElse(false);
  }

  /**
   * Whether main holds run {@code id} in the conversation it runs in, with its spec, as this node
   * reads it: what a post by one of its principals rests on.
   */
  public boolean heldByMainInConversation(String id) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.id = ?1 AND " + HELD_BY_MAIN_IN_CONVERSATION + " LIMIT 1",
            row -> true,
            id)
        .orElse(false);
  }

  /**
   * Every run this box made that main has never acknowledged — it began here ({@link
   * ChangeLog#begunHere}) and has no synced base, so main never took it, or took it and the answer
   * was lost — in the order it was written. A run synced from another box is never among them, even
   * on a box that was main, which records no base for what it holds.
   */
  public List<String> unacknowledged() {
    return madeHere("SELECT id FROM runs WHERE base_rev IS NULL OR base_rev = '' ORDER BY rowid");
  }

  /**
   * Stamps each of {@code ids} as a run the box whose FDE handle is {@code boxHandle} executes
   * ({@link #stamp}), each stamp a revision journaled by {@link Actor#system()}, so the next push
   * carries it. A run already carrying the stamp, or gone, is untouched. Returns the ids stamped.
   */
  public List<String> stamp(String boxHandle, Collection<String> ids) {
    var stamp = stamp(boxHandle);
    return Actor.call(
        Actor.system(),
        () ->
            ids.stream()
                .filter(
                    id ->
                        db.transaction(
                            () -> {
                              var run = findById(id);
                              if (run.isEmpty()
                                  || (Objects.equals(stamp(run.get().node()), stamp)
                                      && Objects.equals(stamp(run.get().owner()), stamp))) {
                                return false;
                              }
                              db.execute(
                                  "UPDATE runs SET node = ?, owner = ? WHERE id = ?",
                                  stamp,
                                  stamp,
                                  id);
                              recordRevision(id, ChangeLog.Entry.LOCAL, false);
                              return true;
                            }))
                .toList());
  }

  /**
   * Stamps every run this box made ({@link ChangeLog#begunHere}) that carries no node as the box's,
   * now that it is main and its FDE handle is {@code boxHandle}: main's own runs are acknowledged
   * as it makes them, so only an unstamped one is not yet its own. Returns the ids stamped.
   */
  public List<String> stampUnstamped(String boxHandle) {
    return stamp(
        boxHandle, madeHere("SELECT id FROM runs WHERE node IS NULL OR node = '' ORDER BY rowid"));
  }

  /**
   * Every run this box executes, by its {@code node} {@code boxHandle}, that acts for no one: an
   * older release reserved some with no owner, and main takes a run only acting for its box's FDE.
   */
  public List<String> ownerless(String boxHandle) {
    if (Strings.isBlank(boxHandle)) {
      return List.of();
    }
    return db.query(
        "SELECT id FROM runs WHERE node = ? AND (owner IS NULL OR owner = '') ORDER BY rowid",
        row -> row.text(0),
        boxHandle);
  }

  private List<String> madeHere(String selectIds) {
    return db.query(selectIds, row -> row.text(0)).stream()
        .filter(id -> changeLog.begunHere(ENTITY, id))
        .toList();
  }

  @Override
  public boolean acknowledge(String id, Map<String, Object> accepted, String rev) {
    return journal.acknowledge(id, accepted, rev);
  }

  /** Every run the box whose FDE handle is {@code handle} executed that is still live there. */
  public List<RunRow> liveUnder(String handle) {
    return allRuns().stream().filter(run -> run.liveOn(handle)).toList();
  }

  /**
   * The runs main holds under {@code handle} — acknowledged, or among {@code held}, the ones main
   * said it took though this box never heard — that are still live here or carry a change main has
   * not taken: what a change of this node's handle away from {@code handle} would strand, since
   * they would stop being this box's to finish and push.
   */
  public List<RunRow> heldUnder(String handle, Set<String> held) {
    var unacknowledged = Set.copyOf(unacknowledged());
    var untaken = dirtyIds();
    return allRuns().stream()
        .filter(run -> run.ownedBy(handle))
        .filter(run -> !unacknowledged.contains(run.id()) || held.contains(run.id()))
        .filter(run -> run.liveOn(handle) || untaken.contains(run.id()))
        .toList();
  }

  private List<RunRow> allRuns() {
    return db.query("SELECT " + COLUMNS + " FROM runs ORDER BY rowid", this::mapRow);
  }

  /**
   * Every run of {@code project} live on this box, in the gate's terms — every role, review and
   * full room turns included, because each one that reserves repos blocks a claim over them. The
   * reservation reads it inside its transaction; {@code sail agent attach} reads it under the
   * project's claim lock to refuse resuming a conversation over repos a live run holds.
   */
  public List<DispatchGate.RunningRun> runningOnNode(String project, String localHandle) {
    return db.query(
        "SELECT id, IFNULL(spec_id, room_id), role, repos FROM runs"
            + " WHERE project = ? AND status IN ('running', 'stopping')"
            + " AND IFNULL(node, '') = ?",
        row ->
            new DispatchGate.RunningRun(
                row.text(0), row.text(1), row.text(2), repoList(row.text(3))),
        project,
        ownerKey(localHandle));
  }

  private static List<String> repoList(String json) {
    return json == null ? List.of() : YamlUtil.parseStringList(json);
  }

  /** Who may write a run on this box: the rule every door and main's commit decide by. */
  @Override
  public RunAuthority authority() {
    return new RunAuthority(db);
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
          recordRevision(id, ChangeLog.Entry.LOCAL, false);
        });
  }

  /**
   * Stamps when the run's agent last showed progress, coalesced to at most one write per {@code
   * floor} window: the write is skipped while the row's {@code last_activity_at} is still within
   * {@code floor} of now, so a continuous {@code agent_log_chunk} stream costs one UPDATE per
   * window instead of one per chunk. Deliberately journals <em>no</em> revision — presence needs
   * ~minute granularity, and a revision per stamp would flood the ChangeLog and fire sync-on-write
   * on every chunk; the value rides along on the run's next real revision instead, so a foreign
   * box's copy is as fresh as the run's own lifecycle, and {@link #latestWinsFields} keeps a stamp
   * that moved on both sides from ever parking a conflict. Only a {@code running} row is stamped: a
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

  /** The run whose current or past principals name {@code principal}, if this box holds one. */
  public Optional<RunRow> byPrincipal(String principal) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE principal = ?1 OR id IN"
            + " (SELECT run_id FROM run_principals WHERE principal = ?1) LIMIT 1",
        this::mapRow,
        principal);
  }

  private void recordPrincipal(String id, String principal) {
    if (Strings.isBlank(principal)) {
      return;
    }
    db.execute(
        "INSERT OR IGNORE INTO run_principals (run_id, principal) VALUES (?, ?)", id, principal);
  }

  private void recordPrincipals(String id, Map<String, Object> snapshot) {
    Snapshots.stringList(snapshot, "principals")
        .forEach(principal -> recordPrincipal(id, principal));
    recordPrincipal(id, Snapshots.text(snapshot, "principal"));
  }

  /**
   * Whether {@code handle} has the shape {@link #principalHandle} mints for a run's principal —
   * {@code <family>/<id>} — which no FDE handle has. How an assignee that names a run rather than
   * an FDE is told apart.
   */
  public static boolean isPrincipalHandle(String handle) {
    return handle != null && handle.contains("/");
  }

  /**
   * Whether {@code principal} names run {@code runId} in the shape {@link #principalHandle} mints —
   * {@code <family>/<marker><run id>}, the marker one of none, {@code review-}, {@code fix-} or
   * {@code room-} — so a run can carry no principal but its own.
   */
  public static boolean namesRun(String principal, String runId) {
    return runId != null && runOf(principal).filter(runId::equals).isPresent();
  }

  /** The run {@code principal} names, when it has the shape {@link #principalHandle} mints. */
  public static Optional<String> runOf(String principal) {
    if (!isPrincipalHandle(principal) || principal.indexOf('/') != principal.lastIndexOf('/')) {
      return Optional.empty();
    }
    var tail = principal.substring(principal.indexOf('/') + 1);
    var marker = PRINCIPAL_MARKERS.stream().filter(tail::startsWith).findFirst().orElse("");
    var runId = tail.substring(marker.length());
    return runId.isEmpty() ? Optional.empty() : Optional.of(runId);
  }

  private static final String REVIEW_MARKER = "review-";
  private static final String FIX_MARKER = "fix-";
  private static final String ROOM_MARKER = "room-";
  private static final List<String> PRINCIPAL_MARKERS =
      List.of(REVIEW_MARKER, FIX_MARKER, ROOM_MARKER);

  /**
   * The run's minted principal handle: the agent family (the yaml name up to its first dash) over
   * the full run id, with review and fix invocations marked as such — {@code claude/<run-uuid>},
   * {@code claude/review-<run-uuid>}, {@code claude/fix-<run-uuid>}. The whole UUID, never a
   * truncation: the handle is a security identity compared in ownership checks and audit rows, so
   * it must be exactly as collision-proof as the run id itself.
   */
  private static String principalHandle(String agent, String role, String id) {
    var family = Objects.toString(agent, "");
    var dash = family.indexOf('-');
    var base = dash > 0 ? family.substring(0, dash) : family;
    var runId = Objects.requireNonNull(id, "run id");
    var marker =
        switch (Lane.of(role).orElse(null)) {
          case REVIEW -> REVIEW_MARKER;
          case FIX -> FIX_MARKER;
          case ROOM -> ROOM_MARKER;
          case null, default -> "";
        };
    return base + "/" + marker + runId;
  }

  private String mintCredential(String id, Duration maxDuration) {
    var credential = Secrets.mint("sailrun");
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
   * The latest run of {@code project} that executed on this box, whichever lane, or empty: the
   * session agent status, log, and report commands read. Ownership is by node: a box with a handle
   * owns exactly the runs stamped with it; a box with no handle owns exactly its own blank-node
   * runs and never a run adopted from another box via sync.
   */
  public Optional<RunRow> latestForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND IFNULL(node, '') = ?"
            + " ORDER BY started_at DESC, id DESC"
            + " LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  /**
   * The active run of {@code project} that executed on this box, whichever lane, or empty. {@code
   * stopping} counts as active: an interrupted stop's claim must stay addressable so a
   * project-targeted stop retry resumes it. Node-scoped like {@link #latestForProjectOnNode}.
   */
  public Optional<RunRow> runningForProjectOnNode(String project, String localHandle) {
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE project = ? AND status IN ('running', 'stopping')"
            + " AND IFNULL(node, '') = ?"
            + " ORDER BY started_at DESC, id DESC LIMIT 1",
        this::mapRow,
        project,
        ownerKey(localHandle));
  }

  /**
   * Every run holding an unfinished stop claim ({@code stopping}) — a stop that recorded its
   * terminal intent but was interrupted before the halt was verified. The reconciler's
   * interrupted-stop pass finalizes these once their unit is gone.
   */
  public List<RunRow> stopping() {
    return db.query("SELECT " + COLUMNS + " FROM runs WHERE status = 'stopping'", this::mapRow);
  }

  /**
   * Every run still in the {@code running} state, across all projects, nodes and lanes — what the
   * watcher re-armer, the missed-stop reconciler, the session reaper and presence each walk. A
   * reviewer and a fix agent are runs like a build: launched as a unit, watched, and reconciled.
   */
  public List<RunRow> running() {
    return db.query("SELECT " + COLUMNS + " FROM runs WHERE status = 'running'", this::mapRow);
  }

  /** The runs that serve {@code reviewId} — its reviewers and its fix agent — newest first. */
  public List<RunRow> forReview(String reviewId) {
    return db.query(
        "SELECT " + COLUMNS + " FROM runs WHERE review_id = ? ORDER BY started_at DESC, id DESC",
        this::mapRow,
        reviewId);
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

  /** Chat runs of a room — spec-less rooms track their turns here. */
  public List<RunRow> listForRoom(String roomId) {
    return db.query(
        "SELECT " + COLUMNS + " FROM runs WHERE room_id = ? ORDER BY started_at DESC, id DESC",
        this::mapRow,
        roomId);
  }

  public List<RunRow> listForSpec(String specId) {
    return db.query(
        "SELECT " + COLUMNS + " FROM runs WHERE spec_id = ? ORDER BY started_at DESC, id DESC",
        this::mapRow,
        specId);
  }

  /**
   * The newest build attempt of {@code specId} — the one run whose stop moves the spec out of
   * {@code in_progress}, which an operator's stop cancels — or empty when it has none.
   */
  public Optional<RunRow> latestBuildAttempt(String specId) {
    return latestInLanes(specId, Lane.BUILD);
  }

  /**
   * The newest run of the review loop for {@code specId} — its build, or the reviewer or fix agent
   * that came after — or empty when it has none: the run whose stop the loop is waiting on, or last
   * acted on. A chat turn in the spec's room is not one of the loop's.
   */
  public Optional<RunRow> latestLoopRun(String specId) {
    return latestInLanes(specId, Lane.BUILD, Lane.REVIEW, Lane.FIX);
  }

  /**
   * The newest run of {@code specId} among {@code lanes}, or empty when it has none. Ties on {@code
   * started_at} break on the UUIDv7 id, which orders by mint time.
   */
  private Optional<RunRow> latestInLanes(String specId, Lane... lanes) {
    var parameters = new ArrayList<Object>();
    parameters.add(specId);
    Arrays.stream(lanes).map(Lane::wire).forEach(parameters::add);
    return db.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM runs WHERE spec_id = ? AND role IN ("
            + String.join(", ", Arrays.stream(lanes).map(lane -> "?").toList())
            + ") ORDER BY started_at DESC, id DESC LIMIT 1",
        this::mapRow,
        parameters.toArray());
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
    db.transaction(
        () -> {
          alongside.run();
          complete(id, status, exitCode);
          return null;
        });
  }

  /**
   * Status transition that commits only if the run still holds {@code expected}. Returns whether
   * the transition happened; a false return wrote nothing. A terminal target status stamps {@code
   * completed_at}; a non-terminal one clears it.
   */
  public boolean transition(String id, String expected, String status) {
    return transition(id, expected, status, null, () -> {});
  }

  /**
   * An operator's stop claim: {@code running → status} as {@link #transition(String, String,
   * String)} commits it, marking in the same revision that an operator ended the run and running
   * {@code alongside} inside the same immediate transaction once the claim has won — the seam a
   * stop uses to move its spec terminal in the very same commit. A lost claim wrote nothing and
   * never ran {@code alongside}, and an {@code alongside} that throws rolls the claim back. {@code
   * status} is {@code stopping} while the halt is under way, or {@code stopped} for a run with no
   * process left to halt. The mark outlives the claim's finalization, so a stop of the same run
   * that arrives afterwards still reads as the operator's.
   */
  public boolean claimStop(String id, String status, Runnable alongside) {
    return transition(
        id,
        "running",
        status,
        null,
        () -> {
          markStopSource(id, STOPPED_BY_OPERATOR);
          alongside.run();
        });
  }

  /**
   * Gives back a stop claim whose halt failed: {@code stopping → running}, the operator's mark
   * cleared in the same revision and {@code alongside} run in the same transaction, so the run is
   * again one that may end on its own.
   */
  public boolean releaseStop(String id, Runnable alongside) {
    return transition(
        id,
        "stopping",
        "running",
        null,
        () -> {
          markStopSource(id, null);
          alongside.run();
        });
  }

  private void markStopSource(String id, String stopSource) {
    db.execute("UPDATE runs SET stop_source = ? WHERE id = ?", stopSource, id);
  }

  /**
   * As {@link #transition(String, String, String)}, also stamping {@code exitCode} (when non-null)
   * in the same UPDATE, so a finisher that knows the process outcome commits the terminal status
   * and its exit code as one atomic, single-revision write — a crash or a sync push can never
   * observe the terminal status without its exit code.
   */
  public boolean transition(String id, String expected, String status, Integer exitCode) {
    return transition(id, expected, status, exitCode, () -> {});
  }

  private boolean transition(
      String id, String expected, String status, Integer exitCode, Runnable alongside) {
    return db.transaction(
        () -> {
          db.execute(
              "UPDATE runs SET status = ?, completed_at = ?, exit_code = COALESCE(?, exit_code)"
                  + " WHERE id = ? AND status = ?",
              status,
              RunStatus.isTerminal(status) ? DateTimeUtils.now().toString() : null,
              exitCode != null ? exitCode.longValue() : null,
              id,
              expected);
          if (db.changes() == 0) {
            return false;
          }
          if (RunStatus.isTerminal(status)) {
            revokeCredential(id);
          }
          alongside.run();
          recordRevision(id, ChangeLog.Entry.LOCAL, false);
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
    return db.transaction(
        () -> {
          if (latestBuildAttempt(specId).filter(latest -> latest.id().equals(id)).isEmpty()) {
            return false;
          }
          work.run();
          return true;
        });
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
          revokeCredential(id);
          recordRevision(id, ChangeLog.Entry.LOCAL, false);
        });
  }

  /**
   * Stamps the agent process identity and watcher pid on a run once launch has produced them. The
   * run row is created before launch (so terminal hook events can find it), then updated here with
   * what the launch resolved. {@code pidTicks} is the agent process's {@code /proc} start-time
   * fingerprint — pids are reused by the kernel, so the pid alone can later name an unrelated
   * process, and the stop lane refuses to signal a pid whose fingerprint no longer matches. The
   * process identity is this box's own bookkeeping ({@code LOCAL_FIELDS}), never synced, so it
   * journals no revision: one would name a version of the run main never holds.
   *
   * <p>Commits only while the run is still {@code running} and returns whether it did. A stop that
   * lands during launch preparation records its terminal intent on the row; the launcher discovers
   * that loss here and must tear down whatever it just started instead of letting an unrecorded
   * agent escape the reservation. {@code BEGIN IMMEDIATE} closes the read-then-write window against
   * the stop's own claim transaction.
   */
  public boolean updateProcess(String id, Integer pid, Long pidTicks, Integer watcherPid) {
    return db.transaction(
        () -> {
          db.execute(
              "UPDATE runs SET pid = ?, pid_ticks = ?, watcher_pid = ? WHERE id = ?"
                  + " AND status = 'running'",
              pid != null ? pid.longValue() : null,
              pidTicks,
              watcherPid != null ? watcherPid.longValue() : null,
              id);
          return db.changes() > 0;
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
          recordRevision(id, ChangeLog.Entry.LOCAL, false);
        });
  }

  @Override
  public String entityType() {
    return ENTITY;
  }

  @Override
  public Set<String> latestWinsFields() {
    return journal.latestWinsFields();
  }

  @Override
  public Map<String, Object> currentForSync(String id) {
    return journal.currentForSync(id);
  }

  @Override
  public Optional<String> liveBase(String id) {
    return journal.liveBase(id);
  }

  /**
   * Whether the box whose FDE handle is {@code handle} may push its own change to run {@code id} up
   * to main: runs are single-writer, so only a run that box executed ({@link RunRow#ownedBy}). A
   * run this box no longer holds defers to main's own rule.
   */
  @Override
  public boolean mayPush(String id, String handle) {
    return findById(id).map(run -> run.ownedBy(handle)).orElse(true);
  }

  /**
   * A run the box whose FDE handle is {@code handle} executed ({@link RunRow#ownedBy}) that has not
   * finished is live there: its row, credential and room guard are what the agent and its watcher
   * act through, so main's version never rewrites or removes them. Another box's run is never live
   * here, so it is adopted as main holds it.
   */
  @Override
  public boolean live(String id, String handle) {
    return findById(id).filter(run -> run.liveOn(handle)).isPresent();
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return journal.comparableSnapshot(id);
  }

  public Map<String, Object> comparableAtRev(String id, String rev) {
    return journal.comparableAtRev(id, rev);
  }

  public String latestRev(String id) {
    return journal.latestRev(id);
  }

  public String baseRevOf(String id) {
    return journal.baseRevOf(id);
  }

  public Set<String> syncEntityIds() {
    return journal.entityIds();
  }

  public Set<String> dirtyIds() {
    return journal.dirtyIds();
  }

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    journal.applyRevision(id, snapshot, rev);
  }

  /**
   * Adopts main's run. Main holding none of it — no snapshot and no rev — is how a run main denied
   * leaves this box, and the posts its principals made that main never took go with it: main holds
   * no run to decide them by, so they could never land.
   */
  @Override
  public void adoptForSync(String id, Map<String, Object> snapshot, String rev) {
    var orphaned = snapshot == null && rev == null ? principalsOf(id) : List.<String>of();
    applyRevision(id, snapshot, rev);
    new MessageStore(db).withdrawUnsynced(orphaned);
  }

  /** The principals of run {@code id} that name the run itself, the only ones its posts carry. */
  private List<String> principalsOf(String id) {
    var principals = new LinkedHashSet<>(principals(id));
    findById(id).map(RunRow::principal).ifPresent(principals::add);
    return principals.stream().filter(principal -> namesRun(principal, id)).toList();
  }

  /** Removes an erased run's row, with the box-local credential it was issued. */
  @Override
  public void eraseRow(String id) {
    db.execute("DELETE FROM run_credentials WHERE run_id = ?", id);
    journal.eraseRow(id);
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  @Override
  public PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority) {
    return journal.commitRevision(id, snapshot, expectedRev, authority);
  }

  /** Resolves an open conflict through the shared {@link RevisionJournal#resolveConflict}. */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, MainVersion theirs) {
    return journal.resolveConflict(id, chosen, theirs);
  }

  String recordRevision(String id, String origin, boolean deleted) {
    return journal.recordRevision(id, origin, deleted);
  }

  private void writeRow(RunRow row) {
    db.execute(
        """
        INSERT INTO runs (id, project, spec_id, node, role, agent, branch, task, pid, watcher_pid,
            status, exit_code, log_path, unit, started_at, completed_at, repos, pid_ticks,
            principal, owner, session_id, session_source, transcript_path, last_activity_at,
            room_id, review_id, stop_source)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            last_activity_at = excluded.last_activity_at, room_id = excluded.room_id,
            review_id = excluded.review_id, stop_source = excluded.stop_source""",
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
        row.lastActivityAt(),
        row.roomId(),
        row.reviewId(),
        row.stopSource());
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
    map.put("room_id", run.roomId());
    map.put("review_id", run.reviewId());
    map.put("stop_source", run.stopSource());
    return map;
  }

  /**
   * The executing box's process bookkeeping: only that box can act on it, and it rewrites it while
   * the run lives. It never crosses the wire, and an adoption never touches the local values.
   */
  private static final Set<String> LOCAL_FIELDS =
      Set.of("pid", "watcher_pid", "pid_ticks", "log_path", "transcript_path");

  private static final Set<String> LATEST_WINS_FIELDS = Set.of("last_activity_at");

  private static final Set<String> SYNC_FIELDS =
      Set.of(
          "project",
          "spec_id",
          "node",
          "role",
          "agent",
          "branch",
          "task",
          "status",
          "exit_code",
          "unit",
          "started_at",
          "completed_at",
          "repos",
          "principal",
          "owner",
          "session_id",
          "session_source",
          "last_activity_at",
          "room_id",
          "review_id",
          "stop_source",
          "principals");

  /**
   * The subset of a snapshot that carries the run's meaning across boxes. The surrogate id is out
   * because every replica keys on it independently, and so is {@link #LOCAL_FIELDS}: carrying it
   * would make a liveness bump a spurious change to push, or a conflict to park.
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
    return m;
  }

  private static RunRow rowFrom(String id, Map<String, Object> snapshot) {
    return new RunRow(
        Ids.requireUuid(id),
        Snapshots.text(snapshot, "project"),
        Snapshots.text(snapshot, "spec_id"),
        Snapshots.text(snapshot, "node"),
        Snapshots.text(snapshot, "role"),
        Snapshots.text(snapshot, "agent"),
        Snapshots.text(snapshot, "branch"),
        Snapshots.text(snapshot, "task"),
        Snapshots.integer(snapshot, "pid"),
        Snapshots.integer(snapshot, "watcher_pid"),
        Snapshots.text(snapshot, "status"),
        Snapshots.integer(snapshot, "exit_code"),
        Snapshots.text(snapshot, "log_path"),
        Snapshots.text(snapshot, "unit"),
        Snapshots.text(snapshot, "started_at"),
        Snapshots.text(snapshot, "completed_at"),
        Snapshots.stringList(snapshot, "repos"),
        Snapshots.longValue(snapshot, "pid_ticks"),
        Snapshots.text(snapshot, "principal"),
        Snapshots.text(snapshot, "owner"),
        Snapshots.text(snapshot, "session_id"),
        Snapshots.text(snapshot, "session_source"),
        Snapshots.text(snapshot, "transcript_path"),
        Snapshots.text(snapshot, "last_activity_at"),
        Snapshots.text(snapshot, "room_id"),
        Snapshots.text(snapshot, "review_id"),
        Snapshots.text(snapshot, "stop_source"));
  }

  /** The run's store-specific half of the shared {@link RevisionJournal} sync protocol. */
  private final class RunSchema implements EntitySchema {

    /**
     * The heartbeat is stamped without a revision ({@link #stampActivity}), so the live row runs
     * ahead of what main last heard, and a round main acknowledged but this box never recorded
     * leaves both sides holding different stamps over one base. Two readings of a clock are not a
     * decision: the later one wins.
     */
    @Override
    public Set<String> latestWinsFields() {
      return LATEST_WINS_FIELDS;
    }

    @Override
    public String entityType() {
      return ENTITY;
    }

    @Override
    public String table() {
      return "runs";
    }

    @Override
    public boolean exists(String id) {
      return findById(id).isPresent();
    }

    @Override
    public Map<String, Object> snapshotMap(String id) {
      return findById(id)
          .map(
              run -> {
                var map = RunStore.snapshotMap(run);
                map.put("principals", principals(id));
                return map;
              })
          .orElse(null);
    }

    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      var applied = new LinkedHashMap<>(snapshot);
      findById(id)
          .map(RunStore::snapshotMap)
          .ifPresent(local -> LOCAL_FIELDS.forEach(field -> applied.put(field, local.get(field))));
      writeRow(rowFrom(id, applied));
      recordPrincipals(id, applied);
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      return RunStore.comparable(full);
    }

    @Override
    public void deleteRow(String id) {
      revokeCredential(id);
      db.execute("DELETE FROM runs WHERE id = ?", id);
    }
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
        row.text(23),
        row.text(24),
        row.text(25),
        row.text(26));
  }
}
