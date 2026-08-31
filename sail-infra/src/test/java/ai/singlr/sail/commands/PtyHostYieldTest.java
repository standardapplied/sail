/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyRooms;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class PtyHostYieldTest {

  private static final PtyIdentity.Resolver RESOLVER =
      token ->
          switch (token) {
            case "" -> new PtyIdentity("box", false);
            case "tok-mady" -> new PtyIdentity("mady", false);
            default -> throw new IOException("Session token is not valid or has expired.");
          };

  @TempDir Path dir;

  @Test
  void endsOnlyTheNamedLiveSessionsAndAMissingHostHasNothingToEnd() throws Exception {
    assertDoesNotThrow(
        () -> new PtyHostYield(dir.resolve("absent.sock")).end(List.of("resume-1"), "r"),
        "no socket means no host and no sessions");

    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"), dir.resolve("s"), RESOLVER, PtyRooms.NONE, PtyEvents.NONE)) {
      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        client.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
        client.create("shell", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }

      new PtyHostYield(dir.resolve("h.sock"))
          .end(List.of("resume-1", "resume-ghost"), "yielded to dispatch 2");

      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        assertEquals(
            List.of("shell"),
            client.list().stream().map(PtyMessage.SessionInfo::name).toList(),
            "the named live session ended, the unnamed one lives, the absent one is skipped");
      }
      assertEquals(1, host.sessionCount());
    }
  }

  @Test
  void aSessionAnotherFdeOwnsSurfacesTheHostsRefusal() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"), dir.resolve("s"), RESOLVER, PtyRooms.NONE, PtyEvents.NONE)) {
      try (var mady = SessionClient.connect(dir.resolve("h.sock"), "tok-mady")) {
        mady.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      var refused =
          assertThrows(
              IOException.class,
              () -> new PtyHostYield(dir.resolve("h.sock")).end(List.of("resume-1"), "r"));
      assertTrue(refused.getMessage().contains("belongs to mady"), refused.getMessage());
      assertEquals(1, host.sessionCount(), "the box owner cannot end what it does not own");
    }
  }
}
