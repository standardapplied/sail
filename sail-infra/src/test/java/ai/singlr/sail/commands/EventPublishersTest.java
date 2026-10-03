/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.BusTesting;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.EventSubscriber;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.RunTracker;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SpecLifecycleReactor;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.api.TestControlPlane;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncRpcServer;
import ai.singlr.sail.sync.SyncTransitionSink;
import ai.singlr.sail.sync.SyncWire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every legitimate publisher still lands its events, each through its real code path to a live
 * server's door: the host CLI's dispatch, restart, ad-hoc run and stop, and the watcher's stop, on
 * a node whose FDE is a member; a node's sync announcing what it pulled; and main's sync server
 * relaying a node's finished run. Each lands with the publisher the server authenticated: the box's
 * FDE, whom its host token acts as, or for main's relay the FDE who pushed, whose session the
 * gateway hands it. A run a node pushed on another member's spec is not relayed as that spec's.
 */
class EventPublishersTest {

  private static final String ME = "me";
  private static final SyncConfig NODE = new SyncConfig("node", "sail@main", ME, "node-box");
  private static final SyncConfig MAIN = new SyncConfig("main", null, "uday", "main-box");
  private static final Event.Publisher MEMBER_ME = new Event.Publisher(ME, "member", "api");

  private static final String YAML =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
        auto_branch: true
        branch_prefix: sail/
      """;

  @TempDir Path tempDir;
  private SyncBox box;
  private EventBus bus;
  private SailOperations serverOperations;
  private SailApiServer server;
  private SailEventPublisher publisher;
  private String yaml;
  private final List<Event> landed = new CopyOnWriteArrayList<>();
  private final Semaphore delivered = new Semaphore(0);
  private long consumed;

  @AfterEach
  void tearDown() {
    System.clearProperty("SAIL_SERVER");
    System.clearProperty("SAIL_TOKEN");
    if (server != null) {
      server.close();
    }
    if (serverOperations != null) {
      serverOperations.close();
    }
    if (bus != null) {
      bus.close();
    }
    if (box != null) {
      box.close();
    }
  }

  @Test
  void aNodesHostCliDispatchRestartRunAndStopEventsLand() throws Exception {
    serve(NODE, "member");
    seedSpec(box, "auth", ME);
    var cli =
        cli(
            DispatchCommandWiringTest.shell()
                .on("git -C /home/dev/workspace/app rev-parse --verify --quiet refs/heads/x", "")
                .on("git -C /home/dev/workspace/app checkout -f x", ""));
    var live =
        cli(
            DispatchCommandWiringTest.shell()
                .on("agent.pid", "123")
                .on("kill -0 123", "")
                .on("agent-session.json", "{\"task\": \"work\"}"));

    assertInstanceOf(
        DispatchOperations.Dispatched.class,
        DispatchCommand.dispatchAsOperator(cli, "acme", request(false), ME));
    AgentStopCommand.stopAsOperator(cli, "acme", ME, false);
    Acting.system(
        () -> box.specs.updateReposAndStatus("auth", List.of("app"), SpecStatus.REVIEW, "x"));
    assertInstanceOf(
        DispatchOperations.Dispatched.class,
        DispatchCommand.dispatchAsOperator(cli, "acme", request(true), ME));
    AgentStopCommand.stopAsOperator(cli, "acme", ME, false);
    Actor.call(
        live.identity().operator(),
        () ->
            live.dispatching()
                .startAdhoc(
                    "acme",
                    new DispatchOperations.AdhocRequest("look", null, null, true, false),
                    ME));

    var types = landedSoFar().stream().map(Event::type).toList();
    assertEquals(
        List.of(
            Event.WellKnownTypes.SPEC_DISPATCHED,
            Event.WellKnownTypes.SNAPSHOT_CREATED,
            Event.WellKnownTypes.AGENT_CANCELLED,
            Event.WellKnownTypes.SPEC_RESTARTED,
            Event.WellKnownTypes.SPEC_DISPATCHED,
            Event.WellKnownTypes.SNAPSHOT_CREATED,
            Event.WellKnownTypes.AGENT_CANCELLED,
            Event.WellKnownTypes.AGENT_SESSION_STARTED),
        types);
    assertTrue(
        landed.stream().allMatch(event -> MEMBER_ME.equals(event.publisher())), types.toString());
  }

  @Test
  void theWatchersStopLandsAndFinishesTheRunItsBoxExecuted() throws Exception {
    serve(NODE, "member");
    seedSpec(box, "auth", "someone-else");
    var runs = new RunStore(box.db);
    var run = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs.reserveDispatch(
                run, "acme", "auth", ME, "build", List.of(), "claude-code", "b", "t", "l", "u"));
    var tracked = new CountDownLatch(1);
    bus.subscribe(
        BusTesting.latching(new RunTracker(runs, SyncScheduler.disabled(), () -> ME), tracked));

    AgentWatchCommand.emitSyntheticStop(
        publisher::publish,
        "acme",
        new AgentSession.ExitState(false, 0, "auth", "claude-code", run, "build"));

    var stop = landedSoFar().getFirst();
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, stop.type());
    assertEquals(MEMBER_ME, stop.publisher());
    assertEquals(Event.WellKnownData.SOURCE_WATCHER, stop.data().get(Event.WellKnownData.SOURCE));
    BusTesting.awaitDelivery(tracked);
    assertEquals("stopped", runs.findById(run).orElseThrow().status());
  }

  @Test
  void aNodesSyncAnnouncesTheMessagesItPulledAndItsBoard() throws Exception {
    serve(NODE, "member");
    try (var main = new SyncBox("main")) {
      new FdeStore(main.db).add(ME, null, null, "member");
      new FdeStore(main.db).add("uday", null, null, "admin");
      seedSpec(main, "shared", ME);
      var message =
          Acting.as(
              "uday", () -> new MessageStore(main.db).append("shared", "uday", "hello", null));
      var sync =
          new SyncOperations(
              box.db,
              "node-host",
              tempDir,
              () -> NODE,
              target ->
                  PipedSyncChannel.to(
                      SyncRpcServer.over(
                          main.db,
                          "main",
                          null,
                          Actor.sync(ME, Role.MEMBER),
                          () -> SyncServerCommand.roster(main.db),
                          SyncTransitionSink.NONE,
                          SyncWire.UPGRADE_FLOOR)));

      Acting.by(new Actor(ME, Role.MEMBER, Actor.Lane.CLI), () -> sync.sync(new SyncRequest(null)));

      var announcements = landedSoFar();
      assertEquals(
          List.of(Event.WellKnownTypes.SPEC_MESSAGE_POSTED, Event.WellKnownTypes.BOARD_UPDATED),
          announcements.stream().map(Event::type).toList());
      var announced = announcements.getFirst();
      assertEquals("uday", announced.agent());
      assertEquals("shared", announced.spec());
      assertEquals(message.id(), announced.data().get(Event.WellKnownData.MESSAGE_ID));
      assertEquals("hello", announced.data().get("preview"));
      assertTrue(landed.stream().allMatch(event -> MEMBER_ME.equals(event.publisher())));
    }
  }

  @Test
  void mainsSyncServerRelaysANodesFinishedRunAsTheFdeWhoPushedIt() throws Exception {
    serve(MAIN, "admin");
    relayAs("mady");
    seedSpec(box, "auth", "mady");
    try (var node = new SyncBox("mady")) {
      SyncBox.round(box, node);
      var runs = new RunStore(node.db);
      var run = startRunOn(runs, "auth");
      SyncBox.round(box, node);
      forgetLanded();
      Acting.system(() -> runs.transition(run, "running", "stopped", 2));

      SyncBox.round(box, node);

      var relayed = landedSoFar();
      assertEquals(
          List.of(Event.WellKnownTypes.AGENT_SESSION_STOPPED, Event.WellKnownTypes.AGENT_FAILED),
          relayed.stream().map(Event::type).toList());
      var pusher = new Event.Publisher("mady", "member", "api");
      assertTrue(relayed.stream().allMatch(event -> pusher.equals(event.publisher())));
      for (var event : relayed) {
        assertEquals(run, event.data().get(Event.WellKnownData.RUN_ID));
        assertEquals(Event.WellKnownData.SOURCE_SYNC, event.data().get(Event.WellKnownData.SOURCE));
        assertEquals("auth", event.spec());
        assertEquals("acme", event.project());
      }
    }
  }

  @Test
  void aRunANodePushedOnAnotherMembersSpecIsNotRelayedAsThatSpecsStop() throws Exception {
    serve(MAIN, "admin");
    relayAs("mady");
    new FdeStore(box.db).add("bob", null, null, "member");
    seedSpec(box, "theirs", "bob");
    Acting.system(
        () -> box.specs.compareAndSetStatus("theirs", SpecStatus.PENDING, SpecStatus.IN_PROGRESS));
    bus.subscribe(new SpecLifecycleReactor(box.specs));
    try (var node = new SyncBox("mady")) {
      SyncBox.round(box, node);
      var runs = new RunStore(node.db);
      var forged = startRunOn(runs, "theirs");
      SyncBox.round(box, node);
      forgetLanded();
      Acting.system(() -> runs.transition(forged, "running", "stopped", 2));

      SyncBox.round(box, node);

      assertEquals(
          "stopped",
          new RunStore(box.db).findById(forged).orElseThrow().status(),
          "main took the run, so its bridge was handed the transition");
      assertEquals(List.of(), landedSoFar());
      assertEquals(SpecStatus.IN_PROGRESS, box.specs.findById("theirs").orElseThrow().status());
    }
  }

  private void relayAs(String pusher) {
    var fde = new FdeStore(box.db).add(pusher, null, null, "member");
    System.setProperty(
        "SAIL_TOKEN", new TokenStore(box.db).create(pusher, "member", fde.id(), null).token());
    box.transitions(SyncServerCommand.transitionBridge(box.db, "main"));
  }

  private static String startRunOn(RunStore runs, String spec) {
    var run = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs.reserveDispatch(
                run, "acme", spec, "mady", "build", List.of(), "claude-code", "b", "t", "l", "u"));
    return run;
  }

  private void serve(SyncConfig config, String role) throws IOException {
    box = new SyncBox(tempDir, "box");
    new FdeStore(box.db).add(config.handle(), null, null, role);
    var token = new TokenStore(box.db).create(HostToken.NAME, "admin").token();
    yaml = tempDir.resolve("sail.yaml").toString();
    Files.writeString(Path.of(yaml), YAML);
    bus = new EventBus();
    bus.subscribe(recorder());
    serverOperations =
        TestControlPlane.on(
            OperationsFactory.create(
                box.db, shell(), yaml, bus, null, SyncScheduler.disabled(), SessionYield.NONE),
            box.db,
            tempDir,
            config);
    server =
        new SailApiServer(
            "127.0.0.1",
            0,
            serverOperations,
            TestAuth.sessions(box.db, config),
            bus,
            null,
            null,
            null);
    server.start();
    publisher = new SailEventPublisher("127.0.0.1", server.port(), token);
    System.setProperty("SAIL_SERVER", "http://127.0.0.1:" + server.port());
    System.setProperty("SAIL_TOKEN", token);
  }

  private SailOperations cli(ShellExec shell) {
    return TestControlPlane.on(
        DispatchCommand.operations(
            box.db,
            shell,
            yaml,
            this::publish,
            new WatcherSpawner(shell, (command, logPath) -> 4242L),
            (project, config) -> "snap-1",
            command -> 0,
            DispatchOperations.Listener.NONE,
            SessionYield.NONE),
        box.db,
        tempDir,
        NODE);
  }

  private static DispatchOperations.Request request(boolean restart) {
    return new DispatchOperations.Request("auth", "background", false, null, restart);
  }

  private void publish(Event event) {
    try {
      publisher.publish(event);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static ShellExec shell() {
    return DispatchCommandWiringTest.shell();
  }

  private EventSubscriber recorder() {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "recorder";
      }

      @Override
      public Predicate<Event> filter() {
        return EventSubscriber.all();
      }

      @Override
      public void onEvent(Event event) {
        landed.add(event);
        delivered.release();
      }
    };
  }

  /**
   * Every event the bus has taken so far, once the recorder holds each: the bus counts a publish
   * before the door's response returns, and delivers to the recorder on its own thread.
   */
  private List<Event> landedSoFar() throws InterruptedException {
    var published = bus.publishedCount();
    var pending = Math.toIntExact(published - consumed);
    if (!delivered.tryAcquire(pending, BusTesting.DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError("bus did not deliver " + pending + " events in time");
    }
    consumed = published;
    return List.copyOf(landed);
  }

  private void forgetLanded() throws InterruptedException {
    landedSoFar();
    landed.clear();
  }

  private static void seedSpec(SyncBox on, String id, String assignee) {
    Acting.as(
        assignee,
        () -> {
          on.specs.create(
              new SpecStore.SpecRow(
                  id,
                  "acme",
                  id,
                  SpecStatus.PENDING,
                  assignee,
                  null,
                  null,
                  null,
                  null,
                  0,
                  assignee,
                  null,
                  null,
                  assignee,
                  List.of(),
                  List.of()));
          on.specs.setContent(id, "Do " + id, "");
        });
  }
}
