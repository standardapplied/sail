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
import java.util.concurrent.atomic.AtomicBoolean;

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
 * still-{@code running} run released as {@code stopped}, in one transaction). Every run — build or
 * ad-hoc, background or foreground — owns its run-scoped unit and pid file, so a stop can only ever
 * signal the process the addressed run launched (the residual risk is in-container pid reuse
 * against a stale pid file, not cross-run identity theft, and the verified halt re-probes before
 * anything is finalized). A run that is no longer its spec's latest attempt is never a lever to
 * cancel newer work (a terminal one is {@link AlreadyTerminal}; a live or dead one is halted or
 * released without touching the spec), and a second stop of the same run is idempotent by
 * construction, returning {@link AlreadyTerminal} without signalling anything.
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
  public sealed interface Outcome permits Stopped, NotRunning, AlreadyTerminal {
    /** Whether this stop wrote anything — a halted agent, a cancelled spec, a released run. */
    default boolean mutated() {
      return switch (this) {
        case Stopped ignored -> true;
        case NotRunning notRunning -> notRunning.specCancelled() || notRunning.runReleased();
        case AlreadyTerminal ignored -> false;
      };
    }

    /**
     * The single wire vocabulary for why nothing was killed — null for {@link Stopped} — shared by
     * the API response and the CLI's {@code --json} view so scripts see one set of reasons.
     */
    default String reason() {
      return switch (this) {
        case Stopped ignored -> null;
        case NotRunning notRunning ->
            notRunning.runReleased() ? "no_agent_running" : "run_not_running";
        case AlreadyTerminal ignored -> "run_not_running";
      };
    }
  }

  /**
   * The live agent was halted after its terminal intent was recorded. {@code runId} always names
   * the stopped run — an ad-hoc session is a run like any other; {@code specId} is null for a run
   * that works no spec; {@code specCancelled} reports whether the spec moved to {@link
   * SpecStatus#CANCELLED}.
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
   * Resolves the session shown by CLI status/config views: the active session run's (build or
   * ad-hoc) own recorded identity, or null when the project has no active run on this box.
   */
  public static AgentSession.SessionInfo resolveSession(
      ShellExec shell, RunStore runs, String project, String localHandle) throws Exception {
    var run = runs.runningForProjectOnNode(project, localHandle).orElse(null);
    if (run == null) {
      return null;
    }
    return new AgentSession(shell).queryStatus(project, runUnit(run));
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
    if (!run.sessionRole()) {
      throw new ApiException(
          ErrorCode.INVALID_ROLE,
          "Run "
              + run.id()
              + " is a "
              + run.role()
              + " run driven by the review pipeline, not a"
              + " stoppable agent session.",
          "Stop the spec's build run instead, or let the pipeline finish.");
    }
    authorize(actor, run);
    projects.requireExists(run.project());
    return stopResolved(run, actor, dryRun);
  }

  /**
   * A project-targeted stop resolves the newest active session run — build or ad-hoc, the two are
   * mutually exclusive by reservation — and applies the one resolved-run procedure. A project with
   * no active run on this box has nothing to stop.
   */
  private Outcome stopProject(String project, Actor actor, String localHandle, boolean dryRun) {
    projects.requireExists(project);
    var run = runStore.runningForProjectOnNode(project, localHandle).orElse(null);
    if (run == null) {
      return new NotRunning(null, null, false, false);
    }
    authorize(actor, run);
    return stopResolved(run, actor, dryRun);
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
        return new AlreadyTerminal(run.id(), specIdOf(run), run.status());
      }
      if (dryRun) {
        return isCurrentAttempt(run)
            ? new NotRunning(run.id(), run.specId(), true, false)
            : new AlreadyTerminal(run.id(), run.specId(), run.status());
      }
      if (!runStore.runIfLatestAttempt(run.id(), run.specId(), () -> commitCancel(spec))) {
        return new AlreadyTerminal(run.id(), run.specId(), run.status());
      }
      events.publish(operatorCancelEvent(run, actor));
      return new NotRunning(run.id(), run.specId(), true, false);
    }
    var unit = runUnit(run);
    var info = probe(run.project(), unit);
    if (info == null || !info.running()) {
      if (dryRun) {
        return new NotRunning(run.id(), specIdOf(run), previewCancel(run, spec), true);
      }
      return new NotRunning(run.id(), specIdOf(run), recordIntent(run, spec, actor), true);
    }
    listener.halting(run.project(), unit.unitName(), info.pid());
    if (dryRun) {
      return new Stopped(run.id(), run.specId(), info.pid(), previewCancel(run, spec));
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
    return new Stopped(run.id(), specIdOf(run), pid, cancelled);
  }

  /**
   * Resumes a stop claim left behind by an interrupted kill — a crash between the claim and its
   * finalization. The terminal intent is already durable (the spec is cancelled), so only the
   * process side remains: a dead unit just finalizes the claim and publishes the withheld operator
   * event, a live one is halted, verified, and then finalized. The same pid identity guard as the
   * first stop applies — a durable claim is never authority to kill a different process that later
   * occupies the unit. A failure leaves the claim in place for the next retry or the reconciler's
   * interrupted-stop pass — never restored, because the original operator intent still stands.
   */
  private Outcome resumeStop(RunStore.RunRow run, Actor actor, boolean dryRun) {
    var unit = runUnit(run);
    var info = probe(run.project(), unit);
    if (info == null || !info.running()) {
      if (!dryRun) {
        finishStop(run, actor);
      }
      return new NotRunning(run.id(), specIdOf(run), false, true);
    }
    listener.halting(run.project(), unit.unitName(), info.pid());
    if (dryRun) {
      return new Stopped(run.id(), specIdOf(run), info.pid(), false);
    }
    halt(run.project(), unit);
    verifyHalted(run.project(), unit);
    finishStop(run, actor);
    return new Stopped(run.id(), specIdOf(run), info.pid(), false);
  }

  /**
   * Commits the stop claim: run {@code running → stopping} and spec {@code → cancelled} in one
   * transaction, each compare-and-set from the state this stop resolved. Either CAS losing means a
   * concurrent transition (a watcher completion, a lifecycle advance) won the race after this stop
   * read its snapshot — the claim rolls back entirely and the stop refuses with a conflict rather
   * than cancelling from stale state. The cancel itself is further gated on this run still being
   * its spec's latest attempt, inside the same transaction: a restarted attempt may be live
   * elsewhere while this box still owns an older {@code running} row, and stopping that older row
   * must halt its process without cancelling the spec out from under the newer agent. Returns
   * whether the spec was cancelled.
   */
  private boolean claimStop(RunStore.RunRow run, SpecStore.SpecRow spec) {
    return transitionAndCancel(run, spec, STOPPING);
  }

  /**
   * The one gated-cancel write both stop shapes share: run {@code running → status} and, when this
   * run is still cancelable and its spec's latest build attempt, the spec cancel — one transaction,
   * every check compare-and-set inside it. A lost run CAS means a concurrent transition consumed
   * the resolved state first; the stop refuses with a conflict rather than committing over it.
   * Returns whether the spec was cancelled.
   */
  private boolean transitionAndCancel(RunStore.RunRow run, SpecStore.SpecRow spec, String status) {
    var cancelled = new AtomicBoolean();
    var moved =
        runStore.transition(
            run.id(),
            "running",
            status,
            () -> {
              if (cancelable(spec)) {
                cancelled.set(
                    runStore.runIfLatestAttempt(run.id(), run.specId(), () -> commitCancel(spec)));
              }
            });
    if (!moved) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Run " + run.id() + " changed status while stopping.",
          "Retry the stop.");
    }
    return cancelled.get();
  }

  /**
   * Finalizes a claim whose halt is verified; the event is the winner's to publish, exactly once.
   */
  private void finishStop(RunStore.RunRow run, Actor actor) {
    if (runStore.transition(run.id(), STOPPING, "stopped")) {
      events.publish(operatorCancelEvent(run, actor));
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
        .filter(RunStore.RunRow::buildRole)
        .findFirst()
        .map(latest -> latest.id().equals(run.id()))
        .orElse(false);
  }

  /** The dry-run preview of the live paths' gated cancel: cancelable and still current. */
  private boolean previewCancel(RunStore.RunRow run, SpecStore.SpecRow spec) {
    return cancelable(spec) && isCurrentAttempt(run);
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
   * Records the operator's terminal intent for a run with no live process to kill: the spec (when
   * still {@code in_progress}/{@code review}) moves to {@link SpecStatus#CANCELLED} and the run is
   * released as {@code stopped} with no exit code — in one transaction when both apply, so a
   * failure between the writes can never expose a cancelled spec whose run is still {@code running}
   * (a state no reconciler owns). The release is compare-and-set from {@code running}: a watcher
   * completion landing between the dead probe and this commit wins, and the rescue refuses with a
   * conflict instead of overwriting a successful finish with {@code stopped}. The cancel is gated
   * on this run still being its spec's latest attempt, exactly as {@link #claimStop} gates the live
   * path, so a stale dead row can never cancel a newer attempt's spec. One {@code agent_cancelled}
   * event carrying {@code source=operator} and the acting FDE is published after the commit. Both
   * writes are ordinary synced revisions, so every peer adopts the terminal state. Returns whether
   * the spec was cancelled.
   */
  private boolean recordIntent(RunStore.RunRow run, SpecStore.SpecRow spec, Actor actor) {
    var cancelled = transitionAndCancel(run, spec, "stopped");
    events.publish(operatorCancelEvent(run, actor));
    return cancelled;
  }

  private static Event operatorCancelEvent(RunStore.RunRow run, Actor actor) {
    var agent = Strings.isNotBlank(actor.handle()) ? actor.handle() : Event.SAIL_AGENT;
    return cancelEvent(run, Event.WellKnownData.SOURCE_OPERATOR, agent);
  }

  /**
   * The one {@code agent_cancelled} event shape every cancel lane publishes — the operator stop and
   * the reconciler's interrupted-stop finalization differ only in {@code source} and acting agent,
   * so a field added here reaches every SSE/audit consumer from both.
   */
  static Event cancelEvent(RunStore.RunRow run, String source, String agent) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, source);
    data.put(Event.WellKnownData.RUN_ID, run.id());
    return Event.of(
        run.project(),
        specIdOf(run),
        Event.WellKnownTypes.AGENT_CANCELLED,
        agent,
        HostInfo.hostname(),
        data);
  }

  /** The run's spec id with the blank ad-hoc form normalized to null: no spec means no spec. */
  static String specIdOf(RunStore.RunRow run) {
    return Strings.isBlank(run.specId()) ? null : run.specId();
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
    var owner =
        Strings.isBlank(run.specId())
            ? run.node()
            : specStore.findById(run.specId()).map(SpecStore.SpecRow::assignee).orElse(null);
    if (RunPolicy.access(actor, run.id(), specIdOf(run), owner)
        instanceof AccessDecision.Refused refused) {
      throw new ApiException(refused.code(), refused.message(), refused.fix());
    }
  }

  /**
   * The systemd/file identity of a session run, rebuilt from the unit name recorded at launch. A
   * foreground run records no unit — it is a blocking child process, not a service — but its
   * run-scoped pid file carries the same identity, so probing and killing work through the file
   * side while the systemd side simply reports nothing.
   */
  static AgentUnit runUnit(RunStore.RunRow run) {
    return AgentUnit.recorded(run.id(), Objects.toString(run.unit(), ""));
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
