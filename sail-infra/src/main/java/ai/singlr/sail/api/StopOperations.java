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
 * <p>The order is the whole point: the terminal intent — spec {@code cancelled}, run {@code
 * stopped}, an {@link Event.WellKnownTypes#AGENT_CANCELLED} event — is recorded <em>before</em> the
 * process is signalled, so every existing guard makes the cancel win the race with the watcher's
 * own stop. {@link RunTracker} only stamps a run still {@code running}; the review pipeline only
 * acts on a reviewable spec; the missed-stop reconciler sweeps only {@code in_progress} and {@code
 * review}; dispatch selects only {@code pending}. {@link SpecStatus#CANCELLED} sits outside all of
 * those sets, so a deliberate stop is honored by the existing contracts with no new special cases.
 *
 * <p>The lane is also the clean way out of a stranded spec, not only a process kill: a resolved run
 * whose agent already died still gets its intent recorded (spec cancelled, a still-{@code running}
 * run released as {@code stopped}). A pid mismatch is a structured no-op — stopping a stale run id
 * can never kill a different, newer run — and a second stop of the same run is idempotent by
 * construction, returning {@link AlreadyTerminal} without signalling anything.
 */
public final class StopOperations {

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

  private Outcome stopProject(String project, Actor actor, String localHandle, boolean dryRun) {
    projects.requireExists(project);
    var run = runStore.runningForProjectOnNode(project, localHandle).orElse(null);
    if (run == null) {
      return stopAdHoc(project, dryRun);
    }
    authorize(actor, run);
    return stopResolved(run, actor, dryRun);
  }

  /**
   * The clean-stop procedure over the resolved, owned, authorized run: probe liveness on the run's
   * own recorded unit, record the terminal intent, and only then halt. Every no-op branch is
   * structured and write-free except the stranded rescues, which record intent without a kill.
   */
  private Outcome stopResolved(RunStore.RunRow run, Actor actor, boolean dryRun) {
    var spec = specOf(run);
    if (!"running".equals(run.status())) {
      if (!cancelable(spec)) {
        return new AlreadyTerminal(run.id(), run.specId(), run.status());
      }
      if (!dryRun) {
        recordIntent(run, spec, actor, false);
      }
      return new NotRunning(run.id(), run.specId(), true, false);
    }
    var unit = runUnit(run);
    var info = probe(run.project(), unit);
    if (info == null || !info.running()) {
      if (!dryRun) {
        recordIntent(run, spec, actor, true);
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
    var cancelled = recordIntent(run, spec, actor, true);
    halt(run.project(), unit);
    return new Stopped(run.id(), run.specId(), info.pid(), cancelled);
  }

  /**
   * The ad-hoc fallback for a project with no active run row: a {@code sail agent start} session
   * owns no run and no spec, so there is no intent to record — the kill is the whole stop, and
   * nothing exists for the reaper or the pipeline to resume.
   */
  private Outcome stopAdHoc(String project, boolean dryRun) {
    var info = probe(project, AgentUnit.BUILD);
    if (info == null || !info.running()) {
      return new NotRunning(null, null, false, false);
    }
    listener.halting(project, AgentUnit.BUILD.unitName(), info.pid());
    if (!dryRun) {
      halt(project, AgentUnit.BUILD);
    }
    return new Stopped(null, null, info.pid(), false);
  }

  /**
   * Records the operator's terminal intent, atomically ahead of any signal: the spec (when still
   * {@code in_progress}/{@code review}) moves to {@link SpecStatus#CANCELLED}, a still-{@code
   * running} run is released as {@code stopped} with no exit code, and one {@code agent_cancelled}
   * event carrying {@code source=operator} and the acting FDE marks the run as deliberately
   * cancelled. Both writes are ordinary synced revisions, so every peer adopts the terminal state.
   */
  private boolean recordIntent(
      RunStore.RunRow run, SpecStore.SpecRow spec, Actor actor, boolean releaseRun) {
    var cancelling = cancelable(spec);
    if (cancelling) {
      specStore.updateStatus(spec.id(), SpecStatus.CANCELLED);
    }
    if (releaseRun) {
      runStore.complete(run.id(), "stopped", null);
    }
    events.publish(cancelEvent(run, actor));
    return cancelling;
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
