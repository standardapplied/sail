/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.LiveMemberPrompt;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * A room as the server runs it, over a {@link FakeContainer} and a {@link FakeAgentProcess}: the
 * real stores, the real bus with the real {@link RoomWakeReactor} and {@link RunTracker}
 * subscribed, and the real {@link SailOperations} — its message door, its membership doors and its
 * dispatch machinery, {@link LiveMembers} included. Only the container shell, the member's process
 * and the clock are fake, and the reactor runs what it schedules on the thread that delivered the
 * event, so {@link #settle} returns once a message has reached the member's stdin.
 */
final class LiveRoom implements AutoCloseable {

  static final String PROJECT = ReviewLoop.PROJECT;
  static final String HANDLE = ReviewLoop.HANDLE;

  /** Long enough that nothing scripted to happen is ever forced. */
  static final LiveMembers.Grace PATIENT =
      new LiveMembers.Grace(
          Duration.ofSeconds(60),
          Duration.ofSeconds(60),
          Duration.ofSeconds(60),
          Duration.ofSeconds(60));

  /** Short enough that a process scripted to ignore everything is forced at once. */
  static final LiveMembers.Grace IMPATIENT =
      new LiveMembers.Grace(
          Duration.ofMillis(20),
          Duration.ofMillis(20),
          Duration.ofMillis(20),
          Duration.ofMillis(20));

  private static final long PATIENCE_SECONDS = 20;

  final Sqlite db;
  final SpecStore specs;
  final RunStore runs;
  final MessageStore messages;
  final RoomStore rooms;
  final ReviewStore reviews;
  final FakeContainer container = new FakeContainer(PROJECT);
  final Shell shell = new Shell();
  final FakeAgentProcess processes = new FakeAgentProcess(container);

  /**
   * The server's clock: it starts at the real time, since run rows are stamped with it, and moves
   * only when a test says time passed.
   */
  final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

  final List<Event> published = new CopyOnWriteArrayList<>();
  final List<String> woken = new CopyOnWriteArrayList<>();
  final List<List<String>> launchCommands = new CopyOnWriteArrayList<>();
  EventBus bus;
  SailOperations sail;
  LiveMembers live;
  RoomWakeReactor reactor;

  private final ProjectReader reader;
  private final Settling settling = new Settling();
  private final Semaphore flushes = new Semaphore(0);
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition heard = lock.newCondition();
  private volatile LiveMembers.Grace grace = PATIENT;
  private volatile SessionYield terminals = SessionYield.NONE;

  LiveRoom(Path dir) {
    this(dir, ReviewLoop.YAML);
  }

  LiveRoom(Path dir, String yaml) {
    db = Sqlite.open(dir.resolve("room-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var projects = new ProjectStore(db);
    reader = ProjectReader.ofCatalog(projects);
    Acting.system(() -> projects.upsert(PROJECT, yaml));
    new FdeStore(db).add(HANDLE, null, null, "admin");
    specs = new SpecStore(db);
    runs = new RunStore(db);
    messages = new MessageStore(db);
    rooms = new RoomStore(db);
    reviews = new ReviewStore(db);
    start();
  }

  /** Everything a server start builds, over the stores and the container that outlive it. */
  private void start() {
    var started = new EventBus();
    bus = started;
    sail = operations(started);
    live = sail.liveMembers().useProcesses(processes).useTiming(timing());
    reactor =
        new RoomWakeReactor(
            specs,
            rooms,
            runs,
            messages,
            () -> HANDLE,
            (project, specId) -> {
              woken.add(specId);
              sail.startRoomRun(project, specId, HANDLE);
            },
            sail::guardRoomRun,
            live,
            Duration.ZERO,
            Duration.ZERO,
            RoomWakeReactor.COOLDOWN,
            new DirectExecutorService(),
            pause -> {},
            now::get);
    started.subscribe(settling.counted(new SpecStoreAuditPersister(new EventStore(db))));
    started.subscribe(
        settling.counted(new RunTracker(runs, SyncScheduler.disabled(), () -> HANDLE)));
    started.subscribe(settling.counted(reactor));
    started.subscribe(settling.counted(recorder()));
  }

  /** The operations of one sail process over this box's database and container. */
  private SailOperations operations(EventBus events) {
    return new SailOperations(
            shell,
            reader,
            WatcherSpawner::spawnProcess,
            events,
            null,
            specs,
            reviews,
            runs,
            new ProjectStore(db),
            ConnectEnvironment::detect,
            SyncScheduler.disabled(),
            new FdeStore(db),
            new Terminals(),
            new OperationHooks(
                events::publish,
                new WatcherSpawner(shell, null),
                (project, config) -> "",
                this::launch,
                DispatchOperations.Listener.NONE,
                StopOperations.Listener.NONE))
        .useMessages(messages)
        .useRooms(rooms);
  }

  /**
   * Another sail process on this box, a CLI command's: the same database and container, none of the
   * server's members, and its events told to the server as a CLI tells them.
   */
  SailOperations elsewhere() {
    return operations(bus);
  }

  /** The fake clock, a text window that elapses only when told to, and the grace in force. */
  private LiveMembers.Timing timing() {
    return new LiveMembers.Timing(now::get, window -> flushes.acquire(), grace);
  }

  /** Ends are forced at once from now on: for a process scripted to ignore what it is told. */
  void impatient() {
    grace = IMPATIENT;
    live.useTiming(timing());
  }

  /** The session host answers through {@code terminals} from now on. */
  void terminals(SessionYield terminals) {
    this.terminals = terminals;
  }

  /**
   * The container's shell as sail reaches it: the {@link FakeContainer}, except that a command
   * containing the fragment a test names is never run at all, as when {@code incus} itself fails.
   */
  final class Shell implements ShellExec {

    private volatile String unanswered = "";

    /** Every command containing {@code fragment} throws from now on; blank for none. */
    void unanswered(String fragment) {
      this.unanswered = fragment;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      if (!unanswered.isEmpty() && String.join(" ", command).contains(unanswered)) {
        throw new IOException("incus: connection refused");
      }
      return container.exec(command);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  /** The seam the server hands its reservation, answering through whatever the test set last. */
  private final class Terminals implements SessionYield {
    @Override
    public Hold lock(String project) throws IOException {
      return terminals.lock(project);
    }

    @Override
    public void end(List<String> sessions, String reason) throws IOException {
      terminals.end(sessions, reason);
    }

    @Override
    public boolean live(String session) throws IOException {
      return terminals.live(session);
    }
  }

  /**
   * Sail dies and comes back: the old server's bus and members are abandoned as a killed process
   * leaves them, and a new server comes up over the same database and the same container.
   */
  void restart() {
    settle();
    bus.close();
    start();
  }

  /** Lets one deferred text flush through, as the window it waits out elapsing would. */
  void windowElapses() {
    flushes.release();
  }

  void later(Duration elapsed) {
    now.updateAndGet(instant -> instant.plus(elapsed));
  }

  /** A draft spec this box owns, working {@code api}, with its room. */
  void spec(String id) {
    Acting.as(
        HANDLE,
        () ->
            specs.create(
                new SpecStore.SpecRow(
                    id,
                    PROJECT,
                    "Live spec",
                    SpecStatus.DRAFT,
                    HANDLE,
                    null,
                    null,
                    null,
                    "feat/test",
                    0,
                    HANDLE,
                    "",
                    "",
                    null,
                    List.of(),
                    List.of("api"))));
    Acting.system(() -> rooms.ensureFor(id, PROJECT, "Live spec", HANDLE, null));
  }

  /** A room with no spec that this box's FDE owns. */
  void room(String id) {
    Acting.as(
        HANDLE,
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    id, PROJECT, "Lounge", HANDLE, null, null, HANDLE, null, null, HANDLE)));
  }

  private static Actor owner() {
    return new Actor(HANDLE, Role.ADMIN, Actor.Lane.API);
  }

  /** Seats {@code agent} in the room through the door Mast uses. */
  void engage(String conversation, String agent, String mode) {
    Actor.call(
            owner(),
            () ->
                sail.addRoomMember(
                    conversation, new EngageRequest(agent, mode, null, false), HANDLE))
        .orThrow();
    settle();
  }

  /** Dismisses the room's member through the door Mast uses. */
  void dismiss(String conversation) {
    vacate(conversation);
    settle();
  }

  /** As {@link #dismiss}, from inside a reaction to an event: nothing is waited for. */
  void vacate(String conversation) {
    Actor.call(owner(), () -> sail.removeRoomMember(conversation, HANDLE)).orThrow();
  }

  /** The box's FDE posts {@code body} in the room; returns once the room has reacted to it. */
  String says(String conversation, String body) {
    var posted =
        Actor.call(
                owner(),
                () ->
                    sail.postRoomMessage(
                        conversation, new SpecMessageRequest(body, null, false), HANDLE))
            .orThrow();
    settle();
    return posted.message().id();
  }

  /** Seats claude in {@code conversation} and posts {@code body}; the process that started. */
  FakeAgentProcess.Fake prompted(String conversation, String mode, String body) {
    engage(conversation, "claude-code", mode);
    says(conversation, body);
    return processes.latest();
  }

  /** The process says {@code lines}, and everything they cause has happened on return. */
  void says(FakeAgentProcess.Fake process, String... lines) {
    process.says(lines);
    process.settle();
    settle();
  }

  /** The stdin line that is the messages {@code messageIds} of {@code roomId} and nothing else. */
  String promptOf(String roomId, String... messageIds) {
    var ids = List.of(messageIds);
    return LiveStream.userMessage(
        LiveMemberPrompt.messages(
                messages.list(roomId, null, 50).stream()
                    .filter(message -> ids.contains(message.id()))
                    .toList())
            .getFirst()
            .text());
  }

  /** The turn number of every turn end told so far. */
  List<Object> turns() {
    return data(Event.WellKnownTypes.AGENT_TURN_ENDED).stream()
        .map(data -> data.get("turn"))
        .toList();
  }

  /** A live event's data: what every one carries, then its own. */
  static Map<String, Object> live(
      RunStore.RunRow run, String roomId, int turn, Map<String, Object> more) {
    var data = new LinkedHashMap<String, Object>();
    data.put("run_id", run.id());
    data.put("room_id", roomId);
    data.put("principal", run.principal());
    data.put("turn", turn);
    data.putAll(more);
    return data;
  }

  /** The live invocation every member starts under, then {@code tail}. */
  static List<String> invocation(String... tail) {
    var command =
        new ArrayList<>(
            List.of(
                "claude",
                "--print",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--permission-prompts",
                "none",
                "--settings",
                "/home/dev/.sail/claude-live.json"));
    command.addAll(List.of(tail));
    return command;
  }

  /** The run {@code runId} as its row stands now. */
  RunStore.RunRow run(String runId) {
    return runs.findById(runId).orElseThrow();
  }

  /** The run of the member's process started last. */
  RunStore.RunRow memberRun() {
    return runs.findById(processes.latest().runId).orElseThrow();
  }

  /** Everything said in the room, oldest first, as {@code author: body}. */
  List<String> conversation(String roomId) {
    return messages.list(roomId, null, 100).stream()
        .map(message -> message.author() + ": " + message.body())
        .toList();
  }

  List<Event> events(String type) {
    return published.stream().filter(event -> type.equals(event.type())).toList();
  }

  /** The {@code data} of every event of {@code type} published so far, oldest first. */
  List<Map<String, Object>> data(String type) {
    return events(type).stream().map(Event::data).toList();
  }

  /** Blocks until every subscriber has handled every event published so far. */
  void settle() {
    settling.settle();
  }

  /** Blocks until {@code count} events of {@code type} have been published, then settles. */
  void await(String type, int count) {
    lock.lock();
    try {
      var remaining = TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
      while (events(type).size() < count) {
        if (remaining <= 0) {
          throw new AssertionError(
              "expected " + count + " " + type + " event(s), heard " + events(type).size());
        }
        remaining = heard.awaitNanos(remaining);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for " + type, e);
    } finally {
      lock.unlock();
    }
    settle();
  }

  /** Blocks until the member's run of {@code process} has been finished and its stop told. */
  void awaitStopped(FakeAgentProcess.Fake process) {
    lock.lock();
    try {
      var remaining = TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
      while (stopOf(process.runId) == null) {
        if (remaining <= 0) {
          throw new AssertionError("run " + process.runId + " never stopped");
        }
        remaining = heard.awaitNanos(remaining);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for a stop", e);
    } finally {
      lock.unlock();
    }
    settle();
  }

  /** The stop published for {@code runId}, or null while it runs. */
  Event stopOf(String runId) {
    return events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
        .filter(event -> runId.equals(event.data().get(Event.WellKnownData.RUN_ID)))
        .findFirst()
        .orElse(null);
  }

  private int launch(List<String> command) {
    var runId = command.get(command.size() - 3);
    launchCommands.add(List.copyOf(command));
    container.started(runId);
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
        lock.lock();
        try {
          heard.signalAll();
        } finally {
          lock.unlock();
        }
      }
    };
  }

  /** The stream-json lines Claude Code writes, as a test scripts them. */
  static final class Lines {

    private Lines() {}

    static String init(String sessionId) {
      return "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + sessionId + "\"}";
    }

    static String textStart() {
      return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
          + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}}";
    }

    static String delta(String text) {
      return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
          + "\"delta\":{\"type\":\"text_delta\",\"text\":\""
          + text
          + "\"}}}";
    }

    static String toolUse(String id, String tool, String input) {
      return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\""
          + id
          + "\",\"name\":\""
          + tool
          + "\",\"input\":"
          + input
          + "}]}}";
    }

    static String toolResult(String id) {
      return "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
          + "\"tool_use_id\":\""
          + id
          + "\",\"content\":\"ok\"}]}}";
    }

    static String result(String text) {
      return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\""
          + text
          + "\"}";
    }

    static String failure(String subtype, String text) {
      return "{\"type\":\"result\",\"subtype\":\""
          + subtype
          + "\",\"is_error\":true,\"result\":\""
          + text
          + "\"}";
    }
  }

  /** Every fake process still up exits, as sail's own exit would close their stdin. */
  @Override
  public void close() {
    processes.processes.forEach(FakeAgentProcess.Fake::exitsIfRunning);
    live.close();
    bus.close();
    db.close();
  }
}
