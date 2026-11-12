/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ListenConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The TCP door on its own: connections that present no ticket share a pool whose oldest is let go
 * for a newcomer, IPv6 peers count by their /64, a listener lost after binding is bound again at
 * the next tick and said so where host status reads it, an accept that fails while the listener
 * stays bound ends nothing, and refused connections are said once a tick.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PtyTcpDoorTest {

  private static final InetSocketAddress LOOPBACK =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

  @TempDir Path dir;
  private final AtomicLong now = new AtomicLong(1_000_000_000L);
  private final List<SocketChannel> opened = new ArrayList<>();
  private PtyTcpDoor door;

  @BeforeEach
  void openTheDoor() {
    door =
        new PtyTcpDoor(
            LOOPBACK,
            8,
            now::get,
            new PtyTickets(8),
            PtyIdentity.Holders.NONE,
            dir.resolve("pty.listening"),
            (channel, admission) -> {});
    door.tick(now.get());
  }

  @AfterEach
  void closeTheDoor() throws IOException {
    door.close();
    for (var channel : opened) {
      channel.close();
    }
  }

  @Test
  void whenThePoolOfConnectionsWithNoTicketIsFullItsOldestIsLetGo() throws IOException {
    var first = served();
    var second = served();

    var third = served();

    assertEquals(-1, first.read(ByteBuffer.allocate(1)), "the oldest is let go");
    assertTrue(second.isOpen() && third.isOpen());
  }

  @Test
  void aConnectionWithNoTicketIsLetGoOnlyForOneThatConnectedAfterIt() throws IOException {
    try (var narrow =
        new PtyTcpDoor(
            LOOPBACK,
            4,
            now::get,
            new PtyTickets(8),
            PtyIdentity.Holders.NONE,
            dir.resolve("narrow.listening"),
            (channel, admission) -> {})) {
      narrow.tick(now.get());
      var address = narrow.address().orElseThrow();
      for (var round = 0; round < 500; round++) {
        SocketChannel.open(address).close();
        try (var live = handshaken(address)) {
          PtyWire.write(live, new PtyMessage.Hello("tkt_" + "0".repeat(64)));

          assertInstanceOf(
              PtyMessage.Err.class,
              PtyWire.read(live),
              "round " + round + ": the one before it left first and is the one let go");
        }
      }
    }
  }

  @Test
  void aConnectionTheDoorEndsHasGivenBackItsSlotBeforeItsClientSeesItEnd() throws IOException {
    try (var single =
        new PtyTcpDoor(
            LOOPBACK,
            1,
            now::get,
            new PtyTickets(8),
            PtyIdentity.Holders.NONE,
            dir.resolve("single.listening"),
            (channel, admission) -> {})) {
      single.tick(now.get());
      var address = single.address().orElseThrow();
      for (var round = 0; round < 200; round++) {
        try (var live = SocketChannel.open(address)) {
          PtyWire.handshake(live, live);
          PtyWire.write(live, new PtyMessage.Hello("tkt_" + "0".repeat(64)));

          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(live), "round " + round);
          assertEquals(-1, live.read(ByteBuffer.allocate(1)), "round " + round + ": it ended");
        }
      }
    }
  }

  @Test
  void aConnectionTurnedAwayAtTheCapGivesBackTheSlotItTook() throws IOException {
    try (var single =
        new PtyTcpDoor(
            LOOPBACK,
            1,
            now::get,
            new PtyTickets(8),
            PtyIdentity.Holders.NONE,
            dir.resolve("single.listening"),
            (channel, admission) -> {})) {
      single.tick(now.get());
      var address = single.address().orElseThrow();
      var held = handshaken(address);
      for (var i = 0; i < 20; i++) {
        try (var over = SocketChannel.open(address)) {
          assertEquals(-1, over.read(ByteBuffer.allocate(8)), "past the cap: closed unread");
        }
      }

      held.close();

      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> handshaken(address).close(),
          "the slot is free again once the one that held it is gone");
    }
  }

  @Test
  void anIpv6PeerCountsByItsSlash64AndAnIpv4PeerByItsAddress() throws IOException {
    assertEquals(
        PtyTcpDoor.peerOf(InetAddress.ofLiteral("fd7a:115c:a1e0:1::7")),
        PtyTcpDoor.peerOf(InetAddress.ofLiteral("fd7a:115c:a1e0:1:ffff::9")));
    assertFalse(
        PtyTcpDoor.peerOf(InetAddress.ofLiteral("fd7a:115c:a1e0:1::7"))
            .equals(PtyTcpDoor.peerOf(InetAddress.ofLiteral("fd7a:115c:a1e0:2::7"))));
    assertEquals("10.0.0.7", PtyTcpDoor.peerOf(InetAddress.ofLiteral("10.0.0.7")));
  }

  @Test
  void aListenerLostAfterBindingIsNotListeningUntilTheNextTickBindsItAgain() throws IOException {
    var listening = dir.resolve("pty.listening");
    assertEquals(
        Optional.of(ListenConfig.hostPort(door.address().orElseThrow())),
        ListenConfig.said(listening));

    door.listener.close();
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          while (door.listening()) {
            Thread.onSpinWait();
          }
        },
        "a lost listener is said not listening");

    assertEquals(Optional.empty(), door.address());
    assertEquals(Optional.empty(), ListenConfig.said(listening));
    door.tick(now.get());
    assertTrue(door.listening());
    assertEquals(
        Optional.of(ListenConfig.hostPort(door.address().orElseThrow())),
        ListenConfig.said(listening));
  }

  @Test
  void refusedConnectionsAreSaidOnceATickNotOnceEach() throws IOException {
    var err = System.err;
    var said = new ByteArrayOutputStream();
    System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
    try {
      for (var i = 0; i < 20; i++) {
        try (var tcp = SocketChannel.open(door.address().orElseThrow())) {
          PtyWire.handshake(tcp, tcp);
          PtyWire.write(tcp, new PtyMessage.Hello("tkt_" + "0".repeat(64)));
          PtyWire.read(tcp);
        }
      }
      var before = said.toString(StandardCharsets.UTF_8);
      door.tick(now.get());

      assertEquals("", before);
      assertEquals(
          List.of(
              "pty-host: refused principal=? session=- reason=tcp: 20 connection(s): This is not a"
                  + " ticket this host issued; ask the API for a new one."),
          said.toString(StandardCharsets.UTF_8).lines().toList());
    } finally {
      System.setErr(err);
    }
  }

  @Test
  void anAcceptThatFailsWhileTheListenerStaysBoundIsPassedOverAndTheDoorServesOn()
      throws Exception {
    var err = System.err;
    var said = new ByteArrayOutputStream();
    try (var real = ServerSocketChannel.open().bind(LOOPBACK);
        var failing = new FailingAccepts(real, 3)) {
      var loop = Thread.ofVirtual().start(() -> door.acceptLoop(failing));
      try (var tcp = SocketChannel.open(real.getLocalAddress())) {
        var magic = ByteBuffer.allocate(8);
        while (magic.hasRemaining()) {
          assertTrue(tcp.read(magic) >= 0, "served after three failed accepts");
        }
      }
      System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
      door.tick(now.get());
      System.setErr(err);
      assertTrue(loop.isAlive());
      door.close();
      failing.close();
      loop.join();
    } finally {
      System.setErr(err);
    }

    assertTrue(
        said.toString(StandardCharsets.UTF_8)
            .contains("tcp: 3 connection(s): an accept failed: Too many open files"),
        said.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aDoorClosedWhileItBindsLeavesNothingBound() throws IOException {
    var late =
        new PtyTcpDoor(
            LOOPBACK,
            8,
            now::get,
            new PtyTickets(8),
            PtyIdentity.Holders.NONE,
            dir.resolve("late.listening"),
            (channel, admission) -> {});
    late.close();

    late.tick(now.get());

    assertFalse(late.listening());
    assertEquals(Optional.empty(), late.address());
    assertEquals(Optional.empty(), ListenConfig.said(dir.resolve("late.listening")));
  }

  /**
   * A connection the door shook hands on, tried again while it is turned away at its cap: a slot is
   * released once the thread of the connection that held it has seen it end, which a client that
   * just closed one cannot wait for.
   */
  static SocketChannel handshaken(InetSocketAddress address) throws IOException {
    while (true) {
      var channel = SocketChannel.open(address);
      try {
        PtyWire.handshake(channel, channel);
        return channel;
      } catch (IOException turnedAway) {
        channel.close();
      }
    }
  }

  /** A connection the door is serving: it wrote the magic, so it counted it. */
  private SocketChannel served() throws IOException {
    var channel = SocketChannel.open(door.address().orElseThrow());
    opened.add(channel);
    var magic = ByteBuffer.allocate(8);
    while (magic.hasRemaining()) {
      assertTrue(channel.read(magic) >= 0);
    }
    return channel;
  }

  /** A listener whose first {@code failures} accepts fail as a box out of descriptors does. */
  private static final class FailingAccepts extends ServerSocketChannel {
    private final ServerSocketChannel real;
    private int failures;

    FailingAccepts(ServerSocketChannel real, int failures) {
      super(real.provider());
      this.real = real;
      this.failures = failures;
    }

    @Override
    public SocketChannel accept() throws IOException {
      if (failures > 0) {
        failures--;
        throw new IOException("Too many open files");
      }
      return real.accept();
    }

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
      return real.getOption(name);
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
      return real.supportedOptions();
    }

    @Override
    public ServerSocket socket() {
      return real.socket();
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
      return real.getLocalAddress();
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
      real.close();
    }

    @Override
    protected void implConfigureBlocking(boolean block) {
      throw new UnsupportedOperationException();
    }
  }
}
