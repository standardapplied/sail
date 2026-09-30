/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Replays the {@code agent_session_stopped} events the control plane missed, so the subscribers
 * that handle a live stop ({@link ReviewPipelineController}, {@link RunTracker}) drive each
 * orphaned spec to its real outcome instead of leaving it parked until the stranded-spec alarm. One
 * routine serves two callers: the daemon start hook runs a pass immediately, and {@link #start}
 * repeats the same pass periodically, so an agent that finishes unobserved mid-run (its watcher
 * died with a daemon restart or crashed) is reconciled within a sweep interval instead of requiring
 * another restart.
 *
 * <p>Each pass walks the {@code in_progress} specs and applies {@link MissedStops#assess} to each
 * spec's newest session, and only when <em>this node executed it</em> — a synced foreign run is its
 * executing node's to reconcile, and probing the local unit for it would synthesize an
 * authoritative stop for an agent that is still alive elsewhere. Ownership is checked after
 * selecting the newest run, never before: filtering first would fall back to a superseded local
 * session and replay its stop over a newer foreign run that is still executing. Database-only
 * checks come first, so a pass with nothing to reconcile issues no systemctl calls. A terminal
 * session replays the stop with its recorded exit code — after probing its recorded unit, because
 * the row may be a hook-backstop claim for an agent that is still running (an active unit vetoes
 * the replay). A running session past the launch grace period whose recorded run identity is
 * inactive or absent gets a synthesized stop, unless its stop was recorded already and reached no
 * finisher: then the row alone is finished once its process probes gone. Every session — background
 * or foreground — records its run-scoped identity, and the probe reads the pid file before the
 * systemd unit, so foreground sessions reconcile the same way; only a legacy row with no recorded
 * unit is skipped, because its blocking launcher owned its completion. The synthesized stop carries
 * <em>no exit code</em>: the transient unit is garbage-collected on exit, so the real code is
 * unrecoverable, and the replay path makes the same choice for a terminal session that never
 * recorded one — the pipeline treats the absent code as not-a-failure and lets review judge the
 * work. Every replayed stop carries {@code source=reconcile} so the event log shows it was
 * reconstructed, not observed.
 *
 * <p>Best-effort by design: a failing spec is logged and skipped, a failing pass is logged and
 * retried on the next tick, and passes never overlap. Run after the bus subscribers are wired.
 */
public final class MissedStopReconciler implements AutoCloseable {

  /** Sweep cadence: prompt enough that an orphaned run resumes its lifecycle within a minute. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

  /**
   * How old a running session must be before its unit may be probed: dispatch claims the spec
   * seconds before the unit exists, and a sweep landing in that window must not declare a
   * never-started agent dead.
   */
  public static final Duration LAUNCH_GRACE = Duration.ofMinutes(2);

  private static final SpecStore.SpecFilter IN_PROGRESS =
      new SpecStore.SpecFilter(null, "in_progress", null, null, null);

  private static final SpecStore.SpecFilter REVIEW =
      new SpecStore.SpecFilter(null, "review", null, null, null);

  /** Answers whether a run's recorded agent identity is still active. */
  @FunctionalInterface
  public interface UnitProbe {
    boolean active(String project, String runId, String unit) throws Exception;
  }

  private final SpecStore specStore;
  private final RunStore sessionStore;
  private final EventStore eventStore;
  private final ReviewStore reviewStore;
  private final EventBus bus;
  private final UnitProbe unitProbe;
  private final Supplier<String> localHandle;
  private final Supplier<Instant> clock;
  private final PeriodicPass pass;
  private final Set<String> reviewRescueAttempted = ConcurrentHashMap.newKeySet();

  public MissedStopReconciler(
      SpecStore specStore,
      RunStore sessionStore,
      EventStore eventStore,
      ReviewStore reviewStore,
      EventBus bus,
      UnitProbe unitProbe,
      Supplier<String> localHandle,
      Supplier<Instant> clock) {
    this.specStore = specStore;
    this.sessionStore = sessionStore;
    this.eventStore = eventStore;
    this.reviewStore = reviewStore;
    this.bus = bus;
    this.unitProbe = unitProbe;
    this.localHandle = localHandle;
    this.clock = clock;
    this.pass = new PeriodicPass("reconcile", this::sweep);
  }

  /**
   * Probes the run-scoped pid file first, then its systemd unit. The pid path also covers
   * foreground builds, which use the same run identity without creating a service. A run that reads
   * as not running is gone only when its container says so: a container that is stopped or no
   * longer exists runs nothing; a running one must answer a command and then read the run as not
   * running a second time, so a command lost while incusd restarted, before the container answered
   * again, never reads a working agent as gone; and one incus cannot report fails the probe — which
   * every pass reads as alive — so an incus outage never reads as every run in it gone.
   */
  public static UnitProbe systemdUnitProbe(ShellExec shell) {
    var agentSession = new AgentSession(shell);
    var containers = new ContainerManager(shell);
    return (project, runId, unit) -> {
      var recorded = AgentUnit.recorded(runId, unit);
      if (running(agentSession.queryStatus(project, recorded))) {
        return true;
      }
      return switch (containers.queryState(project)) {
        case ContainerState.Stopped stopped -> false;
        case ContainerState.NotCreated gone -> false;
        case ContainerState.Running running -> {
          agentSession.requireReachable(project);
          yield running(agentSession.queryStatus(project, recorded));
        }
        case ContainerState.Error unknown ->
            throw new IOException(
                "Container '" + project + "' could not be read: " + unknown.message());
      };
    };
  }

  private static boolean running(AgentSession.SessionInfo info) {
    return info != null && info.running();
  }

  /** Starts the periodic sweep at the default cadence. */
  public void start() {
    start(DEFAULT_INTERVAL);
  }

  public void start(Duration interval) {
    pass.start(interval);
  }

  /**
   * Runs one pass unless another is still in flight (a slow probe must not stack passes). Returns
   * whether the pass ran; never throws, so the schedule survives any failure.
   */
  boolean sweepIfIdle() {
    return pass.runIfIdle();
  }

  /**
   * Runs one reconciliation pass and returns how many stops were replayed. Errors are logged and
   * swallowed — per spec so one broken project cannot shadow the rest, and around the pass so
   * reconciliation can never block server startup; anything missed is retried on the next sweep and
   * ultimately caught by the stranded-spec alarm. Runs as this box's machinery.
   */
  public int sweep() {
    return Actor.call(Actor.system(), this::reconcileAll);
  }

  private int reconcileAll() {
    var replayed = 0;
    try {
      var handledThisSweep = new HashSet<String>();
      for (var spec : specStore.list(IN_PROGRESS)) {
        try {
          if (reconcile(spec)) {
            handledThisSweep.add(spec.id());
            replayed++;
          }
        } catch (Exception e) {
          System.err.println(
              "  [reconcile] failed for "
                  + spec.project()
                  + "/"
                  + spec.id()
                  + ": "
                  + e.getMessage());
        }
      }
      replayed += rescueStrandedReviews(handledThisSweep);
      replayed += finishDeadSessions(handledThisSweep);
      replayed += finalizeInterruptedStops();
    } catch (Exception e) {
      System.err.println("  [reconcile] missed-stop sweep aborted: " + e.getMessage());
    }
    return replayed;
  }

  /**
   * Rescues every spec stranded in {@code review}, skipping any whose stop was already replayed
   * earlier in this same sweep. Without the skip a spec that the in-progress pass just reconciled —
   * whose replayed stop drives it {@code in_progress → review} on an async subscriber thread —
   * would be seen in {@code review} by this pass and have its stop replayed a second time,
   * double-handling the one run. The sweep is thereby internally consistent regardless of when the
   * async transition lands.
   */
  int rescueStrandedReviews(Set<String> handledThisSweep) {
    var rescued = 0;
    for (var spec : specStore.list(REVIEW)) {
      if (handledThisSweep.contains(spec.id())) {
        continue;
      }
      try {
        if (rescueStrandedReview(spec)) {
          handledThisSweep.add(spec.id());
          rescued++;
        }
      } catch (Exception e) {
        System.err.println(
            "  [reconcile] review rescue failed for "
                + spec.project()
                + "/"
                + spec.id()
                + ": "
                + e.getMessage());
      }
    }
    return rescued;
  }

  /**
   * Finishes every running session this box owns whose process is gone, other than the one the
   * in-progress pass reconciles: a dispatch whose spec never left {@code pending} (a crash between
   * reserve and claim) or outlived its work, a spec-less ad-hoc session, a superseded build
   * session, a room or room-full run of a spec still under way, and the newest build session of a
   * spec in {@code review}, which the review rescue leaves {@code running}. The newest build
   * session of an {@code in_progress} spec, or of a spec whose stop this sweep already replayed, is
   * the in-progress pass's: it replays that spec's stop, or finishes a row whose recorded stop
   * reached no finisher, and a spec stop is never replayed for any other run. Only a run older than
   * {@link #LAUNCH_GRACE} is touched, so a run still inside a healthy launch window is never
   * disturbed, and a run is finished only once it is {@linkplain #gone gone} — a reservation must
   * never be freed under a working agent. A spec's run is finished with the exit code the recorded
   * stop naming it carried. The finish is the same {@code running → stopped} compare-and-set every
   * finisher uses, so racing the watcher's own completion can never overwrite a recorded exit, and
   * it is logged as reconstructed. Foreign runs are left to their executing box and never probed.
   */
  int finishDeadSessions(Set<String> handledThisSweep) {
    var node = localHandle.get();
    var deadline = clock.get().minus(LAUNCH_GRACE);
    var released = 0;
    for (var run : sessionStore.running()) {
      if (!run.ownedBy(node)
          || !MissedStops.parseOr(run.startedAt(), Instant.MAX).isBefore(deadline)
          || reconciledBySpec(run, handledThisSweep)) {
        continue;
      }
      try {
        if (gone(run)
            && finish(
                run,
                Strings.isBlank(run.specId()) ? null : stopCoverage(run.specId(), run).exitCode(),
                Strings.isBlank(run.specId())
                    ? "session with no spec whose recorded process is gone"
                    : "running with its recorded process gone")) {
          released++;
        }
      } catch (Exception e) {
        System.err.println("  [reconcile] could not probe run " + run.id() + ": " + e.getMessage());
      }
    }
    return released;
  }

  /**
   * Finishes {@code run}, whose recorded process is gone, as {@code stopped} with {@code exitCode}
   * when a recorded stop carried one: the same compare-and-set every finisher uses, logged as
   * reconstructed. Returns whether this call finished it.
   */
  private boolean finish(RunStore.RunRow run, Integer exitCode, String why) {
    System.err.println(
        "  [reconcile] finishing run "
            + run.id()
            + (Strings.isBlank(run.specId()) ? "" : " of spec " + run.specId())
            + " ("
            + why
            + ")");
    return sessionStore.transition(run.id(), "running", "stopped", exitCode);
  }

  /**
   * Whether {@code run} is the in-progress pass's to reconcile: its spec's newest build session,
   * while the spec is {@code in_progress} or had its stop replayed earlier in this sweep, whose
   * async status flip must not make one sweep both replay and release the one run.
   */
  private boolean reconciledBySpec(RunStore.RunRow run, Set<String> handledThisSweep) {
    if (Strings.isBlank(run.specId())
        || sessionStore
            .latestBuildAttempt(run.specId())
            .filter(newest -> newest.id().equals(run.id()))
            .isEmpty()) {
      return false;
    }
    return handledThisSweep.contains(run.specId())
        || specStore
            .findById(run.specId())
            .map(spec -> spec.status() == SpecStatus.IN_PROGRESS)
            .orElse(false);
  }

  /**
   * Whether the run's recorded process is gone: its identity probes not running. The probe reads
   * the run-scoped pid file before the systemd unit, so it covers foreground sessions (which launch
   * no service) and background sessions that crashed before their pid was persisted alike. The one
   * liveness question every pass asks. A probe that fails — incus unreachable, a command lost while
   * incusd restarts — propagates, and the pass logs it and leaves that run for the next sweep: a
   * run the sweep could not observe is never finished, nor its stop replayed.
   */
  private boolean gone(RunStore.RunRow run) throws Exception {
    return !unitProbe.active(run.project(), run.id(), Objects.toString(run.unit(), ""));
  }

  /**
   * Finalizes stop claims interrupted before their verified finish: a run left {@code stopping} —
   * its spec already cancelled by the claim — whose recorded unit is now dead is released as {@code
   * stopped} and its withheld {@code agent_cancelled} event published, so a crash between a stop's
   * claim and its finalization heals within a sweep instead of parking the run forever. The finish
   * is the same compare-and-set the live stop uses, so racing a concurrent stop retry can never
   * double-finalize or double-publish. A claim whose unit is still active is left alone — a live
   * stop is mid-halt, or an interrupted one still has its agent to kill and the operator's retry
   * owns that. Foreign claims are their executing box's to finalize.
   */
  int finalizeInterruptedStops() {
    var node = localHandle.get();
    var finalized = 0;
    for (var run : sessionStore.stopping()) {
      if (!run.ownedBy(node)) {
        continue;
      }
      try {
        if (unitProbe.active(run.project(), run.id(), StopOperations.runUnit(run).unitName())) {
          continue;
        }
        if (sessionStore.transition(run.id(), "stopping", "stopped")) {
          System.err.println(
              "  [reconcile] finalized interrupted stop "
                  + run.id()
                  + " for "
                  + run.project()
                  + "/"
                  + run.specId()
                  + " (claim held with its unit gone)");
          bus.publish(cancelledEvent(run));
          finalized++;
        }
      } catch (Exception e) {
        System.err.println(
            "  [reconcile] interrupted-stop finalize failed for "
                + run.id()
                + ": "
                + e.getMessage());
      }
    }
    return finalized;
  }

  private static Event cancelledEvent(RunStore.RunRow run) {
    return StopOperations.cancelEvent(run, Event.WellKnownData.SOURCE_RECONCILE, Event.SAIL_AGENT);
  }

  /**
   * Rescues a spec stranded in {@code review}, in either of the two shapes that leave it parked
   * with nothing coming to move it. <em>Dropped kickoff</em>: an out-of-band status write (a manual
   * edit, or a sync revision from another box) moved the spec to {@code review} while its agent was
   * still running here, so the authoritative stop hit the pipeline's guard against a non-{@code
   * in_progress} spec and no review was created. <em>Errored review</em>: the pipeline ran but its
   * last attempt failed by infrastructure (unparseable reviewer output, an agent crash) — the
   * design retries an errored attempt on the next stop, but the fix agent runs inline and produces
   * no stop, so without a replay the retry never comes (the nexus-accounts-apis field incident).
   * Replaying the stop lets the pipeline kick off or retry. Each rescue key — the spec for a
   * dropped kickoff, the errored review row for a retry — fires at most once per server lifetime,
   * and the pipeline's errored-attempt budget escalates a persistent failure, so neither shape can
   * loop; an escalated or running review is left alone, since a human or a live pipeline owns the
   * spec then.
   */
  private boolean rescueStrandedReview(SpecStore.SpecRow spec) throws Exception {
    var rescue = rescueFor(spec);
    if (rescue == null || reviewRescueAttempted.contains(rescue.key())) {
      return false;
    }
    var node = localHandle.get();
    var latest =
        sessionStore
            .latestBuildAttempt(spec.id())
            .filter(run -> run.ownedBy(node))
            .filter(run -> RunStatus.isTerminal(run.status()));
    if (latest.isEmpty() || !gone(latest.get())) {
      return false;
    }
    reviewRescueAttempted.add(rescue.key());
    publishStop(spec, latest.get(), latest.get().exitCode(), rescue.why());
    return true;
  }

  private record Rescue(String key, String why) {}

  private Rescue rescueFor(SpecStore.SpecRow spec) {
    if (!reviewStarted(spec.id())) {
      return new Rescue(
          spec.id(),
          "stranded in review with no review started; replaying the stop to kick it off");
    }
    return reviewStore
        .latestReviewForSpec(spec.id())
        .filter(review -> review.errored() && "failed".equals(review.status()))
        .map(
            review ->
                new Rescue(
                    review.id(),
                    "review "
                        + review.id()
                        + " errored ("
                        + review.error()
                        + "); replaying the stop to retry the iteration"))
        .orElse(null);
  }

  private boolean reviewStarted(String specId) {
    return !eventStore.forSpecAndType(specId, "review_stage_started").isEmpty();
  }

  private boolean reconcile(SpecStore.SpecRow spec) throws Exception {
    var node = localHandle.get();
    var latest = sessionStore.latestBuildAttempt(spec.id()).filter(run -> run.ownedBy(node));
    if (latest.isEmpty()) {
      return false;
    }
    var session = latest.get();
    var coverage = stopCoverage(spec.id(), session);
    var outcome = MissedStops.assess(session, coverage, clock.get(), LAUNCH_GRACE);
    return switch (outcome) {
      case MissedStops.Outcome.ReplayStop replay -> {
        if (!gone(session)) {
          yield false;
        }
        publishStop(spec, session, replay.exitCode(), replay.why());
        yield true;
      }
      case MissedStops.Outcome.ProbeUnit probe -> {
        if (Strings.isBlank(session.unit()) || !gone(session)) {
          yield false;
        }
        publishStop(spec, session, null, "unit inactive or gone; " + probe.why());
        yield true;
      }
      case MissedStops.Outcome.FinishRun unfinished ->
          gone(session) && finish(session, unfinished.exitCode(), unfinished.why());
      case MissedStops.Outcome.Skip ignored -> false;
    };
  }

  /**
   * The {@link MissedStops.StopCoverage} of this session: when an authoritative ({@code
   * source}-carrying) stop for it was recorded since it started, the exit code that stop carried,
   * and whether the pipeline left evidence of acting on it — an {@code agent_failed} verdict or
   * review stage activity since the same instant. A stop is this session's when it names this run,
   * or names none, as stops did before they named their run: another run's stop — a room run of the
   * same spec — never speaks for it. Observed-but-unacted is the field failure this rescues: a
   * raced statement killed the review kickoff after the watcher's stop landed, and the old
   * observed-means-covered check made every later sweep skip the stranded spec. Unreadable stop
   * rows count as covering and in-flight (timestamp {@code MAX}) — the sweep prefers doing nothing
   * over acting on data it cannot interpret.
   */
  private MissedStops.StopCoverage stopCoverage(String specId, RunStore.RunRow session) {
    var since = MissedStops.parseOr(session.startedAt(), Instant.MIN);
    var newest =
        eventStore.forSpecAndType(specId, Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
            .map(RecordedStop::of)
            .filter(stop -> stop.authoritative() && stop.speaksFor(session.id()))
            .filter(stop -> !stop.at().isBefore(since))
            .max(Comparator.comparing(RecordedStop::at));
    if (newest.isEmpty()) {
      return MissedStops.StopCoverage.none();
    }
    return new MissedStops.StopCoverage(
        newest.get().at(), newest.get().exitCode(), actedOnSince(specId, since));
  }

  /**
   * One recorded stop as the sweep reads it: when it was recorded, whether it carries a {@code
   * source}, the run it names (null for none) and the exit code it carried for that run. An
   * unreadable row is an authoritative stop naming no run.
   */
  private record RecordedStop(Instant at, boolean authoritative, String runId, Integer exitCode) {

    static RecordedStop of(EventStore.EventRow row) {
      try {
        var data = YamlUtil.parseMap(row.data());
        var runId = Objects.toString(data.get(Event.WellKnownData.RUN_ID), null);
        return new RecordedStop(
            timestampOf(row),
            data.get(Event.WellKnownData.SOURCE) != null,
            runId,
            runId == null ? null : Event.WellKnownData.exitCode(data));
      } catch (Exception e) {
        return new RecordedStop(timestampOf(row), true, null, null);
      }
    }

    boolean speaksFor(String sessionId) {
      return runId == null || runId.equals(sessionId);
    }
  }

  private static final List<String> ACTED_ON_EVIDENCE =
      List.of(
          Event.WellKnownTypes.AGENT_FAILED,
          "review_stage_started",
          "review_stage_failed",
          "review_errored",
          "review_escalated");

  private boolean actedOnSince(String specId, Instant since) {
    return ACTED_ON_EVIDENCE.stream()
        .anyMatch(
            type ->
                eventStore.forSpecAndType(specId, type).stream()
                    .anyMatch(row -> !timestampOf(row).isBefore(since)));
  }

  private static Instant timestampOf(EventStore.EventRow row) {
    return MissedStops.parseOr(row.timestamp(), Instant.MAX);
  }

  private void publishStop(
      SpecStore.SpecRow spec, RunStore.RunRow session, Integer exitCode, String why) {
    System.err.println(
        "  [reconcile] replaying missed stop for "
            + spec.project()
            + "/"
            + spec.id()
            + " (session "
            + session.id()
            + ", exit "
            + (exitCode != null ? exitCode : "unknown")
            + "): "
            + why);
    bus.publish(stopEvent(spec, session.id(), session.role(), exitCode));
  }

  static Event stopEvent(SpecStore.SpecRow spec, String runId, String role, Integer exitCode) {
    var agent = spec.agent() != null ? spec.agent() : Event.SAIL_AGENT;
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_RECONCILE);
    if (exitCode != null) {
      data.put(Event.WellKnownData.EXIT_CODE, exitCode);
    }
    if (runId != null && !runId.isBlank()) {
      data.put(Event.WellKnownData.RUN_ID, runId);
    }
    if (role != null && !role.isBlank()) {
      data.put(Event.WellKnownData.RUN_ROLE, role);
    }
    return Event.of(
        spec.project(),
        spec.id(),
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        agent,
        HostInfo.hostname(),
        data);
  }

  @Override
  public void close() {
    pass.close();
  }
}
