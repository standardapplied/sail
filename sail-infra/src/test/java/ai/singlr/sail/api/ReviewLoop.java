/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The review loop as the server runs it, over a {@link FakeContainer}: the real stores, the real
 * bus with the real {@link ReviewPipelineController} and {@link RunTracker} subscribed, and the
 * real launch path ({@link DispatchOperations}'s review lanes over {@link RunLauncher} and {@link
 * WatcherSpawner}). Only the container shell is fake. A run ends here the one way it ends in
 * production — an authoritative stop on the bus, built from what the watcher would read off the
 * container — and {@link #settle} returns once every subscriber has handled everything published,
 * so a test reads the loop's rows without sleeping or polling.
 */
final class ReviewLoop implements AutoCloseable {

  static final String PROJECT = "test-project";
  static final String HANDLE = "node-a";

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

  private final Path yaml;
  private final Function<String, ReviewPipelineConfig> config;
  private final Function<String, String> reviewer;
  private final AtomicLong offered = new AtomicLong();
  private final AtomicLong handled = new AtomicLong();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition idle = lock.newCondition();
  private volatile ScriptedAgent script;
  private volatile boolean refusing;

  /** A loop whose pipeline and reviewer are resolved from {@code yaml}, as the server wires it. */
  static ReviewLoop wired(Path dir, String yaml) {
    return new ReviewLoop(dir, yaml, null, null);
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
    this.yaml = dir.resolve("sail-" + System.nanoTime() + ".yaml");
    describe(yamlContent);
    Function<String, SailYaml> loader = project -> load();
    this.config = config != null ? config : ReviewWiring.configResolver(loader);
    this.reviewer = reviewer != null ? reviewer : ReviewWiring.reviewerResolver(loader);
    db = Sqlite.open(dir.resolve("loop-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
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
                yaml.toString(),
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
        () -> HANDLE,
        clock);
  }

  /** The watcher re-armer this server would run, probing the container and the host. */
  WatcherRearmer rearmer() {
    return new WatcherRearmer(
        runs,
        WatcherRearmer.systemdUnitActiveProbe(container),
        new WatcherSpawner(container, null)::watcherProcessRunningForRun,
        WatcherRearmer.livingProcess(),
        () -> HANDLE,
        operations::relaunchWatcher);
  }

  /** Rewrites the project's descriptor, as an edit to {@code sail.yaml} between two runs does. */
  void describe(String yamlContent) {
    try {
      Files.writeString(yaml, yamlContent);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private SailYaml load() {
    try {
      return SailYaml.fromMap(YamlUtil.parseFile(yaml));
    } catch (Exception e) {
      return null;
    }
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
    stopped(runId, null);
  }

  /** The run's watcher kills it for {@code reason} and publishes the stop that says so. */
  void kill(String runId, String reason) {
    try {
      new AgentSession(container).killAgent(PROJECT, AgentUnit.forRun(runId));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    stopped(runId, reason);
  }

  private void stopped(String runId, String reason) {
    onEvent(watcherStop(runId, reason));
  }

  /** The stop the run's watcher publishes, read off the container as the watcher reads it. */
  Event watcherStop(String runId, String reason) {
    try {
      var exit = new AgentSession(container).queryExitStatus(PROJECT, AgentUnit.forRun(runId));
      return RunStops.of(
          Event.WellKnownData.SOURCE_WATCHER,
          PROJECT,
          exit.specId(),
          exit.agentType(),
          exit.runId(),
          exit.role(),
          exit.exitCode(),
          reason);
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

  private int launch(List<String> command) {
    if (refusing) {
      return 1;
    }
    launchCommands.add(List.copyOf(command));
    container.started(command.get(command.size() - 3));
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
