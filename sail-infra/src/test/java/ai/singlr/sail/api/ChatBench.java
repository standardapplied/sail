/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.Recorded;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * A box where sam and tom each have a running workspace of acme ({@code try}, {@code tom}), sam
 * with a stored Claude Code key and tom with none, ada an admin; its chats held by a {@link
 * ChatHost} over the real pty door of the box ({@link HostLanes.Pty}) and Incus scripted, whose
 * processes stand in ({@link FakeAgentProcess}), found inside their workspace by their pid file
 * ({@link FakeContainer}), and are fed what Claude Code wrote in the S0 probe ({@link Recorded}),
 * on a clock the test moves. A read's wait lets its bound pass at once unless a test asks it to
 * wait for an item.
 */
final class ChatBench implements AutoCloseable {

  /**
   * Each step of an end: a stand-in ends at once when its stdin closes, so this is waited out only
   * by one that does not.
   */
  static final Duration GRACE = Duration.ofMillis(200);

  /** The longest a test waits for what the host does on a thread of its own. */
  static final Duration PATIENCE = Duration.ofSeconds(20);

  final Sqlite db;
  final FdeStore fdes;
  final ChatStore store;
  final ScriptedShellExecutor incus;
  final HostLanes.Pty door;
  private final ChatLaunch launch;

  /**
   * The processes a chat starts, found inside their workspace by their pid file and signalled there
   * ({@link FakeAgentProcess#container}).
   */
  final FakeAgentProcess processes = new FakeAgentProcess(new FakeContainer("acme"));

  final ManualClock clock =
      new ManualClock(Instant.parse("2026-11-27T09:00:00Z"), Duration.ofDays(40));
  final ChatHost host;
  final String samId;

  /** A permit each time a read first waits for an item, once {@link #readsWait} says reads wait. */
  final Semaphore held = new Semaphore(0);

  /** The text windows a test lets close, once {@link #windowsWait} says windows wait. */
  final Semaphore windows = new Semaphore(0);

  private volatile boolean readsWait;
  private volatile boolean windowsWait;

  /** Workspace {@code name} of acme, running, labelled for {@code owner} by {@code id}. */
  static String workspace(String name, String owner, String id) {
    return workspace("acme", name, owner, id);
  }

  /**
   * Workspace {@code name} of {@code project}, running, labelled for {@code owner} by {@code id}.
   */
  static String workspace(String project, String name, String owner, String id) {
    return """
        {"name": "%s-%s", "status": "Running", "type": "container",
         "config": {"user.sail.project": "%s", "user.sail.workspace": "%s",
                    "user.sail.ready": "1762941601000",
                    "user.sail.owner": "%s", "user.sail.owner_id": "%s"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(project, name, project, name, owner, id);
  }

  ChatBench(Path dir) {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    HeldProjects.hold(db, "beta");
    Acting.system(
        () -> {
          new ProjectStore(db)
              .upsert(
                  TestDefinitions.yaml(
                      "name: acme\nagent:\n  type: claude-code\n  install: [claude-code]\n"));
          new ProjectStore(db)
              .upsert(
                  TestDefinitions.yaml("name: beta\nagent:\n  type: codex\n  install: [codex]\n"));
        });
    fdes = new FdeStore(db);
    store = new ChatStore(db);
    samId = Acting.system(() -> fdes.add("sam", "Sam", null, "member")).id();
    var tomId = Acting.system(() -> fdes.add("tom", "Tom", null, "member")).id();
    Acting.system(() -> fdes.add("ada", "Ada", null, "admin"));
    fdes.logins().store(samId, Harnesses.of("claude-code"), "work", "api-key", "fake-key-sam");
    incus =
        new ScriptedShellExecutor(new ShellExec.Result(0, "[]", ""))
            .onOk("incus list ^acme-try$", "[" + workspace("try", "sam", samId) + "]")
            .onOk("incus list ^acme-tom$", "[" + workspace("tom", "tom", tomId) + "]")
            .onOk("incus list ^beta-dock$", "[" + workspace("beta", "dock", "sam", samId) + "]")
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk(
                "incus list --format json",
                "[" + workspace("try", "sam", samId) + ", " + workspace("tom", "tom", tomId) + "]");
    var roles = TestAuth.roles(db, new SyncConfig("main", null, "ada", "box"));
    door =
        new HostLanes.Pty(
            db,
            roles,
            null,
            new BoxInstances(incus),
            TestWorkspaces.over(incus),
            ProjectReader.ofCatalog(new ProjectStore(db)),
            handle -> WorkspaceOwner.onThisBox(fdes, handle, "ada"),
            incus);
    launch =
        new ChatLaunch(
            door,
            fdes,
            ProjectReader.ofCatalog(new ProjectStore(db)),
            processes,
            processes.container(),
            roles,
            Harnesses::of);
    processes.container().holds("try");
    processes.onStart(fake -> fake.onClose(fake::exitsIfRunning));
    host = restarted();
  }

  /** A host over this box's chats, as sail starts one: what a restart leaves. */
  ChatHost restarted() {
    return restarted(new ProcessEnd.Grace(GRACE, GRACE, GRACE, GRACE));
  }

  /** A host over this box's chats whose ends wait {@code grace} each step. */
  ChatHost restarted(ProcessEnd.Grace grace) {
    return restarted(launch, grace);
  }

  /**
   * A host over this box's chats, as sail starts one, admitting and starting them by {@code
   * launch}.
   */
  ChatHost restarted(ChatLaunch launch) {
    return restarted(launch, new ProcessEnd.Grace(GRACE, GRACE, GRACE, GRACE));
  }

  private ChatHost restarted(ChatLaunch launch, ProcessEnd.Grace grace) {
    return new ChatHost(
        store,
        launch,
        new ChatTiming(
            clock::instant,
            window -> {
              if (!windowsWait) {
                throw new InterruptedException("the window is the next line's to close");
              }
              windows.acquire();
            },
            (arrivals, within) -> {
              if (readsWait) {
                held.release();
                arrivals.wait();
              } else {
                clock.advance(within);
              }
            },
            Duration.ofSeconds(25),
            Duration.ofMinutes(5),
            grace));
  }

  /** How many chats this box holds. */
  long chats() {
    return db.queryOne("SELECT COUNT(*) FROM chat_sessions", row -> row.integer(0)).orElseThrow();
  }

  /**
   * Reads from now on wait for an item to be logged, and say when they wait ({@link #held}); else a
   * read's bound passes at once.
   */
  void readsWait() {
    readsWait = true;
  }

  /**
   * Text windows from now on close only as a test lets them ({@link #windows}); else the next line
   * closes one.
   */
  void windowsWait() {
    windowsWait = true;
  }

  /** {@code work} as {@code handle}'s, through the API's lane. */
  static <T> T as(String handle, Supplier<T> work) {
    return Actor.call(
        new Actor(handle, handle.equals("ada") ? Role.ADMIN : Role.MEMBER, Actor.Lane.API),
        work::get);
  }

  /** Sam's chat with Claude Code in his workspace, asking first. */
  String samsChat() {
    return as("sam", () -> host.open("acme", "try", "claude-code", "default", false)).session();
  }

  ChatHost.Sent send(String session, String text) {
    return as("sam", () -> host.send(session, text));
  }

  ChatHost.Read read(String session, long after) {
    return as("sam", () -> host.read(session, after));
  }

  /** The process started last, having said each of {@code lines}, a recording's, read through. */
  FakeAgentProcess.Fake says(String... lines) {
    var fake = processes.latest();
    fake.says(lines);
    fake.settle();
    return fake;
  }

  /**
   * Sam ends chat {@code session} on a thread of its own as a process starts for it, and returns
   * once that end is held up in the host or is done; past {@link #PATIENCE}, the test fails.
   */
  Thread endsAsItStarts(String session) {
    return heldInTheHost(
        "sail-test-end-" + session,
        () ->
            as(
                "sam",
                () -> {
                  host.end(session);
                  return null;
                }));
  }

  /**
   * Runs {@code work} on a thread of its own, named {@code name}, and returns once it is held up in
   * a lock of the host's or is done; past {@link #PATIENCE}, the test fails.
   */
  Thread heldInTheHost(String name, Runnable work) {
    var running = Thread.ofPlatform().name(name).start(work);
    var deadline = System.nanoTime() + PATIENCE.toNanos();
    while (running.isAlive() && !heldInTheHost(running)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(name + " was neither held nor done");
      }
      Thread.onSpinWait();
    }
    return running;
  }

  /** Whether {@code thread} waits to enter a lock of the host's own. */
  private static boolean heldInTheHost(Thread thread) {
    var stack = thread.getStackTrace();
    return thread.getState() == Thread.State.BLOCKED
        && stack.length > 0
        && stack[0].getClassName().equals(ChatHost.class.getName());
  }

  /** Line {@code number} of recording {@code recording}. */
  static String line(String recording, int number) {
    return Recorded.line(recording, number);
  }

  /** {@code fake} met its end and its reader is done with it: what follows its end has happened. */
  static void over(FakeAgentProcess.Fake fake) {
    fake.awaitEndSeen();
    fake.settle();
  }

  /** Each item of {@code session}'s log as its kind. */
  List<String> kinds(String session) {
    return store.after(session, 0, 1000).stream().map(ChatStore.Item::kind).toList();
  }

  /** Each item of {@code session}'s log as a client reads it. */
  List<Map<String, Object>> items(String session) {
    return read(session, 0).items();
  }

  @Override
  public void close() {
    host.close();
    db.close();
  }
}
