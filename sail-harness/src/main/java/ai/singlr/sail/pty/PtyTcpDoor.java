/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Rebinding;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.ListenConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import jdk.net.ExtendedSocketOptions;

/**
 * The terminal host's door on the private network: a TCP listener that serves nothing until a
 * connection presents, as its {@code Hello} token, a ticket the host issued. Until then the door
 * reads at most {@link #TICKET_FRAME} bytes of the connection, counts it against a cap for its
 * peer's address ({@link #PER_PEER}) beside its cap for every TCP connection, which it owns apart
 * from the unix socket's, and closes it at {@link #UNTICKETED_DEADLINE} from the connect ({@link
 * #tick}); a first frame that is not a {@code Hello} is closed unanswered. A presented ticket is
 * redeemed and its credential resolved again against the roster; only then does the door hand the
 * connection to the host, which serves it the unix socket's frames within what the ticket admits.
 *
 * <p>A ticketed connection keeps {@code TCP_NODELAY}, so a keystroke is not held back for the next,
 * and TCP keepalive: a peer that vanished without closing (a laptop asleep, a dropped NAT flow) is
 * found dead within {@link #KEEPALIVE_IDLE} plus {@link #KEEPALIVE_COUNT} probes {@link
 * #KEEPALIVE_INTERVAL} apart where the platform takes those options, and within its own default
 * otherwise; the connection then ends and its slot, attachment and write token go with it.
 *
 * <p>The address may not be up when the host starts: the door binds when it can ({@link
 * Rebinding}), and the unix socket never waits on it.
 */
final class PtyTcpDoor implements AutoCloseable {

  static final Duration UNTICKETED_DEADLINE = Duration.ofSeconds(5);
  static final int TICKET_FRAME = 256;
  static final int PER_PEER = 8;
  static final Duration KEEPALIVE_IDLE = Duration.ofSeconds(30);
  static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(10);
  static final int KEEPALIVE_COUNT = 3;

  /** What the host does with a connection this door admitted: serves it until it ends. */
  @FunctionalInterface
  interface Served {
    void serve(SocketChannel channel, PtySessionHost.Admission admission);
  }

  private record Pending(InetAddress peer, long connectedAt) {}

  private final InetSocketAddress address;
  private final int cap;
  private final LongSupplier clock;
  private final PtyTickets tickets;
  private final PtyIdentity.Holders holders;
  private final Served host;
  private final Rebinding rebinding;
  private final AtomicInteger open = new AtomicInteger();
  private final Map<SocketChannel, Pending> unticketed = new HashMap<>();
  private volatile ServerSocketChannel listener;
  private volatile boolean closed;

  /**
   * The door on {@code address}, serving at most {@code cap} connections at once on {@code clock},
   * redeeming {@code tickets} for the people {@code holders} names, handing each to {@code host}.
   */
  PtyTcpDoor(
      InetSocketAddress address,
      int cap,
      LongSupplier clock,
      PtyTickets tickets,
      PtyIdentity.Holders holders,
      Served host) {
    this.address = address;
    this.cap = cap;
    this.clock = clock;
    this.tickets = tickets;
    this.holders = holders;
    this.host = host;
    this.rebinding =
        new Rebinding(
            "The terminal's private address "
                + ListenConfig.hostPort(address)
                + " ('"
                + ListenConfig.TERMINAL
                + "')",
            this::bind,
            said -> PtySessionHost.log("tcp-door", "-", "-", said));
  }

  /**
   * One pass at {@code nowNanos}: binds the listener when it is not and its backoff has passed, and
   * closes every connection that presented no ticket within {@link #UNTICKETED_DEADLINE} of its
   * connect. The host's timer drives it each second; tests drive it with synthetic time.
   */
  void tick(long nowNanos) {
    rebinding.tick(nowNanos);
    reap(nowNanos);
  }

  /** Whether the listener is bound, so a ticket issued now can be presented. */
  boolean listening() {
    return rebinding.listening();
  }

  /** The address the listener is bound to, once it is. */
  Optional<InetSocketAddress> address() throws IOException {
    var bound = listener;
    return bound == null
        ? Optional.empty()
        : Optional.of((InetSocketAddress) bound.getLocalAddress());
  }

  private void bind() throws IOException {
    if (closed) {
      throw new IOException("the host is shutting down");
    }
    var channel = ServerSocketChannel.open();
    try {
      channel.bind(address);
    } catch (IOException unbound) {
      channel.close();
      throw unbound;
    }
    listener = channel;
    Thread.ofVirtual().name("pty-host-tcp-accept").start(() -> acceptLoop(channel));
  }

  private void acceptLoop(ServerSocketChannel bound) {
    while (!closed) {
      try {
        var channel = bound.accept();
        Thread.ofVirtual().name("pty-host-tcp-conn").start(() -> serve(channel));
      } catch (IOException e) {
        if (!closed) {
          throw new IllegalStateException("pty host TCP accept failed", e);
        }
        return;
      }
    }
  }

  /**
   * Serves one TCP connection from accept to its end, counting it against this door's cap and
   * releasing the slot it took whatever happens to it.
   */
  private void serve(SocketChannel channel) {
    var overCap = open.incrementAndGet() > cap;
    try (channel) {
      if (overCap) {
        PtySessionHost.log("refused", "?", "-", "tcp: connection cap " + cap);
        return;
      }
      var peer = ((InetSocketAddress) channel.getRemoteAddress()).getAddress();
      if (!pending(channel, peer)) {
        PtySessionHost.log("refused", "?", "-", "tcp: " + PER_PEER + " unticketed from one peer");
        return;
      }
      PtySessionHost.Admission admission;
      try {
        set(channel, StandardSocketOptions.TCP_NODELAY, true);
        admission = admit(channel);
      } finally {
        settled(channel);
      }
      if (admission != null) {
        keepAlive(channel);
        host.serve(channel, admission);
      }
    } catch (IOException disconnected) {
      var unused = disconnected;
    } catch (RuntimeException crashed) {
      PtySessionHost.log("exception", "?", "-", "tcp: " + crashed);
    } finally {
      open.decrementAndGet();
    }
  }

  /**
   * The ticket a connection presents, redeemed, and its credential resolved again; null, after the
   * refusal is said or nothing is, when it presents none that admits it.
   */
  private PtySessionHost.Admission admit(SocketChannel channel) throws IOException {
    PtyWire.handshake(channel, channel);
    if (!(PtyWire.read(channel, TICKET_FRAME) instanceof PtyMessage.Hello(var presented))) {
      PtySessionHost.log("refused", "?", "-", "tcp: the first frame presented no ticket");
      return null;
    }
    settled(channel);
    try {
      var ticket = tickets.redeem(presented, clock.getAsLong());
      return new PtySessionHost.Admission(
          new PtySessionHost.Credential.Holder(ticket.holder()),
          holders.holder(ticket.holder()),
          ticket::refusal);
    } catch (IOException refused) {
      PtySessionHost.log("refused", "?", "-", "tcp: " + refused.getMessage());
      PtySessionHost.reply(channel, new PtyMessage.Err(Remedied.whole(refused)));
      return null;
    } catch (RuntimeException unavailable) {
      PtySessionHost.log("exception", "?", "-", "tcp: " + unavailable);
      PtySessionHost.reply(channel, new PtyMessage.Err(PtySessionHost.UNSERVED));
      return null;
    }
  }

  private void keepAlive(SocketChannel channel) {
    set(channel, StandardSocketOptions.SO_KEEPALIVE, true);
    set(channel, ExtendedSocketOptions.TCP_KEEPIDLE, (int) KEEPALIVE_IDLE.toSeconds());
    set(channel, ExtendedSocketOptions.TCP_KEEPINTERVAL, (int) KEEPALIVE_INTERVAL.toSeconds());
    set(channel, ExtendedSocketOptions.TCP_KEEPCOUNT, KEEPALIVE_COUNT);
  }

  /** Sets {@code option} where the platform takes it; one it does not is logged and passed over. */
  private static <T> void set(SocketChannel channel, SocketOption<T> option, T value) {
    if (!channel.supportedOptions().contains(option)) {
      return;
    }
    try {
      channel.setOption(option, value);
    } catch (IOException | UnsupportedOperationException refused) {
      PtySessionHost.log("tcp-option-failed", "-", "-", option.name() + ": " + refused);
    }
  }

  /** Registers {@code channel} as presenting no ticket yet; false when its peer holds its cap. */
  private synchronized boolean pending(SocketChannel channel, InetAddress peer) {
    var held = unticketed.values().stream().filter(pending -> pending.peer().equals(peer)).count();
    if (held >= PER_PEER) {
      return false;
    }
    unticketed.put(channel, new Pending(peer, clock.getAsLong()));
    return true;
  }

  private synchronized void settled(SocketChannel channel) {
    unticketed.remove(channel);
  }

  private synchronized void reap(long nowNanos) {
    unticketed.forEach(
        (channel, pending) -> {
          if (nowNanos - pending.connectedAt() > UNTICKETED_DEADLINE.toNanos()) {
            PtySessionHost.log(
                "refused",
                "?",
                "-",
                "tcp: no ticket within " + UNTICKETED_DEADLINE.toSeconds() + " s");
            PtySessionHost.closeQuietly(channel);
          }
        });
  }

  @Override
  public void close() throws IOException {
    closed = true;
    var bound = listener;
    if (bound != null) {
      bound.close();
    }
  }
}
