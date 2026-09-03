/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyRooms;
import ai.singlr.sail.pty.PtySessionHost;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the two lifecycle facts a terminal client builds its ended-versus-lost verdict on, against a
 * real container child: a clean {@code exit} reaches an attached client as {@code SessionEnded}
 * before the channel goes quiet (never as a bare EOF the client would misread as a link drop), and
 * a host restart is detectable by the boot id every connection is welcomed with — the sessions the
 * previous run held are gone from the listing and the id has changed. Self-cleaning.
 */
class PtyLifecycleIT extends AbstractIncusIT {

  @TempDir Path dir;

  private PtySessionHost startHost() throws Exception {
    var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new PtyIdentity("uday", true),
            PtyRooms.NONE,
            PtyEvents.NONE);
    host.start();
    return host;
  }

  private static PtyMessage.SessionInfo listed(SessionClient client, String name) throws Exception {
    return client.list().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
  }

  private void prepareDevWorkspace(String container) throws Exception {
    var prepared =
        exec(
            container,
            List.of("bash", "-c", "mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev"));
    assertTrue(prepared.ok(), "could not prepare the dev workspace: " + prepared.stderr());
  }

  @Test
  void aCleanExitInsideTheContainerReachesAnAttachedClientAsSessionEndedNotEof() throws Exception {
    ensureIncusOrSkip();
    var container = "sail-it-pty-exit";
    try {
      launch(container);
      prepareDevWorkspace(container);

      try (var host = startHost();
          var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create("c1", List.of("sh", "-c", "read a; exit 3"), "/tmp", container, "", 80, 24);
        var born = listed(client, "c1");
        assertTrue(born.live());
        assertFalse(born.instanceId().isBlank(), "every incarnation is minted an id at create");
        var channel = client.attach("c1", true);

        var stdinFeed = new PipedOutputStream();
        var stdin = new PipedInputStream(stdinFeed);
        stdinFeed.write("go\n".getBytes(StandardCharsets.UTF_8));
        stdinFeed.flush();

        var reason = AttachLoop.run(channel, stdin, new ByteArrayOutputStream());

        assertEquals(
            "exited(3)",
            reason,
            "the ending frame carries the child's exit status; a bare EOF would read as"
                + " 'connection closed' and the client would retry a session that is gone");
        var corpse = listed(client, "c1");
        assertFalse(
            corpse.live(),
            "the corpse stays listed as ended so a later listing explains the absence");
        assertEquals(
            born.instanceId(),
            corpse.instanceId(),
            "the corpse is the same incarnation the client watched live — a client keyed on the"
                + " id can tell it from a replacement that dies before anyone sees it run");
      }
    } finally {
      deleteContainerQuietly(container);
    }
  }

  @Test
  void aHostRestartChangesTheBootIdAndDropsTheSessionsThePreviousRunHeld() throws Exception {
    ensureIncusOrSkip();
    var container = "sail-it-pty-reboot";
    try {
      launch(container);
      prepareDevWorkspace(container);

      String firstBoot;
      try (var host = startHost();
          var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create("c1", List.of("sh", "-c", "read a"), "/tmp", container, "", 80, 24);
        firstBoot = client.hostBootId();
        assertEquals(host.bootId(), firstBoot);
        assertTrue(client.list().stream().anyMatch(s -> s.name().equals("c1") && s.live()));
      }

      try (var restarted = startHost();
          var client = SessionClient.connect(dir.resolve("h.sock"))) {
        assertNotEquals(firstBoot, client.hostBootId(), "a restart is a new boot id");
        assertEquals(restarted.bootId(), client.hostBootId());
        assertTrue(
            client.list().stream().noneMatch(s -> s.name().equals("c1")),
            "the previous run's session is absent — together with the changed id, that is"
                + " 'host restarted', not 'it died'");
      }
    } finally {
      deleteContainerQuietly(container);
    }
  }
}
