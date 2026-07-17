/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * The single clean-stop executor behind every lane, a peer of {@link DispatchOperations} built the
 * same way: the procedure — resolve the active owned run, record the terminal intent, halt the
 * process — lives here exactly once, and only the seams that differ per lane are injected (where
 * events go, how the kill runs, what the operator sees). {@code sail agent stop} and {@code POST
 * /v1/runs/{id}/stop} both delegate here and add nothing but rendering.
 *
 * <p>The order is the whole point: one transaction — the stop claim — moves the run {@code running
 * → stopping} and the spec to {@link SpecStatus#CANCELLED} <em>before</em> the process is
 * signalled, so every existing guard makes the cancel win the race with the watcher's own stop.
 * {@link RunTracker} only stamps a run still {@code running}; the review pipeline only acts on a
 * reviewable spec; the missed-stop reconciler sweeps only {@code in_progress} and {@code review};
 * dispatch selects only {@code pending}. {@link SpecStatus#CANCELLED} sits outside all of those
 * sets, so a deliberate stop is honored by the existing contracts with no new special cases. Both
 * claim writes are compare-and-set from the freshly resolved states, so a lifecycle transition that
 * lands first fails the claim with a conflict instead of being silently overwritten. The rest of
 * the terminal intent — run {@code stopped}, an {@link Event.WellKnownTypes#AGENT_CANCELLED} event
 * — is finalized only once the halt is verified to have left no live process on the run's unit: a
 * kill that fails restores the claim and throws, and a crash mid-stop leaves the explicit {@code
 * stopping} claim — resumed by the next stop of the same target, finalized by the reconciler's
 * interrupted-stop pass once the unit is gone — so the database never claims a live agent is
 * terminal in a state no reconciler owns.
 *
 * <p>The lane is also the clean way out of a stranded spec, not only a process kill: a resolved run
 * whose agent already died still gets its intent recorded atomically (spec cancelled, a
 * still-{@code running} run released as {@code stopped}, in one transaction). A pid mismatch is a
 * structured no-op — stopping a stale run id can never kill a different, newer run — a terminal run
 * that is no longer its spec's latest attempt is {@link AlreadyTerminal} rather than a lever to
 * cancel newer work, and a second stop of the same run is idempotent by construction, returning
 * {@link AlreadyTerminal} without signalling anything.
 */
public final class StopOperations {

  /** The run status a committed stop claim holds between the claim and its verified finish. */
  static final String STOPPING = "stopping";

  /** What a stop should act on: one project's active run (CLI) or an exact run id (API). */
  public sealed interface Target permits RunTarget, ProjectTarget {}

  /** Stop the exact run {@code runId}, the API lane's address. */
  public record RunTarget(String runId) implements Target {}

  /** Stop {@code project}'s active run — or its ad-hoc session — the CLI lane's address. */
  public record ProjectTarget(String project) implements Target {}

  /** What a stop produced; each lane renders it without duplicating any decision. */
  public sealed interface Outcome permits Stopped, NotRunning, AlreadyTerminal, NotActive {
    /** Whether this stop wrote anything — a halted agent, a cancelled spec, a released run. */
    default boolean mutated() {
      return switch (this) {
        case Stopped ignored -> true;
        case NotRunning notRunning -> notRunning.specCancelled() || notRunning.runReleased();
        case AlreadyTerminal ignored -> false;
        case NotActive ignored -> false;
      };
    }
  }

  /**
   * The live agent was halted after its terminal intent was recorded. {@code runId} and {@code
   * specId} are null for an ad-hoc session that minted no run; {@code specCancelled} reports
   * whether the spec moved to {@link SpecStatus#CANCELLED}.
   */
  public record Stopped(String runId, String specId, Integer pid, boolean specCancelled)
      implements Outcome {}

  /**
   * No live agent process was found for the target, so nothing was signalled — but any stranded
   * state was still cancelled: {@code specCancelled} reports a spec rescued out of {@code
   * in_progress}/{@code review}, and {@code runReleased} a still-{@code running} run row released
   * as {@code stopped}. All-false means there was simply nothing to stop.
   */
  public record NotRunning(String runId, String specId, boolean specCancelled, boolean runReleased)
      implements Outcome {}

  /** The run and its spec are already terminal — a repeated stop, a pure no-op. */
  public record AlreadyTerminal(String runId, String specId, String runStatus) implements Outcome {}

  /**
   * The live agent on the run's unit is not this run's process ({@code livePid} differs from the
   * run's recorded pid), so nothing was killed and nothing was written.
   */
  public record NotActive(String runId, String specId, Integer livePid) implements Outcome {}

  /** How the kill runs — the only side effect that differs from a store write. */
  @FunctionalInterface
  public interface AgentHalter {
    void halt(String project, AgentUnit unit) throws Exception;
  }

  /** The real halter: {@link AgentSession#killAgent} — SIGTERM, wait, SIGKILL, pid-file cleanup. */
  public static AgentHalter sessionHalter(ShellExec shell) {
    var session = new AgentSession(shell);
    return session::killAgent;
  }

  /**
   * Presentation hook fired with the kill about to be issued (also on a dry run, which stops
   * there), so an interactive lane can narrate without the executor knowing about terminals.
   */
  public interface Listener {
    Listener NONE = new Listener() {};

    default void halting(String project, String unit, Integer pid) {}
  }

  private final ShellExec shell;
  private final ProjectLoader projects;
  private final SpecStore specStore;
  private final RunStore runStore;
  private final DispatchOperations.EventSink events;
  private final AgentHalter halter;
  private final Listener listener;

  public StopOperations(
      ShellExec shell,
      String file,
      SpecStore specStore,
      RunStore runStore,
      DispatchOperations.EventSink events,
      AgentHalter halter,
      Listener listener) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.projects = new ProjectLoader(shell, Objects.requireNonNull(file, "file"));
    this.specStore = Objects.requireNonNull(specStore, "specStore");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.events = Objects.requireNonNull(events, "events");
    this.halter = Objects.requireNonNull(halter, "halter");
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /**
   * Executes one clean stop. Refusals throw {@link ApiException} with a structured code before any
   * mutation: an unknown run is a 404, a run that executed elsewhere a {@code run_on_other_node},
   * and a caller who is neither the run's spec assignee nor an admin a {@code
   * forbidden_not_assignee} (the same {@link RunPolicy} the log routes enforce). A dry run resolves
   * and probes but writes and signals nothing, returning the outcome it would produce.
   */
  public Outcome stop(Target target, Actor actor, String localHandle, boolean dryRun) {
    return switch (target) {
      case RunTarget run -> stopRun(run.runId(), actor, localHandle, dryRun);
      case ProjectTarget project -> stopProject(project.project(), actor, localHandle, dryRun);
    };
  }

  private Outcome stopRun(String runId, Actor actor, String localHandle, boolean dryRun) {
    var run =
        runStore
            .findById(runId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'."));
    if (!SailOperations.ownsRun(run.node(), localHandle)) {
      var node = Strings.isBlank(run.node()) ? "an unknown node" : run.node();
      throw new ApiException(
          ErrorCode.RUN_ON_OTHER_NODE,
          "Run " + run.id() + " executed on " + node + "; only its executing box can stop it.",
          "Stop it from " + node + "'s box.");
    }
    authorize(actor, run);
    projects.requireExists(run.project());
    return stopResolved(run, actor, dryRun);
  }

  /**
   * A project-targeted stop resolves the active run row first, but a stale row must not mask the
   * separate fixed ad-hoc session: a dispatched agent that died without completing its row leaves a
   * {@code running} row behind while a later {@code sail agent start} session is the live process
   * the operator means to stop. When the resolved run turns out dead ({@link NotRunning}), the
   * stranded rescue still happens and the ad-hoc identity is probed and stopped as well. A
   * blank-unit run (launched before units were run-scoped) shares that fixed identity, so a pid
   * mismatch there ({@link NotActive}) proves the live process is a newer ad-hoc session and the
   * stale run's own agent is gone — the project lane rescues the stale state and stops the actual
   * session, while an exact run-target request stays a conservative no-op.
   */
  private Outcome stopProject(String project, Actor actor, String localHandle, boolean dryRun) {
    projects.requireExists(project);
    var run = runStore.runningForProjectOnNode(project, localHandle).orElse(null);
    if (run == null) {
      return stopAdHoc(project, dryRun);
    }
    authorize(actor, run);
    var resolved = stopResolved(run, actor, dryRun);
    if (resolved instanceof NotActive && Strings.isBlank(run.unit())) {
      return rescueStaleLegacyRun(project, run, actor, dryRun);
    }
    if (!(resolved instanceof NotRunning stranded)) {
      return resolved;
    }
    if (stopAdHoc(project, dryRun) instanceof Stopped stopped) {
      return new Stopped(null, stranded.specId(), stopped.pid(), stranded.specCancelled());
    }
    return stranded;
  }

  private Outcome rescueStaleLegacyRun(
      String project, RunStore.RunRow run, Actor actor, boolean dryRun) {
    var staleSpec = specOf(run);
    var cancelled = cancelable(staleSpec);
    if (!dryRun) {
      recordIntent(run, staleSpec, actor);
    }
    if (stopAdHoc(project, dryRun) instanceof Stopped stopped) {
      return new Stopped(null, run.specId(), stopped.pid(), cancelled);
    }
    return new NotRunning(run.id(), run.specId(), cancelled, true);
  }

  /**
   * The clean-stop procedure over the resolved, owned, authorized run: probe liveness on the run's
   * own recorded unit, claim the stop (spec cancelled, run {@code stopping}, one transaction),
   * halt, and finalize the claim once the kill is verified. A run already holding a claim is an
   * interrupted stop and resumes instead. Every no-op branch is structured and write-free except
   * the stranded rescues, which record intent without a kill.
   */
  private Outcome stopResolved(RunStore.RunRow run, Actor actor, boolean dryRun) {
    var spec = specOf(run);
    if (STOPPING.equals(run.status())) {
      return resumeStop(run, actor, dryRun);
    }
    if (!"running".equals(run.status())) {
      if (!cancelable(spec)) {
        return new AlreadyTerminal(run.id(), run.specId(), run.status());
      }
      if (dryRun) {
        return isCurrentAttempt(run)
            ? new NotRunning(run.id(), run.specId(), true, false)
            : new AlreadyTerminal(run.id(), run.specId(), run.status());
      }
      if (!runStore.runIfLatestAttempt(run.id(), run.specId(), () -> commitCancel(spec))) {
        return new AlreadyTerminal(run.id(), run.specId(), run.status());
      }
      events.publish(cancelEvent(run, actor));
      return new NotRunning(run.id(), run.specId(), true, false);
    }
    var unit = runUnit(run);
    var info = probe(run.project(), unit);
    if (info == null || !info.running()) {
      if (!dryRun) {
        recordIntent(run, spec, actor);
      }
      return new NotRunning(run.id(), run.specId(), cancelable(spec), true);
    }
    if (run.pid() == null || info.pid() != run.pid()) {
      return new NotActive(run.id(), run.specId(), info.pid());
    }
    listener.halting(run.project(), unit.unitName(), info.pid());
    if (dryRun) {
      return new Stopped(run.id(), run.specId(), info.pid(), cancelable(spec));
    }
    return killVerified(run, spec, actor, unit, info.pid());
  }

  /**
   * The live-kill sequence over a durable claim. {@link #claimStop} commits the whole terminal
   * intent atomically — run {@code running → stopping}, spec {@code → cancelled}, both
   * compare-and-set — so the cancel wins the race with the watcher's own stop while the signal is
   * in flight, and an interruption at any later point leaves the explicit claim rather than a
   * cancelled spec over a run still recorded {@code running}. A verified halt finalizes the claim
   * ({@code stopping → stopped}) and publishes the operator event; a kill that fails or leaves a
   * live process on the unit restores the claim and throws, so the run is again {@code running} and
   * reconcilable.
   */
  private Outcome killVerified(
      RunStore.RunRow run, SpecStore.SpecRow spec, Actor actor, AgentUnit unit, int pid) {
    var cancelled = claimStop(run, spec);
    try {
      halt(run.project(), unit);
      verifyHalted(run.project(), unit);
    } catch (RuntimeException failure) {
      abortStop(run, spec, cancelled);
      throw failure;
    }
    finishStop(run, actor);
    return new Stopped(run.id(), run.specId(), pid, cancelled);
  }

  /**
   * Resumes a stop claim left behind by an interrupted kill — a crash between the claim and its
   * finalization. The terminal intent is already durable (the spec is cancelled), so only the
   * process side remains: a dead unit just finalizes the claim and publishes the withheld operator
   * event, a live one is halted, verified, and then finalized. The same pid identity guard as the
   * first stop applies — a durable claim is never authority to kill a different process that later
   * occupies the unit, which matters most for a migrated blank-unit run whose fixed ad-hoc identity
   * a newer {@code sail agent start} session may now own. A failure leaves the claim in place for
   * the next retry or the reconciler's interrupted-stop pass — never restored, because the original
   * operator intent still stands.
   */
  private Outcome resumeStop(RunStore.RunRow run, Actor actor, boolean dryRun) {
    var unit = runUnit(run);
    var info = probe(run.project(), unit);
    if (info == null || !info.running()) {
      if (!dryRun) {
        finishStop(run, actor);
      }
      return new NotRunning(run.id(), run.specId(), false, true);
    }
    if (run.pid() == null || info.pid() != run.pid()) {
      return new NotActive(run.id(), run.specId(), info.pid());
    }
    listener.halting(run.project(), unit.unitName(), info.pid());
    if (dryRun) {
      return new Stopped(run.id(), run.specId(), info.pid(), false);
    }
    halt(run.project(), unit);
    verifyHalted(run.project(), unit);
    finishStop(run, actor);
    return new Stopped(run.id(), run.specId(), info.pid(), false);
  }

  /**
   * Commits the stop claim: run {@code running → stopping} and spec {@code → cancelled} in one
   * transaction, each compare-and-set from the state this stop resolved. Either CAS losing means a
   * concurrent transition (a watcher completion, a lifecycle advance) won the race after this stop
   * read its snapshot — the claim rolls back entirely and the stop refuses with a conflict rather
   * than cancelling from stale state. Returns whether the spec was cancelled.
   */
  private boolean claimStop(RunStore.RunRow run, SpecStore.SpecRow spec) {
    var cancelling = cancelable(spec);
    var claimed =
        runStore.transition(
            run.id(),
            "running",
            STOPPING,
            () -> {
              if (cancelling) {
                commitCancel(spec);
              }
            });
    if (!claimed) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Run " + run.id() + " changed status while stopping.",
          "Retry the stop.");
    }
    return cancelling;
  }

  /**
   * Finalizes a claim whose halt is verified; the event is the winner's to publish, exactly once.
   */
  private void finishStop(RunStore.RunRow run, Actor actor) {
    if (runStore.transition(run.id(), STOPPING, "stopped")) {
      events.publish(cancelEvent(run, actor));
    }
  }

  /**
   * Restores a failed claim — run back to {@code running}, spec back to its pre-claim status — in
   * one transaction, each side conditional on the claim still being held, so a finalization that
   * won in between is never overwritten.
   */
  private void abortStop(RunStore.RunRow run, SpecStore.SpecRow spec, boolean cancelled) {
    runStore.transition(
        run.id(),
        STOPPING,
        "running",
        () -> {
          if (cancelled) {
            specStore.compareAndSetStatus(spec.id(), SpecStatus.CANCELLED, spec.status());
          }
        });
  }

  /**
   * A verified halt leaves no live process on the addressed unit. Rejecting <em>any</em> running
   * result — not only the original pid — keeps a replacement process (a unit restart, a pid reused
   * mid-halt) from being mistaken for a successful termination.
   */
  private void verifyHalted(String project, AgentUnit unit) {
    var remaining = probe(project, unit);
    if (remaining != null && remaining.running()) {
      throw new ApiException(
          ErrorCode.AGENT_STOP_FAILED,
          "Agent PID " + remaining.pid() + " is still running after the stop signal.",
          "Retry the stop.");
    }
  }

  /**
   * Whether this run is still its spec's latest attempt. A terminal run may rescue a stranded spec
   * only while it is the current attempt: after a restart, stopping an older run id must be an
   * idempotent no-op, never a lever that cancels the spec out from under the newer active run. This
   * read only previews the dry-run outcome — the live path decides inside {@link
   * RunStore#runIfLatestAttempt}, atomically with the cancel, so a restart reserving a newer
   * attempt between the check and the write can never lose its spec.
   */
  private boolean isCurrentAttempt(RunStore.RunRow run) {
    return runStore.listForSpec(run.specId()).stream()
        .findFirst()
        .map(latest -> latest.id().equals(run.id()))
        .orElse(false);
  }

  /**
   * Cancels the spec compare-and-set from the status this stop resolved; a CAS that loses means a
   * concurrent transition consumed that status first, and the stop must be retried against the new
   * state, not committed over it. Runs inside the caller's transaction, so the thrown conflict
   * rolls back the rest of the intent with it.
   */
  private void commitCancel(SpecStore.SpecRow spec) {
    if (!specStore.compareAndSetStatus(spec.id(), spec.status(), SpecStatus.CANCELLED)) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Spec " + spec.id() + " changed status while stopping.",
          "Retry the stop.");
    }
  }

  /**
   * The ad-hoc fallback for a project with no active run row: a {@code sail agent start} session
   * owns no run and no spec, so there is no intent to record — the verified kill is the whole stop,
   * and nothing exists for the reaper or the pipeline to resume. Verified matters here too: the
   * halter is a no-op when the pid file is missing while the probe can still see the live process
   * through systemd, and only a re-probe keeps that state from being reported stopped.
   */
  private Outcome stopAdHoc(String project, boolean dryRun) {
    var info = probe(project, AgentUnit.BUILD);
    if (info == null || !info.running()) {
      return new NotRunning(null, null, false, false);
    }
    listener.halting(project, AgentUnit.BUILD.unitName(), info.pid());
    if (!dryRun) {
      halt(project, AgentUnit.BUILD);
      verifyHalted(project, AgentUnit.BUILD);
    }
    return new Stopped(null, null, info.pid(), false);
  }

  /**
   * Records the operator's terminal intent for a run with no live process to kill: the spec (when
   * still {@code in_progress}/{@code review}) moves to {@link SpecStatus#CANCELLED} and the run is
   * released as {@code stopped} with no exit code — in one transaction when both apply, so a
   * failure between the writes can never expose a cancelled spec whose run is still {@code running}
   * (a state no reconciler owns). One {@code agent_cancelled} event carrying {@code
   * source=operator} and the acting FDE is published after the commit. Both writes are ordinary
   * synced revisions, so every peer adopts the terminal state.
   */
  private void recordIntent(RunStore.RunRow run, SpecStore.SpecRow spec, Actor actor) {
    var cancelling = cancelable(spec);
    runStore.complete(
        run.id(),
        "stopped",
        null,
        () -> {
          if (cancelling) {
            commitCancel(spec);
          }
        });
    events.publish(cancelEvent(run, actor));
  }

  private static Event cancelEvent(RunStore.RunRow run, Actor actor) {
    var agent = Strings.isNotBlank(actor.handle()) ? actor.handle() : Event.SAIL_AGENT;
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_OPERATOR);
    data.put(Event.WellKnownData.RUN_ID, run.id());
    return Event.of(
        run.project(),
        run.specId(),
        Event.WellKnownTypes.AGENT_CANCELLED,
        agent,
        HostInfo.hostname(),
        data);
  }

  private SpecStore.SpecRow specOf(RunStore.RunRow run) {
    if (Strings.isBlank(run.specId())) {
      return null;
    }
    return specStore.findById(run.specId()).orElse(null);
  }

  private static boolean cancelable(SpecStore.SpecRow spec) {
    return spec != null
        && (spec.status() == SpecStatus.IN_PROGRESS || spec.status() == SpecStatus.REVIEW);
  }

  private void authorize(Actor actor, RunStore.RunRow run) {
    var assignee =
        Strings.isBlank(run.specId())
            ? null
            : specStore.findById(run.specId()).map(SpecStore.SpecRow::assignee).orElse(null);
    if (RunPolicy.access(actor, run.id(), run.specId(), assignee)
        instanceof AccessDecision.Refused refused) {
      throw new ApiException(refused.code(), refused.message(), refused.fix());
    }
  }

  /**
   * The systemd/file identity of a run: rebuilt from the unit name recorded at launch, or the fixed
   * ad-hoc identity for a run launched before units were run-scoped — that agent runs as {@code
   * sail-agent}, so the fixed paths are exactly where it lives.
   */
  static AgentUnit runUnit(RunStore.RunRow run) {
    return Strings.isBlank(run.unit()) ? AgentUnit.BUILD : AgentUnit.recorded(run.id(), run.unit());
  }

  private AgentSession.SessionInfo probe(String project, AgentUnit unit) {
    try {
      return new AgentSession(shell).queryStatus(project, unit);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STATUS_FAILED, "Failed to query agent status.", e);
    }
  }

  private void halt(String project, AgentUnit unit) {
    try {
      halter.halt(project, unit);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STOP_FAILED, "Failed to stop agent.", e);
    }
  }
}
