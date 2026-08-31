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
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
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
  private final PtyIdentity.Resolver identity;
  private final PtyRooms rooms;
  private final PtyEvents events;
  private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
  private volatile ServerSocketChannel server;
  private volatile boolean closed;

  public PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyRooms rooms,
      PtyEvents events) {
    this.socketPath = socketPath;
    this.sessionsDir = sessionsDir;
    this.journalCapacity = journalCapacity;
    this.identity = identity;
    this.rooms = rooms;
    this.events = events;
  }

  public void start() throws IOException {
    Files.createDirectories(sessionsDir);
    var socketDir = socketPath.getParent();
    if (socketDir != null) {
      Files.createDirectories(socketDir);
      ownerOnly(socketDir);
    }
    Files.deleteIfExists(socketPath);
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));
    ownerOnly(socketPath);
    Thread.ofVirtual().name("pty-host-accept").start(this::acceptLoop);
  }

  /**
   * Restricts {@code path} to owner-only. The socket is the identity boundary's only door — a
   * blank-token connection resolves to the box owner — so no group or other principal may reach it.
   * A best-effort no-op on a filesystem without POSIX permissions (never the provisioned box).
   */
  private static void ownerOnly(java.nio.file.Path path) {
    try {
      Files.setPosixFilePermissions(
          path,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    } catch (UnsupportedOperationException | IOException ignored) {
      var unused = ignored;
    }
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
      PtyIdentity who;
      if (PtyWire.read(channel) instanceof PtyMessage.Hello(var token)) {
        try {
          who = identity.resolve(token);
        } catch (IOException refused) {
          reply(channel, new PtyMessage.Err(refused.getMessage()));
          return;
        }
        reply(channel, new PtyMessage.Ok());
      } else {
        reply(channel, new PtyMessage.Err("The first frame must identify you: send Hello."));
        return;
      }
      while (true) {
        var message = PtyWire.read(channel);
        switch (message) {
          case PtyMessage.Create m -> reply(channel, create(m, who));
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
            if (!admitted(who, session)) {
              reply(
                  channel,
                  new PtyMessage.Err(
                      "Session '" + m.session() + "' belongs to " + session.ownerFde() + "."));
              break;
            }
            reply(channel, new PtyMessage.Ok());
            subscriberId = session.attach(msg -> reply(channel, msg), m.write(), who.fde());
            attached = session;
          }
          case PtyMessage.Input m -> {
            if (attached == null) {
              reply(channel, new PtyMessage.Err("Attach before writing."));
            } else if (!attached.input(subscriberId, m.seq(), m.bytes())) {
              reply(channel, new PtyMessage.Err("You do not hold the write token."));
            }
          }
          case PtyMessage.Resize m -> {
            if (attached != null) {
              var unused = attached.resize(subscriberId, m.cols(), m.rows());
            }
          }
          case PtyMessage.TakeWrite m -> {
            if (attached == null) {
              reply(channel, new PtyMessage.Err("Attach before taking the write token."));
            } else {
              attached.takeWrite(subscriberId, who.fde());
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
          case PtyMessage.ListSessions m -> reply(channel, listSessions(who, m));
          case PtyMessage.Kill m -> reply(channel, kill(m.session(), who));
          default -> reply(channel, new PtyMessage.Err("Unexpected client frame."));
        }
      }
    } catch (IOException e) {
      if (attached != null) {
        attached.detach(subscriberId);
      }
    }
  }

  private static boolean admitted(PtyIdentity who, PtySession session) {
    return who.admin() || who.fde().equals(session.ownerFde());
  }

  /** The session's command as requested; an empty request means a login shell. */
  static List<String> requestedOrShell(List<String> requested) {
    return requested.isEmpty() ? List.of("bash", "-l") : requested;
  }

  /**
   * The environment a session's child inherits: a terminal type, plus {@code SAIL_ROOM_ID} when the
   * session is room-bound — the one fact that lets everything the child creates ({@code spec
   * create} above all) land in the room the session serves.
   */
  static Map<String, String> childEnv(String room) {
    var env = new java.util.LinkedHashMap<String, String>();
    env.put("TERM", "xterm-256color");
    if (room != null && !room.isBlank()) {
      env.put("SAIL_ROOM_ID", room);
    }
    return env;
  }

  /**
   * Resolves the process a session spawns. A non-blank project wraps {@code origin.command()} in
   * the dev-user {@code incus exec -t} lane so the session runs inside that project's container
   * with a real tty, carrying {@code env} across explicitly. The container name is validated here,
   * at the host — clients send only a name, never raw {@code incus} arguments.
   */
  static List<String> childCommand(PtySession.Origin origin, Map<String, String> env) {
    var project = origin.project();
    return project == null || project.isBlank()
        ? origin.command()
        : ai.singlr.sail.engine.ContainerExec.asDevUserTty(project, env, origin.command());
  }

  private PtyMessage create(PtyMessage.Create m, PtyIdentity who) {
    var existing = sessions.get(m.session());
    if (existing != null && !admitted(who, existing)) {
      return new PtyMessage.Err(
          "Session '" + m.session() + "' belongs to " + existing.ownerFde() + ".");
    }
    if (existing != null && existing.live()) {
      return new PtyMessage.Err(
          "Session '" + m.session() + "' is already running; attach or kill it.");
    }
    var commandBytes = PtyWire.wireSize(m.command());
    if (commandBytes > PtyMessage.MAX_COMMAND_BYTES) {
      return new PtyMessage.Err(
          "Refusing a "
              + commandBytes
              + "-byte command for session '"
              + m.session()
              + "'; the cap is "
              + PtyMessage.MAX_COMMAND_BYTES
              + " bytes. Put it in a script and run that.");
    }
    var room = java.util.Objects.toString(m.room(), "");
    if (!room.isBlank()) {
      try {
        ai.singlr.sail.engine.NameValidator.requireValidSpecId(room);
        rooms.admit(room, m.project(), who);
      } catch (IllegalArgumentException | IOException refused) {
        return new PtyMessage.Err(
            "Refusing room for session '" + m.session() + "': " + refused.getMessage());
      }
    }
    if (existing != null) {
      remove(m.session(), existing);
    }
    try {
      var ring = sessionsDir.resolve(m.session() + ".ring");
      Files.deleteIfExists(ring);
      var origin =
          new PtySession.Origin(
              m.session(), who.fde(), m.project(), room, requestedOrShell(m.command()));
      var env = childEnv(room);
      var session =
          PtySession.start(
              origin,
              events,
              childCommand(origin, env),
              env,
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

  /**
   * One name-ordered page of the caller's sessions. A page is bounded by {@link
   * PtyMessage#PAGE_LIMIT} entries of at most {@link PtyMessage#MAX_COMMAND_BYTES} of encoded
   * command each (names are file names, so a filesystem bounds them), which keeps every page well
   * under the wire's frame cap no matter how many sessions the host holds.
   */
  private PtyMessage listSessions(PtyIdentity who, PtyMessage.ListSessions request) {
    var after = java.util.Objects.toString(request.after(), "");
    var limit = Math.clamp(request.limit(), 1, PtyMessage.PAGE_LIMIT);
    var mine =
        sessions.values().stream()
            .filter(session -> admitted(who, session))
            .filter(session -> session.name().compareTo(after) > 0)
            .sorted(java.util.Comparator.comparing(PtySession::name))
            .toList();
    var page = mine.stream().limit(limit).map(PtySessionHost::infoOf).toList();
    var next = mine.size() > limit ? page.getLast().name() : "";
    return new PtyMessage.Sessions(page, next);
  }

  private static PtyMessage.SessionInfo infoOf(PtySession session) {
    return new PtyMessage.SessionInfo(
        session.name(),
        session.live(),
        session.attachedCount(),
        session.writerFde(),
        session.origin().room(),
        session.origin().command());
  }

  private PtyMessage kill(String name, PtyIdentity who) {
    var session = sessions.get(name);
    if (session == null) {
      return new PtyMessage.Err("No session '" + name + "'.");
    }
    if (!admitted(who, session)) {
      return new PtyMessage.Err("Session '" + name + "' belongs to " + session.ownerFde() + ".");
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
