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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import jdk.net.ExtendedSocketOptions;

/**
 * The terminal host's door on the private network: a TCP listener that serves nothing until a
 * connection presents, as its {@code Hello} token, a ticket the host issued. Until then the door
 * reads at most {@link #TICKET_FRAME} bytes of the connection, and closes it at {@link
 * #UNTICKETED_DEADLINE} from the connect ({@link #tick}); a first frame that is not a {@code Hello}
 * is closed unanswered. Such connections are capped for their peer ({@link #PER_PEER}, an IPv6 peer
 * counted by its /64) and live in a pool of a quarter of the door's cap, whose oldest is let go for
 * a newcomer, so a peer holding many of them, from however many addresses, never keeps a ticketed
 * client out; the door's cap on every TCP connection is its own, apart from the unix socket's.
 * Refused connections are counted and said once a tick, never once each. A presented ticket is
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
 * Rebinding}), and the unix socket never waits on it. A listener that is lost later is bound again
 * the same way.
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

  private record Pending(String peer, long connectedAt) {}

  /** How long the accept loop waits after an accept that failed and left the listener bound. */
  static final Duration ACCEPT_PAUSE = Duration.ofMillis(100);

  private final InetSocketAddress address;
  private final int cap;
  private final LongSupplier clock;
  private final PtyTickets tickets;
  private final PtyIdentity.Holders holders;
  private final Served host;
  private final Path listening;
  private final Rebinding rebinding;
  private final int pool;
  private final AtomicInteger open = new AtomicInteger();
  private final Map<SocketChannel, Pending> unticketed = new LinkedHashMap<>();
  private final Map<String, Integer> refusals = new LinkedHashMap<>();
  volatile ServerSocketChannel listener;
  private volatile boolean closed;

  /**
   * The door on {@code address}, serving at most {@code cap} connections at once on {@code clock},
   * redeeming {@code tickets} for the people {@code holders} names, handing each to {@code host},
   * and saying in {@code listening} what it is bound to while it is.
   */
  PtyTcpDoor(
      InetSocketAddress address,
      int cap,
      LongSupplier clock,
      PtyTickets tickets,
      PtyIdentity.Holders holders,
      Path listening,
      Served host) {
    this.address = address;
    this.listening = listening;
    this.cap = cap;
    this.clock = clock;
    this.tickets = tickets;
    this.holders = holders;
    this.host = host;
    this.pool = Math.max(1, cap / 4);
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
    sayRefusals();
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

  /**
   * Binds the listener, then asks whether the door closed meanwhile: {@link #close} sets {@code
   * closed} before it reads {@code listener} and this sets {@code listener} before it reads {@code
   * closed}, so one of the two always closes a listener bound as the door closes.
   */
  private void bind() throws IOException {
    var channel = ServerSocketChannel.open();
    try {
      channel.bind(address);
    } catch (IOException unbound) {
      channel.close();
      throw unbound;
    }
    listener = channel;
    if (closed) {
      listener = null;
      channel.close();
      throw new IOException("the host is shutting down");
    }
    ListenConfig.say(listening, (InetSocketAddress) channel.getLocalAddress());
    Thread.ofVirtual().name("pty-host-tcp-accept").start(() -> acceptLoop(channel));
  }

  /**
   * Accepts until the door closes. An accept that fails while the listener stays bound (out of
   * descriptors, say) is counted and passed over after {@link #ACCEPT_PAUSE}; a listener that is
   * gone ends the loop, and the door is then not listening and binds again at its next tick.
   */
  void acceptLoop(ServerSocketChannel bound) {
    while (!closed) {
      try {
        taken(bound.accept());
      } catch (ClosedChannelException | RuntimeException gone) {
        if (!closed) {
          lose(bound, gone);
        }
        return;
      } catch (IOException failed) {
        if (!bound.isOpen()) {
          if (!closed) {
            lose(bound, failed);
          }
          return;
        }
        refused("an accept failed: " + failed.getMessage());
        LockSupport.parkNanos(ACCEPT_PAUSE.toNanos());
      }
    }
  }

  /** The listener {@code bound} is gone: the door is not listening, and binds at its next tick. */
  private void lose(ServerSocketChannel bound, Exception why) {
    try {
      bound.close();
    } catch (IOException ignored) {
      var unused = ignored;
    }
    if (listener == bound) {
      listener = null;
    }
    ListenConfig.unsay(listening);
    rebinding.lost(clock.getAsLong(), why.toString());
  }

  /**
   * Takes {@code channel} on the accept thread, in the order connections arrive: it takes a slot of
   * the door's cap and, under the cap, a place in the pool of connections with no ticket. The
   * pool's oldest is therefore the one that connected first and its deadline runs from its accept,
   * never from whenever its thread first runs, so a connection already gone that is served late can
   * never displace a newer one. Every channel taken is served on a thread of its own, which
   * releases the slot.
   */
  private void taken(SocketChannel channel) {
    var refusal = open.incrementAndGet() > cap ? "connection cap " + cap : registered(channel);
    Thread.ofVirtual().name("pty-host-tcp-conn").start(() -> serve(channel, refusal));
  }

  /** Why {@code channel} has no place in the pool, or null once it has one. */
  private String registered(SocketChannel channel) {
    try {
      var peer = peerOf(((InetSocketAddress) channel.getRemoteAddress()).getAddress());
      return pending(channel, peer) ? null : PER_PEER + " connections with no ticket from one peer";
    } catch (IOException gone) {
      return "the connection ended before it was taken";
    }
  }

  /**
   * Serves one TCP connection {@link #taken} to its end, or closes it for {@code refusal}, and
   * releases the slot it took whatever happens to it, and before it closes the channel: a client
   * that sees its connection end may connect again at once, and finds the slot free.
   */
  private void serve(SocketChannel channel, String refusal) {
    try (channel) {
      try {
        serveTaken(channel, refusal);
      } finally {
        open.decrementAndGet();
      }
    } catch (IOException disconnected) {
      var unused = disconnected;
    } catch (RuntimeException crashed) {
      PtySessionHost.log("exception", "?", "-", "tcp: " + crashed);
    }
  }

  private void serveTaken(SocketChannel channel, String refusal) throws IOException {
    if (refusal != null) {
      refused(refusal);
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
  }

  /**
   * The ticket a connection presents, redeemed, and its credential resolved again; null, after the
   * refusal is said or nothing is, when it presents none that admits it.
   */
  private PtySessionHost.Admission admit(SocketChannel channel) throws IOException {
    PtyWire.handshake(channel, channel);
    if (!(PtyWire.read(channel, TICKET_FRAME) instanceof PtyMessage.Hello(var presented))) {
      refused("the first frame presented no ticket");
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
      refused(refused.getMessage());
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
    } catch (ClosedChannelException letGo) {
      var unused = letGo;
    } catch (IOException | UnsupportedOperationException refused) {
      PtySessionHost.log("tcp-option-failed", "-", "-", option.name() + ": " + refused);
    }
  }

  /**
   * The peer {@code address} counts as: itself for IPv4, and its /64 for IPv6, since a host holds a
   * whole /64 to choose its addresses from.
   */
  static String peerOf(InetAddress address) {
    var bytes = address.getAddress();
    return bytes.length == 16
        ? "v6:" + HexFormat.of().formatHex(bytes, 0, 8)
        : address.getHostAddress();
  }

  /**
   * Registers {@code channel} as presenting no ticket yet: false when its peer holds {@link
   * #PER_PEER} already. Such connections have a pool of their own, a quarter of the door's cap, so
   * ticketed ones always have room; when it is full the oldest of them is let go for the newcomer,
   * so a slot held without a ticket buys nothing.
   */
  private synchronized boolean pending(SocketChannel channel, String peer) {
    var held = unticketed.values().stream().filter(pending -> pending.peer().equals(peer)).count();
    if (held >= PER_PEER) {
      return false;
    }
    if (unticketed.size() >= pool) {
      var oldest = unticketed.keySet().iterator().next();
      unticketed.remove(oldest);
      PtySessionHost.closeQuietly(oldest);
      refused("the oldest connection with no ticket let go for a newer one");
    }
    unticketed.put(channel, new Pending(peer, clock.getAsLong()));
    return true;
  }

  private synchronized void settled(SocketChannel channel) {
    unticketed.remove(channel);
  }

  private synchronized void reap(long nowNanos) {
    for (var entry : List.copyOf(unticketed.entrySet())) {
      if (nowNanos - entry.getValue().connectedAt() > UNTICKETED_DEADLINE.toNanos()) {
        refused("no ticket within " + UNTICKETED_DEADLINE.toSeconds() + " s");
        PtySessionHost.closeQuietly(entry.getKey());
      }
    }
  }

  /**
   * Counts a refused connection by why; the counts are said once each tick, so a flood of
   * connections floods no log.
   */
  private synchronized void refused(String why) {
    refusals.merge(why, 1, Integer::sum);
  }

  private synchronized void sayRefusals() {
    refusals.forEach(
        (why, count) ->
            PtySessionHost.log("refused", "?", "-", "tcp: " + count + " connection(s): " + why));
    refusals.clear();
  }

  @Override
  public void close() throws IOException {
    closed = true;
    var bound = listener;
    if (bound != null) {
      bound.close();
    }
    ListenConfig.unsay(listening);
  }
}
