/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.RunReservation;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Brick 3 end to end: a completed run with a recorded session resumes inside a host-owned,
 * room-bound session running the resume argv in a real container; the conversation survives a
 * detach, a second attach joins it (history replayed, still live) rather than forking; and a
 * dispatch reserving the project ends it — attached or detached — with the reason in the stream and
 * on the room's {@code pty_session_ended} event. The container gets a stand-in {@code claude} that
 * echoes its arguments and its input, so the argv and the liveness are both observable.
 */
class AgentResumeSessionIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-agent-resume";
  private static final String ROOM = "resume-talk";
  private static final SailYaml CONFIG =
      SailYaml.fromMap(
          Map.of(
              "name",
              CONTAINER,
              "ssh",
              Map.of("user", "dev"),
              "repos",
              List.of(Map.of("url", "https://example.com/app.git", "path", "app"))));

  @TempDir Path dir;

  @Test
  void aResumedConversationLivesInTheHostSurvivesDetachAndYieldsToDispatch() throws Exception {
    ensureIncusOrSkip();
    var dbPath = dir.resolve("sail.db");
    var socket = dir.resolve("h.sock");

    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      var rooms = new RoomStore(db);
      rooms.create(
          new RoomStore.RoomRow(
              ROOM, CONTAINER, "Resume talk", "it", "on", null, "it", null, null, "it"));
      new FdeStore(db).add("it", "IT", "it@example.dev", "admin");
      var runStore = new RunStore(db);
      var runId = completedRun(runStore, "sess-abc");
      var run = runStore.findById(runId).orElseThrow();
      var hostYield = new PtyHostYield(socket, dir.resolve("locks"));
      var reservation =
          new RunReservation(runStore, shell, DispatchOperations.Listener.NONE, hostYield);

      try (var host =
          new PtySessionHost(
              socket,
              dir.resolve("s"),
              64 * 1024,
              token -> new PtyIdentity("it", true),
              new PtyHostRooms(dbPath),
              new PtyHostEvents(dbPath, dir.resolve("pty-events.drops")),
              "0.0.0-test")) {
        host.start();
        launchPrepared(CONTAINER);
        installDevUserAndStandInClaude();

        var plan =
            new AgentAttachCommand.ResumePlan(
                SessionYield.resumeSession(runId),
                AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, "sess-abc"),
                CONTAINER,
                AgentAttachCommand.knownRoom(rooms, run));
        assertEquals(ROOM, plan.room(), "the run's room binds the session");

        try (var client = SessionClient.connect(socket)) {
          assertTrue(AgentAttachCommand.openOrJoin(client, plan, 80, 24), "the first attach opens");
          var terminal = Terminal.attach(client, plan.session());
          terminal.await("resumed=--resume sess-abc");
          terminal.type("hello\n");
          terminal.await("got:hello");
          terminal.detach();
          assertNull(terminal.ending(), "a detach leaves the session running");
        }

        try (var client = SessionClient.connect(socket)) {
          assertFalse(AgentAttachCommand.openOrJoin(client, plan, 80, 24), "a second attach joins");
          var terminal = Terminal.attach(client, plan.session());
          terminal.await("got:hello");
          terminal.type("again\n");
          terminal.await("got:again");

          var dispatched = reserve(reservation, runStore);
          var reason = "yielded to dispatch " + dispatched + " of spec " + ROOM;
          terminal.await("[sail: session ended — " + reason + "]");
          assertEquals(reason, terminal.ending(), "the attached client hears why it ended");
        }
        assertEquals(0, host.sessionCount());
        assertTrue(
            endedEvents(db).stream()
                .anyMatch(
                    d ->
                        plan.session().equals(d.get("session"))
                            && ROOM.equals(d.get("room_id"))
                            && String.valueOf(d.get("reason")).startsWith("yielded to dispatch")),
            "the room's ending event names the yield: " + endedEvents(db));

        try (var client = SessionClient.connect(socket)) {
          assertTrue(
              AgentAttachCommand.openOrJoin(client, plan, 80, 24), "reopened after the yield");
        }
        reserve(reservation, runStore);
        try (var client = SessionClient.connect(socket)) {
          assertTrue(
              client.list().stream().noneMatch(info -> info.live()),
              "a detached-but-live conversation yields identically");
        }
      }
    } finally {
      deleteContainerQuietly(CONTAINER);
    }
  }

  private static String completedRun(RunStore runStore, String sessionId) {
    var id = DateTimeUtils.newId().toString();
    runStore.reserveDispatch(
        id,
        CONTAINER,
        ROOM,
        "it",
        "it",
        "build",
        List.of(),
        "claude-code",
        null,
        "task",
        "log",
        "u");
    runStore.recordSession(id, sessionId, "hook", null);
    runStore.transition(id, "running", "completed", 0);
    return id;
  }

  /** Reserves a whole-container build and completes it at once, so the next claim is free. */
  private static String reserve(RunReservation reservation, RunStore runStore) {
    var id = DateTimeUtils.newId().toString();
    reservation.reserve(
        id,
        CONTAINER,
        ROOM,
        "it",
        "it",
        "build",
        List.of(),
        "claude-code",
        null,
        "task",
        AgentUnit.forRun(id),
        CONFIG);
    runStore.transition(id, "running", "completed", 0);
    return id;
  }

  private void installDevUserAndStandInClaude() throws Exception {
    var script =
        """
        userdel -r ubuntu 2>/dev/null || true
        id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev
        mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev
        cat > /usr/local/bin/claude <<'EOF'
        #!/bin/sh
        echo "resumed=$*"
        while read line; do echo "got:$line"; done
        EOF
        chmod +x /usr/local/bin/claude
        """;
    var result = exec(CONTAINER, List.of("bash", "-c", script));
    assertTrue(result.ok(), "the dev user and stand-in claude must install: " + result.stderr());
  }

  private static List<Map<String, Object>> endedEvents(Sqlite db) {
    return new EventStore(db)
        .recent(50).stream()
            .filter(e -> e.type().equals("pty_session_ended"))
            .map(e -> YamlUtil.parseMap(e.data()))
            .toList();
  }

  /** An attach loop driven from the test: typed input in, rendered output polled, ending kept. */
  private static final class Terminal {
    private final PipedOutputStream keys = new PipedOutputStream();
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
    private final AtomicReference<String> ending = new AtomicReference<>();
    private final Thread loop;

    private Terminal(SessionClient client, String session) throws Exception {
      var channel = client.attach(session, true);
      var stdin = new PipedInputStream(keys);
      loop =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      ending.set(AttachLoop.run(channel, stdin, screen));
                    } catch (Exception e) {
                      ending.set("attach loop failed: " + e);
                    }
                  });
    }

    static Terminal attach(SessionClient client, String session) throws Exception {
      return new Terminal(client, session);
    }

    void type(String text) throws Exception {
      keys.write(text.getBytes(StandardCharsets.UTF_8));
      keys.flush();
    }

    void detach() throws Exception {
      keys.write(AttachLoop.DETACH_KEY);
      keys.flush();
    }

    void await(String marker) throws Exception {
      var deadline = System.nanoTime() + 30_000_000_000L;
      while (!rendered().contains(marker)) {
        if (System.nanoTime() > deadline || !loop.isAlive()) {
          throw new AssertionError("never saw '" + marker + "' in: " + rendered());
        }
        Thread.sleep(50);
      }
    }

    String ending() throws Exception {
      loop.join(30_000);
      return ending.get();
    }

    private String rendered() {
      return screen.toString(StandardCharsets.UTF_8);
    }
  }
}
