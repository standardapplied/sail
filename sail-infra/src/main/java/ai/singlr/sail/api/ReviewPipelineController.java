/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageType;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.FindingParser;
import ai.singlr.sail.engine.FixTaskBuilder;
import ai.singlr.sail.engine.ReviewPromptBuilder;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Drives the review loop one stop at a time. Every agent the loop uses — the build, each reviewer,
 * the fix agent — is a run that ends with an authoritative {@code agent_session_stopped} on the
 * bus, and this controller is the one router of those stops, by the lane of the run that stopped:
 *
 * <ul>
 *   <li>a <b>build</b>'s (or an ad-hoc run's) stop moves its spec to {@code review} and starts the
 *       review the spec is due;
 *   <li>a <b>reviewer</b>'s stop resolves the stage it ran from the findings in that run's own log,
 *       then launches the next stage, passes the review, or hands the findings to a fix agent;
 *   <li>a <b>fix agent</b>'s stop commits what it left uncommitted and starts the re-review as the
 *       next iteration;
 *   <li>a <b>room</b> run's stop is ignored: a chat is not work the loop judges.
 * </ul>
 *
 * <p>Nothing waits on an agent. A launch returns once the run is started ({@link ReviewLanes}), and
 * the loop's state between stops is rows that already exist: which stage a review is in is its
 * stage rows' statuses, which iteration its {@code iteration}, and which run it waits on the newest
 * live run that serves it. So a daemon restart loses nothing: a run that is still going is re-armed
 * with a watcher, one that ended unobserved has its stop published by the missed-stop reconciler,
 * and either way the stop lands here and the loop goes on from the rows.
 *
 * <p>A stop that is not the one the loop is waiting on — a duplicate, a replay, the stop of a run a
 * newer one has replaced — never repeats a step. While a run still serves the spec's latest review
 * it changes nothing; otherwise the loop goes on from what the review's rows say is owed ({@link
 * #resume}, read by {@link ReviewLoopState}): an errored review is retried as the same iteration, a
 * running one continues from its stages, a gate-failed one that never got its fix agent gets it.
 * That is both the loop's retry and its crash recovery, and the reconciler's replays are what drive
 * it. A pass parks the spec in {@code awaiting_merge} — the PR is open but unmerged, and only the
 * human who merges it marks the spec {@code done}.
 *
 * <p>A reviewer or a fix agent claims its spec's repos through the dispatch gate, and between one
 * run's stop and the next run's claim another run may take them — a chat turn in the spec's room,
 * another spec's build. A refused claim is not an error: the review waits, owed the step it could
 * not take, and every stop in the project tries the waiting reviews again, since a stop is what
 * frees a claim.
 *
 * <p>Events are delivered one at a time on the subscriber's own drain thread, so two stops never
 * race each other through the loop.
 */
public final class ReviewPipelineController implements EventSubscriber {

  private static final Set<String> ROUTED_TYPES =
      Set.of(Event.WellKnownTypes.AGENT_SESSION_STOPPED, Event.WellKnownTypes.AGENT_CANCELLED);

  /**
   * How many errored (infrastructure-failed) review attempts of one iteration may accumulate before
   * the spec escalates. Errored attempts retry without burning an iteration, and the reconciler
   * replays a stop for each one — unbounded, that pair is an infinite loop of doomed reviews;
   * bounded, a transient failure self-heals and a persistent one surfaces to a human.
   */
  static final int MAX_ERRORED_RETRIES = 3;

  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final RunStore runStore;
  private final Function<String, ReviewPipelineConfig> configResolver;
  private final Function<String, String> reviewerResolver;
  private final ReviewLanes lanes;
  private final EventBus eventBus;
  private final Runnable syncTrigger;
  private final Supplier<String> localHandle;
  private final ReviewLoopState loop;
  private MessageStore messageStore;

  /**
   * @param reviewerResolver resolves a project's default reviewer agent (the
   *     installed-agent-that-isn't-the-coder, else the coder for self-review — see {@code
   *     AgentRoster.reviewer}) for stages that do not name one explicitly
   * @param lanes launches the loop's reviewers and fix agents and reads what they said
   * @param syncTrigger fired after every state change the loop makes — a spec transition, a review
   *     or stage write, a run it started — so they propagate to main immediately through the same
   *     sync-on-write seam manual edits use; main is the notification authority and must see the
   *     loop advance without waiting for a periodic sync. A no-op on main and standalone boxes
   * @param localHandle this box's FDE handle: the loop acts only on runs this box executed
   */
  public ReviewPipelineController(
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      Function<String, ReviewPipelineConfig> configResolver,
      Function<String, String> reviewerResolver,
      ReviewLanes lanes,
      EventBus eventBus,
      Runnable syncTrigger,
      Supplier<String> localHandle) {
    this.specStore = Objects.requireNonNull(specStore, "specStore");
    this.reviewStore = Objects.requireNonNull(reviewStore, "reviewStore");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.configResolver = Objects.requireNonNull(configResolver, "configResolver");
    this.reviewerResolver = Objects.requireNonNull(reviewerResolver, "reviewerResolver");
    this.lanes = Objects.requireNonNull(lanes, "lanes");
    this.eventBus = eventBus;
    this.syncTrigger = Objects.requireNonNull(syncTrigger, "syncTrigger");
    this.localHandle = Objects.requireNonNull(localHandle, "localHandle");
    this.loop = new ReviewLoopState(reviewStore, runStore, localHandle);
  }

  public ReviewPipelineController useMessages(MessageStore messages) {
    this.messageStore = Objects.requireNonNull(messages, "messages");
    return this;
  }

  /**
   * Advances a spec's status and signals sync so the transition reaches main. Compare-and-set from
   * the two states the pipeline owns ({@code in_progress}, {@code review}): once any other
   * transition wins — above all an operator's {@code cancelled}, which is terminal — the pipeline's
   * stale write is dropped instead of resurrecting the spec.
   */
  private void advanceSpec(String specId, SpecStatus status) {
    var advanced =
        specStore.compareAndSetStatus(specId, SpecStatus.IN_PROGRESS, status)
            || specStore.compareAndSetStatus(specId, SpecStatus.REVIEW, status);
    if (!advanced) {
      System.err.println(
          "review-pipeline: spec "
              + specId
              + " no longer in a pipeline-owned status; not advancing it to "
              + status.wire());
      return;
    }
    syncTrigger.run();
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
      System.err.println(
          "review-pipeline: failed to process "
              + event.type()
              + " for spec "
              + specOf(event)
              + ": "
              + e.getMessage());
      publishPipelineError(event, e);
    }
  }

  /**
   * A failure in this handler used to be one journal line and a silently stranded spec — the review
   * never started and nothing downstream noticed (the field incident: a raced SQLite statement
   * killed the kickoff twice in one week). Publish it loudly so Slack shows the failure, and rely
   * on {@link MissedStopReconciler}'s replay to deliver the stop again on a later sweep. Publishing
   * must never mask the original failure.
   */
  private void publishPipelineError(Event event, Exception failure) {
    try {
      publishEvent(
          event.project(),
          specOf(event),
          "review_pipeline_error",
          Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
    } catch (RuntimeException e) {
      System.err.println("review-pipeline: could not publish pipeline error: " + e.getMessage());
    }
  }

  /** The spec an event is about: the one it names, else the one the run it names works. */
  private String specOf(Event event) {
    return Strings.isNotBlank(event.spec())
        ? event.spec()
        : runOf(event).map(RunStore.RunRow::specId).orElse(null);
  }

  /**
   * The one router (the loop's only entry): an authoritative stop goes to the handler of the lane
   * that stopped, read from this box's own row of the run and from the stop itself only when it
   * names no run this box holds. A stop is never routed by its spec's status — a spec is {@code
   * in_progress} both while its build runs and while its fix agent does, and only the lane tells a
   * late build stop from the fix agent's. A role this box does not know is a build's, as a
   * role-less stop always was; a retired invite's is ignored.
   *
   * <p>A run an operator stopped is never routed by its lane, whoever reports its end and in
   * whatever order — the operator's cancel, the watcher's stop of the unit that died under the
   * halt, the reconciler's replay of either: the unit's death would otherwise read as the run's own
   * end, a build to review, an error to retry, or a clean finish to build on.
   */
  private void route(Event event) {
    if (!isAuthoritative(event)) {
      return;
    }
    try {
      if (Event.WellKnownTypes.AGENT_CANCELLED.equals(event.type())) {
        runOf(event).ifPresent(this::operatorStopped);
      } else {
        routeStop(event);
      }
    } finally {
      resumeWaiting(event.project());
    }
  }

  private void routeStop(Event event) {
    var run = runOf(event).map(stopped -> finished(stopped, event));
    if (run.filter(RunStore.RunRow::stoppedByOperator).isPresent()) {
      operatorStopped(run.get());
      return;
    }
    var role =
        run.map(RunStore.RunRow::role)
            .orElseGet(
                () -> Objects.toString(event.data().get(Event.WellKnownData.RUN_ROLE), null));
    switch (Lane.of(role).orElse(null)) {
      case REVIEW -> run.ifPresent(reviewer -> reviewerStopped(reviewer, event));
      case FIX -> run.ifPresent(fix -> fixStopped(fix, event));
      case ROOM, ROOM_FULL -> {}
      case BUILD, ADHOC -> buildStopped(event);
      case null -> {
        if (!Lane.retired(role)) {
          buildStopped(event);
        }
      }
    }
  }

  /**
   * The run that stopped is finished before the loop acts on its stop, by the same write the run
   * tracker makes: the run that follows reserves through the dispatch gate, which would refuse it
   * beside a run of its own spec still recorded {@code running}, and the tracker hears the stop on
   * a thread of its own. Returns the row as it stands once that write is settled — the one the
   * router judges, since only then is it known whether an operator's stop claimed the run first.
   */
  private RunStore.RunRow finished(RunStore.RunRow run, Event event) {
    if (!run.ownedBy(localHandle.get())) {
      return run;
    }
    if (RunTracker.finish(
        runStore, run.id(), "stopped", Event.WellKnownData.exitCode(event.data()))) {
      syncTrigger.run();
    }
    return runStore.findById(run.id()).orElse(run);
  }

  private Optional<RunStore.RunRow> runOf(Event event) {
    var runId = Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null);
    return Strings.isBlank(runId) ? Optional.empty() : runStore.findById(runId);
  }

  /**
   * A stop frees whatever its run held, so every review of the project that a refused claim left
   * waiting takes its step now ({@link ReviewLanes.Launch.Deferred}) — of the specs whose loop this
   * box drives. Only a waiting step is taken here, a reviewer or a fix agent the gate would not
   * admit: an errored review keeps to the reconciler's pace, so a broken reviewer is not retried
   * three times in one breath, and a review whose run has ended waits for that run's own stop,
   * which may be the very next event.
   */
  private void resumeWaiting(String project) {
    try {
      Stream.of(SpecStatus.REVIEW, SpecStatus.IN_PROGRESS)
          .map(status -> new SpecStore.SpecFilter(project, status.wire(), null, null, null))
          .flatMap(filter -> specStore.list(filter).stream())
          .map(SpecStore.SpecRow::id)
          .forEach(specId -> resumeIfWaiting(project, specId));
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not look for waiting reviews in " + project + ": " + e);
    }
  }

  /**
   * One spec's waiting step, when this box drives its loop. Its failure is its own: it never costs
   * the specs after it theirs, nor replaces the failure of the stop that was being routed.
   */
  private void resumeIfWaiting(String project, String specId) {
    try {
      if (loop.drivenHere(specId) && waitsOnAClaim(loop.owed(specId))) {
        resume(project, specId);
      }
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not resume waiting spec " + specId + ": " + e.getMessage());
    }
  }

  private static boolean waitsOnAClaim(ReviewLoopState.Owed owed) {
    return owed instanceof ReviewLoopState.Owed.Advance || owed instanceof ReviewLoopState.Owed.Fix;
  }

  /**
   * A build ended: a non-zero exit is the agent's failure, surfaced and left for triage; anything
   * else — a clean exit, or a run the watcher or the reconciler ended without an exit code — hands
   * the spec to review, which judges the work the build left. A dispatch supersedes the reviews
   * before it, so a build's own stop finds none and starts the first. A stop that finds one, or
   * that a newer run of the spec's loop has replaced — a build whose spec was re-dispatched before
   * its stop was heard — is late or replayed, and the loop only goes on from what the spec's review
   * is owed: never a second review beside a working fix agent, a spec its fix agent is working
   * pulled back into {@code review}, nor a review started beside the build that replaced this one.
   */
  private void buildStopped(Event event) {
    var specId = event.spec();
    if (Strings.isBlank(specId)) {
      return;
    }
    var spec = specStore.findById(specId);
    if (spec.isEmpty() || !reviewable(spec.get().status())) {
      return;
    }
    if (runOf(event).filter(this::replaced).isPresent()) {
      resume(event.project(), specId);
      return;
    }
    var exitCode = Event.WellKnownData.exitCode(event.data());
    if (exitCode != null && exitCode != 0) {
      publishEvent(event.project(), specId, Event.WellKnownTypes.AGENT_FAILED, exit(exitCode));
      return;
    }
    if (reviewStore.latestReviewForSpec(specId).isPresent()) {
      resume(event.project(), specId);
      return;
    }
    startReview(event.project(), specId, 1);
  }

  /** Whether a newer run of its spec's loop has come after {@code run}. */
  private boolean replaced(RunStore.RunRow run) {
    return runStore
        .latestLoopRun(run.specId())
        .filter(newest -> !newest.id().equals(run.id()))
        .isPresent();
  }

  /**
   * Whether an authoritative stop for a spec in this status moves its review loop. Both {@code
   * in_progress} (the normal path) and {@code review} qualify: a spec can be moved to {@code
   * review} out of band — a manual edit, or a sync revision from another box — while its agent is
   * still running here, and the old {@code == IN_PROGRESS} guard then dropped the real stop and
   * stranded the spec in {@code review} with no review ever created. Every other status — {@code
   * cancelled} above all, and {@code done}, {@code awaiting_merge}, {@code archived}, {@code
   * draft}, {@code pending} — is someone's decision the loop never acts over, so stops for it are
   * ignored: no review starts, no stage is judged, no fix agent launches.
   */
  private static boolean reviewable(SpecStatus status) {
    return status == SpecStatus.IN_PROGRESS || status == SpecStatus.REVIEW;
  }

  private boolean reviewable(String specId) {
    return specId != null
        && specStore.findById(specId).filter(spec -> reviewable(spec.status())).isPresent();
  }

  /**
   * Starts iteration {@code iteration} of the spec's review: the spec is in review, the review row
   * is written {@code running}, and its first stage begins. A project with no pipeline parks the
   * spec in {@code review} for a person.
   */
  private void startReview(String project, String specId, int iteration) {
    advanceSpec(specId, SpecStatus.REVIEW);
    var config = configResolver.apply(project);
    if (config == null || config.stages().isEmpty()) {
      return;
    }
    advance(createReview(specId, iteration), config, project, specId);
  }

  private String createReview(String specId, int iteration) {
    var reviewId = reviewStore.createReview(specId, iteration);
    syncTrigger.run();
    return reviewId;
  }

  /**
   * The review's stage rows, one per configured stage, in order — created here for every stage the
   * review does not hold yet. A review is written as a row and then its stages, so one a crash
   * caught in between holds too few; it is completed before anything reads it as a review whose
   * every stage has passed.
   */
  private List<ReviewStore.StageRow> stagesOf(String reviewId, ReviewPipelineConfig config) {
    var held = reviewStore.stagesForReview(reviewId).size();
    if (held >= config.stages().size()) {
      return reviewStore.stagesForReview(reviewId);
    }
    for (var stageConfig : config.stages().subList(held, config.stages().size())) {
      reviewStore.createStage(
          reviewId, stageConfig.name(), stageConfig.type().name().toLowerCase(Locale.ROOT));
    }
    syncTrigger.run();
    return reviewStore.stagesForReview(reviewId);
  }

  /**
   * Goes on with a running review from its stage rows ({@link #stagesOf}): the first stage that has
   * not passed is the one the review is in. A human stage opens and waits for a person; an agent
   * stage gets its reviewer launched, and the review waits for that run's stop. With every stage
   * passed the review has.
   */
  private void advance(
      String reviewId, ReviewPipelineConfig config, String project, String specId) {
    var stages = stagesOf(reviewId, config);
    var stageConfigs = config.stages();
    for (var i = 0; i < stageConfigs.size(); i++) {
      var stage = stages.get(i);
      if ("passed".equals(stage.status())) {
        continue;
      }
      if (stageConfigs.get(i).type() == StageType.HUMAN) {
        awaitHuman(stage, project, specId);
      } else {
        launchReviewer(stage, stageConfigs.get(i), project, specId);
      }
      return;
    }
    reviewStore.updateReviewStatus(reviewId, "passed");
    advanceSpec(specId, SpecStatus.AWAITING_MERGE);
    postRoom(
        specId,
        ReviewNarration.passed(
            reviewStore.findReview(reviewId).map(ReviewStore.ReviewRow::iteration).orElse(0),
            reviewStore.findingsForReview(reviewId).stream()
                .filter(finding -> finding.resolution() == Finding.Resolution.OPEN)
                .toList(),
            reviewStore.disputedFindings(specId)));
    publishEvent(project, specId, "review_completed", null);
  }

  private void awaitHuman(ReviewStore.StageRow stage, String project, String specId) {
    if ("running".equals(stage.status())) {
      return;
    }
    reviewStore.startStage(stage.id(), "human");
    publishEvent(project, specId, "review_stage_started", stage.name());
    postRoom(specId, ReviewNarration.awaitingHuman(reviewStore.disputedFindings(specId)));
    syncTrigger.run();
  }

  /**
   * Starts an agent stage: the stage row turns {@code running}, then its reviewer launches as a run
   * that serves the review — in that order, so the reviewer a stage waits on is always a run
   * recorded after the stage started ({@link ReviewLoopState#stageReviewedBy}). A claim the gate
   * refuses leaves the stage started with no reviewer, for the next stop in the project to try
   * again, and a stage already started is not started again; this box announces the stage started
   * only once a reviewer is running for it. A stage that cannot start — no reviewer to resolve, a
   * container that will not take the launch — is an infrastructure error like any other.
   */
  private void launchReviewer(
      ReviewStore.StageRow stage, StageConfig stageConfig, String project, String specId) {
    var agent = stageConfig.agent() != null ? stageConfig.agent() : reviewerResolver.apply(project);
    if (agent == null) {
      stageCouldNotStart(
          stage,
          project,
          specId,
          "no reviewer agent resolved; set stages[].agent or agent.install in sail.yaml");
      return;
    }
    var spec = specStore.findById(specId);
    var branch = spec.map(SpecStore.SpecRow::branch).orElse("main");
    var repos = spec.map(SpecStore.SpecRow::repos).orElse(List.of());
    if (!"running".equals(stage.status())) {
      reviewStore.startStage(stage.id(), agent);
      syncTrigger.run();
    }
    var launched =
        launch(
            () -> {
              var carried =
                  reviewStore.carryForwardFindings(specId, stage.reviewId(), stage.name());
              var built =
                  ReviewPromptBuilder.build(
                      branch, repos, stageConfig.categories(), roomMessages(specId), carried);
              return new ReviewLanes.Invocation(
                  Lane.REVIEW,
                  stage.reviewId(),
                  project,
                  specId,
                  agent,
                  built.prompt(),
                  branch,
                  repos,
                  null,
                  spec.map(SpecStore.SpecRow::reasoningEffort).orElse(null),
                  built.renderedMessages().stream().map(MessageStore.MessageRow::id).toList());
            },
            stage.reviewId());
    switch (launched) {
      case Launched.Serving serving ->
          publishEvent(project, specId, "review_stage_started", stage.name());
      case Launched.Waiting waiting -> {}
      case Launched.Failed failed ->
          stageCouldNotStart(
              stage, project, specId, noun(Lane.REVIEW) + " could not start: " + failed.why());
    }
  }

  private void stageCouldNotStart(
      ReviewStore.StageRow stage, String project, String specId, String why) {
    System.err.println("review-pipeline: agent stage '" + stage.name() + "': " + why);
    reviewStore.completeStage(stage.id(), "failed", why);
    handleStageError(stage.reviewId(), project, specId, stage.name(), why);
  }

  /** How a launch left the review it serves. */
  private sealed interface Launched {

    /** A run now serves the review, and the loop waits for its stop. */
    record Serving() implements Launched {}

    /** The gate refused the claim: the review waits for a stop in the project to free it. */
    record Waiting() implements Launched {}

    /** Nothing was started, and nothing will be: an infrastructure error naming why. */
    record Failed(String why) implements Launched {}
  }

  /**
   * Launches the run {@code invocation} describes for review {@code reviewId}. A launch that fails
   * after its agent started — the launch command or the status read failing over a live unit —
   * still left a run serving the review, and the loop waits for that run's stop rather than call a
   * working agent a failure and act over it.
   */
  private Launched launch(Supplier<ReviewLanes.Invocation> invocation, String reviewId) {
    try {
      return switch (lanes.launch(invocation.get(), localHandle.get())) {
        case ReviewLanes.Launch.Started started -> {
          syncTrigger.run();
          yield new Launched.Serving();
        }
        case ReviewLanes.Launch.Deferred deferred -> {
          System.err.println(
              "review-pipeline: review " + reviewId + " waits for its claim — " + deferred.why());
          yield new Launched.Waiting();
        }
      };
    } catch (Exception e) {
      if (!loop.served(reviewId)) {
        return new Launched.Failed(reasonOf(e));
      }
      System.err.println(
          "review-pipeline: a launch for review "
              + reviewId
              + " reported a failure after its agent started ("
              + reasonOf(e)
              + "); waiting for that run's stop");
      syncTrigger.run();
      return new Launched.Serving();
    }
  }

  /**
   * A reviewer ended. When it is the run its review waits on, the stage it ran is resolved: a
   * reviewer the watcher killed or that exited non-zero is an infrastructure error naming why, and
   * one that ended cleanly is judged on the findings in its own log.
   */
  private void reviewerStopped(RunStore.RunRow run, Event event) {
    var config = configResolver.apply(run.project());
    var served =
        awaited(run, "running")
            .flatMap(review -> loop.stageReviewedBy(review.id(), run))
            .flatMap(stage -> served(stage, config))
            .orElse(null);
    if (served == null) {
      resume(run.project(), run.specId());
      return;
    }
    var stage = served.stage();
    var reviewId = stage.reviewId();
    var outcome =
        failureOf(event)
            .map(failure -> errored(stage, noun(Lane.REVIEW) + " " + failure))
            .orElseGet(() -> resolveStage(stage, served.config(), run));
    syncTrigger.run();
    switch (outcome) {
      case StageOutcome.Errored errored ->
          handleStageError(reviewId, run.project(), run.specId(), stage.name(), errored.message());
      case StageOutcome.GateFailed failed ->
          gateFailed(reviewId, config, served, run.project(), run.specId());
      case StageOutcome.Passed passed -> advance(reviewId, config, run.project(), run.specId());
    }
  }

  /** A stage row and the configuration it runs under. */
  private record ServedStage(ReviewStore.StageRow stage, StageConfig config) {}

  /** The stage with the configuration of its name; empty for one the project no longer names. */
  private static Optional<ServedStage> served(
      ReviewStore.StageRow stage, ReviewPipelineConfig config) {
    return config.stages().stream()
        .filter(stageConfig -> stageConfig.name().equals(stage.name()))
        .findFirst()
        .map(stageConfig -> new ServedStage(stage, stageConfig));
  }

  /**
   * The review {@code run} serves, when the loop is waiting on this very run: the run is this box's
   * and the newest of its spec's loop — no reviewer, fix agent or re-dispatched build has come
   * after it — its spec is still the loop's to move, and its review is the spec's latest in this
   * dispatch attempt and in one of {@code statuses}. Anything else is a stop the loop has already
   * moved past, or one it must not act over.
   */
  private Optional<ReviewStore.ReviewRow> awaited(RunStore.RunRow run, String... statuses) {
    if (!run.ownedBy(localHandle.get()) || run.reviewId() == null || !reviewable(run.specId())) {
      return Optional.empty();
    }
    if (replaced(run)) {
      return Optional.empty();
    }
    return reviewStore
        .latestReviewForSpec(run.specId())
        .filter(review -> review.id().equals(run.reviewId()))
        .filter(review -> List.of(statuses).contains(review.status()));
  }

  /**
   * Why a run did not end well, or empty when it did: the guardrail the watcher ended it for
   * ({@code killed: time limit (45m)}, {@code killed: stall (20m)}), else its non-zero exit ({@code
   * failed: exit 1}). A stop that names neither — a clean exit, or a reconciled stop of a run
   * nothing is known to have ended — is no failure, and the run's work is judged on what it left.
   */
  private static Optional<String> failureOf(Event event) {
    var reason = Objects.toString(event.data().get(Event.WellKnownData.REASON), null);
    if (Strings.isNotBlank(reason)) {
      return Optional.of("killed: " + reason);
    }
    var exitCode = Event.WellKnownData.exitCode(event.data());
    return exitCode != null && exitCode != 0
        ? Optional.of("failed: " + exit(exitCode))
        : Optional.empty();
  }

  private static String exit(int exitCode) {
    return "exit " + exitCode;
  }

  /** What the loop calls the agent of a review lane when it says how that agent ended. */
  private static String noun(Lane lane) {
    return lane == Lane.FIX ? "fix agent" : "reviewer";
  }

  /**
   * Goes on from the rows when a stop arrives that the loop is not waiting on — a duplicate, a
   * replay, the stop of a run whose step a crash cut short, a stop that freed a claim a review was
   * waiting for. What the spec's latest review is owed is done ({@link ReviewLoopState#owed}): a
   * review that failed by infrastructure error is retried as the same iteration, within its budget
   * (the loop's retry: the reconciler replays a stop for every errored review, once); a {@code
   * running} review whose stage has no reviewer goes on from its stages; and a review that failed
   * its gate and never got its fix agent gets it. A review a run still serves, one that passed or
   * escalated, and one whose reviewer or fix agent has ended — whose own stop is the only word on
   * what its work is worth — are left as they are.
   */
  private void resume(String project, String specId) {
    if (!reviewable(specId)) {
      return;
    }
    switch (loop.owed(specId)) {
      case ReviewLoopState.Owed.Retry retry -> retry(retry.review(), project, specId);
      case ReviewLoopState.Owed.Advance advance -> goOn(advance.review(), project, specId);
      case ReviewLoopState.Owed.Fix fix -> owedFix(fix.review(), project);
      case ReviewLoopState.Owed.Stop awaitsItsStop -> {}
      case ReviewLoopState.Owed.Nothing nothing -> {}
    }
  }

  /**
   * Runs an errored review's iteration again — an infrastructure error burns no iteration — until
   * {@link #MAX_ERRORED_RETRIES} attempts of it have errored in a row, when the spec escalates.
   */
  private void retry(ReviewStore.ReviewRow errored, String project, String specId) {
    if (erroredAttempts(specId, errored.iteration()) >= MAX_ERRORED_RETRIES) {
      var reason =
          MAX_ERRORED_RETRIES
              + " review attempts errored in a row at iteration "
              + errored.iteration()
              + "; fix the reviewer, then re-dispatch with --restart";
      System.err.println("review-pipeline: spec " + specId + " escalated — " + reason);
      escalate(project, specId, errored.id(), reason);
      return;
    }
    startReview(project, specId, errored.iteration());
  }

  /** Goes on with a running review no run serves, from its stage rows. */
  private void goOn(ReviewStore.ReviewRow review, String project, String specId) {
    advanceSpec(specId, SpecStatus.REVIEW);
    var config = configResolver.apply(project);
    if (config == null || config.stages().isEmpty()) {
      return;
    }
    advance(review.id(), config, project, specId);
  }

  /**
   * Hands a gate-failed review to its fix agent when none was ever launched for it: the loop was
   * cut short, or the gate refused the fix agent's claim, before the fix run was recorded. The
   * verdict was said when the gate failed and is not said again.
   */
  private void owedFix(ReviewStore.ReviewRow review, String project) {
    var config = configResolver.apply(project);
    var failed =
        reviewStore.stagesForReview(review.id()).stream()
            .filter(stage -> "failed".equals(stage.status()))
            .findFirst()
            .flatMap(stage -> served(stage, config));
    fixOrEscalate(review, config, failed, project);
  }

  /** How an agent stage ended: gate verdicts are review outcomes; errors are infrastructure. */
  sealed interface StageOutcome {
    record Passed() implements StageOutcome {}

    record GateFailed() implements StageOutcome {}

    record Errored(String message) implements StageOutcome {}
  }

  private StageOutcome errored(ReviewStore.StageRow stage, String message) {
    reviewStore.completeStage(stage.id(), "failed", message);
    return new StageOutcome.Errored(message);
  }

  /**
   * Judges a stage on what its reviewer said: the findings parsed from that run's own log, applied
   * with the reviewer's rulings on the findings the stage carries, then weighed against the stage's
   * gate.
   */
  private StageOutcome resolveStage(
      ReviewStore.StageRow stage, StageConfig stageConfig, RunStore.RunRow run) {
    try {
      var parseResult = FindingParser.parse(lanes.output(run));
      if (parseResult instanceof FindingParser.ParseResult.Unparseable unparseable) {
        return errored(
            stage, "reviewer output unparseable: " + String.join("; ", unparseable.warnings()));
      }
      var parsed = (FindingParser.ParseResult.Parsed) parseResult;
      var carried = reviewStore.carryForwardFindings(run.specId(), stage.reviewId(), stage.name());
      applyStageResult(run.specId(), stage.id(), carried, parsed);

      var findings = reviewStore.findingsForStage(stage.id());
      var passed = stageConfig.gate().passes(findings);

      reviewStore.completeStage(stage.id(), passed ? "passed" : "failed");
      publishEvent(
          run.project(),
          run.specId(),
          passed ? "review_stage_passed" : "review_stage_failed",
          stage.name(),
          ReviewNarration.severityCounts(findings));

      return passed ? new StageOutcome.Passed() : new StageOutcome.GateFailed();
    } catch (Exception e) {
      System.err.println(
          "review-pipeline: agent stage '" + stage.name() + "' errored: " + e.getMessage());
      return errored(stage, e.getMessage());
    }
  }

  /**
   * Applies the reviewer's complete result — verdicts and new findings — as one atomic write,
   * fail-closed on both axes. Verdicts: {@code fixed} and {@code disputed} (both evidence-backed,
   * enforced by {@link FindingParser#reconcile}) resolve the predecessor row with the evidence
   * recorded; everything else — {@code still_open}, a missing verdict, a ruling without evidence —
   * re-attaches the finding to this stage as a carried row, so it keeps aging and keeps facing the
   * gate. A reviewer that stops mentioning last iteration's finding launders nothing. A finding a
   * human resolved while the stage ran keeps that resolution, and the room is told the ruling on it
   * was set aside — whether it was resolved before the reviewer's run ended, and so is no longer
   * among the findings this stage carries, or in the moment between. Atomicity: if any new finding
   * fails to insert, the resolutions roll back too, so an errored stage retries with every carried
   * finding still {@code OPEN} instead of silently retired.
   */
  private void applyStageResult(
      String specId,
      String stageId,
      List<Finding> carried,
      FindingParser.ParseResult.Parsed parsed) {
    var reconciled = FindingParser.reconcile(carried, parsed.verdicts());
    warn(reconciled.warnings());
    var resolvedMeanwhile =
        reconciled.unmatchedVerdictIds().stream().filter(this::resolvedFinding).count();
    var misaddressed = reconciled.unmatchedVerdictIds().size() - resolvedMeanwhile;
    if (misaddressed > 0) {
      postRoom(specId, ReviewNarration.misaddressedVerdicts((int) misaddressed));
    }
    var rulings =
        carried.stream()
            .map(
                finding -> {
                  var verdict = reconciled.rulings().get(finding.id());
                  return new ReviewStore.StageRuling(
                      finding, resolutionOf(verdict.ruling()), verdict.evidence());
                })
            .toList();
    var setAside =
        reviewStore.applyStageResult(stageId, rulings, parsed.findings()).size()
            + (int) resolvedMeanwhile;
    if (setAside > 0) {
      postRoom(specId, ReviewNarration.alreadyResolved(setAside));
    }
  }

  /**
   * Whether {@code findingId} names a finding someone resolved: the reviewer was asked to rule on
   * it when its stage began, and it left the carried set before the reviewer answered.
   */
  private boolean resolvedFinding(String findingId) {
    return reviewStore
        .findFinding(findingId)
        .filter(finding -> finding.resolution() != Finding.Resolution.OPEN)
        .isPresent();
  }

  private static Finding.Resolution resolutionOf(FindingParser.Ruling ruling) {
    return switch (ruling) {
      case FIXED -> Finding.Resolution.FIXED;
      case DISPUTED -> Finding.Resolution.DISPUTED;
      case STILL_OPEN -> Finding.Resolution.OPEN;
    };
  }

  private static void warn(List<String> warnings) {
    warnings.forEach(warning -> System.err.println("review-pipeline: " + warning));
  }

  /**
   * An errored stage is an infrastructure failure, not a review verdict: record why on the review,
   * say so loudly, and stop — without a fix iteration (there are no findings to fix) and without
   * counting against {@code max_iterations} (the next stop retries the same iteration; see {@link
   * #retry}).
   */
  private void handleStageError(
      String reviewId, String project, String specId, String stageName, String message) {
    reviewStore.failReviewWithError(reviewId, message);
    syncTrigger.run();
    System.err.println(
        "review-pipeline: review "
            + reviewId
            + " for spec "
            + specId
            + " errored at stage '"
            + stageName
            + "': "
            + message);
    publishEvent(project, specId, "review_errored", message);
  }

  /**
   * A stage failed its gate: the review has failed, the room is told what it found, and its
   * findings go to a fix agent — or the spec escalates ({@link #fixOrEscalate}).
   */
  private void gateFailed(
      String reviewId,
      ReviewPipelineConfig config,
      ServedStage failedStage,
      String project,
      String specId) {
    reviewStore.updateReviewStatus(reviewId, "failed");
    var review = reviewStore.findReview(reviewId);
    if (review.isEmpty()) {
      return;
    }
    postRoom(
        specId,
        ReviewNarration.failed(
            review.get().iteration(),
            reviewStore.openFindingsForReview(reviewId),
            reviewStore.disputedFindings(specId)));
    fixOrEscalate(review.get(), config, Optional.of(failedStage), project);
  }

  /**
   * What follows a gate failure: the spec escalates when a finding of the failed stage is stuck or
   * the iterations are spent, and otherwise the review's open findings go to a fix agent.
   */
  private void fixOrEscalate(
      ReviewStore.ReviewRow review,
      ReviewPipelineConfig config,
      Optional<ServedStage> failedStage,
      String project) {
    var specId = review.specId();
    var stuck =
        failedStage.flatMap(
            failed ->
                stuckFinding(
                    failed.config().gate(),
                    reviewStore.findingsForStage(failed.stage().id()),
                    config.maxFindingAge()));
    if (stuck.isPresent()) {
      escalate(
          project,
          specId,
          review.id(),
          "finding \""
              + stuck.get().title()
              + "\" survived "
              + reviewStore.findingAge(stuck.get().id())
              + " fix iterations; the loop is stuck on it — fix or dismiss it, then re-dispatch");
      return;
    }
    if (review.iteration() >= config.maxIterations()) {
      escalate(
          project,
          specId,
          review.id(),
          "review iterations exhausted ("
              + config.maxIterations()
              + "); re-dispatch with --restart to start a fresh attempt");
      return;
    }
    var openFindings = reviewStore.openFindingsForReview(review.id());
    if (!openFindings.isEmpty()) {
      launchFix(review.id(), specId, openFindings, project);
    }
  }

  /**
   * The convergence check: a gate-blocking finding whose {@code carried_from} chain shows it has
   * already survived {@code maxFindingAge} fix iterations. Sub-gate findings may age freely — they
   * do not drive the loop — and a loop resolving old blockers while new ones surface never trips
   * this: that loop is converging and runs to {@code max_iterations} as before. Scoped to the
   * failed stage's own findings: a finding is gate-blocking only under the gate of the stage that
   * owns it, so an aged finding another stage's gate permits never counts as this stage's blocker.
   */
  private Optional<Finding> stuckFinding(
      ReviewPipelineConfig.Gate gate, List<Finding> stageFindings, int maxFindingAge) {
    return stageFindings.stream()
        .filter(gate::blocks)
        .filter(finding -> reviewStore.findingAge(finding.id()) >= maxFindingAge)
        .findFirst();
  }

  /**
   * Hands a failed review's findings to the spec's own agent as a fix run that serves that review.
   * The run acts as itself — principal {@code <agent>/fix-<runId>} — so the room's audit trail
   * attributes its posts to the fix lane, never to the reviewer, and it runs with the stop gate
   * asking for a committed, pushed tree. Once the run exists the spec is back {@code in_progress}
   * and the room is told a fix iteration started; the loop then waits for its stop. A claim the
   * gate refuses changes nothing: the fix is still owed, and the next stop in the project tries it
   * again.
   */
  private void launchFix(String reviewId, String specId, List<Finding> findings, String project) {
    var spec = specStore.findById(specId).orElse(null);
    if (spec == null) {
      return;
    }
    var launched =
        launch(
            () -> {
              var built =
                  FixTaskBuilder.build(specId, spec.title(), findings, roomMessages(specId));
              return new ReviewLanes.Invocation(
                  Lane.FIX,
                  reviewId,
                  project,
                  specId,
                  spec.agent() != null ? spec.agent() : "claude-code",
                  built.task(),
                  spec.branch(),
                  spec.repos(),
                  spec.model(),
                  spec.reasoningEffort(),
                  built.renderedMessages().stream().map(MessageStore.MessageRow::id).toList());
            },
            reviewId);
    switch (launched) {
      case Launched.Serving serving -> {
        advanceSpec(specId, SpecStatus.IN_PROGRESS);
        publishEvent(project, specId, "review_iteration_started", null);
      }
      case Launched.Waiting waiting -> {}
      case Launched.Failed failed ->
          fixFailed(
              reviewId, project, specId, noun(Lane.FIX) + " could not start: " + failed.why());
    }
  }

  /**
   * Why a launch failed, as far down as it is said: a launch error wraps what refused it — an agent
   * sail does not know, a container that will not answer — and the wrapper alone says only that the
   * launch failed.
   */
  private static String reasonOf(Exception failure) {
    var cause = failure.getCause();
    return cause == null || Strings.isBlank(cause.getMessage())
        ? failure.getMessage()
        : failure.getMessage() + " " + cause.getMessage();
  }

  /**
   * The fix agent ended. When it is the run its review waits on and it ended well, whatever it left
   * uncommitted on the spec's branch is committed and pushed — the gate is a nudge, not a jail, and
   * otherwise the re-review judges a branch without the fixes and the shared clone carries the
   * leftovers into the next dispatch — and the review runs again as the next iteration. A fix agent
   * the watcher killed or that exited non-zero did not address the findings: nothing of its is
   * committed, the branch still holds the code the reviewer just failed, and the spec escalates.
   * The review is read again once the rescue is done: a re-dispatch that superseded it meanwhile
   * owns the spec, and no re-review starts beside its build. The re-review's row is written before
   * the spec moves, so no crash leaves a spec in {@code review} with nothing owed.
   */
  private void fixStopped(RunStore.RunRow run, Event event) {
    var review = awaited(run, "failed").filter(failed -> !failed.errored());
    var spec = specStore.findById(run.specId());
    if (review.isEmpty() || spec.isEmpty()) {
      resume(run.project(), run.specId());
      return;
    }
    var reviewId = review.get().id();
    var failure = failureOf(event);
    if (failure.isPresent()) {
      fixFailed(reviewId, run.project(), run.specId(), noun(Lane.FIX) + " " + failure.get());
      return;
    }
    try {
      var rescued =
          lanes.ensureCommitted(
              run.project(),
              spec.get().repos(),
              spec.get().branch(),
              FixTaskBuilder.commitMessage(reviewStore.openFindingsForReview(reviewId)));
      if (!rescued.isEmpty()) {
        publishGuardrail(
            run.project(),
            run.specId(),
            "fix agent left uncommitted changes in " + ReviewNarration.rescues(rescued),
            "committed and pushed them to " + spec.get().branch());
      }
    } catch (Exception e) {
      fixFailed(
          reviewId,
          run.project(),
          run.specId(),
          "fix agent's work could not be committed: " + e.getMessage());
      return;
    }
    if (awaited(run, "failed").isEmpty()) {
      return;
    }
    var config = configResolver.apply(run.project());
    var reReview = createReview(run.specId(), review.get().iteration() + 1);
    advanceSpec(run.specId(), SpecStatus.REVIEW);
    advance(reReview, config, run.project(), run.specId());
  }

  /**
   * A fix iteration that did not address the findings: said so loudly, with why, as {@code
   * review_iteration_failed} (parallel to {@code review_errored}), and escalated — there is no
   * re-review to run, because the branch still holds the code the reviewer just failed.
   */
  private void fixFailed(String reviewId, String project, String specId, String why) {
    System.err.println(
        "review-pipeline: fix iteration of review "
            + reviewId
            + " for spec "
            + specId
            + " failed: "
            + why);
    publishEvent(project, specId, "review_iteration_failed", why);
    escalate(
        project, specId, reviewId, "fix iteration failed — " + why + "; triage and re-dispatch");
  }

  /**
   * An operator stopped a run. For a reviewer or a fix agent the stop was a person's decision about
   * the loop, so the loop does not retry over it: the review escalates, and the spec waits in
   * {@code review} for that person — also when the stop landed while the run was still launching
   * and the failed launch already errored the review. A build an operator stopped has no review to
   * escalate and starts none: its spec is whoever owns it to move. While the halt is still under
   * way nothing is decided, since a halt that fails gives the run back; the first stop heard once
   * it is verified escalates, and the rest find the review already escalated.
   */
  private void operatorStopped(RunStore.RunRow run) {
    if (!RunStatus.isTerminal(run.status())) {
      return;
    }
    var review = awaited(run, "running", "failed");
    if (review.isEmpty()) {
      return;
    }
    var why = noun(run.lane().orElse(Lane.REVIEW)) + " stopped by an operator";
    escalate(
        run.project(),
        run.specId(),
        review.get().id(),
        why + "; re-dispatch with --restart to start a fresh attempt");
    reviewStore.stagesForReview(review.get().id()).stream()
        .filter(stage -> "running".equals(stage.status()))
        .forEach(stage -> reviewStore.completeStage(stage.id(), "failed", why));
    syncTrigger.run();
  }

  /** Errored attempts of this iteration in the current dispatch attempt — the retry budget. */
  private long erroredAttempts(String specId, int iteration) {
    return reviewStore.reviewsForSpec(specId).stream()
        .filter(r -> !r.superseded() && r.errored() && r.iteration() == iteration)
        .count();
  }

  private String roomOf(String specId) {
    return specStore.findById(specId).map(SpecStore.SpecRow::roomIdOrIdentity).orElse(specId);
  }

  /**
   * The room's recent messages for the reviewer's prompt and the fix task. Best-effort like {@link
   * #postRoom}: the conversation enriches the prompt, it is not a precondition — a dead message
   * store must degrade the review, never error it.
   */
  private List<MessageStore.MessageRow> roomMessages(String specId) {
    if (messageStore == null) {
      return List.of();
    }
    try {
      return messageStore.list(roomOf(specId), null, 20);
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not read the room of spec " + specId + ": " + e.getMessage());
      return List.of();
    }
  }

  /**
   * Posts the pipeline's own narration to the spec's room. Lifecycle beats (stage started, fix
   * iteration, guardrail, escalation) already ride the event stream; the room carries only what
   * events cannot — the findings themselves. This is also the loop's cross-iteration memory: the
   * reviewer's prompt includes the room's recent messages, so the next pass sees the previous
   * verdict. Best-effort by design — a room write must never fail the pipeline.
   */
  private void postRoom(String specId, String body) {
    if (messageStore == null) return;
    try {
      messageStore.append(roomOf(specId), MessageStore.SAIL_AUTHOR, body, null);
      syncTrigger.run();
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not post to the room of spec " + specId + ": " + e.getMessage());
    }
  }

  private void publishGuardrail(String project, String specId, String reason, String action) {
    if (eventBus == null) return;
    eventBus.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.GUARDRAIL_TRIGGERED,
            Event.SAIL_AGENT,
            hostname(),
            Map.of("reason", reason, "action", action)));
  }

  /** The reason travels as the event detail, so Slack says why — not a one-size-fits-all line. */
  private void escalate(String project, String specId, String reviewId, String reason) {
    reviewStore.updateReviewStatus(reviewId, "escalated");
    advanceSpec(specId, SpecStatus.REVIEW);
    publishEvent(project, specId, "review_escalated", reason);
  }

  private void publishEvent(String project, String specId, String type, String detail) {
    publishEvent(project, specId, type, detail, Map.of());
  }

  private void publishEvent(
      String project, String specId, String type, String detail, Map<String, Object> findings) {
    if (eventBus == null) return;
    var data = new LinkedHashMap<String, Object>();
    if (detail != null) {
      data.put("detail", detail);
    }
    if (!findings.isEmpty()) {
      data.put("findings", findings);
    }
    eventBus.publish(Event.of(project, specId, type, Event.SAIL_AGENT, hostname(), data));
  }

  /**
   * Whether this stop is the real termination, not a mid-run turn-end. The in-container agent hook
   * fires {@code Stop} when a turn ends — before the process exits and with no exit code — so the
   * controller waits for the watcher's stop (which carries a {@code source}) rather than acting on
   * a turn boundary, or on a crash the hook can't report an exit code for. A sync-derived stop is
   * narration, not execution: it describes an agent that ran on another box, whose own controller
   * drives the review there — acting on it here would review the wrong box's checkout.
   */
  private static boolean isAuthoritative(Event event) {
    var source = event.data().get(Event.WellKnownData.SOURCE);
    return source != null && !Event.WellKnownData.SOURCE_SYNC.equals(source);
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
