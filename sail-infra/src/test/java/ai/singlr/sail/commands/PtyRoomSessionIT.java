/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.LocalApiSocket;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.BoxCredentialFile;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The keystone of room-bound sessions, end to end through both real socket lanes: {@code sail
 * session new --room X --project P --command ...} opens a terminal inside a real container, the
 * child inherits {@code SAIL_ROOM_ID}, and the installed {@code spec} helper — talking to the
 * production local API over the bind-mounted socket with the box's ambient credential — creates
 * specs that are born in room X, mint no identity room, and honor an explicit {@code --room}. A
 * session opened without a room behaves exactly as before. The pty host's own event rows carry the
 * room, and every session — CLI-token or session-token identified, room-bound or not — leaves all
 * three lifecycle facts (started, attached, ended) in the event store, from which the bridge
 * republishes them onto the live bus. Self-cleaning.
 */
class PtyRoomSessionIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-pty-room";
  private static final Path CONTAINER_DIR = Path.of("/var/lib/sail/run");

  @TempDir Path dir;

  @Test
  void specsCreatedInsideARoomBoundSessionAreBornInThatRoom() throws Exception {
    ensureIncusOrSkip();

    var socketDir = Files.createTempDirectory("sail-it-pty-room-socket");
    Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    var dbPath = dir.resolve("sail.db");

    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      var rooms = new RoomStore(db);
      rooms.create(room("design-talk", "Design talk"));
      rooms.create(room("other-talk", "Other talk"));
      var fdeStore = new FdeStore(db);
      fdeStore.add("it", "IT", "it@example.dev", "admin");
      fdeStore.add("mady", "Mady", "mady@example.dev", "admin");
      var boxStore = new BoxCredentialStore(db);
      BoxCredentialFile.ensure(boxStore, "it", socketDir);
      var bus = new EventBus();
      var operations =
          new SailOperations(
                  new ShellExecutor(false),
                  "sail.yaml",
                  bus,
                  null,
                  specStore,
                  new ReviewStore(db),
                  new RunStore(db),
                  null,
                  SyncScheduler.disabled(),
                  fdeStore,
                  SessionYield.NONE)
              .useMessages(new MessageStore(db))
              .useBoxCredentials(boxStore)
              .useRooms(rooms);

      try (var api = new LocalApiSocket(bus, operations, socketDir.resolve("api.sock"));
          var host =
              new PtySessionHost(
                  dir.resolve("h.sock"),
                  dir.resolve("s"),
                  64 * 1024,
                  token -> new PtyIdentity(token.isBlank() ? "it" : "mady", true),
                  new PtyHostRooms(dbPath),
                  new PtyHostEvents(dbPath, dir.resolve("pty-events.drops")))) {
        api.start();
        host.start();

        var live = new java.util.concurrent.ConcurrentLinkedQueue<ai.singlr.sail.api.Event>();
        bus.subscribe(
            new ai.singlr.sail.api.EventSubscriber() {
              @Override
              public String name() {
                return "it-live-collector";
              }

              @Override
              public java.util.function.Predicate<ai.singlr.sail.api.Event> filter() {
                return event ->
                    ai.singlr.sail.api.Event.WellKnownTypes.ptySessionFact(event.type());
              }

              @Override
              public void onEvent(ai.singlr.sail.api.Event event) {
                live.add(event);
              }
            });
        var bridge = new ai.singlr.sail.api.PtyEventBridge(new EventStore(db), bus);

        launchPrepared(CONTAINER);
        var dev =
            exec(
                CONTAINER,
                List.of(
                    "bash",
                    "-c",
                    "userdel -r ubuntu 2>/dev/null || true;"
                        + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                        + " mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev"));
        assertTrue(dev.ok(), "the dev user must exist: " + dev.stderr());
        Files.createDirectories(SailPaths.apiSocketHostDir());
        ContainerSailSetup.ensureInstalled(shell, CONTAINER);
        new IncusDeviceManager(shell).ensureEventSocket(CONTAINER, socketDir, CONTAINER_DIR);

        var bound =
            runToExit(
                "brainstorm",
                "design-talk",
                "spec create --id born-here --title 'Born here'"
                    + " && spec create --id moved --title Moved --room other-talk"
                    + " && echo room-was=$SAIL_ROOM_ID");
        assertTrue(bound.contains("room-was=design-talk"), "the child saw its room: " + bound);

        var solo = runToExit("plain", "", "spec create --id solo --title Solo && echo done");
        assertTrue(solo.contains("done"), solo);

        var mast =
            runToExitAs(
                "mast-session-token",
                "mast-term",
                "design-talk",
                "spec create --id mast-made --title 'Mast made' && echo mast-was=$SAIL_ROOM_ID");
        assertTrue(
            mast.contains("mast-was=design-talk"),
            "the token-identified session sees its room too: " + mast);
        assertEquals(
            "design-talk",
            specStore.findById("mast-made").orElseThrow().roomIdOrIdentity(),
            "a token-identified room-bound session births specs in its room too");

        assertEquals(
            "design-talk",
            specStore.findById("born-here").orElseThrow().roomIdOrIdentity(),
            "a spec created in a room-bound session is born in that room");
        assertTrue(
            rooms.findById("born-here").isEmpty(),
            "a spec with a home room mints no room of its own");
        assertEquals(
            "other-talk",
            specStore.findById("moved").orElseThrow().roomIdOrIdentity(),
            "an explicit --room overrides the session's room");
        assertEquals(
            "solo",
            specStore.findById("solo").orElseThrow().roomIdOrIdentity(),
            "outside any room-bound session a spec keeps its identity room");
        assertTrue(rooms.findById("solo").isPresent(), "and that identity room is minted");

        var started =
            new EventStore(db)
                .recent(50).stream()
                    .filter(e -> e.type().equals("pty_session_started"))
                    .map(e -> YamlUtil.parseMap(e.data()))
                    .toList();
        assertTrue(
            started.stream()
                .anyMatch(
                    d ->
                        "brainstorm".equals(d.get("session"))
                            && "design-talk".equals(d.get("room_id"))),
            "the session's own event names the room: " + started);
        assertTrue(
            started.stream()
                .anyMatch(d -> "plain".equals(d.get("session")) && !d.containsKey("room_id")),
            "an unbound session's event names none: " + started);

        var eventStore = new EventStore(db);
        for (var expected :
            List.of(
                new String[] {"brainstorm", "it", "design-talk"},
                new String[] {"plain", "it", null},
                new String[] {"mast-term", "mady", "design-talk"})) {
          var session = expected[0];
          var owner = expected[1];
          var room = expected[2];
          awaitFact(eventStore, "pty_session_started", session, owner, room);
          awaitFact(eventStore, "pty_session_attached", session, owner, room);
          awaitFact(eventStore, "pty_session_ended", session, "sail", room);
        }

        bridge.publishNewRows();
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (live.size() < 9 && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertEquals(
            9,
            live.size(),
            "all nine lifecycle facts must reach the live event lane: "
                + live.stream().map(e -> e.type() + ":" + e.data().get("session")).toList());
        bridge.close();
      }
    } finally {
      deleteContainerQuietly(CONTAINER);
      deleteRecursively(socketDir);
    }
  }

  private static void awaitFact(
      EventStore events, String type, String session, String agent, String room)
      throws InterruptedException {
    var deadline = System.nanoTime() + 10_000_000_000L;
    while (!hasFact(events.recent(200), type, session, agent, room)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            type + " for session '" + session + "' (agent " + agent + ") never reached the store");
      }
      Thread.sleep(20);
    }
  }

  private static boolean hasFact(
      List<EventStore.EventRow> rows, String type, String session, String agent, String room) {
    return rows.stream()
        .anyMatch(
            row ->
                row.type().equals(type)
                    && row.agent().equals(agent)
                    && java.util.Objects.equals(row.specId(), room)
                    && session.equals(YamlUtil.parseMap(row.data()).get("session")));
  }

  private String runToExitAs(String token, String session, String room, String script)
      throws Exception {
    try (var client = SessionClient.connect(dir.resolve("h.sock"), token)) {
      client.create(
          session,
          List.of("bash", "-lc", "read _; " + script + "; exit 0"),
          System.getProperty("user.home", "/home/dev"),
          CONTAINER,
          room,
          80,
          24);
    }
    try (var client = SessionClient.connect(dir.resolve("h.sock"), token)) {
      var channel = client.attach(session, true);
      var toChild = new PipedOutputStream();
      var stdin = new PipedInputStream(toChild);
      var stdout = new ByteArrayOutputStream();
      toChild.write('\n');
      toChild.flush();
      var reason = AttachLoop.run(channel, stdin, stdout);
      var rendered = stdout.toString(StandardCharsets.UTF_8);
      assertEquals("exited(0)", reason, "the session's script must succeed: " + rendered);
      return rendered;
    }
  }

  /** The child gates on attach ({@code read _}) so it can never exit before we connect. */
  private String runToExit(String session, String room, String script) throws Exception {
    var socket = dir.resolve("h.sock").toString();
    var args =
        new java.util.ArrayList<>(
            List.of("new", "--socket", socket, session, "--project", CONTAINER));
    if (!room.isEmpty()) {
      args.addAll(List.of("--room", room));
    }
    args.addAll(List.of("--command", "bash", "-lc", "read _; " + script + "; exit 0"));
    assertEquals(0, new CommandLine(new SessionCommand()).execute(args.toArray(String[]::new)));

    try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
      var channel = client.attach(session, true);
      var toChild = new PipedOutputStream();
      var stdin = new PipedInputStream(toChild);
      var stdout = new ByteArrayOutputStream();
      toChild.write('\n');
      toChild.flush();
      var reason = AttachLoop.run(channel, stdin, stdout);
      var rendered = stdout.toString(StandardCharsets.UTF_8);
      assertEquals("exited(0)", reason, "the session's script must succeed: " + rendered);
      return rendered;
    }
  }

  private static RoomStore.RoomRow room(String id, String title) {
    return new RoomStore.RoomRow(id, CONTAINER, title, "it", "on", null, "it", null, null, "it");
  }
}
