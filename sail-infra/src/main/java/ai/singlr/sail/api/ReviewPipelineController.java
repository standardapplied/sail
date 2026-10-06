/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.RunStore.RunRow;
import ai.singlr.sail.store.SpecStore;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Drives the review loop one stop at a time. Every agent the loop uses — the build, each reviewer,
 * the fix agent — is a run that ends with an authoritative {@code agent_session_stopped} on the
 * bus, and this controller is the one router of those stops, by the lane of the run that stopped.
 *
 * <p>It only routes. A stop becomes a {@link LoopTrigger}; {@link LoopDecision#next} reads the
 * spec's {@link LoopFacts} and names the one {@link LoopStep} to take; {@link LoopSteps} carries it
 * out, and what it came to is the next trigger, until a step leaves nothing to follow.
 *
 * <p>Nothing waits on an agent, and the loop's state between stops is rows that already exist. So a
 * daemon restart loses nothing: a run still going is re-armed with a watcher, one that ended
 * unobserved has its stop published by the missed-stop reconciler, and either way the stop lands
 * here and the loop goes on from the rows.
 *
 * <p>Events are delivered one at a time on the subscriber's drain thread, so two stops never race.
 */
public final class ReviewPipelineController implements EventSubscriber {

  private static final Set<String> ROUTED_TYPES =
      Set.of(Event.WellKnownTypes.AGENT_SESSION_STOPPED, Event.WellKnownTypes.AGENT_CANCELLED);

  /** How many steps one event may take: the longest chain the loop's table allows is shorter. */
  static final int MAX_STEPS = 8;

  /** The loop a stop moves, and what happened to it. */
  private record Routed(String project, String specId, LoopTrigger trigger) {

    static Routed of(RunRow run, LoopTrigger trigger) {
      return new Routed(run.project(), run.specId(), trigger);
    }
  }

  private final SpecStore specStore;
  private final RunStore runStore;
  private final Runnable syncTrigger;
  private final Supplier<String> localHandle;
  private final LoopFactsReader reader;
  private final Function<String, LoopFacts.Pipeline> pipelines;
  private final LoopNarrator narrator;
  private final LoopSteps steps;

  /**
   * @param reviewerResolver a project's default reviewer, for stages that name none
   * @param skills where a reviewer's and a fix agent's skill are read from at each launch
   * @param syncTrigger fired after every state change the loop makes, so main, the notification
   *     authority, sees the loop advance at once. A no-op on main and standalone boxes
   * @param localHandle this box's FDE handle: the loop acts only on runs this box executed
   */
  public ReviewPipelineController(
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      Function<String, ReviewPipelineConfig> configResolver,
      Function<String, String> reviewerResolver,
      ReviewLanes lanes,
      StageSkills skills,
      EventBus eventBus,
      Runnable syncTrigger,
      Supplier<String> localHandle) {
    this.specStore = Objects.requireNonNull(specStore, "specStore");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.syncTrigger = Objects.requireNonNull(syncTrigger, "syncTrigger");
    this.localHandle = Objects.requireNonNull(localHandle, "localHandle");
    Objects.requireNonNull(reviewStore, "reviewStore");
    Objects.requireNonNull(configResolver, "configResolver");
    Objects.requireNonNull(reviewerResolver, "reviewerResolver");
    Objects.requireNonNull(lanes, "lanes");
    Objects.requireNonNull(skills, "skills");
    this.pipelines = LoopFactsReader.pipelines(configResolver, reviewerResolver);
    this.reader = new LoopFactsReader(specStore, reviewStore, runStore, localHandle);
    this.narrator = new LoopNarrator(specStore, eventBus, syncTrigger);
    this.steps =
        new LoopSteps(
            specStore, reviewStore, lanes, skills, reader, narrator, syncTrigger, localHandle);
  }

  public ReviewPipelineController useMessages(MessageStore messages) {
    narrator.useMessages(Objects.requireNonNull(messages, "messages"));
    return this;
  }

  @Override
  public String name() {
    return "review-pipeline";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> ROUTED_TYPES.contains(e.type());
  }

  /** Reacts to a stop as this box's machinery. */
  @Override
  public void onEvent(Event event) {
    try {
      Actor.run(Actor.system(), () -> route(event));
    } catch (Exception e) {
      var named = Strings.isNotBlank(event.spec());
      var specId = named ? event.spec() : runOf(event).map(RunRow::specId).orElse(null);
      System.err.println(
          "review-pipeline: failed to process %s for spec %s: %s"
              .formatted(event.type(), specId, e.getMessage()));
      narrator.publishPipelineError(event.project(), specId, e);
    }
  }

  /**
   * The loop's only entry: the stop's own loop is driven, and then, whatever came of that, every
   * review of the project the stop may have freed, all under one reading of the pipeline.
   */
  private void route(Event event) {
    if (!isAuthoritative(event)) {
      return;
    }
    var read = reader.forOneEvent(pipelines);
    try {
      routed(event).ifPresent(stop -> drive(stop, read));
    } finally {
      resumeWaiting(event.project(), read);
    }
  }

  /**
   * A stop becomes the trigger of the lane that stopped, read from this box's own row of the run
   * and from the stop itself only when it names no run this box holds — never from its spec's
   * status, which is {@code in_progress} both while its build runs and while its fix agent does. A
   * role this box does not know is a build's, as a role-less stop always was; a retired invite's is
   * ignored, and so is a room run's: a chat is not work the loop judges. A run an operator stopped
   * is never routed by its lane, whoever reports its end and in whatever order — their cancel, the
   * watcher's stop of the unit that died under the halt, a replay of either: none is its own end.
   */
  private Optional<Routed> routed(Event event) {
    var cancelled = Event.WellKnownTypes.AGENT_CANCELLED.equals(event.type());
    var run = runOf(event).map(stopped -> cancelled ? stopped : finished(stopped, event));
    if (cancelled || run.filter(RunRow::stoppedByOperator).isPresent()) {
      return run.map(stopped -> Routed.of(stopped, new LoopTrigger.OperatorStopped(stopped)));
    }
    var data = event.data();
    var role =
        run.map(RunRow::role)
            .orElseGet(() -> Objects.toString(data.get(Event.WellKnownData.RUN_ROLE), null));
    var build =
        new Routed(
            event.project(),
            event.spec(),
            new LoopTrigger.BuildEnded(
                run.map(RunRow::id).orElse(null), Event.WellKnownData.exitCode(data)));
    var failure = LoopTrigger.failureOf(data);
    return switch (Lane.of(role).orElse(null)) {
      case REVIEW -> run.map(it -> Routed.of(it, new LoopTrigger.ReviewerEnded(it, failure)));
      case FIX -> run.map(it -> Routed.of(it, new LoopTrigger.FixEnded(it, failure)));
      case ROOM, ROOM_FULL -> Optional.empty();
      case BUILD, ADHOC -> Optional.of(build);
      case null -> Optional.of(build).filter(known -> !Lane.retired(role));
    };
  }

  /**
   * The run that stopped is finished before the loop acts on its stop, by the write the run tracker
   * makes on a thread of its own: the dispatch gate would refuse the run that follows beside a run
   * of its own spec still recorded {@code running}. Returns the row once that write is settled,
   * since only then is it known whether an operator's stop claimed the run first.
   */
  private RunRow finished(RunRow run, Event event) {
    if (!run.ownedBy(localHandle.get())) {
      return run;
    }
    if (RunTracker.finish(
        runStore, run.id(), "stopped", Event.WellKnownData.exitCode(event.data()))) {
      syncTrigger.run();
    }
    return runStore.findById(run.id()).orElse(run);
  }

  private Optional<RunRow> runOf(Event event) {
    var runId = Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null);
    return Strings.isBlank(runId) ? Optional.empty() : runStore.findById(runId);
  }

  /**
   * A stop frees whatever its run held, so every spec of the project the loop may be moving is told
   * ({@link LoopTrigger.Freed}): in {@code review} first, then {@code in_progress}. A failure for
   * one never costs the specs after it theirs, nor replaces the routed stop's.
   */
  private void resumeWaiting(String project, BiFunction<String, String, LoopFacts> read) {
    try {
      Stream.of(SpecStatus.REVIEW, SpecStatus.IN_PROGRESS)
          .map(status -> new SpecStore.SpecFilter(project, status.wire(), null, null, null))
          .flatMap(filter -> specStore.list(filter).stream())
          .forEach(spec -> freed(new Routed(project, spec.id(), new LoopTrigger.Freed()), read));
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not look for waiting reviews in " + project + ": " + e);
    }
  }

  private void freed(Routed spec, BiFunction<String, String, LoopFacts> read) {
    try {
      drive(spec, read);
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not go on with spec %s after a stop freed its claims: %s"
              .formatted(spec.specId(), e.getMessage()));
    }
  }

  /** Drives the loop {@code stop} moves; a stop that names no spec moves none. */
  private void drive(Routed stop, BiFunction<String, String, LoopFacts> read) {
    var specId = stop.specId();
    if (Strings.isNotBlank(specId)) {
      drive(specId, () -> read.apply(stop.project(), specId), stop.trigger(), steps::take);
    }
  }

  /**
   * Reads the facts, decides, acts, and repeats with what the step came to, until a step leaves
   * nothing to follow. No event of the loop's table takes more than {@link #MAX_STEPS} steps, so
   * one that does is a fault, and says which steps it took.
   */
  static void drive(
      String specId,
      Supplier<LoopFacts> read,
      LoopTrigger trigger,
      BiFunction<LoopFacts, LoopStep, Optional<LoopTrigger>> act) {
    var taken = new ArrayList<String>();
    var next = Optional.of(trigger);
    while (next.isPresent()) {
      var facts = read.get();
      var step = LoopDecision.next(facts, next.get());
      taken.add(step.getClass().getSimpleName());
      if (taken.size() > MAX_STEPS) {
        throw new IllegalStateException(
            "the review loop of spec %s took more than %d steps for one event: %s"
                .formatted(specId, MAX_STEPS, String.join(", ", taken)));
      }
      next = act.apply(facts, step);
    }
  }

  /**
   * Whether this stop is the real termination, not a turn's end: the in-container hook fires {@code
   * Stop} before the process exits, so the loop waits for the watcher's stop, which carries a
   * {@code source}. A sync-derived stop tells of an agent that ran on another box, whose own
   * controller drives the review there: acting on it here would review the wrong box's checkout.
   */
  private static boolean isAuthoritative(Event event) {
    return Event.WellKnownData.authoritative(event.data())
        && !Event.WellKnownData.SOURCE_SYNC.equals(event.data().get(Event.WellKnownData.SOURCE));
  }
}
