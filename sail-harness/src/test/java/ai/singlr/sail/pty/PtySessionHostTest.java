/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class PtySessionHostTest {

  @TempDir Path dir;

  private PtySessionHost host;
  private final java.util.concurrent.ConcurrentLinkedQueue<String> events =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  private static final PtyIdentity.Resolver RESOLVER =
      token ->
          switch (token) {
            case "", "tok-uday" -> new PtyIdentity("uday", false);
            case "tok-mady" -> new PtyIdentity("mady", false);
            case "tok-root" -> new PtyIdentity("root", true);
            default -> throw new IOException("Session token is not valid or has expired.");
          };

  private PtySessionHost startHost() throws IOException {
    host =
        new PtySessionHost(
            dir.resolve("host.sock"),
            dir.resolve("sessions"),
            64 * 1024,
            RESOLVER,
            new PtyEvents() {
              @Override
              public void sessionStarted(String session, String project, String fde) {
                events.add("started:" + session + ":" + fde);
              }

              @Override
              public void sessionAttached(String session, String project, String fde) {
                events.add("attached:" + session + ":" + fde);
              }

              @Override
              public void sessionEnded(String session, String project, String reason) {
                events.add("ended:" + session);
              }
            });
    host.start();
    return host;
  }

  private SocketChannel connect() throws IOException {
    return connect("tok-uday");
  }

  private SocketChannel connect(String token) throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(token));
    var reply = PtyWire.read(channel);
    if (reply instanceof PtyMessage.Err(var message)) {
      throw new IOException(message);
    }
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
  void identityComesFirstAndAdmissionIsOwnerOrAdmin() throws Exception {
    try (var ignored = startHost()) {
      var rude = SocketChannel.open(StandardProtocolFamily.UNIX);
      rude.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
      PtyWire.handshake(rude, rude);
      PtyWire.write(rude, new PtyMessage.ListSessions());
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(rude), "hello must come first");
      rude.close();

      assertThrows(IOException.class, () -> connect("tok-forged"), "unknown tokens are refused");

      try (var owner = connect("tok-uday")) {
        PtyWire.write(
            owner,
            new PtyMessage.Create("mine", List.of("sh", "-c", "read a"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
        assertTrue(events.contains("started:mine:uday"), events.toString());
      }

      try (var foreign = connect("tok-mady")) {
        PtyWire.write(foreign, new PtyMessage.Attach("mine", false));
        assertInstanceOf(
            PtyMessage.Err.class, PtyWire.read(foreign), "a foreign member cannot observe");
        PtyWire.write(foreign, new PtyMessage.Kill("mine"));
        assertInstanceOf(PtyMessage.Err.class, PtyWire.read(foreign), "nor kill");
      }

      try (var admin = connect("tok-root")) {
        PtyWire.write(admin, new PtyMessage.Attach("mine", false));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(admin), "an admin observes anywhere");
        PtyWire.write(admin, new PtyMessage.TakeWrite());
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (!events.contains("attached:mine:root") && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertTrue(events.contains("attached:mine:root"), events.toString());

        try (var owner = connect("tok-uday")) {
          PtyWire.write(owner, new PtyMessage.ListSessions());
          var listed = (PtyMessage.Sessions) PtyWire.read(owner);
          assertEquals(
              "root", listed.sessions().getFirst().writerFde(), "the token names its holder");
        }
      }

      try (var owner = connect("tok-uday")) {
        PtyWire.write(owner, new PtyMessage.ListSessions());
        var listed = (PtyMessage.Sessions) PtyWire.read(owner);
        var freed = System.nanoTime() + 5_000_000_000L;
        while (!listed.sessions().getFirst().writerFde().isEmpty() && System.nanoTime() < freed) {
          Thread.onSpinWait();
          PtyWire.write(owner, new PtyMessage.ListSessions());
          listed = (PtyMessage.Sessions) PtyWire.read(owner);
        }
        assertEquals(
            "", listed.sessions().getFirst().writerFde(), "a departed holder releases the token");
        PtyWire.write(owner, new PtyMessage.Kill("mine"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner), "the owner always may");
      }
    }
  }

  private static PtyMessage readControl(SocketChannel channel) throws IOException {
    while (true) {
      var m = PtyWire.read(channel);
      if (m instanceof PtyMessage.Ok
          || m instanceof PtyMessage.Err
          || m instanceof PtyMessage.Sessions
          || m instanceof PtyMessage.SessionEnded) {
        return m;
      }
    }
  }

  private static PtyMessage.Sessions awaitCorpse(SocketChannel channel, String name)
      throws IOException {
    var deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      PtyWire.write(channel, new PtyMessage.ListSessions());
      var listed = (PtyMessage.Sessions) readControl(channel);
      if (listed.sessions().stream().anyMatch(s -> s.name().equals(name) && !s.live())) {
        return listed;
      }
    }
    throw new AssertionError("session '" + name + "' never became a corpse");
  }

  @Test
  void aNonWritersInputIsRefusedWithoutKillingTheConnection() throws Exception {
    try (var ignored = startHost()) {
      try (var owner = connect("tok-uday")) {
        PtyWire.write(
            owner,
            new PtyMessage.Create(
                "shared", List.of("sh", "-c", "read a; read b"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(owner));
      }
      try (var observer = connect("tok-uday")) {
        PtyWire.write(observer, new PtyMessage.Attach("shared", false));
        assertInstanceOf(PtyMessage.Ok.class, readControl(observer));

        PtyWire.write(observer, new PtyMessage.Input(1, "nope\n".getBytes(StandardCharsets.UTF_8)));
        assertInstanceOf(
            PtyMessage.Err.class, readControl(observer), "a non-writer's input is refused");

        PtyWire.write(observer, new PtyMessage.ListSessions());
        assertInstanceOf(
            PtyMessage.Sessions.class,
            readControl(observer),
            "the connection survives the refusal and still answers");
      }
    }
  }

  @Test
  void listSessionsShowsOnlyYourOwnUnlessAdmin() throws Exception {
    try (var ignored = startHost()) {
      try (var uday = connect("tok-uday")) {
        PtyWire.write(
            uday,
            new PtyMessage.Create("udays", List.of("sh", "-c", "read a"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(uday));
      }
      try (var mady = connect("tok-mady")) {
        PtyWire.write(
            mady,
            new PtyMessage.Create("madys", List.of("sh", "-c", "read a"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(mady));

        PtyWire.write(mady, new PtyMessage.ListSessions());
        var listed = (PtyMessage.Sessions) readControl(mady);
        assertEquals(
            List.of("madys"),
            listed.sessions().stream().map(PtyMessage.SessionInfo::name).toList(),
            "a member sees only their own sessions, never another owner's");
      }
      try (var admin = connect("tok-root")) {
        PtyWire.write(admin, new PtyMessage.ListSessions());
        var listed = (PtyMessage.Sessions) readControl(admin);
        assertEquals(2, listed.sessions().size(), "an admin sees every owner's sessions");
      }
    }
  }

  @Test
  void aForeignMemberCannotEvictAnothersCorpseByReusingItsName() throws Exception {
    try (var ignored = startHost()) {
      try (var uday = connect("tok-uday")) {
        PtyWire.write(
            uday,
            new PtyMessage.Create("keep", List.of("sh", "-c", "exit 0"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(uday));
        awaitCorpse(uday, "keep");
      }
      try (var mady = connect("tok-mady")) {
        PtyWire.write(mady, new PtyMessage.Create("keep", List.of("sh"), "/tmp", "acme", 80, 24));
        assertInstanceOf(
            PtyMessage.Err.class,
            readControl(mady),
            "a foreign member cannot reuse an owner's name, even a corpse");
      }
      try (var uday = connect("tok-uday")) {
        PtyWire.write(uday, new PtyMessage.ListSessions());
        var listed = (PtyMessage.Sessions) readControl(uday);
        assertEquals(1, listed.sessions().size(), "the owner's corpse is left intact");
        assertEquals("keep", listed.sessions().getFirst().name());
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
                "s1",
                List.of("sh", "-c", "echo hi; read a; echo bye:$a; read b"),
                "/tmp",
                "acme",
                80,
                24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Attach("s1", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
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
                "dup", List.of("sh", "-c", "echo old-life; read a"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(channel, new PtyMessage.Create("dup", List.of("sh"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Kill("dup"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "dup", List.of("sh", "-c", "echo new-life; read a"), "/tmp", "acme", 80, 24));
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
            channel,
            new PtyMessage.Create("a", List.of("sh", "-c", "read x"), "/tmp", "acme", 80, 24));
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
            new PtyMessage.Create("lonely", List.of("sh", "-c", "read x"), "/tmp", "acme", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(
            channel,
            new PtyMessage.Create("corpse", List.of("sh", "-c", "exit 0"), "/tmp", "acme", 80, 24));
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
