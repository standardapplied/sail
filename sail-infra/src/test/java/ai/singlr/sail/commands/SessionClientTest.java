/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtySessionHost;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class SessionClientTest {

  @TempDir Path dir;

  @Test
  void verbsRoundTripAndErrorsSurfaceAsExceptions() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            (room, project, who) -> {},
            ai.singlr.sail.pty.PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create("s1", List.of("sh", "-c", "read a"), "/tmp", "", "lounge", 80, 24);
        var listed = client.list();
        assertEquals(1, listed.size());
        assertEquals("s1", listed.getFirst().name());
        assertTrue(listed.getFirst().live());
        assertEquals("lounge", listed.getFirst().room(), "the room rides create to list");
        assertEquals(List.of("sh", "-c", "read a"), listed.getFirst().command());

        var dup =
            assertThrows(
                IOException.class,
                () -> client.create("s1", List.of("sh"), "/tmp", "", "", 80, 24));
        assertTrue(dup.getMessage().contains("already running"));

        client.kill("s1");
        assertEquals(0, client.list().size());
      }
    }
  }

  @Test
  void aBoxWithNoFdeIsToldTheFixAsItsReaderTypesIt() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new HostAccess(null, null).identity("", ""),
            (room, project, who) -> {},
            PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      var refused =
          assertThrows(IOException.class, () -> SessionClient.connect(dir.resolve("h.sock")));

      assertEquals(
          List.of(
              "This box has no FDE identity. Set one with 'sudo sail host config set sync-handle"
                  + " <handle>' or connect through the gateway."),
          CliCommand.lines(refused, OperatorCommand.SUDO));
      assertEquals(
          List.of(
              "This box has no FDE identity. Set one with 'sail host config set sync-handle"
                  + " <handle>' or connect through the gateway."),
          CliCommand.lines(refused, OperatorCommand.PLAIN));
    }
  }

  @Test
  void aRefusedRoomKeepsItsRemedyAcrossTheSocket() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new PtyIdentity("uday", false),
            (room, project, who) -> {
              throw Remedied.io(
                  "Room " + room + " is not on this box yet.",
                  Remedy.of("Run '", Remedy.sail("sync"), "'."));
            },
            PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        var refused =
            assertThrows(
                IOException.class,
                () -> client.create("s1", List.of("sh"), "/tmp", "", "lounge", 80, 24));

        assertEquals(
            List.of(
                "Refusing room for session 's1': Room lounge is not on this box yet. Run 'sudo"
                    + " sail sync'."),
            CliCommand.lines(refused, OperatorCommand.SUDO));
        assertEquals(
            List.of(
                "Refusing room for session 's1': Room lounge is not on this box yet. Run 'sail"
                    + " sync'."),
            CliCommand.lines(refused, OperatorCommand.PLAIN));
      }
    }
  }

  @Test
  void aMissingHostExplainsItselfInsteadOfStackTracing() {
    var error =
        assertThrows(IOException.class, () -> SessionClient.connect(dir.resolve("absent.sock")));
    assertTrue(error.getMessage().contains("session host"), error.getMessage());
  }
}
