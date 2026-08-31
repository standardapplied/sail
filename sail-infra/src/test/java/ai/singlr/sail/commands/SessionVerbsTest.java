/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@EnabledOnOs(OS.LINUX)
class SessionVerbsTest {

  private static final ai.singlr.sail.pty.PtyRooms ROOMS =
      (room, project, who) -> {
        if (!room.equals("design-talk")) {
          throw new java.io.IOException("Room '" + room + "' was not found.");
        }
      };

  @TempDir Path dir;

  private int run(String... args) {
    return new CommandLine(new SessionCommand()).execute(args);
  }

  @Test
  void newLsKillRoundTripThroughTheRealHost() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ROOMS,
            ai.singlr.sail.pty.PtyEvents.NONE)) {
      var socket = dir.resolve("h.sock").toString();

      assertEquals(0, run("new", "--socket", socket, "t1", "--command", "sh", "-c", "read a"));
      assertEquals(1, host.sessionCount());

      assertEquals(0, run("ls", "--socket", socket));

      assertEquals(0, run("kill", "t1", "--socket", socket));
      assertEquals(0, host.sessionCount());

      assertEquals(0, run("ls", "--socket", socket), "an empty listing is not an error");
    }
  }

  @Test
  void newPinsTheSessionToARoomAndPassesTheWholeCommandThrough() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ROOMS,
            ai.singlr.sail.pty.PtyEvents.NONE)) {
      var socket = dir.resolve("h.sock").toString();

      assertEquals(
          0,
          run(
              "new",
              "--socket",
              socket,
              "brainstorm",
              "--room",
              "design-talk",
              "--command",
              "sh",
              "-c",
              "echo room=$SAIL_ROOM_ID > " + dir.resolve("seen") + "; read a"));
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        var listed = client.list().getFirst();
        assertEquals("design-talk", listed.room());
        assertEquals("sh", listed.command().getFirst());
        assertEquals(3, listed.command().size(), "dash-prefixed argv words belong to --command");
      }
      var deadline = System.nanoTime() + 5_000_000_000L;
      while (!java.nio.file.Files.exists(dir.resolve("seen")) && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(
          "room=design-talk\n",
          java.nio.file.Files.readString(dir.resolve("seen")),
          "the child inherits SAIL_ROOM_ID");

      var refused =
          run("new", "--socket", socket, "bad", "--room", "Not A Room", "--command", "sh");
      assertNotEquals(0, refused, "a malformed room id is refused by the host");
      assertEquals(1, host.sessionCount());
    }
  }

  @Test
  void attachToAMissingSessionFailsBeforeTouchingTheTerminal() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ROOMS,
            ai.singlr.sail.pty.PtyEvents.NONE)) {
      var exit = run("attach", "ghost", "--socket", dir.resolve("h.sock").toString());
      assertNotEquals(0, exit, "a missing session is a loud failure, not a hung raw terminal");
      assertTrue(host.sessionCount() == 0);
    }
  }

  @Test
  void verbsExplainAMissingHostInsteadOfStackTracing() {
    var exit = run("ls", "--socket", dir.resolve("absent.sock").toString());
    assertNotEquals(0, exit);
  }
}
