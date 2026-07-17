/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * the replay). A running session past the launch grace period whose <em>recorded</em> systemd unit
 * is inactive or absent gets a synthesized stop — a running session with no recorded unit (launched
 * before units were run-scoped) is skipped instead: this sweep cannot know that unit's liveness,
 * and the run's own detached watcher already owns its stop. The synthesized stop carries <em>no
 * exit code</em>: the transient unit is garbage-collected on exit, so the real code is
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

  private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("completed", "stopped", "failed");

  /**
   * Answers whether a run's agent systemd unit is still active — addressed by the unit name
   * recorded on the run at launch, never re-derived.
   */
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
   * Probes the run's agent unit through systemd — the authoritative liveness source. A transient
   * unit vanishes after a clean exit, and systemd reports an absent unit as {@code inactive}, so
   * absent counts as dead; conversely a probe that cannot reach the container reports active,
   * biasing the sweep toward doing nothing when it cannot know.
   */
  public static UnitProbe systemdUnitProbe(ShellExec shell) {
    var agentSession = new AgentSession(shell);
    return (project, runId, unit) ->
        agentSession.queryExitStatus(project, AgentUnit.recorded(runId, unit)).active();
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
   * ultimately caught by the stranded-spec alarm.
   */
  public int sweep() {
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
      replayed += releaseStrandedReservations(handledThisSweep);
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
   * Releases a repo reservation orphaned by a crash between reserving a dispatch run and claiming
   * its spec: the run committed {@code running} but the spec never left {@code pending}, so the
   * in-progress reconcile pass never reaches it and its repo would block every future overlapping
   * dispatch. Only a run older than {@link #LAUNCH_GRACE} is touched, so a run still inside the
   * microsecond reserve-then-claim window of a healthy dispatch is never disturbed. The spec was
   * never claimed, so it stays {@code pending} and dispatchable; only the dead reservation is
   * cleared. Foreign runs are left to their executing box. A run whose spec was already handled
   * earlier in this same sweep is skipped, so an async status flip caused by the sweep's own
   * replayed stop can never make one pass both reconcile and release the one run.
   */
  int releaseStrandedReservations(Set<String> handledThisSweep) {
    var node = localHandle.get();
    var deadline = clock.get().minus(LAUNCH_GRACE);
    var released = 0;
    for (var run : sessionStore.running()) {
      if (handledThisSweep.contains(run.specId())
          || !SailOperations.ownsRun(run.node(), node)
          || !MissedStops.parseOr(run.startedAt(), Instant.MAX).isBefore(deadline)
          || specBeingWorked(run.specId())) {
        continue;
      }
      System.err.println(
          "  [reconcile] releasing stranded reservation "
              + run.id()
              + " for spec "
              + run.specId()
              + " (running with no agent working it; spec is not in_progress or review)");
      sessionStore.complete(run.id(), "stopped", null);
      released++;
    }
    return released;
  }

  /**
   * Finalizes stop claims interrupted before their verified finish: a run left {@code stopping} —
   * its spec already cancelled by the claim — whose recorded unit is now dead is released as {@code
   * stopped} and its withheld {@code agent_cancelled} event published, so a crash between a stop's
   * claim and its finalization heals within a sweep instead of parking the run forever. The finish
   * is the same compare-and-set the live stop uses, so racing a concurrent stop retry can never
   * double-finalize or double-publish. A claim whose unit is still active is left alone — a live
   * stop is mid-halt, or an interrupted one still has its agent to kill and the operator's retry
   * owns that. A blank-unit claim (a run launched before units were run-scoped) probes the fixed
   * ad-hoc identity — the same compatibility mapping the stop itself uses — so a legacy claim whose
   * agent is verifiably gone still finalizes instead of reserving its repos forever; if a newer
   * ad-hoc session holds that shared unit, the probe reads active and the claim is left untouched.
   * Foreign claims are their executing box's to finalize.
   */
  int finalizeInterruptedStops() {
    var node = localHandle.get();
    var finalized = 0;
    for (var run : sessionStore.stopping()) {
      if (!SailOperations.ownsRun(run.node(), node)) {
        continue;
      }
      var unit = Strings.isBlank(run.unit()) ? AgentUnit.BUILD.unitName() : run.unit();
      try {
        if (unitProbe.active(run.project(), run.id(), unit)) {
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
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_RECONCILE);
    data.put(Event.WellKnownData.RUN_ID, run.id());
    return Event.of(
        run.project(),
        run.specId(),
        Event.WellKnownTypes.AGENT_CANCELLED,
        Event.SAIL_AGENT,
        HostInfo.hostname(),
        data);
  }

  /**
   * Whether the run's spec is actively being worked on this box, so a {@code running} run for it is
   * legitimate and left to the in-progress and review sweeps. Any other spec state — pending (a
   * crash between reserve and claim), or a terminal state the run outlived (a dropped completion,
   * or a pre-run-scoped run carried across an upgrade) — means the reservation is dead and blocking
   * the dispatch gate for no agent.
   */
  private boolean specBeingWorked(String specId) {
    return specStore
        .findById(specId)
        .map(spec -> spec.status().wire())
        .map(status -> "in_progress".equals(status) || "review".equals(status))
        .orElse(false);
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
        sessionStore.listForSpec(spec.id()).stream()
            .findFirst()
            .filter(run -> SailOperations.ownsRun(run.node(), node))
            .filter(run -> TERMINAL_RUN_STATUSES.contains(run.status()));
    if (latest.isEmpty() || unitStillActive(spec, latest.get())) {
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
    var latest =
        sessionStore.listForSpec(spec.id()).stream()
            .findFirst()
            .filter(run -> SailOperations.ownsRun(run.node(), node));
    if (latest.isEmpty()) {
      return false;
    }
    var session = latest.get();
    var coverage = stopCoverage(spec.id(), session.startedAt());
    var outcome = MissedStops.assess(session, coverage, clock.get(), LAUNCH_GRACE);
    return switch (outcome) {
      case MissedStops.Outcome.ReplayStop replay -> {
        if (unitStillActive(spec, session)) {
          yield false;
        }
        publishStop(spec, session, replay.exitCode(), replay.why());
        yield true;
      }
      case MissedStops.Outcome.ProbeUnit probe -> {
        if (Strings.isBlank(session.unit()) || unitStillActive(spec, session)) {
          yield false;
        }
        publishStop(spec, session, null, "unit inactive or gone; " + probe.why());
        yield true;
      }
      case MissedStops.Outcome.Skip ignored -> false;
    };
  }

  /**
   * Whether the run's recorded systemd unit is still alive — the veto that keeps a lying terminal
   * row from forging a stop. A hook turn-end completes the run row as a watcher-dead backstop, but
   * a turn-end is not a process exit: in the field a gate-allowed mid-run stop marked the row
   * terminal while the agent kept working, and the next sweep replayed the "finished" run — the
   * review then judged half-done work and the fix agent raced the live agent in one clone. The unit
   * is the authoritative liveness source, so an active unit means the run's own watcher owns the
   * real stop and the sweep must wait. A row with no recorded unit cannot be probed and keeps the
   * replay behavior; a probe failure propagates to the per-spec catch — logged, skipped, retried
   * next sweep — so the sweep never forges a stop on data it cannot interpret, and never silently.
   */
  private boolean unitStillActive(SpecStore.SpecRow spec, RunStore.RunRow session)
      throws Exception {
    return !Strings.isBlank(session.unit())
        && unitProbe.active(spec.project(), session.id(), session.unit());
  }

  /**
   * The {@link MissedStops.StopCoverage} of this session: when an authoritative ({@code
   * source}-carrying) stop was recorded since the session started, and whether the pipeline left
   * evidence of acting on it — an {@code agent_failed} verdict or review stage activity since the
   * same instant. Observed-but-unacted is the field failure this rescues: a raced statement killed
   * the review kickoff after the watcher's stop landed, and the old observed-means-covered check
   * made every later sweep skip the stranded spec. Unreadable stop rows count as covering and
   * in-flight (timestamp {@code MAX}) — the sweep prefers doing nothing over acting on data it
   * cannot interpret.
   */
  private MissedStops.StopCoverage stopCoverage(String specId, String startedAt) {
    var since = MissedStops.parseOr(startedAt, Instant.MIN);
    var observedAt =
        eventStore.forSpecAndType(specId, Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
            .filter(row -> carriesSource(row) && !timestampOf(row).isBefore(since))
            .map(MissedStopReconciler::timestampOf)
            .max(Instant::compareTo)
            .orElse(null);
    if (observedAt == null) {
      return MissedStops.StopCoverage.none();
    }
    return new MissedStops.StopCoverage(observedAt, actedOnSince(specId, since));
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

  private static boolean carriesSource(EventStore.EventRow row) {
    try {
      return YamlUtil.parseMap(row.data()).get(Event.WellKnownData.SOURCE) != null;
    } catch (Exception e) {
      return true;
    }
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
    bus.publish(stopEvent(spec, session.id(), exitCode));
  }

  static Event stopEvent(SpecStore.SpecRow spec, String runId, Integer exitCode) {
    var agent = spec.agent() != null ? spec.agent() : Event.SAIL_AGENT;
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_RECONCILE);
    if (exitCode != null) {
      data.put(Event.WellKnownData.EXIT_CODE, exitCode);
    }
    if (runId != null && !runId.isBlank()) {
      data.put(Event.WellKnownData.RUN_ID, runId);
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
