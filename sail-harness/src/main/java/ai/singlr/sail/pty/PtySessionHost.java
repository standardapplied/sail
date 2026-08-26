/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-container session host: owns every {@link PtySession}, serves the {@link PtyWire}
 * protocol on a unix socket, and reaps what nobody wants — a session never attached within the
 * grace window (the mosh rule), and an ended session once its corpse retention passes. Each
 * connection is one attached client at most; commands answer {@code Ok}/{@code Err}; session
 * traffic flows through the subscriber queue onto the same channel, serialized per connection.
 *
 * <p>Reaping is driven by {@link #sweep(long)} with an injected clock — the production timer thread
 * merely calls it; tests call it directly with synthetic time.
 */
public final class PtySessionHost implements AutoCloseable {

  static final Duration NEVER_ATTACHED_GRACE = Duration.ofSeconds(60);
  static final Duration CORPSE_RETENTION = Duration.ofMinutes(10);

  private final Path socketPath;
  private final Path sessionsDir;
  private final long journalCapacity;
  private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
  private volatile ServerSocketChannel server;
  private volatile boolean closed;

  public PtySessionHost(Path socketPath, Path sessionsDir, long journalCapacity) {
    this.socketPath = socketPath;
    this.sessionsDir = sessionsDir;
    this.journalCapacity = journalCapacity;
  }

  public void start() throws IOException {
    Files.createDirectories(sessionsDir);
    Files.deleteIfExists(socketPath);
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));
    Thread.ofVirtual().name("pty-host-accept").start(this::acceptLoop);
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        var channel = server.accept();
        Thread.ofVirtual().name("pty-host-conn").start(() -> serve(channel));
      } catch (IOException e) {
        if (!closed) {
          throw new IllegalStateException("pty host accept failed", e);
        }
        return;
      }
    }
  }

  private void serve(SocketChannel channel) {
    PtySession attached = null;
    var subscriberId = -1L;
    try (channel) {
      PtyWire.handshake(channel, channel);
      while (true) {
        var message = PtyWire.read(channel);
        switch (message) {
          case PtyMessage.Create m -> reply(channel, create(m));
          case PtyMessage.Attach m -> {
            if (attached != null) {
              reply(channel, new PtyMessage.Err("This connection is already attached."));
              break;
            }
            var session = sessions.get(m.session());
            if (session == null || !session.live()) {
              reply(
                  channel, new PtyMessage.Err("No live session '" + m.session() + "' to attach."));
              break;
            }
            reply(channel, new PtyMessage.Ok());
            subscriberId = session.attach(msg -> reply(channel, msg), m.write());
            attached = session;
          }
          case PtyMessage.Input m -> {
            if (attached == null) {
              reply(channel, new PtyMessage.Err("Attach before writing."));
            } else {
              attached.input(subscriberId, m.seq(), m.bytes());
            }
          }
          case PtyMessage.Resize m -> {
            if (attached != null) {
              attached.resize(subscriberId, m.cols(), m.rows());
            }
          }
          case PtyMessage.TakeWrite m -> {
            if (attached == null) {
              reply(channel, new PtyMessage.Err("Attach before taking the write token."));
            } else {
              attached.takeWrite(subscriberId, "");
            }
          }
          case PtyMessage.Detach m -> {
            if (attached != null) {
              attached.detach(subscriberId);
              attached = null;
              subscriberId = -1;
            }
            reply(channel, new PtyMessage.Ok());
          }
          case PtyMessage.ListSessions m -> reply(channel, listSessions());
          case PtyMessage.Kill m -> reply(channel, kill(m.session()));
          default -> reply(channel, new PtyMessage.Err("Unexpected client frame."));
        }
      }
    } catch (IOException e) {
      if (attached != null) {
        attached.detach(subscriberId);
      }
    }
  }

  private PtyMessage create(PtyMessage.Create m) {
    var existing = sessions.get(m.session());
    if (existing != null && existing.live()) {
      return new PtyMessage.Err(
          "Session '" + m.session() + "' is already running; attach or kill it.");
    }
    if (existing != null) {
      remove(m.session(), existing);
    }
    try {
      var ring = sessionsDir.resolve(m.session() + ".ring");
      Files.deleteIfExists(ring);
      var session =
          PtySession.start(
              m.session(),
              m.command(),
              Map.of("TERM", "xterm-256color"),
              Path.of(m.cwd()),
              ring,
              journalCapacity,
              m.cols(),
              m.rows());
      sessions.put(m.session(), session);
      return new PtyMessage.Ok();
    } catch (IOException e) {
      return new PtyMessage.Err("Could not start session '" + m.session() + "': " + e.getMessage());
    }
  }

  private PtyMessage listSessions() {
    var infos = new ArrayList<PtyMessage.SessionInfo>();
    sessions.values().stream()
        .sorted(java.util.Comparator.comparing(PtySession::name))
        .forEach(
            session ->
                infos.add(
                    new PtyMessage.SessionInfo(
                        session.name(), session.live(), session.attachedCount(), "")));
    return new PtyMessage.Sessions(infos);
  }

  private PtyMessage kill(String name) {
    var session = sessions.get(name);
    if (session == null) {
      return new PtyMessage.Err("No session '" + name + "'.");
    }
    remove(name, session);
    return new PtyMessage.Ok();
  }

  private void remove(String name, PtySession session) {
    sessions.remove(name, session);
    session.close();
  }

  /** One reaping pass at {@code nowNanos}: the mosh grace and retention rules, nothing else. */
  public void sweep(long nowNanos) {
    sessions.forEach(
        (name, session) -> {
          if (session.live()
              && !session.everAttached()
              && nowNanos - session.createdAtNanos() > NEVER_ATTACHED_GRACE.toNanos()) {
            remove(name, session);
            return;
          }
          if (!session.live()
              && session.attachedCount() == 0
              && nowNanos - session.endedAtNanos() > CORPSE_RETENTION.toNanos()) {
            remove(name, session);
          }
        });
  }

  public int sessionCount() {
    return sessions.size();
  }

  private static void reply(SocketChannel channel, PtyMessage message) {
    try {
      synchronized (channel.blockingLock()) {
        PtyWire.write(channel, message);
      }
    } catch (IOException e) {
      try {
        channel.close();
      } catch (IOException ignored) {
        var unused = ignored;
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      if (server != null) {
        server.close();
      }
      Files.deleteIfExists(socketPath);
    } catch (IOException e) {
      throw new IllegalStateException("pty host close failed", e);
    }
    sessions.forEach((name, session) -> session.close());
    sessions.clear();
  }
}
