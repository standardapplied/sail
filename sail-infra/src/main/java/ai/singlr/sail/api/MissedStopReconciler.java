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
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.time.Instant;
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
 * session replays the stop with its recorded exit code. A running session past the launch grace
 * period whose <em>recorded</em> systemd unit is inactive or absent gets a synthesized stop — a
 * running session with no recorded unit (launched before units were run-scoped) is skipped instead:
 * this sweep cannot know that unit's liveness, and the run's own detached watcher already owns its
 * stop. The synthesized stop carries <em>no exit code</em>: the transient unit is garbage-collected
 * on exit, so the real code is unrecoverable, and the replay path makes the same choice for a
 * terminal session that never recorded one — the pipeline treats the absent code as not-a-failure
 * and lets review judge the work. Every replayed stop carries {@code source=reconcile} so the event
 * log shows it was reconstructed, not observed.
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
      EventBus bus,
      UnitProbe unitProbe,
      Supplier<String> localHandle,
      Supplier<Instant> clock) {
    this.specStore = specStore;
    this.sessionStore = sessionStore;
    this.eventStore = eventStore;
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
      for (var spec : specStore.list(IN_PROGRESS)) {
        try {
          replayed += reconcile(spec) ? 1 : 0;
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
      for (var spec : specStore.list(REVIEW)) {
        replayed += rescueStrandedReview(spec) ? 1 : 0;
      }
    } catch (Exception e) {
      System.err.println("  [reconcile] missed-stop sweep aborted: " + e.getMessage());
    }
    return replayed;
  }

  /**
   * Rescues a spec stranded in {@code review} with no review ever started — the shape a dropped
   * kickoff leaves: an out-of-band status write (a manual edit, or a sync revision from another
   * box) moved the spec to {@code review} while its agent was still running here, so the
   * authoritative stop hit the pipeline's guard against a non-{@code in_progress} spec and no
   * review was created. Replaying the stop lets the review-resilient pipeline kick it off. A {@code
   * review_stage_started} event means a review did run, so the spec is not stranded; and the rescue
   * fires at most once per spec per server lifetime, so a project with no automated pipeline (its
   * {@code review} is a human queue) is never replayed in a loop.
   */
  private boolean rescueStrandedReview(SpecStore.SpecRow spec) {
    if (reviewRescueAttempted.contains(spec.id()) || reviewStarted(spec.id())) {
      return false;
    }
    var node = localHandle.get();
    var latest =
        sessionStore.listForSpec(spec.id()).stream()
            .findFirst()
            .filter(run -> SailOperations.ownsRun(run.node(), node))
            .filter(run -> TERMINAL_RUN_STATUSES.contains(run.status()));
    if (latest.isEmpty()) {
      return false;
    }
    reviewRescueAttempted.add(spec.id());
    publishStop(
        spec,
        latest.get(),
        latest.get().exitCode(),
        "stranded in review with no review started; replaying the stop to kick it off");
    return true;
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
        publishStop(spec, session, replay.exitCode(), replay.why());
        yield true;
      }
      case MissedStops.Outcome.ProbeUnit probe -> {
        if (Strings.isBlank(session.unit())) {
          yield false;
        }
        if (unitProbe.active(spec.project(), session.id(), session.unit())) {
          yield false;
        }
        publishStop(spec, session, null, "unit inactive or gone; " + probe.why());
        yield true;
      }
      case MissedStops.Outcome.Skip ignored -> false;
    };
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
