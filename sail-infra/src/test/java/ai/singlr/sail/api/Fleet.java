/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.Main;
import ai.singlr.sail.commands.ServerStartCommand;
import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SlackNotifications;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SlackPoster;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SlackThreadStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class Fleet implements AutoCloseable {

  private static final String PROJECT = "acme";
  private static final Duration PROCESS_DEADLINE = Duration.ofSeconds(30);
  private static final Duration EVENT_DEADLINE = Duration.ofSeconds(10);
  private static final Notifications SLACK =
      new Notifications(null, null, new SlackNotifications("#sail-activity"));
  private static final ReviewPipelineConfig REVIEW =
      ReviewPipelineConfig.fromMap(
          Map.of(
              "max_iterations",
              1,
              "stages",
              List.of(
                  Map.of(
                      "name",
                      "quality",
                      "type",
                      "agent",
                      "agent",
                      "codex",
                      "gate",
                      "no_critical"))));
  private static final String PROJECT_YAML =
      """
      name: acme
      ssh:
        user: dev
      agent:
        type: codex
        auto_branch: true
        branch_prefix: sail/
      """;
  private static final String RUNNING_JSON =
      """
      [{"name":"acme","status":"Running","state":{"network":{"eth0":{"addresses":[]}}}}]
      """;

  private final Path root;
  private final List<Box> boxes = new ArrayList<>();
  private Box main;

  private Fleet(Path root) {
    this.root = root;
  }

  public static Fleet of(Path root) {
    return new Fleet(root);
  }

  public Box main(String handle) throws Exception {
    if (main != null) {
      throw new IllegalStateException("Fleet already has a main box");
    }
    main = createBox(handle, true);
    return main;
  }

  public Box node(String handle, Box expectedMain) throws Exception {
    if (main == null || main != expectedMain) {
      throw new IllegalArgumentException("Node must reference this fleet's main box");
    }
    var fde = main.fdes.add(handle, handle, handle + "@example.dev", "member");
    var token = new AuthSessionStore(main.db).create(fde.id(), Duration.ofHours(1)).token();
    var node = createBox(handle, false);
    writeSshShim(node, token);
    return node;
  }

  public void sync(Box node) throws Exception {
    if (node.main) {
      throw new IllegalArgumentException("Main does not sync to itself");
    }
    var command =
        new ProcessBuilder(
                javaBinary(),
                "-Duser.home=" + node.home,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                classpath(),
                Main.class.getName(),
                "sync",
                "--json")
            .redirectErrorStream(true);
    var env = command.environment();
    env.put("PATH", node.bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    env.remove("SAIL_DATA_DIR");
    env.remove("SAIL_NO_SYNC");
    env.remove("SAIL_TOKEN");
    var process = command.start();
    process.getOutputStream().close();
    if (!process.waitFor(PROCESS_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      fail("sync timed out for " + node.handle);
    }
    var output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.exitValue(), () -> "sync failed for " + node.handle + ":\n" + output);
  }

  public void syncAll(Box... nodes) throws Exception {
    for (var node : nodes) {
      sync(node);
    }
  }

  public void scenario(String specId, String assignee, Box... nodes) throws Exception {
    main.projects.upsert(PROJECT, PROJECT_YAML, main.handle);
    main.specs.create(spec(specId, assignee));
    syncAll(nodes);
  }

  private Box createBox(String handle, boolean isMain) throws Exception {
    var home = Files.createDirectories(root.resolve(handle + "-home"));
    var sailDir = Files.createDirectories(home.resolve(".sail"));
    var bin = Files.createDirectories(home.resolve("bin"));
    Files.writeString(
        sailDir.resolve("host.yaml"),
        isMain
            ? "sync:\n  role: main\n  handle: " + handle + "\n"
            : "sync:\n  role: node\n  main: sail@mainbox\n  handle: " + handle + "\n");
    var descriptor =
        Files.createDirectories(sailDir.resolve("projects").resolve(PROJECT)).resolve("sail.yaml");
    Files.writeString(descriptor, PROJECT_YAML);
    var db = Sqlite.open(sailDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var box = new Box(handle, isMain, home, bin, descriptor, db);
    boxes.add(box);
    return box;
  }

  private void writeSshShim(Box node, String token) throws Exception {
    var shim = node.bin.resolve("ssh");
    Files.writeString(
        shim,
        """
        #!/bin/sh
        export SAIL_TOKEN='%s'
        exec '%s' '-Duser.home=%s' --enable-native-access=ALL-UNNAMED -cp '%s' %s _sync
        """
            .formatted(token, javaBinary(), main.home, classpath(), Main.class.getName()));
    Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        PROJECT,
        "Fleet invariant " + id,
        SpecStatus.PENDING,
        assignee,
        "codex",
        null,
        null,
        null,
        0,
        "uday",
        null,
        null,
        "uday",
        List.of(),
        List.of());
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String classpath() {
    return System.getProperty("java.class.path");
  }

  @Override
  public void close() {
    for (var i = boxes.size() - 1; i >= 0; i--) {
      boxes.get(i).close();
    }
  }

  public static final class Box implements AutoCloseable {
    private final String handle;
    private final boolean main;
    private final Path home;
    private final Path bin;
    private final Path descriptor;
    private final Sqlite db;
    private final EventBus bus;
    private final ReviewPipelineController reviewsController;
    private final SailApiServer server;
    private final CapturingPoster slack;
    private final DispatchOperations dispatcher;
    private final StopOperations stopper;
    private final SailOperations operations;
    private final SpecStore specs;
    private final ProjectStore projects;
    private final RunStore runs;
    private final ReviewStore reviews;
    private final FdeStore fdes;
    private final MessageStore messages;

    private Box(String handle, boolean main, Path home, Path bin, Path descriptor, Sqlite db)
        throws Exception {
      this.handle = handle;
      this.main = main;
      this.home = home;
      this.bin = bin;
      this.descriptor = descriptor;
      this.db = db;
      specs = new SpecStore(db);
      projects = new ProjectStore(db);
      runs = new RunStore(db);
      reviews = new ReviewStore(db);
      fdes = new FdeStore(db);
      messages = new MessageStore(db);
      fdes.add(handle, handle, handle + "@example.dev", "admin");
      bus = new EventBus();
      reviewsController =
          new ReviewPipelineController(
              specs,
              reviews,
              project -> REVIEW,
              project -> "codex",
              (project, agent, prompt, reviewId, credential) -> CLEAN_REVIEW,
              bus,
              () -> {},
              new DirectExecutorService());
      var shell = new FleetShell();
      dispatcher =
          new DispatchOperations(
              shell,
              descriptor.toString(),
              specs,
              reviews,
              runs,
              fdes,
              bus::publish,
              new WatcherSpawner(shell, (command, log) -> 4242L),
              (project, config) -> "",
              command -> 0,
              DispatchOperations.Listener.NONE);
      stopper =
          new StopOperations(
              shell,
              descriptor.toString(),
              specs,
              runs,
              bus::publish,
              StopOperations.sessionHalter(shell),
              StopOperations.Listener.NONE);
      operations =
          new SailOperations(
                  shell,
                  descriptor.toString(),
                  (command, log) -> 4242L,
                  bus,
                  null,
                  specs,
                  reviews,
                  runs,
                  projects,
                  ai.singlr.sail.engine.ConnectEnvironment::detect,
                  SyncScheduler.disabled(),
                  fdes)
              .useMessages(messages);
      slack = new CapturingPoster();
      var syncConfig =
          main
              ? new SyncConfig("main", null, handle)
              : new SyncConfig("node", "sail@mainbox", handle);
      if (ServerStartCommand.narratesSlack(syncConfig)) {
        bus.subscribe(
            new SlackReactor(
                project -> SLACK, new SlackThreadStore(db), SlackReactor.specLookup(specs), slack));
      }
      var tokens = new TokenStore(db);
      var apiToken = tokens.create("fleet-admin", "admin").token();
      server =
          new SailApiServer(
              "127.0.0.1",
              freePort(),
              operations,
              new SessionAwareAuth(new AuthSessionStore(db), fdes, new TokenAuth(tokens)),
              bus,
              null,
              home.resolve(".sail/sail-api.sock"),
              null);
      server.start();
      ServerConnectionConfig.saveLocalConfig(
          "http://127.0.0.1:" + server.port(), apiToken, home.resolve(".sail/config.yaml"));
    }

    public String handle() {
      return handle;
    }

    public void createProject(String name) {
      projects.upsert(name, "name: " + name + "\n", handle);
    }

    public void renameProject(String oldName, String newName) {
      projects.rename(oldName, newName, "name: " + newName + "\n");
    }

    public boolean hasProject(String name) {
      return projects.findByName(name).isPresent();
    }

    public DispatchOperations.Dispatched dispatch(String specId) {
      var outcome =
          dispatcher.dispatch(
              PROJECT,
              new DispatchOperations.Request(specId, "background", false, null, false),
              Actor.cliOperator(handle),
              handle);
      return assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    }

    public ApiException dispatchRefusal(String specId) {
      try {
        dispatch(specId);
        throw new AssertionError("dispatch unexpectedly succeeded on " + handle);
      } catch (ApiException e) {
        return e;
      }
    }

    public void updateStatus(String specId, SpecStatus status) {
      specs.updateStatus(specId, status);
    }

    public StopOperations.Outcome stop() {
      return stopper.stop(
          new StopOperations.ProjectTarget(PROJECT), Actor.cliOperator(handle), handle, false);
    }

    /**
     * One missed-stop reconciliation pass under maximally aggressive conditions — every unit probes
     * dead and every run is past the launch grace — so anything the reaper could ever replay for
     * this box, it replays here. Returns how many stops were replayed.
     */
    public int reconcileSweep() {
      var reconciler =
          new MissedStopReconciler(
              specs,
              runs,
              new EventStore(db),
              reviews,
              bus,
              (project, runId, unit) -> false,
              () -> handle,
              () -> java.time.Instant.now().plus(Duration.ofHours(1)));
      return reconciler.sweep();
    }

    public void authoritativeStop(String specId) {
      var run = runs.listForSpec(specId).stream().filter(r -> handle.equals(r.node())).findFirst();
      run.ifPresent(row -> runs.complete(row.id(), "completed", 0));
      var event =
          Event.of(
              PROJECT,
              specId,
              Event.WellKnownTypes.AGENT_SESSION_STOPPED,
              "codex",
              handle,
              Map.of(
                  Event.WellKnownData.SOURCE,
                  Event.WellKnownData.SOURCE_WATCHER,
                  Event.WellKnownData.EXIT_CODE,
                  0));
      reviewsController.onEvent(event);
      bus.publish(event);
    }

    public SpecMessageView postMessage(String specId, String body) {
      return operations
          .postRoomMessage(
              specId, new SpecMessageRequest(body, null, false), Actor.cliOperator(handle), handle)
          .orThrow()
          .message();
    }

    public List<SpecMessageView> listMessages(String specId) {
      return operations.roomMessages(specId, null, null, 50).orThrow().messages();
    }

    public void assertSpecStatus(String id, SpecStatus status) {
      assertEquals(status, specs.findById(id).orElseThrow().status());
    }

    public SpecStatus specStatus(String id) {
      return specs.findById(id).orElseThrow().status();
    }

    public int reviewCount(String specId) {
      return reviews.reviewsForSpec(specId).size();
    }

    public List<RunStore.RunRow> runs(String specId) {
      return runs.listForSpec(specId);
    }

    public Result<RunLogResponse> runLog(String runId) {
      return operations.runLog(runId, 100, handle, Actor.cliOperator(handle));
    }

    public String latestPeer(String specId) {
      var history = new ChangeLog(db).history("spec", specId);
      assertTrue(!history.isEmpty(), "spec history must not be empty");
      return history.getLast().peer();
    }

    public int slackPosts() {
      return slack.size();
    }

    public List<SlackPoster.Post> awaitSlackPosts(int count) throws InterruptedException {
      return slack.await(count);
    }

    @Override
    public void close() {
      server.close();
      db.close();
    }
  }

  private static final class FleetShell implements ShellExec {
    @Override
    public Result exec(List<String> command) {
      if (String.join(" ", command).contains("incus list")) {
        return new Result(0, RUNNING_JSON, "");
      }
      return new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private static final class CapturingPoster implements SlackPoster {
    private final List<Post> posts = new ArrayList<>();

    @Override
    public synchronized Result post(Post post) {
      posts.add(post);
      notifyAll();
      return new Result("C123", Integer.toString(posts.size()));
    }

    synchronized int size() {
      return posts.size();
    }

    synchronized List<Post> await(int count) throws InterruptedException {
      var deadline = System.nanoTime() + EVENT_DEADLINE.toNanos();
      while (posts.size() < count) {
        var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          fail("expected " + count + " Slack posts, got " + posts.size());
        }
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }
      return List.copyOf(posts);
    }
  }

  private static int freePort() throws Exception {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
