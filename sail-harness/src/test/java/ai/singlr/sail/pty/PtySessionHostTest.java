/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtySessionHostTest {

  @TempDir Path dir;

  private PtySessionHost host;

  private PtySessionHost startHost() throws IOException {
    host = new PtySessionHost(dir.resolve("host.sock"), dir.resolve("sessions"), 64 * 1024);
    host.start();
    return host;
  }

  private SocketChannel connect() throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
    PtyWire.handshake(channel, channel);
    return channel;
  }

  private static PtyMessage awaitText(SocketChannel channel, String marker) throws IOException {
    var seen = new StringBuilder();
    while (true) {
      var message = PtyWire.read(channel);
      if (message instanceof PtyMessage.Output(var seq, var bytes)) {
        seen.append(new String(bytes, StandardCharsets.UTF_8));
        if (seen.toString().contains(marker)) {
          return message;
        }
      }
      if (message instanceof PtyMessage.SessionEnded ended) {
        throw new AssertionError("session ended before '" + marker + "': " + seen);
      }
    }
  }

  @Test
  void createAttachConverseDetachReattachRepays() throws Exception {
    try (var ignored = startHost()) {
      try (var channel = connect()) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "s1", List.of("sh", "-c", "echo hi; read a; echo bye:$a; read b"), "/tmp", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Attach("s1", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
        assertInstanceOf(PtyMessage.ReplayEnd.class, PtyWire.read(channel));
        awaitText(channel, "hi");

        PtyWire.write(channel, new PtyMessage.Input(1, "world\n".getBytes(StandardCharsets.UTF_8)));
        awaitText(channel, "bye:world");
      }

      try (var again = connect()) {
        PtyWire.write(again, new PtyMessage.Attach("s1", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(again));
        assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(again));
        awaitText(again, "bye:world");
      }
    }
  }

  @Test
  void duplicateLiveCreateRefusedAndKillMakesRoomWithFreshHistory() throws Exception {
    try (var ignored = startHost()) {
      try (var channel = connect()) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "dup", List.of("sh", "-c", "echo old-life; read a"), "/tmp", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(channel, new PtyMessage.Create("dup", List.of("sh"), "/tmp", 80, 24));
        assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Kill("dup"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "dup", List.of("sh", "-c", "echo new-life; read a"), "/tmp", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Attach("dup", false));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
        var replayed = new StringBuilder();
        PtyMessage message;
        while (!((message = PtyWire.read(channel)) instanceof PtyMessage.ReplayEnd)) {
          if (message instanceof PtyMessage.Output(var seq, var bytes)) {
            replayed.append(new String(bytes, StandardCharsets.UTF_8));
          }
        }
        assertTrue(
            !replayed.toString().contains("old-life"),
            "a recreated session never replays its predecessor: " + replayed);
      }
    }
  }

  @Test
  void listShowsLivenessAndAttachment() throws Exception {
    try (var ignored = startHost()) {
      try (var channel = connect()) {
        PtyWire.write(
            channel, new PtyMessage.Create("a", List.of("sh", "-c", "read x"), "/tmp", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(channel, new PtyMessage.ListSessions());
        var listed = (PtyMessage.Sessions) PtyWire.read(channel);
        assertEquals(1, listed.sessions().size());
        assertEquals("a", listed.sessions().getFirst().name());
        assertTrue(listed.sessions().getFirst().live());
        assertEquals(0, listed.sessions().getFirst().attached());
      }
    }
  }

  @Test
  void sweepReapsTheUnwantedAndOnlyTheUnwanted() throws Exception {
    try (var ignored = startHost()) {
      try (var channel = connect()) {
        PtyWire.write(
            channel,
            new PtyMessage.Create("lonely", List.of("sh", "-c", "read x"), "/tmp", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(
            channel,
            new PtyMessage.Create("corpse", List.of("sh", "-c", "exit 0"), "/tmp", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        var deadline = System.nanoTime() + 10_000_000_000L;
        PtyWire.write(channel, new PtyMessage.ListSessions());
        var listed = (PtyMessage.Sessions) PtyWire.read(channel);
        while (listed.sessions().stream().anyMatch(s -> s.name().equals("corpse") && s.live())) {
          if (System.nanoTime() > deadline) {
            throw new AssertionError("corpse never exited");
          }
          PtyWire.write(channel, new PtyMessage.ListSessions());
          listed = (PtyMessage.Sessions) PtyWire.read(channel);
        }

        host.sweep(System.nanoTime());
        assertEquals(2, host.sessionCount(), "young sessions survive a sweep");

        var later = System.nanoTime() + PtySessionHost.CORPSE_RETENTION.toNanos() * 2;
        host.sweep(later);
        assertEquals(0, host.sessionCount(), "grace elapsed: both reaped");
      }
    }
  }
}
