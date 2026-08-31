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
 * room. Self-cleaning.
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
                  fdeStore)
              .useMessages(new MessageStore(db))
              .useBoxCredentials(boxStore)
              .useRooms(rooms);

      try (var api = new LocalApiSocket(bus, operations, socketDir.resolve("api.sock"));
          var host =
              new PtySessionHost(
                  dir.resolve("h.sock"),
                  dir.resolve("s"),
                  64 * 1024,
                  token -> new PtyIdentity("it", true),
                  new PtyHostEvents(dbPath))) {
        api.start();
        host.start();

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
      }
    } finally {
      deleteContainerQuietly(CONTAINER);
      deleteRecursively(socketDir);
    }
  }

  private String runToExit(String session, String room, String script) throws Exception {
    var socket = dir.resolve("h.sock").toString();
    var args =
        new java.util.ArrayList<>(
            List.of("new", "--socket", socket, session, "--project", CONTAINER));
    if (!room.isEmpty()) {
      args.addAll(List.of("--room", room));
    }
    args.addAll(List.of("--command", "bash", "-lc", script + "; exit 0"));
    assertEquals(0, new CommandLine(new SessionCommand()).execute(args.toArray(String[]::new)));

    try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
      var channel = client.attach(session, true);
      var stdin = new PipedInputStream(new PipedOutputStream());
      var stdout = new ByteArrayOutputStream();
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
