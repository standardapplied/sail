/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.RunRetention;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The shared reservation and launch-failure cleanup every launching lane runs. {@link #reserve}
 * atomically claims the container (and, for a build, its target repos) as a {@code running} run row
 * in one {@code BEGIN IMMEDIATE} transaction — so two concurrent launches, even from separate
 * processes, can never both claim the same repos — and returns the run's bearer credential. {@link
 * #releaseIfAbsent} is the mirror: on a launch failure it frees the reservation, but only once the
 * agent is proven absent, so a failure that races a live process never pulls the repo out from
 * under it. Kept in one place so the concurrency guarantee has a single definition across lanes.
 *
 * <p>Host-owned terminal sessions respect the same gate: once a claim lands, every resumed agent
 * conversation ({@link SessionYield#resumeSession}) whose run the new claim would have conflicted
 * with — by exactly the {@link DispatchGate} rules, the conversation holding its run's repos like a
 * working lane — is ended through the {@link SessionYield} seam, so no interactive agent ever sits
 * on a repo a dispatch is about to work.
 */
public final class RunReservation {

  private final RunStore runStore;
  private final ShellExec shell;
  private final DispatchOperations.Listener listener;
  private final Supplier<SessionYield> sessionYield;

  public RunReservation(
      RunStore runStore,
      ShellExec shell,
      DispatchOperations.Listener listener,
      Supplier<SessionYield> sessionYield) {
    this.runStore = runStore;
    this.shell = shell;
    this.listener = listener;
    this.sessionYield = sessionYield;
  }

  /**
   * Atomically reserves the launch as a {@code running} run stamped with {@code owner} and the
   * target repo set: {@link RunStore#reserveDispatch} checks every running local run for a repo
   * overlap and inserts the row in one transaction, so two concurrent launches can never both claim
   * the same repo. A conflict or a store failure aborts before any launch — the row is what every
   * later overlap check and provenance guard depends on. Also prunes the container's oldest run-log
   * directories (best-effort). Returns the run's plaintext bearer credential.
   */
  public String reserve(
      String runId,
      String project,
      String specId,
      String node,
      String owner,
      String role,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    return reserve(
        runId, project, specId, null, node, owner, role, repos, agentType, branch, task, unit,
        config);
  }

  /** Reservation variant for chat lanes that may serve a spec-less room. */
  public String reserve(
      String runId,
      String project,
      String specId,
      String roomId,
      String node,
      String owner,
      String role,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    RunStore.Reservation reservation;
    try {
      reservation =
          runStore.reserveDispatch(
              runId,
              project,
              specId,
              roomId,
              node,
              owner,
              role,
              repos,
              agentType,
              branch,
              task,
              unit.logPath(),
              unit.unitName(),
              configuredMaxDuration(config));
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Failed to record the dispatch run.", e);
    }
    if (reservation instanceof RunStore.Reservation.Conflicted conflicted) {
      throw overlapRefusal(conflicted.conflict());
    }
    if (reservation instanceof RunStore.Reservation.LeaseHeld held) {
      throw leaseRefusal(held);
    }
    yieldDisplacedSessions(runId, project, specId, role, repos);
    pruneRuns(project);
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  /**
   * Ends the resumed conversations this claim displaces: a run's resume session is judged as if the
   * run were still running a working lane over its repos, so a read-only room wake displaces
   * nothing while a build, full turn, or invite over an overlapping repo (or the same spec) ends
   * it. Best-effort after the claim, like pruning: the reservation stands either way, and a host
   * that refuses or cannot be reached is a warning, never a failed launch.
   */
  private void yieldDisplacedSessions(
      String runId, String project, String specId, String role, List<String> repos) {
    var target = repos == null ? List.<String>of() : repos;
    var displaced =
        runStore.listForProject(project).stream()
            .filter(run -> !run.id().equals(runId))
            .filter(run -> displaces(specId, role, target, run))
            .map(run -> SessionYield.resumeSession(run.id()))
            .toList();
    if (displaced.isEmpty()) {
      return;
    }
    var reason =
        "yielded to dispatch " + runId + (Strings.isBlank(specId) ? "" : " of spec " + specId);
    try {
      sessionYield.get().end(displaced, reason);
    } catch (IOException e) {
      System.err.println(
          "  [api] Warning: could not yield terminal sessions "
              + displaced
              + ": "
              + e.getMessage());
    }
  }

  static boolean displaces(
      String specId, String role, List<String> repos, RunStore.RunRow resumed) {
    var conversation =
        new DispatchGate.RunningRun(
            resumed.id(), resumed.specId(), Lane.BUILD.wire(), resumed.repos());
    return DispatchGate.decide(specId, role, repos, List.of(conversation)).isPresent();
  }

  /**
   * Releases the run's repo reservation on a launch failure only when the agent is proven absent —
   * probed on the run's own identity (systemd unit and run-scoped pid file), so the check covers
   * background and foreground launches alike. A failure before or during launch leaves no live
   * process, so the run is failed and its repo freed. But once the agent process exists — a
   * background unit that started, a foreground child whose blocking wait threw — a later failure
   * leaves a live agent, and failing the run would free the repo under it and admit an overlapping
   * session. An unprobeable identity is treated as live for the same reason — the missed-stop
   * reconciler releases a genuinely dead run on its next pass.
   */
  public void releaseIfAbsent(String runId, String project, AgentUnit unit) {
    if (agentLive(project, unit)) {
      return;
    }
    failRun(runId);
  }

  /**
   * The run's configured hard lifetime, bounding its credential: {@code guardrails.max_duration},
   * or null when unset — an unbounded run's credential is revoked by its verified finishers, never
   * by a clock that could expire mid-work.
   */
  private static Duration configuredMaxDuration(SailYaml config) {
    var agent = config.agent();
    if (agent == null || agent.guardrails() == null) {
      return null;
    }
    return Guardrails.parseDuration(agent.guardrails().maxDuration());
  }

  private void pruneRuns(String project) {
    try {
      var runs = runStore.listForProject(project);
      var ids = runs.stream().map(RunStore.RunRow::id).toList();
      var active =
          runs.stream()
              .filter(DispatchOperations::ownsLiveAgent)
              .map(RunStore.RunRow::id)
              .collect(Collectors.toUnmodifiableSet());
      var pruned = RunRetention.prune(shell, project, ids, active, RunRetention.DEFAULT_KEEP);
      listener.runsPruned(pruned.size());
    } catch (Exception e) {
      System.err.println("  [api] Warning: could not prune runs: " + e.getMessage());
    }
  }

  /**
   * Fails a run on a launch error, but only if the run is still {@code running}: a stop that
   * cancelled the run mid-launch, or a watcher that already recorded the real exit, owns the
   * terminal record and must not be overwritten by the launch's cleanup.
   */
  private void failRun(String runId) {
    runBookkeeping(
        "mark run failed " + runId,
        () -> runStore.transition(runId, "running", "failed", (Integer) null));
  }

  private boolean agentLive(String project, AgentUnit unit) {
    try {
      var status = new AgentSession(shell).queryStatus(project, unit);
      return status != null && status.running();
    } catch (Exception e) {
      return true;
    }
  }

  /**
   * Runs a best-effort run-store bookkeeping update. A store error is logged but never propagated:
   * bookkeeping must never fail a launch or mask the agent's real outcome.
   */
  private void runBookkeeping(String action, Runnable op) {
    if (runStore == null) {
      return;
    }
    try {
      op.run();
    } catch (RuntimeException e) {
      System.err.println("  [api] Warning: could not " + action + ": " + e.getMessage());
    }
  }

  /**
   * The refusal when a running local run already reserves an overlapping repo set — the dispatch
   * gate's verdict rendered for a client. Package-static because the dispatch dry lane's read-only
   * overlap check surfaces the same refusal without reserving.
   */
  static ApiException overlapRefusal(DispatchGate.Conflict conflict) {
    var run = conflict.run();
    var occupied =
        Strings.isBlank(run.specId())
            ? "Ad-hoc agent run " + run.runId() + " is occupying this container"
            : "Agent run "
                + run.runId()
                + " is already working spec '"
                + run.specId()
                + "' in "
                + (conflict.overlap().isEmpty()
                    ? "this container"
                    : "repo(s) " + conflict.overlap());
    return new ApiException(
        ErrorCode.AGENT_ALREADY_RUNNING,
        occupied + ".",
        "Wait for it to finish or stop it, or dispatch a spec targeting disjoint repos.");
  }

  /**
   * The refusal when an exclusive container operation (a snapshot restore) holds the container: no
   * run of any role may start into a container that is about to be rolled back.
   */
  private static ApiException leaseRefusal(RunStore.Reservation.LeaseHeld held) {
    return new ApiException(
        ErrorCode.CONFLICT,
        "A snapshot " + held.action() + " is in progress in this container.",
        "Wait for its snapshot_restored event, then retry.");
  }
}
