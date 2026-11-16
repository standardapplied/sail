/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentPresence;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GuardrailTrigger;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Replays the {@code agent_session_stopped} events the control plane missed, so the subscribers
 * that handle a live stop ({@link ReviewPipelineController}, {@link RunTracker}) drive each
 * orphaned spec to its real outcome instead of leaving it parked until the stranded-spec alarm. One
 * routine serves two callers: the daemon start hook runs a pass immediately, and {@link #start}
 * repeats the same pass periodically, so an agent that finishes unobserved mid-run (its watcher
 * died with a daemon restart or crashed) is reconciled within a sweep interval instead of requiring
 * another restart.
 *
 * <p>Every run of the review loop is reconciled the same way, whichever lane it ran in: a build, a
 * reviewer and a fix agent are each a run with a unit to probe and a stop the loop advances on.
 * Each pass walks the {@code in_progress} specs and applies {@link MissedStops#assess} to each
 * spec's newest loop run ({@link RunStore#latestLoopRun} — its build, or the fix agent answering a
 * review), and only when <em>this node executed it</em> — a synced foreign run is its executing
 * node's to reconcile, and probing the local unit for it would synthesize an authoritative stop for
 * an agent that is still alive elsewhere. Ownership is checked after selecting the newest run,
 * never before: filtering first would fall back to a superseded local session and replay its stop
 * over a newer foreign run that is still executing. Database-only checks come first, so a pass with
 * nothing to reconcile issues no systemctl calls. A terminal session replays the stop with its
 * recorded exit code — after probing its recorded unit, because the row may be a hook-backstop
 * claim for an agent that is still running (an active unit vetoes the replay). A running session
 * past the launch grace period whose recorded run identity is inactive or absent gets a synthesized
 * stop, unless its stop was recorded already and reached no finisher: then the row alone is
 * finished once its process probes gone. Every session — background or foreground — records its
 * run-scoped identity, and the probe reads the pid file before the systemd unit, so foreground
 * sessions reconcile the same way; only a legacy row with no recorded unit is skipped, because its
 * blocking launcher owned its completion.
 *
 * <p>A run's end is its watcher's to report: the watcher alone saw the exit code, or holds the
 * limit it ended the run for. So a run that is gone while a live watcher still covers it ({@link
 * WatcherCoverage}) is left to that watcher — a sweep that lands between a watcher's kill and its
 * stop, or before its next liveness poll, never gets in first with a stop that says less. Only for
 * a run no watcher covers does the sweep speak, and then it says what is still known ({@link
 * UnitProbe#ending}): the non-zero exit code a failed unit still holds, and the limit the watcher
 * recorded beside the run before a stop that never arrived. A run nothing says ended badly carries
 * neither — a cleanly exited transient unit is collected, so there is no code to read — and the
 * pipeline judges it on the work it left, unless it was lost ({@link #lost}): it never recorded a
 * process, or its instance is stopped or gone, which no review judges. Every replayed stop carries
 * {@code source=reconcile} so the event log shows it was reconstructed, not observed.
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

  /**
   * How far before each pass the next one still asks a container a run started or finished in:
   * further back than the launch-file tidy's one-minute age filter, so a file too young to remove
   * on one pass is seen again on a later one.
   */
  static final Duration TIDY_LOOKBACK = Duration.ofMinutes(2);

  /** How long a person stays disabled here before their stored logins are erased. */
  static final Duration DISABLED_FOR = Duration.ofHours(24);

  /** How many unread reports of runs serving a review a pass reads before it replays a stop. */
  static final int READ_FIRST = 3;

  /** How long a pass starts reading those reports for. */
  static final Duration READ_FIRST_FOR = Duration.ofSeconds(10);

  private static final SpecStore.SpecFilter IN_PROGRESS =
      new SpecStore.SpecFilter(null, "in_progress", null, null, null);

  /**
   * What a run's recorded agent identity still says: whether it is active, and how it ended. A
   * probe that cannot tell throws, and a caller that finishes or frees anything on "not active"
   * reads that as alive: this sweep's probe ({@link #systemdUnitProbe}) throws for a container that
   * gave no answer. The watcher re-armer's probe answers false there instead, which for it is the
   * side that does nothing: no watcher is armed onto an agent nobody could see.
   */
  @FunctionalInterface
  public interface UnitProbe {
    /** Whether the run's agent is there, in the place it lives ({@link RunPlace}). */
    boolean active(RunPlace place, String runId, String unit) throws Exception;

    /** How the run ended, as far as its instance still says; nothing, by default. */
    default Ending ending(RunPlace place, String runId, String unit) throws Exception {
      return Ending.UNKNOWN;
    }

    /**
     * Removes from {@code instance} the launch file of every run not in {@code live} ({@link
     * AgentSession#removeLaunchFiles}), throwing when the instance did not run it; nothing, by
     * default.
     */
    default void removeLaunchFiles(String instance, List<String> live) throws Exception {}

    /** Whether {@code place}'s instance is stopped or gone; not, by default. */
    default boolean instanceGone(RunPlace place) throws Exception {
      return false;
    }

    /** None, by default ({@link Workspaces#disown}). */
    default List<String> disown(Predicate<String> held) throws Exception {
      return List.of();
    }
  }

  /**
   * How a run that is gone ended, as far as anything still says.
   *
   * @param exitCode the non-zero code its failed unit still holds, or null: a cleanly exited unit
   *     is collected and holds none
   * @param reason the limit its watcher ended it for, as recorded beside the run, or null
   */
  public record Ending(Integer exitCode, String reason) {
    static final Ending UNKNOWN = new Ending(null, null);
  }

  private final SpecStore specStore;
  private final RunStore sessionStore;
  private final EventStore eventStore;
  private final EventBus bus;
  private final UnitProbe unitProbe;
  private final WatcherCoverage coverage;
  private final Supplier<String> localHandle;
  private final Supplier<Instant> clock;
  private final Map<String, Long> disabledSince = new HashMap<>();
  private LongSupplier nanos = System::nanoTime;
  private final FdeStore fdes;
  private final RunReports reports;
  private final PeriodicPass pass;
  private volatile Instant tidiedSince = Instant.EPOCH;
  private volatile Set<String> unansweredFirst = Set.of();
  private final Set<String> untidied = ConcurrentHashMap.newKeySet();
  private final Set<String> undisowned = ConcurrentHashMap.newKeySet();
  private volatile Map<String, String> lastRoster;
  private volatile boolean unlisted;
  private final StrandedReviewRescue strandedReviews;

  public MissedStopReconciler(
      SpecStore specStore,
      RunStore sessionStore,
      EventStore eventStore,
      ReviewStore reviewStore,
      EventBus bus,
      UnitProbe unitProbe,
      WatcherCoverage coverage,
      Supplier<String> localHandle,
      Supplier<Instant> clock,
      FdeStore fdes,
      RunReports reports) {
    this.specStore = specStore;
    this.sessionStore = sessionStore;
    this.eventStore = eventStore;
    this.bus = bus;
    this.unitProbe = unitProbe;
    this.coverage = coverage;
    this.localHandle = localHandle;
    this.clock = clock;
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.reports = Objects.requireNonNull(reports, "reports");
    this.strandedReviews =
        new StrandedReviewRescue(
            specStore,
            sessionStore,
            reviewStore,
            localHandle,
            clock,
            LAUNCH_GRACE,
            this::gone,
            run -> RunLogins.replaced(fdes, run),
            this::publishStop);
    this.pass = new PeriodicPass("reconcile", this::sweep);
  }

  /**
   * The production probe: a run is active or gone as {@link AgentPresence} reads it — the pid file
   * first, then its systemd unit, so foreground builds, which use the same run identity without
   * creating a service, are covered too. A container that gave no answer fails the probe, which
   * every pass reads as alive, so an incus outage never reads as every run in it gone.
   */
  public static UnitProbe systemdUnitProbe(ShellExec shell) {
    var agentSession = new AgentSession(shell);
    var presence = new AgentPresence(shell);
    return new UnitProbe() {
      @Override
      public boolean instanceGone(RunPlace place) throws Exception {
        return switch (presence.state(place)) {
          case ContainerState.Stopped stopped -> true;
          case ContainerState.NotCreated absent -> true;
          default -> false;
        };
      }

      @Override
      public boolean active(RunPlace place, String runId, String unit) throws Exception {
        return switch (presence.of(place, AgentUnit.recorded(runId, unit))) {
          case AgentSession.Presence.Running running -> true;
          case AgentSession.Presence.Gone gone -> false;
          case AgentSession.Presence.Unanswered silent ->
              throw new IOException(
                  "Container '" + place.instance() + "' gave no answer about run " + runId + ".");
        };
      }

      /**
       * Reads what the watcher itself would have: the exit code the unit holds — kept only by a
       * unit that failed, so a zero is no code at all — and the trigger the watcher recorded beside
       * the run when it ended it for a limit. A trigger that only notified ended nothing.
       */
      @Override
      public Ending ending(RunPlace place, String runId, String unit) throws Exception {
        var recorded = AgentUnit.recorded(runId, unit);
        var exitCode =
            agentSession
                .answeredExitStatus(place.instance(), recorded)
                .map(AgentSession.ExitState::exitCode)
                .filter(code -> code != 0);
        var limit =
            GuardrailTrigger.read(shell, place.instance(), recorded)
                .filter(GuardrailTrigger::stops)
                .map(GuardrailTrigger::cause);
        return new Ending(exitCode.orElse(null), limit.orElse(null));
      }

      @Override
      public void removeLaunchFiles(String instance, List<String> live) throws Exception {
        if (!agentSession.removeLaunchFiles(instance, live)) {
          throw new IOException("Container '" + instance + "' did not tidy its launch files.");
        }
      }

      @Override
      public List<String> disown(Predicate<String> held) throws Exception {
        return Workspaces.onThisBox(shell).disown(held);
      }
    };
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

  /**
   * One pass. The unread reports of the runs that serve a review are read first, newest first, a
   * few and for a while ({@link #READ_FIRST}, {@link #READ_FIRST_FOR}): a reviewer's late read can
   * hold its review for a refused login, and its stop must not be replayed, nor its review rescued,
   * before the pass knows. Every other unread report is read last, as before.
   */
  private int reconcileAll() {
    var replayed = 0;
    try {
      var readFirst = readServingFirst();
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
      read(unread().stream().filter(run -> !readFirst.contains(run.id())).toList());
      removeLeftovers();
    } catch (Exception e) {
      System.err.println("  [reconcile] missed-stop sweep aborted: " + e.getMessage());
    }
    return replayed;
  }

  /**
   * Removes what may outlive a run but must not: the launch file of every run of this box that is
   * not live, which a launch that died before its wrapper read it left in its container (HL4), and
   * the logins of every person the roster no longer holds, which a removal that crashed before
   * deleting them left on this runner (HL9), and their credentials in the workspaces they owned,
   * and what a push killed midway left in the host's staging directory. Only a container that had a
   * run start or finish since {@link #TIDY_LOOKBACK} before the last pass is asked (every one, on
   * the first pass after a start): a launch that dies leaves its file with a run that finishes, by
   * its launch's cleanup or by this sweep. A container that does not answer, or does not run the
   * tidy (a stopped one), is tried again on a later pass, and said once: not again until its tidy
   * has succeeded.
   */
  void removeLeftovers() {
    var pass = clock.get();
    var tidied = true;
    for (var place : sessionStore.liveRunsByPlace(localHandle.get(), tidiedSince).entrySet()) {
      try {
        unitProbe.removeLaunchFiles(place.getKey(), place.getValue());
        untidied.remove(place.getKey());
      } catch (Exception e) {
        tidied = false;
        if (untidied.add(place.getKey())) {
          System.err.println(
              "  [reconcile] could not tidy the launch files of "
                  + place.getKey()
                  + ": "
                  + e.getMessage());
        }
      }
    }
    if (tidied) {
      tidiedSince = pass.minus(TIDY_LOOKBACK);
    }
    ContainerFilePush.removeStaged(Duration.ofMinutes(1));
    var swept = fdes.logins().sweep(this::held);
    if (swept > 0) {
      System.err.println("  [reconcile] removed the logins of " + swept + " people removed");
    }
    disownRemoved();
  }

  /**
   * Whether person {@code id} keeps their stored logins: unless their row is gone, or it has been
   * disabled for {@link #DISABLED_FOR} of this process's monotonic time, so no clock step erases
   * early, since a node cannot tell a person main removed from a round that did not list them, and
   * disabling is undone by the next round that does. A restart starts the count again, which only
   * keeps logins longer.
   */
  private boolean held(String id) {
    var fde = fdes.byId(id);
    if (fde.isEmpty() || fde.get().active()) {
      disabledSince.remove(id);
      return fde.isPresent();
    }
    var now = nanos.getAsLong();
    return now - disabledSince.computeIfAbsent(id, seen -> now) < DISABLED_FOR.toNanos();
  }

  /** The monotonic clock the day a disabled person keeps their logins is counted on. */
  MissedStopReconciler counting(LongSupplier elapsed) {
    this.nanos = elapsed;
    return this;
  }

  /**
   * A person not active here, missing from the roster or disabled as a node marks one main removed,
   * holds nothing usable here. Incus is listed only at start, when someone stopped being active (or
   * a handle changed hands), or to retry a workspace not reached.
   */
  private void disownRemoved() {
    var roster = new HashMap<String, String>();
    fdes.list().stream()
        .filter(FdeStore.Fde::active)
        .forEach(fde -> roster.put(fde.handle(), fde.id()));
    var gone = new HashSet<String>();
    if (lastRoster != null) {
      lastRoster.forEach(
          (handle, id) -> {
            if (!id.equals(roster.get(handle))) {
              gone.add(handle);
            }
          });
    }
    if (lastRoster != null && gone.isEmpty() && undisowned.isEmpty()) {
      lastRoster = roster;
      return;
    }
    try {
      var unreached =
          unitProbe.disown(handle -> roster.containsKey(handle) && !gone.contains(handle));
      var left = new HashSet<String>();
      for (var line : unreached) {
        var instance = line.substring(0, Math.max(0, line.indexOf(':')));
        left.add(instance);
        if (!undisowned.contains(instance)) {
          System.err.println("  [reconcile] a removed person's credentials are left in " + line);
        }
      }
      undisowned.clear();
      undisowned.addAll(left);
      lastRoster = roster;
      unlisted = false;
    } catch (Exception e) {
      if (!unlisted) {
        System.err.println(
            "  [reconcile] could not take removed people's credentials out: " + e.getMessage());
      }
      unlisted = true;
    }
  }

  /** The sweep's rescue of stranded reviews, skipping the specs it already handled. */
  int rescueStrandedReviews(Set<String> handledThisSweep) {
    return strandedReviews.rescueStrandedReviews(handledThisSweep);
  }

  /**
   * Ends every running session this box owns whose process is gone, other than the one the
   * in-progress pass reconciles: a dispatch whose spec never left {@code pending} (a crash between
   * reserve and claim) or outlived its work, a spec-less ad-hoc session, a superseded build
   * session, a room or room-full run of a spec still under way, a reviewer of a spec in {@code
   * review}, and the newest build session of a spec in {@code review}, which the review rescue
   * leaves {@code running}. The newest loop run of an {@code in_progress} spec, or of a spec whose
   * stop this sweep already replayed, is the in-progress pass's: it replays that spec's stop, or
   * finishes a row whose recorded stop reached no finisher. Only a run older than {@link
   * #LAUNCH_GRACE} is touched, so a run still inside a healthy launch window is never disturbed,
   * and a run is ended only once it is {@linkplain #gone gone} — a reservation must never be freed
   * under a working agent — and no live watcher covers it, whose stop says more than this sweep
   * can.
   *
   * <p>How a dead run ends depends on whether anything waits on its stop. A reviewer or a fix agent
   * whose stop was never recorded has a review waiting on it, so its stop is published — the same
   * stop the watcher would have, without the exit code nobody observed — and the tracker finishes
   * the row while the pipeline goes on. Any other run is finished in place, with the exit code the
   * recorded stop naming it carried: the same {@code running → stopped} compare-and-set every
   * finisher uses, so racing the watcher's own completion can never overwrite a recorded exit,
   * logged as reconstructed. Foreign runs are left to their executing box and never probed.
   */
  int finishDeadSessions(Set<String> handledThisSweep) {
    var node = localHandle.get();
    var deadline = clock.get().minus(LAUNCH_GRACE);
    var released = 0;
    for (var run : sessionStore.running()) {
      if (!run.ownedBy(node)
          || !Objects.requireNonNullElse(run.startedAt(), Instant.MAX).isBefore(deadline)
          || reconciledBySpec(run, handledThisSweep)) {
        continue;
      }
      try {
        if (unwatchedAndGone(run) && end(run)) {
          released++;
        }
      } catch (Exception e) {
        System.err.println("  [reconcile] could not probe run " + run.id() + ": " + e.getMessage());
      }
    }
    return released;
  }

  private boolean end(RunStore.RunRow run) {
    if (Strings.isBlank(run.specId())) {
      return finish(run, null, "session with no spec whose recorded process is gone");
    }
    var recorded = stopCoverage(run.specId(), run);
    if (run.servesReview() && recorded.observedAt() == null) {
      publishStop(run, null, "its review waits on a stop nobody observed");
      return true;
    }
    return finish(run, recorded.exitCode(), "running with its recorded process gone");
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
    return finished(run, "running", exitCode);
  }

  /**
   * {@code run} finished {@code expected → stopped} with what its report says, read here ({@link
   * RunReports}); the write that finished it says what came of its login. Returns whether it did.
   */
  private boolean finished(RunStore.RunRow run, String expected, Integer exitCode) {
    return reports.finish(
        sessionStore,
        run,
        report -> sessionStore.finish(run.id(), expected, "stopped", exitCode, report));
  }

  /**
   * Whether {@code run} is the in-progress pass's to reconcile: its spec's newest loop run, while
   * the spec is {@code in_progress} or had its stop replayed earlier in this sweep, whose async
   * status flip must not make one sweep both replay and release the one run.
   */
  private boolean reconciledBySpec(RunStore.RunRow run, Set<String> handledThisSweep) {
    if (Strings.isBlank(run.specId())
        || sessionStore
            .latestLoopRun(run.specId())
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
    return !unitProbe.active(run.place(), run.id(), Objects.toString(run.unit(), ""));
  }

  /**
   * Whether a {@code running} run is this sweep's to end: its process is gone and no live watcher
   * covers it. A covered run's end is its watcher's to report — it is mid-kill, or its next
   * liveness poll is seconds away — and the sweep comes back to it.
   */
  private boolean unwatchedAndGone(RunStore.RunRow run) throws Exception {
    return gone(run) && !coverage.watching(run);
  }

  /**
   * Reads again what each finished run this box executed cost, while its report is unread: a
   * container that was stopped, missing or slow as the run finished, or an operator's stop, which
   * never waits on one (M3). Whatever the container answers is recorded and journaled, once: a
   * report, or none when the report is gone, says nothing or does not parse; only an unanswered
   * read is left for the next sweep, the run's directory kept by retention meanwhile; what it says
   * of the run's login is said by the read that recorded it (the reconciler's late read). Returns
   * how many were recorded.
   */
  int readUnreadUsage() {
    return read(unread());
  }

  /**
   * Reads, before any stop is replayed, the unread reports of at most {@link #READ_FIRST} runs that
   * serve a review, newest first, starting none after {@link #READ_FIRST_FOR}: the pass's first
   * replay waits at most that and one read ({@code RunReportReader.TIMEOUT}). A run whose read here
   * went unanswered is skipped by the next pass's, so one container that hangs never spends every
   * pass's budget (H2). A store that cannot list them costs the pass nothing but these reads.
   * Returns the runs it read.
   */
  private Set<String> readServingFirst() {
    var deadline = clock.get().plus(READ_FIRST_FOR);
    var tried = new HashSet<String>();
    var unanswered = new HashSet<String>();
    try {
      for (var run :
          unread().stream()
              .filter(RunStore.RunRow::servesReview)
              .filter(run -> !unansweredFirst.contains(run.id()))
              .toList()
              .reversed()) {
        if (tried.size() == READ_FIRST || !clock.get().isBefore(deadline)) {
          break;
        }
        tried.add(run.id());
        if (read(List.of(run)) == 0) {
          unanswered.add(run.id());
        }
      }
    } catch (RuntimeException e) {
      System.err.println("  [reconcile] could not read reviewers' reports first: " + e);
    }
    unansweredFirst = Set.copyOf(unanswered);
    return tried;
  }

  private List<RunStore.RunRow> unread() {
    return sessionStore.unread(localHandle.get());
  }

  private int read(List<RunStore.RunRow> runs) {
    var recorded = 0;
    for (var run : runs) {
      try {
        if (reports.finish(
            sessionStore,
            run,
            report -> report != null && sessionStore.recordRead(run.id(), report))) {
          recorded++;
        }
      } catch (RuntimeException e) {
        System.err.println(
            "  [reconcile] could not read the usage of run " + run.id() + ": " + e.getMessage());
      }
    }
    return recorded;
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
        if (unitProbe.active(run.place(), run.id(), StopOperations.runUnit(run).unitName())) {
          continue;
        }
        if (finished(run, "stopping", null)) {
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

  private boolean reconcile(SpecStore.SpecRow spec) throws Exception {
    var node = localHandle.get();
    var latest = sessionStore.latestLoopRun(spec.id()).filter(run -> run.ownedBy(node));
    if (latest.isEmpty()) {
      return false;
    }
    var session = latest.get();
    if (session.servesReview() && session.stoppedForLogin()) {
      return false;
    }
    var coverage = stopCoverage(spec.id(), session);
    var outcome = MissedStops.assess(session, coverage, clock.get(), LAUNCH_GRACE);
    return switch (outcome) {
      case MissedStops.Outcome.ReplayStop replay -> {
        if (!gone(session)) {
          yield false;
        }
        publishStop(session, replay.exitCode(), replay.why());
        yield true;
      }
      case MissedStops.Outcome.ProbeUnit probe -> {
        if (Strings.isBlank(session.unit()) || !unwatchedAndGone(session)) {
          yield false;
        }
        publishStop(session, null, "unit inactive or gone; " + probe.why());
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
    var since = Objects.requireNonNullElse(session.startedAt(), Instant.MIN);
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
   * Why the watcher ended {@code run}, as the newest stop recorded for this very run said, or empty
   * when none names a reason. A replay carries it, so a run killed for a limit is still a killed
   * run to the pipeline when the daemon died between recording the kill and acting on it. Another
   * run's stop, or one that names no run, never speaks for this one.
   */
  private Optional<String> recordedReason(RunStore.RunRow run) {
    return saidStops(run)
        .filter(stop -> Strings.isNotBlank(stop.reason()))
        .max(Comparator.comparing(RecordedStop::at))
        .map(RecordedStop::reason);
  }

  /** The authoritative stops already said for {@code run}, as the event store recorded them. */
  private Stream<RecordedStop> saidStops(RunStore.RunRow run) {
    return eventStore
        .forSpecAndType(run.specId(), Event.WellKnownTypes.AGENT_SESSION_STOPPED)
        .stream()
        .map(RecordedStop::of)
        .filter(stop -> stop.authoritative() && run.id().equals(stop.runId()));
  }

  /**
   * One recorded stop as the sweep reads it: when it was recorded, whether it carries a {@code
   * source}, the run it names (null for none), and the exit code and the watcher's reason it
   * carried for that run. An unreadable row is an authoritative stop naming no run.
   */
  private record RecordedStop(
      Instant at, boolean authoritative, String runId, Integer exitCode, String reason) {

    static RecordedStop of(EventStore.EventRow row) {
      try {
        var data = YamlUtil.parseMap(row.data());
        var runId = Objects.toString(data.get(Event.WellKnownData.RUN_ID), null);
        return new RecordedStop(
            timestampOf(row),
            Event.WellKnownData.authoritative(data),
            runId,
            runId == null ? null : Event.WellKnownData.exitCode(data),
            Objects.toString(data.get(Event.WellKnownData.REASON), null));
      } catch (Exception e) {
        return new RecordedStop(timestampOf(row), true, null, null, null);
      }
    }

    boolean speaksFor(String sessionId) {
      return runId == null || runId.equals(sessionId);
    }
  }

  private static final List<String> ACTED_ON_EVIDENCE =
      List.of(
          Event.WellKnownTypes.AGENT_FAILED,
          LoopNarrator.HELD_ON_QUESTION,
          "review_stage_started",
          "review_stage_passed",
          "review_stage_failed",
          "review_completed",
          "review_errored",
          "review_iteration_failed",
          "review_escalated");

  private boolean actedOnSince(String specId, Instant since) {
    return ACTED_ON_EVIDENCE.stream()
        .anyMatch(
            type ->
                eventStore.forSpecAndType(specId, type).stream()
                    .anyMatch(row -> !timestampOf(row).isBefore(since)));
  }

  private static Instant timestampOf(EventStore.EventRow row) {
    return Objects.requireNonNullElse(row.timestamp(), Instant.MAX);
  }

  /**
   * Publishes the stop of {@code session}, a run that is gone, saying everything still known of how
   * it ended: the exit code its row recorded, else the one its failed unit still holds, and the
   * limit its watcher ended it for — as a recorded stop of this run said, else as the watcher
   * recorded beside the run before a stop that never reached this box. The stop of a run whose row
   * had already ended is a replay and says so, so whoever narrates stops does not say it twice; the
   * stop of a run still recorded {@code running} is that run's first.
   */
  private void publishStop(RunStore.RunRow session, Integer exitCode, String why) {
    var replay = saidStops(session).findAny().isPresent();
    var ending = endingOf(session);
    var code = exitCode != null ? exitCode : ending.exitCode();
    var reason = recordedReason(session).orElse(ending.reason());
    System.err.println(
        "  [reconcile] replaying missed stop for "
            + session.project()
            + "/"
            + session.specId()
            + " ("
            + session.role()
            + " session "
            + session.id()
            + ", "
            + (reason != null ? reason : code != null ? "exit " + code : "exit unknown")
            + "): "
            + why);
    var stop = stopEvent(session, code, reason);
    if (lost(session, code, reason)) {
      stop = lost(stop);
    }
    bus.publish(replay ? replayed(stop) : stop);
  }

  /**
   * Whether {@code session}, still recorded running and ended with no exit or limit anyone saw, was
   * lost: it never recorded a process (its dispatch died making its workspace), or its instance is
   * stopped or gone (stopped by hand). A run that recorded a process in an instance that still runs
   * ended on its own, a cleanly exited transient unit leaving no code, and is judged on its work.
   */
  private boolean lost(RunStore.RunRow session, Integer code, String reason) {
    if (code != null || reason != null || RunStatus.isTerminal(session.status())) {
      return false;
    }
    try {
      return session.pid() == null || unitProbe.instanceGone(session.place());
    } catch (Exception unreadable) {
      if (unreadable instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  /** {@code stop} marked lost ({@link Event.WellKnownData#LOST}). */
  private static Event lost(Event stop) {
    var data = new LinkedHashMap<>(stop.data());
    data.put(Event.WellKnownData.LOST, true);
    return Event.of(stop.project(), stop.spec(), stop.type(), stop.agent(), stop.host(), data);
  }

  /**
   * {@code stop} marked as said before ({@link Event.WellKnownData#REPLAY}): the stop of a run
   * whose row had already ended, published again only so the loop goes on from it.
   */
  private static Event replayed(Event stop) {
    var data = new LinkedHashMap<>(stop.data());
    data.put(Event.WellKnownData.REPLAY, true);
    return Event.of(stop.project(), stop.spec(), stop.type(), stop.agent(), stop.host(), data);
  }

  /**
   * What the container still says of how {@code run} ended, or nothing when it cannot be read: the
   * stop is owed either way, and a run whose ending is unreadable is one nothing says ended badly.
   */
  private Ending endingOf(RunStore.RunRow run) {
    try {
      return unitProbe.ending(run.place(), run.id(), Objects.toString(run.unit(), ""));
    } catch (Exception unreadable) {
      if (unreadable instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println(
          "  [reconcile] could not read how run "
              + run.id()
              + " ended: "
              + unreadable.getMessage());
      return Ending.UNKNOWN;
    }
  }

  /**
   * The stop of {@code run} as this box reconstructs it: addressed to the run and its lane, so the
   * pipeline routes it as it would the watcher's own, and carrying the {@code reason} the watcher
   * recorded when it ended the run.
   */
  static Event stopEvent(RunStore.RunRow run, Integer exitCode, String reason) {
    return RunStops.of(
        Event.WellKnownData.SOURCE_RECONCILE,
        run.project(),
        run.specId(),
        run.agent(),
        run.id(),
        run.role(),
        exitCode,
        reason);
  }

  @Override
  public void close() {
    pass.close();
  }
}
