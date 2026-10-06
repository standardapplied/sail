/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SlackNotifications;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.SlackPoster;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SlackThreadStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncTransitions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The review loop as the server runs it, over a {@link FakeContainer}: the real stores, the real
 * bus with the real {@link ReviewPipelineController} and {@link RunTracker} subscribed, and the
 * real launch path ({@link DispatchOperations}'s review lanes over {@link RunLauncher} and {@link
 * WatcherSpawner}). Only the container shell and the clock are fake. A run ends here the one way it
 * ends in production — the authoritative stop its watcher publishes: the real {@link RunWatch},
 * under the limits the launch handed it on its command line, reading the container as it does in
 * the field — and {@link #settle} returns once every subscriber has handled everything published,
 * so a test reads the loop's rows without sleeping or polling.
 */
final class ReviewLoop implements AutoCloseable {

  static final String PROJECT = "test-project";
  static final String HANDLE = "node-a";

  /** Longer than any limit a test sets: a watch still polling past it will never end. */
  private static final Duration WATCH_HORIZON = Duration.ofHours(12);

  /** A clock an hour ahead: past every grace window a sweep waits out. */
  static final Supplier<Instant> LATER = () -> Instant.now().plus(Duration.ofHours(1));

  static final String YAML =
      """
      name: test-project
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/api.git
          path: api
      agent:
        type: claude-code
        install: [claude-code, codex]
      """;

  private static final Pattern MODEL = Pattern.compile("--model (\\S+)");
  private static final Pattern EFFORT = Pattern.compile("model_reasoning_effort='\"(\\w+)\"'");
  private static final Pattern MAX_DURATION = Pattern.compile("--max-duration (\\S+)");
  private static final Pattern MAX_IDLE = Pattern.compile("--max-idle (\\S+)");
  private static final Pattern ACTION = Pattern.compile("--action (\\S+)");
  private static final Pattern STARTED_AT = Pattern.compile("--started-at (\\S+)");

  final Sqlite db;
  final SpecStore specs;
  final ReviewStore reviews;
  final RunStore runs;
  final MessageStore messages;
  final FakeContainer container = new FakeContainer(PROJECT);
  final List<Event> published = new CopyOnWriteArrayList<>();
  final List<List<String>> launchCommands = new CopyOnWriteArrayList<>();
  final AtomicLong syncs = new AtomicLong();
  EventBus bus;
  DispatchOperations operations;
  ReviewPipelineController controller;

  private final ProjectStore projects;
  private final ProjectReader reader;
  private final Function<String, ReviewPipelineConfig> config;
  private final Function<String, String> reviewer;
  private final AtomicLong offered = new AtomicLong();
  private final AtomicLong handled = new AtomicLong();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition idle = lock.newCondition();
  private volatile ScriptedAgent script;
  private volatile boolean refusing;
  private volatile Consumer<String> launched = runId -> {};
  private volatile Instant watchEndedAt;

  /** A loop whose pipeline and reviewer are resolved from {@code yaml}, as the server wires it. */
  static ReviewLoop wired(Path dir, String yaml) {
    return new ReviewLoop(dir, yaml, null, null);
  }

  /** A loop whose pipeline is one agent stage per name ({@link #stages}). */
  static ReviewLoop staged(Path dir, String... names) {
    return of(dir, stages(names));
  }

  /** A loop under {@code config}, reviewed by {@code codex}. */
  static ReviewLoop of(Path dir, ReviewPipelineConfig config) {
    return new ReviewLoop(dir, YAML, project -> config, project -> "codex");
  }

  ReviewLoop(
      Path dir,
      String yamlContent,
      Function<String, ReviewPipelineConfig> config,
      Function<String, String> reviewer) {
    db = Sqlite.open(dir.resolve("loop-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    projects = new ProjectStore(db);
    reader = ProjectReader.ofCatalog(projects);
    describe(yamlContent);
    Function<String, SailYaml> loader = project -> load();
    this.config = config != null ? config : ReviewWiring.configResolver(loader);
    this.reviewer = reviewer != null ? reviewer : ReviewWiring.reviewerResolver(loader);
    specs = new SpecStore(db);
    reviews = new ReviewStore(db);
    runs = new RunStore(db);
    messages = new MessageStore(db);
    start();
  }

  /**
   * Everything a server start builds, over the stores and the container that outlive it: the bus,
   * the launch path, the pipeline and the run tracker, with every event persisted as the server's
   * audit persister does.
   */
  private void start() {
    var started = new EventBus();
    bus = started;
    operations =
        new DispatchOperations(
                container,
                reader,
                specs,
                reviews,
                runs,
                new FdeStore(db),
                started::publish,
                new WatcherSpawner(container, null),
                (project, descriptor) -> "",
                this::launch,
                DispatchOperations.Listener.NONE,
                SessionYield.NONE)
            .useMessages(messages)
            .useRooms(new RoomStore(db));
    controller =
        new ReviewPipelineController(
                specs,
                reviews,
                runs,
                config,
                reviewer,
                operations.reviewLanes(),
                started,
                syncs::incrementAndGet,
                () -> HANDLE)
            .useMessages(messages);
    started.subscribe(counted(new SpecStoreAuditPersister(new EventStore(db))));
    started.subscribe(counted(new RunTracker(runs, SyncScheduler.disabled(), () -> HANDLE)));
    started.subscribe(counted(controller));
    started.subscribe(counted(recorder()));
  }

  /**
   * The daemon restarts: everything it held in memory is gone, and a new bus, pipeline and tracker
   * come up over the same database and the same container, whose agents kept running meanwhile.
   */
  void restart() {
    settle();
    bus.close();
    start();
  }

  /** The missed-stop reconciler this server would run, probing the container, at {@code clock}. */
  MissedStopReconciler reconciler(Supplier<Instant> clock) {
    return new MissedStopReconciler(
        specs,
        runs,
        new EventStore(db),
        reviews,
        bus,
        MissedStopReconciler.systemdUnitProbe(container),
        watchers(),
        () -> HANDLE,
        clock);
  }

  private WatcherCoverage watchers() {
    return WatcherCoverage.of(new WatcherSpawner(container, null));
  }

  /** The watcher re-armer this server would run, probing the container and the host. */
  WatcherRearmer rearmer() {
    return new WatcherRearmer(
        runs,
        WatcherRearmer.systemdUnitActiveProbe(container),
        watchers(),
        () -> HANDLE,
        operations::relaunchWatcher);
  }

  /**
   * Records a revision of the project's definition, as an edit arriving by sync between two runs
   * does.
   */
  void describe(String yamlContent) {
    Acting.system(() -> projects.upsert(PROJECT, yamlContent));
  }

  /** Leaves the project's row holding text that is not a definition. */
  void corruptDefinition() {
    db.execute(
        "UPDATE projects SET definition = ? WHERE name = ?", "agent: [unterminated", PROJECT);
  }

  private SailYaml load() {
    return reader.read(PROJECT).orElse(null);
  }

  /** One agent stage per name, each reviewed by the agent of that name, gated on no critical. */
  static ReviewPipelineConfig stages(String... names) {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "max_iterations",
            3,
            "stages",
            List.of(names).stream()
                .map(
                    name ->
                        Map.<String, Object>of(
                            "name", name, "type", "agent", "agent", name, "gate", "no_critical"))
                .toList()));
  }

  /** Records spec {@code id} of this loop's project as a dispatch left it. */
  void spec(
      String id,
      SpecStatus status,
      String agent,
      String model,
      String reasoningEffort,
      List<String> repos) {
    Acting.as(
        null,
        () ->
            specs.create(
                new SpecStore.SpecRow(
                    id,
                    PROJECT,
                    "Test spec",
                    status,
                    null,
                    agent,
                    model,
                    reasoningEffort,
                    "feat/test",
                    0,
                    null,
                    "",
                    "",
                    null,
                    List.of(),
                    repos)));
  }

  /** Spec {@code id}, in progress under {@code claude-code}, working {@code repo}. */
  void spec(String id, String repo) {
    spec(id, SpecStatus.IN_PROGRESS, "claude-code", null, null, List.of(repo));
  }

  /**
   * Records a run of {@code specId} (null for none) in lane {@code role}, with no agent started.
   */
  String run(String specId, String role) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs.create(
                id,
                PROJECT,
                specId,
                HANDLE,
                role,
                "claude-code",
                "feat/test",
                "work",
                null,
                null,
                AgentUnit.forRun(id).logPath(),
                AgentUnit.forRun(id).unitName()));
    return id;
  }

  /** The clean stop a watcher publishes for the run {@code runId} of {@code specId}'s build. */
  static Event buildStop(String specId, String runId) {
    return RunStops.of(
        Event.WellKnownData.SOURCE_WATCHER,
        PROJECT,
        specId,
        "claude-code",
        runId,
        "build",
        0,
        null);
  }

  /** Dispatches {@code specId}'s build over {@code api} to its end: the spec's review starts. */
  String built(String specId) {
    return built(specId, "api");
  }

  /** Dispatches {@code specId}'s build over {@code repo} to its end: the spec's review starts. */
  String built(String specId, String repo) {
    spec(specId, repo);
    var build = run(specId, "build");
    onEvent(buildStop(specId, build));
    return build;
  }

  /** The one agent running in the container; a test that expects one fails on any other count. */
  RunStore.RunRow onlyLive() {
    var live = live();
    if (live.size() != 1) {
      throw new AssertionError("exactly one agent is running: " + live);
    }
    return live.getFirst();
  }

  /** The id of the latest review of {@code specId}'s current dispatch attempt. */
  String reviewOf(String specId) {
    return reviews.latestReviewForSpec(specId).orElseThrow().id();
  }

  String statusOf(String reviewId) {
    return reviews.findReview(reviewId).orElseThrow().status();
  }

  SpecStatus specStatus(String specId) {
    return specs.findById(specId).orElseThrow().status();
  }

  /** The run {@code reviewId} recorded that it waits on, or null when it waits on none. */
  String waitingOn(String reviewId) {
    return reviews.findReview(reviewId).orElseThrow().waitingOn();
  }

  /** What sail said in {@code specId}'s room that starts with {@code prefix}, oldest first. */
  List<String> roomLines(String specId, String prefix) {
    return messages.list(specId, null, 100).stream()
        .map(MessageStore.MessageRow::body)
        .filter(body -> body.startsWith(prefix))
        .toList();
  }

  /**
   * The events main derives from {@code reviewId}'s row as it crosses the wire, first seen: what
   * main says of a review this box drives.
   */
  List<Event> narratedOnMain(String reviewId) {
    return SyncTransitions.detect("review", reviewId, null, reviews.comparableSnapshot(reviewId))
        .stream()
        .flatMap(
            transition ->
                SyncTransitionEvents.eventsFor(transition, spec -> PROJECT, spec -> null, "main")
                    .stream())
        .toList();
  }

  /** The detail of every event of {@code type} published so far, oldest first. */
  List<String> details(String type) {
    return events(type).stream().map(event -> (String) event.data().get("detail")).toList();
  }

  /** What this server's Slack and webhook reactors posted, as they posted it. */
  static final class Narrated {

    private final List<String> slack = new CopyOnWriteArrayList<>();
    private final List<String> webhooks = new CopyOnWriteArrayList<>();

    /** Every text posted to Slack, oldest first. */
    List<String> slack() {
      return slack;
    }

    /** How many times an agent's stop was said in Slack. */
    long stopsInSlack() {
      return slack.stream().filter(text -> text.contains(" stopped")).count();
    }

    /** How many times an agent's stop was sent to the project's webhook. */
    long stopsToWebhooks() {
      return webhooks.stream().filter(Event.WellKnownTypes.AGENT_SESSION_STOPPED::equals).count();
    }
  }

  /**
   * Subscribes the real Slack and webhook reactors to this server's bus, for a project that
   * configures both, and returns what they post from now on: only Slack itself and the webhook
   * endpoint are stood in for.
   */
  Narrated narrating() {
    var narrated = new Narrated();
    var notifications =
        new Notifications("https://ntfy.sh/sail", null, new SlackNotifications("#sail"));
    bus.subscribe(
        counted(
            new SlackReactor(
                project -> notifications,
                new SlackThreadStore(db),
                SlackReactor.specLookup(specs),
                post -> {
                  narrated.slack.add(post.text());
                  return new SlackPoster.Result(post.channel(), "1");
                })));
    bus.subscribe(
        counted(
            new WebhookReactor(
                project -> notifications,
                url -> (event, project, title, message) -> narrated.webhooks.add(event))));
    return narrated;
  }

  /** The stop executor this server would run, halting agents through {@code halter}. */
  StopOperations stops(StopOperations.AgentHalter halter) {
    return new StopOperations(
        container, reader, specs, runs, bus::publish, halter, StopOperations.Listener.NONE);
  }

  /** Every agent this loop launches from now on says what {@code script} says, then exits. */
  ReviewLoop scripted(ScriptedAgent script) {
    this.script = script;
    return this;
  }

  /**
   * Publishes {@code event}, waits for the loop to act on it, and — when the loop is scripted —
   * plays each agent it launched to its end, until nothing is left running.
   */
  void onEvent(Event event) {
    bus.publish(event);
    played();
  }

  private void played() {
    settle();
    while (script != null && !container.live().isEmpty()) {
      play(runs.findById(container.live().getFirst()).orElseThrow());
    }
  }

  private void play(RunStore.RunRow run) {
    var command = launchCommandOf(run.id());
    var agentCommand = command.get(command.size() - 8);
    var credential = command.get(command.size() - 2);
    var model = group(MODEL, agentCommand);
    var effort = group(EFFORT, agentCommand);
    try {
      var said =
          "fix".equals(run.role())
              ? script.runFix(
                  run.project(),
                  run.agent(),
                  run.task(),
                  run.reviewId(),
                  credential,
                  run.branch(),
                  specs.findById(run.specId()).map(SpecStore.SpecRow::repos).orElse(List.of()),
                  model,
                  effort)
              : script.run(
                  run.project(),
                  run.agent(),
                  run.task(),
                  run.reviewId(),
                  credential,
                  model,
                  effort);
      finish(run.id(), said);
    } catch (Exception crashed) {
      exit(run.id(), String.valueOf(crashed.getMessage()), 1);
    }
  }

  private static String group(Pattern pattern, String text) {
    var matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** The command the run's agent was launched with, as the launcher issued it. */
  List<String> launchCommandOf(String runId) {
    return launchCommands.stream()
        .filter(command -> command.get(command.size() - 3).equals(runId))
        .findFirst()
        .orElseThrow();
  }

  /** The command that spawned the run's watcher, joined on spaces. */
  String watcherCommandOf(String runId) {
    return watcherCommandsOf(runId).getFirst();
  }

  /** Every command that spawned a watcher for the run — the launch's, then each re-arm's. */
  List<String> watcherCommandsOf(String runId) {
    return container.commandsContaining("agent watch " + PROJECT + " --run " + runId);
  }

  /** The run's agent says {@code log} and exits cleanly; its watcher publishes the stop. */
  void finish(String runId, String log) {
    exit(runId, log, 0);
  }

  /** The run's agent exits with {@code exitCode}; its watcher publishes the stop. */
  void exit(String runId, String log, int exitCode) {
    container.exited(runId, log, exitCode);
    watch(runId, false, bus::publish);
  }

  /**
   * The run's agent exits with nothing there to hear it: its watcher sees the exit, cannot publish
   * the stop — the daemon is down — and ends its watch, or had died before the agent did.
   */
  void exitsUnheard(String runId, String log, int exitCode) {
    container.exited(runId, log, exitCode);
    container.watcherGone(runId);
  }

  /**
   * The run's agent works on, calling tools, until its watcher ends it for its time limit and
   * publishes the stop that says so.
   */
  void outlastsItsTimeLimit(String runId) {
    watch(runId, true, bus::publish);
  }

  /**
   * The run's agent goes quiet — alive, and calling no tool — until its watcher ends it for the
   * stall and publishes the stop that says so.
   */
  void stalls(String runId) {
    watch(runId, false, bus::publish);
  }

  /**
   * The run's agent goes quiet until its watcher ends it for the stall, with no daemon there to
   * take the stop: the kill and its trigger land in the container, and the stop is lost.
   */
  void stallsUnheard(String runId) {
    watch(
        runId,
        false,
        event -> {
          throw new IOException("connection refused");
        });
  }

  /**
   * Runs the run's watcher to the end of its watch: the real {@link RunWatch}, under the limits its
   * spawn command carried, over this container and a clock that passes as the watch waits. With
   * {@code working} the agent calls a tool between every two polls; otherwise the feed is silent.
   */
  private void watch(String runId, boolean working, RunWatch.StopPublisher publisher) {
    var run = runs.findById(runId).orElseThrow();
    var spawned = watcherCommandsOf(runId).getLast();
    var time = new AtomicReference<>(Instant.parse(group(STARTED_AT, spawned)));
    try {
      new RunWatch(
              PROJECT,
              runId,
              AgentUnit.forRun(runId),
              Guardrails.of(
                  group(MAX_DURATION, spawned), group(MAX_IDLE, spawned), group(ACTION, spawned)),
              time.get(),
              false,
              container,
              feed(runId, time, working),
              publisher,
              new QuietNarrator(),
              time::get)
          .run();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    watchEndedAt = time.get();
    container.watcherGone(runId);
    played();
  }

  /** When, on the watch's own clock, the last watch run here ended. */
  Instant watchEndedAt() {
    return watchEndedAt;
  }

  /**
   * A feed over a clock that passes as the watch waits on it: a silent one lets every wait run out;
   * a working one hands the watch one of the run's own tool calls halfway through each wait, its
   * start and then its finish.
   */
  private static RunWatch.Feed feed(String runId, AtomicReference<Instant> time, boolean working) {
    var started = time.get();
    return new RunWatch.Feed() {

      private boolean calling;

      @Override
      public Event poll(Duration wait) {
        if (Duration.between(started, time.get()).compareTo(WATCH_HORIZON) > 0) {
          throw new AssertionError("the watch of run " + runId + " never ended");
        }
        if (calling) {
          calling = false;
          return said(Event.WellKnownTypes.AGENT_TOOL_FINISHED);
        }
        if (!working || wait.compareTo(Duration.ofSeconds(1)) < 0) {
          time.updateAndGet(now -> now.plus(wait));
          return null;
        }
        time.updateAndGet(now -> now.plus(wait.dividedBy(2)));
        calling = true;
        return said(Event.WellKnownTypes.AGENT_TOOL_STARTED);
      }

      private Event said(String type) {
        return Event.of(
            PROJECT, null, type, "claude-code", "host", Map.of(Event.WellKnownData.RUN_ID, runId));
      }

      @Override
      public boolean live() {
        return true;
      }

      @Override
      public boolean reopen() {
        return true;
      }
    };
  }

  /** The stop the run's watcher publishes, read off the container as the watcher reads it. */
  Event watcherStop(String runId, String reason) {
    try {
      var exit =
          new AgentSession(container)
              .answeredExitStatus(PROJECT, AgentUnit.forRun(runId))
              .orElseThrow();
      return RunWatch.stop(PROJECT, RunWatch.addressedTo(runId, exit), reason);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The runs still running in the container, oldest first. */
  List<RunStore.RunRow> live() {
    return container.live().stream().map(id -> runs.findById(id).orElseThrow()).toList();
  }

  /** Every run launched for {@code specId} in {@code role}, oldest first. */
  List<RunStore.RunRow> runsIn(String specId, String role) {
    return runs.listForSpec(specId).reversed().stream()
        .filter(run -> role.equals(run.role()))
        .toList();
  }

  List<Event> events(String type) {
    return published.stream().filter(event -> type.equals(event.type())).toList();
  }

  /** Blocks until every subscriber has handled every event published so far. */
  void settle() {
    lock.lock();
    try {
      var remaining = TimeUnit.SECONDS.toNanos(BusTesting.DELIVERY_TIMEOUT_SECONDS);
      while (offered.get() != handled.get()) {
        if (remaining <= 0) {
          throw new AssertionError(
              "the loop did not settle: " + offered.get() + " offered, " + handled.get());
        }
        remaining = idle.awaitNanos(remaining);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while the loop settled", e);
    } finally {
      lock.unlock();
    }
  }

  /** Every launch from now on is refused by the container: the launch command exits non-zero. */
  void refuseLaunches() {
    this.refusing = true;
  }

  /** Runs {@code hook} with the run id of every agent launched from now on, once it started. */
  void onLaunch(Consumer<String> hook) {
    this.launched = hook;
  }

  private int launch(List<String> command) {
    if (refusing) {
      return 1;
    }
    var runId = command.get(command.size() - 3);
    launchCommands.add(List.copyOf(command));
    container.started(runId);
    launched.accept(runId);
    return 0;
  }

  private EventSubscriber recorder() {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "recorder";
      }

      @Override
      public Predicate<Event> filter() {
        return event -> true;
      }

      @Override
      public void onEvent(Event event) {
        published.add(event);
      }
    };
  }

  private EventSubscriber counted(EventSubscriber delegate) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return delegate.name();
      }

      @Override
      public Predicate<Event> filter() {
        return event -> {
          var matches = delegate.filter().test(event);
          if (matches) {
            offered.incrementAndGet();
          }
          return matches;
        };
      }

      @Override
      public void onEvent(Event event) throws Exception {
        try {
          delegate.onEvent(event);
        } finally {
          lock.lock();
          try {
            handled.incrementAndGet();
            idle.signalAll();
          } finally {
            lock.unlock();
          }
        }
      }
    };
  }

  @Override
  public void close() {
    bus.close();
    db.close();
  }
}
