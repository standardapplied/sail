/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentPresence;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.RunRetention;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
 * on a repo a dispatch is about to work. The claim and the yield run under the seam's per-project
 * lock, the same lock an attach holds from reading the run rows to creating its session, so an
 * attach can never slip a resume session in between the scan and the launch.
 */
public final class RunReservation {

  private final RunStore runStore;
  private final ShellExec shell;
  private final DispatchOperations.Listener listener;
  private final SessionYield sessionYield;

  public RunReservation(
      RunStore runStore,
      ShellExec shell,
      DispatchOperations.Listener listener,
      SessionYield sessionYield) {
    this.runStore = runStore;
    this.shell = shell;
    this.listener = listener;
    this.sessionYield = Objects.requireNonNull(sessionYield, "sessionYield");
  }

  /**
   * Atomically reserves the launch as a {@code running} run of this box, whose FDE handle is {@code
   * boxHandle} — stamped as every run a box executes is, its handle as both node and owner — with
   * the target repo set: {@link RunStore#reserveDispatch} checks every running local run for a repo
   * overlap and inserts the row in one transaction, so two concurrent launches can never both claim
   * the same repo. A conflict or a store failure aborts before any launch — the row is what every
   * later overlap check and provenance guard depends on. Also prunes the container's oldest run-log
   * directories (best-effort). Returns the run's plaintext bearer credential.
   */
  public String reserve(
      String runId,
      String project,
      String specId,
      String boxHandle,
      String role,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    return reserve(
        runId, project, specId, null, boxHandle, role, repos, agentType, branch, task, unit, config,
        () -> {});
  }

  /** Reservation variant for chat lanes that may serve a spec-less room. */
  public String reserve(
      String runId,
      String project,
      String specId,
      String roomId,
      String boxHandle,
      String role,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    return reserve(
        runId, project, specId, roomId, boxHandle, role, repos, agentType, branch, task, unit,
        config, () -> {});
  }

  /**
   * As {@link #reserve(String, String, String, String, String, String, List, String, String,
   * String, AgentUnit, SailYaml)}, committing {@code alongside} with the reservation: a dispatch's
   * claim of its spec lands with its run or not at all.
   */
  public String reserve(
      String runId,
      String project,
      String specId,
      String roomId,
      String boxHandle,
      String role,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config,
      Runnable alongside) {
    return switch (claim(
        runId,
        project,
        specId,
        role,
        repos,
        () ->
            runStore.reserveDispatch(
                runId,
                project,
                specId,
                roomId,
                boxHandle,
                role,
                repos,
                agentType,
                branch,
                task,
                unit.logPath(),
                unit.unitName(),
                configuredMaxDuration(config, role),
                alongside))) {
      case Claim.Claimed claimed -> claimed.credential();
      case Claim.Held held -> throw overlapRefusal(held.conflict());
    };
  }

  /** How a claim on a spec's repos was answered. */
  public sealed interface Claim {

    /** The run is reserved; {@code credential} is its plaintext bearer credential. */
    record Claimed(String credential) implements Claim {}

    /** Nothing was reserved: the run {@code conflict} names holds what this one would claim. */
    record Held(DispatchGate.Conflict conflict) implements Claim {}
  }

  /**
   * Reserves a reviewer's or a fix agent's run, naming the review it serves, through the same gate
   * a dispatch passes: the run claims its spec's repos, so it never starts beside a live build of
   * its own spec, a full chat turn or another spec's run over them, and a conversation resumed over
   * them yields as it does to a build. A claim another run holds is an answer, not a failure:
   * {@link Claim.Held} names the run in the way, which is what the review then waits on.
   */
  public Claim reserveForReview(
      String runId,
      String reviewId,
      String project,
      String specId,
      String boxHandle,
      Lane lane,
      List<String> repos,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    return claim(
        runId,
        project,
        specId,
        lane.wire(),
        repos,
        () ->
            runStore.reserveForReview(
                runId,
                reviewId,
                project,
                specId,
                boxHandle,
                lane,
                repos,
                agentType,
                branch,
                task,
                unit.logPath(),
                unit.unitName(),
                configuredMaxDuration(config, lane.wire())));
  }

  /**
   * The one claim every lane makes: under the project's claim lock, the reservation is decided and
   * its row written in one transaction, and the conversations a claim that landed displaces yield.
   * A claim a run holds reserves nothing and displaces no one.
   */
  private Claim claim(
      String runId,
      String project,
      String specId,
      String role,
      List<String> repos,
      Supplier<RunStore.Reservation> reservation) {
    Claim claim;
    try (var hold = sessionYield.lock(project)) {
      claim = claimOf(reservation);
      whenClaimed(claim, () -> yieldDisplacedSessions(runId, project, specId, role, repos));
    } catch (IOException e) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED, "Could not lock project '" + project + "' to dispatch.", e);
    }
    whenClaimed(claim, () -> pruneRuns(project));
    return claim;
  }

  private static void whenClaimed(Claim claim, Runnable then) {
    switch (claim) {
      case Claim.Claimed claimed -> then.run();
      case Claim.Held held -> {}
    }
  }

  private Claim claimOf(Supplier<RunStore.Reservation> reservation) {
    RunStore.Reservation reserved;
    try {
      reserved = reservation.get();
    } catch (WriteRefused refused) {
      throw Refusals.exception(refused.refusal());
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Failed to record the dispatch run.", e);
    }
    return switch (reserved) {
      case RunStore.Reservation.Reserved landed -> new Claim.Claimed(landed.credential());
      case RunStore.Reservation.Conflicted conflicted -> new Claim.Held(conflicted.conflict());
      case RunStore.Reservation.LeaseHeld held -> throw leaseRefusal(held);
    };
  }

  /**
   * Ends the resumed conversations this claim displaces: a run's resume session is judged as if the
   * run were still running a working lane over its repos, so a read-only room wake displaces
   * nothing while a build or full turn over an overlapping repo (or the same spec) ends it.
   * Best-effort after the claim, like pruning: the reservation stands either way, and a host that
   * refuses or cannot be reached is a warning, never a failed launch.
   */
  private void yieldDisplacedSessions(
      String runId, String project, String specId, String role, List<String> repos) {
    var target = Objects.requireNonNullElse(repos, List.<String>of());
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
      sessionYield.end(displaced, reason);
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
   * session. An agent the container gives no answer about ({@link AgentPresence}) is treated as
   * live for the same reason — the missed-stop reconciler releases a genuinely dead run on its next
   * pass.
   */
  public void releaseIfAbsent(String runId, String project, AgentUnit unit) {
    if (agentLive(project, unit)) {
      return;
    }
    failRun(runId);
  }

  /**
   * The run's configured hard lifetime, bounding its credential ({@link
   * SailYaml.Agent#lifetimeFor}), or null when none bounds it — an unbounded run's credential is
   * revoked by its verified finishers.
   */
  private static Duration configuredMaxDuration(SailYaml config, String role) {
    var agent = config.agent();
    return agent == null ? null : agent.lifetimeFor(Lane.of(role).orElse(null));
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
      return !new AgentPresence(shell).gone(project, unit);
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
            ? "Ad-hoc agent run " + run.runId() + " is occupying this container."
            : "Agent run "
                + run.runId()
                + " is already working spec '"
                + run.specId()
                + "' in "
                + (conflict.overlap().isEmpty()
                    ? "this container"
                    : "repo(s) " + conflict.overlap())
                + ".";
    return new ApiException(
        ErrorCode.AGENT_ALREADY_RUNNING,
        occupied,
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
