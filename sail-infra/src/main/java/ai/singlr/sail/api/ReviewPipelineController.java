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
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.net.InetAddress;
import java.time.Instant;
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
import java.util.stream.Collectors;

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
 * #resume}): an errored review is retried as the same iteration, a running one continues from its
 * stages, a gate-failed one that never got its fix agent gets it. That is both the loop's retry and
 * its crash recovery, and the reconciler's replays are what drive it. A pass parks the spec in
 * {@code awaiting_merge} — the PR is open but unmerged, and only the human who merges it marks the
 * spec {@code done}.
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
   */
  private void route(Event event) {
    if (!isAuthoritative(event)) {
      return;
    }
    var run = runOf(event);
    if (Event.WellKnownTypes.AGENT_CANCELLED.equals(event.type())) {
      run.filter(RunStore.RunRow::servesReview).ifPresent(this::operatorStopped);
      return;
    }
    run.filter(stopped -> stopped.ownedBy(localHandle.get()))
        .ifPresent(own -> finished(own, event));
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
   * a thread of its own.
   */
  private void finished(RunStore.RunRow run, Event event) {
    if (RunTracker.finish(
        runStore, run.id(), "stopped", Event.WellKnownData.exitCode(event.data()))) {
      syncTrigger.run();
    }
  }

  private Optional<RunStore.RunRow> runOf(Event event) {
    var runId = Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null);
    return Strings.isBlank(runId) ? Optional.empty() : runStore.findById(runId);
  }

  /**
   * A build ended: a non-zero exit is the agent's failure, surfaced and left for triage; anything
   * else — a clean exit, or a run the watcher or the reconciler ended without an exit code — hands
   * the spec to review, which judges the work the build left.
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
    var exitCode = Event.WellKnownData.exitCode(event.data());
    if (exitCode != null && exitCode != 0) {
      publishEvent(event.project(), specId, Event.WellKnownTypes.AGENT_FAILED, "exit " + exitCode);
      return;
    }
    startIteration(event.project(), specId);
  }

  /**
   * Starts the review the spec is due, or goes on with the one it is in. A review a live run serves
   * — a reviewer still judging, or a fix agent still answering it — is left to that run's own stop,
   * whatever stop asked, and the spec is not moved: this is what keeps a build's late or replayed
   * stop from starting a second review beside a working fix agent, or from pulling a spec its fix
   * agent is working back into {@code review}. Otherwise the spec is in review. A running review no
   * run serves is one whose launch never happened or whose daemon died mid-step, and it goes on
   * from its stage rows. Otherwise the next iteration starts: the same one again after an
   * infrastructure error, within its retry budget, and the next after a verdict, within {@code
   * max_iterations}.
   */
  private void startIteration(String project, String specId) {
    var existing = reviewStore.latestReviewForSpec(specId);
    if (existing.isPresent() && served(existing.get())) {
      System.err.println(
          "review-pipeline: not starting a review for spec "
              + specId
              + " — a run still serves review "
              + existing.get().id());
      return;
    }
    advanceSpec(specId, SpecStatus.REVIEW);
    var config = configResolver.apply(project);
    if (config == null || config.stages().isEmpty()) {
      return;
    }
    if (existing.isPresent() && "running".equals(existing.get().status())) {
      advance(existing.get().id(), config, project, specId);
      return;
    }

    var iteration = existing.map(ReviewPipelineController::nextIteration).orElse(1);
    if (iteration > config.maxIterations()) {
      var reason =
          "review iterations exhausted ("
              + config.maxIterations()
              + "); re-dispatch with --restart to start a fresh attempt";
      System.err.println("review-pipeline: spec " + specId + " escalated — " + reason);
      escalate(project, specId, existing.get().id(), reason);
      return;
    }

    if (existing.isPresent()
        && existing.get().errored()
        && erroredAttempts(specId, iteration) >= MAX_ERRORED_RETRIES) {
      var reason =
          MAX_ERRORED_RETRIES
              + " review attempts errored in a row at iteration "
              + iteration
              + "; fix the reviewer, then re-dispatch with --restart";
      System.err.println("review-pipeline: spec " + specId + " escalated — " + reason);
      escalate(project, specId, existing.get().id(), reason);
      return;
    }

    advance(createReview(specId, iteration), config, project, specId);
  }

  /** Whether a run that serves {@code review} — a reviewer or its fix agent — has yet to finish. */
  private boolean served(ReviewStore.ReviewRow review) {
    return runStore.forReview(review.id()).stream()
        .anyMatch(run -> !RunStatus.isTerminal(run.status()));
  }

  /**
   * Whether an authoritative stop for a spec in this status should kick off a review. Both {@code
   * in_progress} (the normal path) and {@code review} qualify: a spec can be moved to {@code
   * review} out of band — a manual edit, or a sync revision from another box — while its agent is
   * still running here, and the old {@code == IN_PROGRESS} guard then dropped the real stop and
   * stranded the spec in {@code review} with no review ever created. Terminal and pre-dispatch
   * states ({@code done}, {@code awaiting_merge}, {@code archived}, {@code draft}, {@code pending})
   * are not reviewable, so their stops are ignored.
   */
  private static boolean reviewable(SpecStatus status) {
    return status == SpecStatus.IN_PROGRESS || status == SpecStatus.REVIEW;
  }

  private String createReview(String specId, int iteration) {
    var reviewId = reviewStore.createReview(specId, iteration);
    reviewStore.updateReviewStatus(reviewId, "running");
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
    postRoom(specId, passedVerdict(reviewId, specId));
    publishEvent(project, specId, "review_completed", null);
  }

  private void awaitHuman(ReviewStore.StageRow stage, String project, String specId) {
    if ("running".equals(stage.status())) {
      return;
    }
    reviewStore.startStage(stage.id(), "human");
    publishEvent(project, specId, "review_stage_started", stage.name());
    postRoom(specId, humanVerdict(specId));
    syncTrigger.run();
  }

  /**
   * Starts an agent stage: the stage row turns {@code running} and its reviewer launches as a run
   * that serves the review. A stage that cannot start — no reviewer to resolve, a container that
   * will not take the launch — is an infrastructure error like any other.
   */
  private void launchReviewer(
      ReviewStore.StageRow stage, StageConfig stageConfig, String project, String specId) {
    var agent = stageConfig.agent() != null ? stageConfig.agent() : reviewerResolver.apply(project);
    if (agent == null) {
      var message = "no reviewer agent resolved; set stages[].agent or agent.install in sail.yaml";
      reviewStore.completeStage(stage.id(), "failed", message);
      handleStageError(stage.reviewId(), project, specId, stage.name(), message);
      return;
    }
    reviewStore.startStage(stage.id(), agent);
    publishEvent(project, specId, "review_stage_started", stage.name());
    try {
      var spec = specStore.findById(specId);
      var branch = spec.map(SpecStore.SpecRow::branch).orElse("main");
      var repos = spec.map(SpecStore.SpecRow::repos).orElse(List.of());
      var carried = reviewStore.carryForwardFindings(specId, stage.reviewId(), stage.name());
      var built =
          ReviewPromptBuilder.compose(
              branch, repos, stageConfig.categories(), roomMessages(specId), carried);
      lanes.launch(
          new ReviewLanes.Invocation(
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
              built.renderedMessages().stream().map(MessageStore.MessageRow::id).toList()),
          localHandle.get());
      syncTrigger.run();
    } catch (Exception e) {
      var why = "reviewer could not start: " + reasonOf(e);
      System.err.println("review-pipeline: agent stage '" + stage.name() + "': " + why);
      reviewStore.completeStage(stage.id(), "failed", why);
      handleStageError(stage.reviewId(), project, specId, stage.name(), why);
    }
  }

  /**
   * A reviewer ended. When it is the run its review waits on, the stage it ran is resolved: a
   * reviewer the watcher killed or that exited non-zero is an infrastructure error naming why, and
   * one that ended cleanly is judged on the findings in its own log.
   */
  private void reviewerStopped(RunStore.RunRow run, Event event) {
    if (beingStopped(run)) {
      return;
    }
    var review = awaited(run, "running");
    var config = configResolver.apply(run.project());
    var index = review.map(awaitedReview -> stageServedBy(awaitedReview.id(), run, config));
    if (index.isEmpty() || index.get() < 0) {
      resume(run.project(), run.specId());
      return;
    }
    var reviewId = review.get().id();
    var stage = reviewStore.stagesForReview(reviewId).get(index.get());
    var stageConfig = config.stages().get(index.get());
    var failure = failureOf(event);
    var outcome =
        failure == null
            ? resolveStage(stage, stageConfig, run)
            : errored(stage, "reviewer " + failure);
    syncTrigger.run();
    switch (outcome) {
      case StageOutcome.Errored errored ->
          handleStageError(reviewId, run.project(), run.specId(), stage.name(), errored.message());
      case StageOutcome.GateFailed failed ->
          handleStageFailure(
              reviewId, config, stage.id(), stageConfig.gate(), run.project(), run.specId());
      case StageOutcome.Passed passed -> advance(reviewId, config, run.project(), run.specId());
    }
  }

  /**
   * The index of the stage {@code run} reviewed, or -1 when no stage is waiting on it: the agent
   * stage of the review that is {@code running} and was started no later than the run was recorded.
   * A stage started after the run is another reviewer's to judge — one whose launch never happened
   * — and reading this run's log for it would pass a stage nobody reviewed.
   */
  private int stageServedBy(String reviewId, RunStore.RunRow run, ReviewPipelineConfig config) {
    var stages = reviewStore.stagesForReview(reviewId);
    var recorded = MissedStops.parseOr(run.startedAt(), Instant.MIN);
    for (var i = 0; i < stages.size() && i < config.stages().size(); i++) {
      var stage = stages.get(i);
      if ("running".equals(stage.status())
          && config.stages().get(i).type() != StageType.HUMAN
          && !MissedStops.parseOr(stage.startedAt(), Instant.MAX).isAfter(recorded)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The review {@code run} serves, when the loop is waiting on this very run: the review is this
   * box's to drive, is the spec's latest and not superseded, is in one of {@code statuses}, and no
   * newer run serves it. Anything else is a stop the loop has already moved past.
   */
  private Optional<ReviewStore.ReviewRow> awaited(RunStore.RunRow run, String... statuses) {
    if (!run.ownedBy(localHandle.get()) || run.reviewId() == null) {
      return Optional.empty();
    }
    return reviewStore
        .latestReviewForSpec(run.specId())
        .filter(review -> review.id().equals(run.reviewId()))
        .filter(review -> !review.superseded() && List.of(statuses).contains(review.status()))
        .filter(
            review ->
                runStore.forReview(review.id()).stream()
                    .findFirst()
                    .filter(newest -> newest.id().equals(run.id()))
                    .isPresent());
  }

  /**
   * Whether an operator's stop holds {@code run}: its claim is recorded and its halt is under way.
   * The unit dying under that halt is seen by the watcher too, whose stop would read as the run's
   * own end — an error to retry, or a clean finish to build on. It is neither: the cancel the
   * operator's stop publishes once the halt is verified decides what becomes of the review.
   */
  private static boolean beingStopped(RunStore.RunRow run) {
    return RunStatus.STOPPING.wire().equals(run.status());
  }

  /**
   * Why a run did not end well, or null when it did: the guardrail the watcher ended it for ({@code
   * killed: time limit (45m)}, {@code killed: stall (20m)}), else its non-zero exit ({@code failed:
   * exit 1}). A stop that names neither — a clean exit, or a reconciled stop whose exit code is
   * unrecoverable — is no failure, and the run's work is judged on what it left.
   */
  private static String failureOf(Event event) {
    var reason = Objects.toString(event.data().get(Event.WellKnownData.REASON), null);
    if (Strings.isNotBlank(reason)) {
      return "killed: " + reason;
    }
    var exitCode = Event.WellKnownData.exitCode(event.data());
    return exitCode != null && exitCode != 0 ? "failed: exit " + exitCode : null;
  }

  /**
   * Goes on from the rows when a stop arrives that the loop is not waiting on — a duplicate, a
   * replay, the stop of a run whose step a crash cut short. Nothing is done while a run still
   * serves the spec's latest review. Otherwise what the review's rows say is owed is done: a review
   * that failed by infrastructure error is retried as the same iteration, within its budget (the
   * loop's retry: the reconciler replays a stop for every errored review, once); a {@code running}
   * review goes on from its stages; and a review that failed its gate and never got its fix agent —
   * the newest run that served it is still its reviewer — gets it. A passed or escalated review is
   * left as it is.
   */
  private void resume(String project, String specId) {
    if (specId == null
        || specStore.findById(specId).filter(spec -> reviewable(spec.status())).isEmpty()) {
      return;
    }
    var latest = reviewStore.latestReviewForSpec(specId).filter(review -> !review.superseded());
    if (latest.isEmpty() || served(latest.get())) {
      return;
    }
    var review = latest.get();
    if ("running".equals(review.status())
        || ("failed".equals(review.status()) && review.errored())) {
      startIteration(project, specId);
    } else if ("failed".equals(review.status())) {
      owedFix(review, project, specId);
    }
  }

  /**
   * Hands a gate-failed review to its fix agent when none was ever launched for it: the review was
   * marked failed and the loop was cut short before the fix run was recorded. A review a fix run
   * already served is that run's stop to move.
   */
  private void owedFix(ReviewStore.ReviewRow review, String project, String specId) {
    var fixed =
        runStore.forReview(review.id()).stream().anyMatch(run -> Lane.FIX.matches(run.role()));
    if (fixed) {
      return;
    }
    var config = configResolver.apply(project);
    var stages = reviewStore.stagesForReview(review.id());
    for (var i = 0; i < stages.size() && i < config.stages().size(); i++) {
      if ("failed".equals(stages.get(i).status())) {
        handleStageFailure(
            review.id(),
            config,
            stages.get(i).id(),
            config.stages().get(i).gate(),
            project,
            specId);
        return;
      }
    }
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
          severityCounts(findings));

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
      postRoom(specId, misaddressedVerdictNote((int) misaddressed));
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
      postRoom(specId, alreadyResolvedNote(setAside));
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

  private static String alreadyResolvedNote(int count) {
    return "Note: the reviewer ruled on "
        + count
        + " finding"
        + (count == 1 ? "" : "s")
        + " someone resolved while the stage ran; that resolution stands and the reviewer's ruling"
        + " on it was set aside.";
  }

  private static Finding.Resolution resolutionOf(FindingParser.Ruling ruling) {
    return switch (ruling) {
      case FIXED -> Finding.Resolution.FIXED;
      case DISPUTED -> Finding.Resolution.DISPUTED;
      case STILL_OPEN -> Finding.Resolution.OPEN;
    };
  }

  /**
   * The room note for a reviewer that ruled on a finding id no carried finding holds — the
   * mis-transcribed-id signature. The verdict was dropped fail-closed and the real finding shows
   * still-open, which reads as unresolved; this says plainly that it may in fact have been
   * addressed, so a human checks the review log rather than trusting the open count. Kept off the
   * pass/fail verdict lines so it fires only when the ambiguity is real.
   */
  private static String misaddressedVerdictNote(int count) {
    return "Note: the reviewer ruled on "
        + count
        + " finding id"
        + (count == 1 ? "" : "s")
        + " that match no open finding on this spec (a mis-transcribed id). A finding shown"
        + " still-open below may already be addressed — check the review log before acting on it.";
  }

  private static void warn(List<String> warnings) {
    warnings.forEach(warning -> System.err.println("review-pipeline: " + warning));
  }

  /**
   * An errored stage is an infrastructure failure, not a review verdict: record why on the review,
   * say so loudly, and stop — without a fix iteration (there are no findings to fix) and without
   * counting against {@code max_iterations} (the next stop retries the same iteration; see {@link
   * #nextIteration}).
   */
  private void handleStageError(
      String reviewId, String project, String specId, String stageName, String message) {
    reviewStore.failReviewWithError(reviewId, message);
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

  /** The iteration the next review runs as: errored iterations are retried, not burned. */
  private static int nextIteration(ReviewStore.ReviewRow latest) {
    return latest.errored() ? latest.iteration() : latest.iteration() + 1;
  }

  private void handleStageFailure(
      String reviewId,
      ReviewPipelineConfig config,
      String failedStageId,
      ReviewPipelineConfig.Gate gate,
      String project,
      String specId) {
    reviewStore.updateReviewStatus(reviewId, "failed");

    var review = reviewStore.findReview(reviewId);
    if (review.isEmpty()) return;

    var openFindings = reviewStore.openFindingsForReview(reviewId);
    postRoom(
        specId,
        failedVerdict(
            review.get().iteration(), openFindings, reviewStore.disputedFindings(specId)));

    var stuck =
        stuckFinding(gate, reviewStore.findingsForStage(failedStageId), config.maxFindingAge());
    if (stuck != null) {
      escalate(
          project,
          specId,
          reviewId,
          "finding \""
              + stuck.title()
              + "\" survived "
              + reviewStore.findingAge(stuck.id())
              + " fix iterations; the loop is stuck on it — fix or dismiss it, then re-dispatch");
      return;
    }

    if (review.get().iteration() >= config.maxIterations()) {
      escalate(
          project,
          specId,
          reviewId,
          "review iterations exhausted (" + config.maxIterations() + ")");
      return;
    }

    if (openFindings.isEmpty()) return;

    launchFix(reviewId, specId, openFindings, project);
  }

  /**
   * The convergence check: a gate-blocking finding whose {@code carried_from} chain shows it has
   * already survived {@code maxFindingAge} fix iterations. Sub-gate findings may age freely — they
   * do not drive the loop — and a loop resolving old blockers while new ones surface never trips
   * this: that loop is converging and runs to {@code max_iterations} as before. Scoped to the
   * failed stage's own findings: a finding is gate-blocking only under the gate of the stage that
   * owns it, so an aged finding another stage's gate permits never counts as this stage's blocker.
   */
  private Finding stuckFinding(
      ReviewPipelineConfig.Gate gate, List<Finding> stageFindings, int maxFindingAge) {
    return stageFindings.stream()
        .filter(gate::blocks)
        .filter(finding -> reviewStore.findingAge(finding.id()) >= maxFindingAge)
        .findFirst()
        .orElse(null);
  }

  /**
   * Hands a failed review's findings to the spec's own agent as a fix run that serves that review.
   * The run acts as itself — principal {@code <agent>/fix-<runId>} — so the room's audit trail
   * attributes its posts to the fix lane, never to the reviewer, and it runs with the stop gate
   * asking for a committed, pushed tree. The loop then waits for its stop.
   */
  private void launchFix(String reviewId, String specId, List<Finding> findings, String project) {
    var spec = specStore.findById(specId);
    if (spec.isEmpty()) {
      return;
    }
    var built = FixTaskBuilder.build(specId, spec.get().title(), findings, roomMessages(specId));
    advanceSpec(specId, SpecStatus.IN_PROGRESS);
    publishEvent(project, specId, "review_iteration_started", null);
    try {
      lanes.launch(
          new ReviewLanes.Invocation(
              Lane.FIX,
              reviewId,
              project,
              specId,
              spec.get().agent() != null ? spec.get().agent() : "claude-code",
              built.task(),
              spec.get().branch(),
              spec.get().repos(),
              spec.get().model(),
              spec.get().reasoningEffort(),
              built.renderedMessages().stream().map(MessageStore.MessageRow::id).toList()),
          localHandle.get());
      syncTrigger.run();
    } catch (Exception e) {
      fixFailed(reviewId, project, specId, "fix agent could not start: " + reasonOf(e));
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
   * owns the spec, and no re-review starts beside its build.
   */
  private void fixStopped(RunStore.RunRow run, Event event) {
    if (beingStopped(run)) {
      return;
    }
    var review = awaited(run, "failed").filter(failed -> !failed.errored());
    var spec = specStore.findById(run.specId());
    if (review.isEmpty() || spec.isEmpty()) {
      resume(run.project(), run.specId());
      return;
    }
    var reviewId = review.get().id();
    var failure = failureOf(event);
    if (failure != null) {
      fixFailed(reviewId, run.project(), run.specId(), "fix agent " + failure);
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
            "fix agent left uncommitted changes in " + describeRescues(rescued),
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
    advanceSpec(run.specId(), SpecStatus.REVIEW);
    advance(
        createReview(run.specId(), review.get().iteration() + 1),
        config,
        run.project(),
        run.specId());
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
   * An operator stopped a reviewer or a fix agent. The stop was a person's decision about the loop,
   * so the loop does not retry over it: the review escalates, and the spec waits in {@code review}
   * for that person. A reviewer's review may already have errored on the watcher's stop of the same
   * halt, when that stop was published after the operator's claim was finalized; it escalates all
   * the same, so the reconciler's retry never undoes the stop.
   */
  private void operatorStopped(RunStore.RunRow run) {
    var reviewer = Lane.REVIEW.matches(run.role());
    var review = reviewer ? awaited(run, "running", "failed") : awaited(run, "failed");
    if (review.isEmpty()) {
      return;
    }
    var why = (reviewer ? "reviewer" : "fix agent") + " stopped by an operator";
    reviewStore.stagesForReview(review.get().id()).stream()
        .filter(stage -> "running".equals(stage.status()))
        .forEach(stage -> reviewStore.completeStage(stage.id(), "failed", why));
    escalate(
        run.project(),
        run.specId(),
        review.get().id(),
        why + "; re-dispatch with --restart to start a fresh attempt");
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

  private static String failedVerdict(
      int iteration, List<Finding> findings, List<Finding> disputed) {
    return "Review failed (iteration "
        + iteration
        + "): "
        + severitySummary(findings)
        + "."
        + findingLines(findings)
        + disputedLines(disputed);
  }

  private String passedVerdict(String reviewId, String specId) {
    var iteration =
        reviewStore.findReview(reviewId).map(ReviewStore.ReviewRow::iteration).orElse(0);
    var open =
        reviewStore.findingsForReview(reviewId).stream()
            .filter(f -> f.resolution() == Finding.Resolution.OPEN)
            .toList();
    var disputed = disputedLines(reviewStore.disputedFindings(specId));
    if (open.isEmpty()) {
      return "Review passed (iteration " + iteration + ")." + disputed + "\nAwaiting merge.";
    }
    return "Review passed (iteration "
        + iteration
        + ") with "
        + severitySummary(open)
        + " below the gate — worth a look before merge."
        + findingLines(open)
        + disputed
        + "\nAwaiting merge.";
  }

  /**
   * The room verdict a human stage opens with: the automated stages before it passed, and every
   * disputed finding the gate excluded is surfaced — argument included — before the human rules.
   * Deliberately not the passed verdict: the pipeline has not passed, so no "Awaiting merge".
   */
  private String humanVerdict(String specId) {
    return "Automated review stages passed."
        + disputedLines(reviewStore.disputedFindings(specId))
        + "\nAwaiting human approval.";
  }

  /**
   * The disputed findings section of a room verdict: excluded from the gate by a reviewer-ruled
   * argument, so every one is surfaced — argument included, never truncated — for the human to
   * confirm or overrule. An agent can silence a finding only by arguing it in the open, never by
   * omission, and a dispute the human cannot see is an omission.
   */
  private static String disputedLines(List<Finding> disputed) {
    if (disputed.isEmpty()) {
      return "";
    }
    var shown =
        disputed.stream()
            .map(
                f ->
                    "- ["
                        + f.severity()
                        + "] "
                        + f.title()
                        + (f.file() == null ? "" : " (" + f.file() + ":" + f.lineStart() + ")")
                        + (Strings.isBlank(f.resolutionEvidence())
                            ? ""
                            : " — " + f.resolutionEvidence()))
            .collect(Collectors.joining("\n"));
    return "\nDisputed (excluded from the gate — needs your ruling):\n" + shown;
  }

  /** {@code "2 high, 1 low"} in severity order, or {@code "no findings"}. */
  private static String severitySummary(List<Finding> findings) {
    var summary =
        severityCounts(findings).entrySet().stream()
            .map(e -> e.getValue() + " " + e.getKey())
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    return summary.isEmpty() ? "no findings" : summary;
  }

  /** One line per finding, capped so a noisy review stays one readable room message. */
  private static String findingLines(List<Finding> findings) {
    if (findings.isEmpty()) {
      return "";
    }
    var shown =
        findings.stream()
            .limit(10)
            .map(
                f ->
                    "- ["
                        + f.severity()
                        + "] "
                        + f.title()
                        + (f.file() == null ? "" : " (" + f.file() + ":" + f.lineStart() + ")"))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    var more = findings.size() - Math.min(findings.size(), 10);
    return "\n" + shown + (more > 0 ? "\n- +" + more + " more" : "");
  }

  /**
   * Names what each rescue swept — {@code "api (3 files: A.java, B.java, C.java)"} — capped so a
   * large sweep stays one readable notification line while still revealing debris immediately.
   */
  private static String describeRescues(List<ReviewLanes.Rescue> rescued) {
    return rescued.stream()
        .map(
            rescue -> {
              var files = rescue.files();
              var shown = files.stream().limit(5).toList();
              var suffix = files.size() > shown.size() ? ", +" + (files.size() - shown.size()) : "";
              return "%s (%d file%s: %s%s)"
                  .formatted(
                      rescue.repo(),
                      files.size(),
                      files.size() == 1 ? "" : "s",
                      String.join(", ", shown),
                      suffix);
            })
        .reduce((a, b) -> a + ", " + b)
        .orElse("");
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

  /** Finding counts keyed by lowercase severity, omitting zero severities — for event payloads. */
  private static Map<String, Object> severityCounts(List<Finding> findings) {
    var counts = new LinkedHashMap<String, Object>();
    for (var severity : Finding.Severity.values()) {
      var count = findings.stream().filter(f -> f.severity() == severity).count();
      if (count > 0) {
        counts.put(severity.name().toLowerCase(Locale.ROOT), (int) count);
      }
    }
    return counts;
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
