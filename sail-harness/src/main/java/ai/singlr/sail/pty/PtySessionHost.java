/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.engine.NameValidator;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The per-container session host: owns every {@link PtySession}, serves the {@link PtyWire}
 * protocol on a unix socket, and reaps what nobody wants — a session never attached within the
 * grace window (the mosh rule), and an ended session once its corpse retention passes. Each
 * connection is one attached client at most; commands answer {@code Ok}/{@code Err}; session
 * traffic flows through the subscriber queue onto the same channel, serialized per connection.
 *
 * <p>Reaping is driven by {@link #sweep(long)} with an injected clock — the production timer thread
 * merely calls it; tests call it directly with synthetic time. The same pass re-validates every
 * admitted connection's credential: a token the resolver no longer honors (a revoked FDE session)
 * severs the connections it admitted, so a revocation ends live attachments within one sweep rather
 * than at the next reconnect.
 *
 * <p>Yielding is the one verb no FDE holds. A dispatch that reserves repos must end whichever
 * resumed conversation sits on them regardless of who opened it, so the host mints a random
 * dispatch credential at start, keeps it owner-only beside the socket ({@link
 * #dispatchCredentialOf}), and admits a {@code Hello} carrying it as {@link PtyIdentity#DISPATCH} —
 * a principal that can yield and do nothing else. Owners and admins keep create, attach, and kill;
 * a user-issued kill never masquerades as a yield.
 *
 * <p>The host is replaceable without ending a session. Every master goes into systemd's descriptor
 * store the moment it is created ({@link Handoff#store()}); a host told to step aside {@link
 * #handoff() hands off} — stops reading, closes nothing — and its successor, started with the
 * store's descriptors as {@link Handoff#inheritedFds()}, adopts each one whose ring and sidecar are
 * sound before it sweeps the directory. An adopted host keeps the previous boot id (kept in {@code
 * host.boot}), so a client comparing ids reads the handoff as a reconnect; a host that inherits
 * nothing mints a new one, as before. A descriptor that cannot be adopted is closed, which hangs
 * its child up: closing the master is the kill switch, so nothing can leak.
 */
public final class PtySessionHost implements AutoCloseable {

  static final Duration NEVER_ATTACHED_GRACE = Duration.ofSeconds(60);
  static final Duration CORPSE_RETENTION = Duration.ofMinutes(10);
  static final String DISPATCH_CREDENTIAL_FILE = "pty-dispatch.token";
  static final String BOOT_ID_FILE = "host.boot";
  private static final List<String> SESSION_FILE_SUFFIXES =
      List.of(
          SessionFiles.META_SUFFIX + ".tmp",
          SessionFiles.RING_SUFFIX,
          SessionFiles.META_SUFFIX,
          SessionFiles.EXIT_SUFFIX);

  /**
   * How this host outlives itself: the store it pushes masters to, the descriptors the previous
   * host left it (by store name), and the shim every child is spawned under — the {@code
   * _pty-child} wrapper that records the exit status a successor cannot collect. {@link #NONE} is a
   * host on its own: no store, no inheritance, children spawned bare.
   */
  public record Handoff(
      SdNotify store, Map<String, Integer> inheritedFds, Function<Path, List<String>> shim) {
    public static final Handoff NONE = new Handoff(SdNotify.NONE, Map.of(), exit -> List.of());

    public Handoff {
      inheritedFds = Map.copyOf(inheritedFds);
    }

    /** What systemd gave this process, with {@code shim} wrapping every child. */
    public static Handoff fromEnvironment(Function<Path, List<String>> shim) {
      return new Handoff(
          SdNotify.fromEnvironment(),
          SdNotify.listenFds(System.getenv(), ProcessHandle.current().pid()),
          shim);
    }
  }

  /**
   * The host's resource ceilings: sessions one FDE may own at once and subscribers one session may
   * fan out to, each refused with an {@code Err} that names the cap; connections the host serves at
   * once, an excess socket being closed before a byte of it is read, so a peer that opens many and
   * never speaks holds no handler, descriptor, or frame past the cap; and sessions the host holds
   * in all, which is also how many masters its unit's descriptor store is sized for. {@link
   * #DEFAULTS} is production; tests inject smaller values.
   */
  public record Limits(
      int sessionsPerFde, int subscribersPerSession, int connections, int sessions) {
    public static final Limits DEFAULTS = new Limits(32, 16, 256, 256);
  }

  private final Path socketPath;
  private final Path sessionsDir;
  private final long journalCapacity;
  private final PtyIdentity.Resolver identity;
  private final PtyRooms rooms;
  private final PtyEvents events;
  private final String version;
  private final Limits limits;
  private final Handoff handoff;
  private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
  private final Set<Admitted> admitted = ConcurrentHashMap.newKeySet();
  private volatile String bootId = "";
  private final AtomicInteger connections = new AtomicInteger();
  private volatile ServerSocketChannel server;
  private volatile byte[] dispatchCredential = new byte[0];
  private volatile boolean closed;

  private record Admitted(SocketChannel channel, String token, String principal) {}

  public PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyRooms rooms,
      PtyEvents events,
      String version) {
    this(
        socketPath,
        sessionsDir,
        journalCapacity,
        identity,
        rooms,
        events,
        version,
        Limits.DEFAULTS,
        Handoff.NONE);
  }

  public PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyRooms rooms,
      PtyEvents events,
      String version,
      Handoff handoff) {
    this(
        socketPath,
        sessionsDir,
        journalCapacity,
        identity,
        rooms,
        events,
        version,
        Limits.DEFAULTS,
        handoff);
  }

  PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyRooms rooms,
      PtyEvents events,
      String version,
      Limits limits) {
    this(
        socketPath,
        sessionsDir,
        journalCapacity,
        identity,
        rooms,
        events,
        version,
        limits,
        Handoff.NONE);
  }

  PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyRooms rooms,
      PtyEvents events,
      String version,
      Limits limits,
      Handoff handoff) {
    this.socketPath = socketPath;
    this.sessionsDir = sessionsDir;
    this.journalCapacity = journalCapacity;
    this.identity = identity;
    this.rooms = rooms;
    this.events = events;
    this.version = version;
    this.limits = limits;
    this.handoff = handoff;
  }

  /**
   * This run of the host: every {@code Hello} is answered with it, so a client comparing ids across
   * connections can tell "the host restarted and lost the session" from "the session ended". A host
   * that adopted at least one session from its predecessor keeps the predecessor's id — the
   * sessions are the same, so the restart is invisible — and one that inherited nothing mints a new
   * one, the one fact that explains every session the previous run held.
   */
  public String bootId() {
    return bootId;
  }

  public void start() throws IOException {
    Files.createDirectories(sessionsDir);
    if (!handoff.store().enabled()) {
      log("handoff-off", "-", "-", "no NOTIFY_SOCKET; sessions will not survive a host restart");
    }
    adoptInherited();
    bootId = bootIdFor(!sessions.isEmpty());
    try {
      Files.writeString(sessionsDir.resolve(BOOT_ID_FILE), bootId);
    } catch (IOException e) {
      log("boot-id-unsaved", "-", "-", e.toString());
    }
    sweepOrphanFiles();
    var socketDir = socketPath.getParent();
    if (socketDir != null) {
      Files.createDirectories(socketDir);
      ownerOnly(socketDir);
    }
    Files.deleteIfExists(socketPath);
    mintDispatchCredential();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));
    ownerOnly(socketPath);
    Thread.ofVirtual().name("pty-host-accept").start(this::acceptLoop);
  }

  /**
   * The dispatch credential of the host serving {@code socket}: an owner-only file beside it, so
   * exactly the processes that may open the socket may read it — the box's own API and CLI lanes.
   */
  public static Path dispatchCredentialOf(Path socket) {
    return socket.resolveSibling(DISPATCH_CREDENTIAL_FILE);
  }

  /**
   * Adopts every inherited descriptor whose ring and sidecar are sound. Anything else — a name the
   * host does not know, a ring or sidecar missing or garbled, a descriptor that is no pty master —
   * is closed (hanging its child up, so nothing leaks), dropped from the store, and logged as lost
   * in the handoff. A number below the store's first descriptor is never a master: it is this
   * process's own stdin, stdout or stderr, and closing it would take the host's log — or, under a
   * test runner, its command channel — with it.
   */
  private void adoptInherited() {
    for (var entry : handoff.inheritedFds().entrySet()) {
      var name = entry.getKey();
      var fd = entry.getValue();
      if (fd < SdNotify.FIRST_LISTEN_FD) {
        log("lost in handoff", "-", name, "descriptor " + fd + " is this process's own stdio");
        handoff.store().removeFd(name);
        continue;
      }
      var refused = adopt(name, fd);
      if (refused == null) {
        continue;
      }
      log("lost in handoff", "-", name, refused);
      try {
        Pty.closeFd(fd);
      } catch (IOException e) {
        log("lost in handoff", "-", name, "close failed: " + e.getMessage());
      }
      handoff.store().removeFd(name);
    }
  }

  /** {@code null} once {@code name} is live again under {@code fd}; otherwise why it cannot be. */
  private String adopt(String name, int fd) {
    try {
      NameValidator.requireValidSessionName(name);
    } catch (IllegalArgumentException bad) {
      return bad.getMessage();
    }
    var files = SessionFiles.in(sessionsDir, name);
    SessionMeta meta;
    try {
      if (!Files.isRegularFile(files.ring()) || Files.size(files.ring()) == 0) {
        return "no ring on disk";
      }
      meta = SessionMeta.read(files.meta());
    } catch (IOException e) {
      return e.getMessage();
    }
    if (!meta.origin().name().equals(name)) {
      return "sidecar belongs to session '" + meta.origin().name() + "'";
    }
    Journal journal;
    try {
      journal = RingJournal.open(files.ring(), journalCapacity);
    } catch (IOException e) {
      return e.getMessage();
    }
    Pty pty;
    try {
      pty = Pty.adopt(fd);
    } catch (IOException e) {
      closeQuietly(journal, name);
      return e.getMessage();
    }
    sessions.put(name, PtySession.resume(meta, events, pty, journal, files, handoff.store()));
    log("adopted", meta.origin().ownerFde(), name, "instance " + meta.origin().instanceId());
    return null;
  }

  private static void closeQuietly(Journal journal, String name) {
    try {
      journal.close();
    } catch (IOException e) {
      log("ring-close-failed", "-", name, e.toString());
    }
  }

  /**
   * The predecessor's boot id when this host adopted any of its sessions and the id can be read;
   * otherwise a fresh one. An unreadable file with adopted sessions is logged: the sessions are
   * still live, and a client that reconnects finds them listed under the new id.
   */
  private String bootIdFor(boolean adoptedAny) {
    if (adoptedAny) {
      try {
        var stored = Files.readString(sessionsDir.resolve(BOOT_ID_FILE)).strip();
        if (!stored.isBlank()) {
          return stored;
        }
        log("boot-id-minted", "-", "-", BOOT_ID_FILE + " is empty");
      } catch (IOException e) {
        log("boot-id-minted", "-", "-", BOOT_ID_FILE + " unreadable: " + e.getMessage());
      }
    }
    return Ids.newId().toString();
  }

  /**
   * Deletes the ring, sidecar and exit file of every session this host did not adopt. Whatever is
   * on disk at start and was not handed over is an orphan of a previous run; leaving rings would
   * leak 4 MB per distinct name forever and let a fresh create collide with a stranger's history
   * that no {@link #admitted} check guards, and a stale exit file would lend a future life of the
   * same name a status that was never its own.
   */
  private void sweepOrphanFiles() throws IOException {
    try (var entries = Files.list(sessionsDir)) {
      for (var file : entries.toList()) {
        var owner = sessionNameOf(file.getFileName().toString());
        if (owner != null && !sessions.containsKey(owner)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  private static String sessionNameOf(String fileName) {
    for (var suffix : SESSION_FILE_SUFFIXES) {
      if (fileName.endsWith(suffix)) {
        return fileName.substring(0, fileName.length() - suffix.length());
      }
    }
    return null;
  }

  /**
   * One structured line to the host's journald unit (stderr): the principal, the session, and why.
   * The wire {@code Err} names only what the caller may see; this is where the host records the
   * rest — every attach, every refusal, every last-resort exception — so a silent close leaves a
   * trace.
   */
  private static void log(String event, String principal, String session, String reason) {
    System.err.println(
        "pty-host: "
            + event
            + " principal="
            + principal
            + " session="
            + session
            + " reason="
            + reason);
  }

  private void mintDispatchCredential() throws IOException {
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    var credential = HexFormat.of().formatHex(bytes);
    var path = dispatchCredentialOf(socketPath);
    Files.deleteIfExists(path);
    try {
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException notPosix) {
      Files.createFile(path);
    }
    Files.writeString(path, credential);
    dispatchCredential = credential.getBytes(StandardCharsets.UTF_8);
  }

  private PtyIdentity identify(String token) throws IOException {
    var presented = token.getBytes(StandardCharsets.UTF_8);
    if (presented.length > 0 && MessageDigest.isEqual(presented, dispatchCredential)) {
      return PtyIdentity.DISPATCH;
    }
    return identity.resolve(token);
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
    Admitted me = null;
    var subscriberId = -1L;
    var principal = "?";
    var overCap = connections.incrementAndGet() > limits.connections();
    try (channel) {
      if (overCap) {
        log("refused", principal, "-", "connection cap " + limits.connections());
        return;
      }
      try {
        PtyWire.handshake(channel, channel);
        PtyIdentity who;
        if (PtyWire.read(channel) instanceof PtyMessage.Hello(var token)) {
          try {
            who = identify(token);
          } catch (IOException refused) {
            reply(channel, new PtyMessage.Err(refused.getMessage()));
            return;
          }
          principal = who.fde();
          me = new Admitted(channel, token, principal);
          admitted.add(me);
          reply(channel, new PtyMessage.Welcome(bootId));
        } else {
          reply(channel, new PtyMessage.Err("The first frame must identify you: send Hello."));
          return;
        }
        while (true) {
          var message = PtyWire.read(channel);
          if (who.dispatchAuthority() && !(message instanceof PtyMessage.Yield)) {
            reply(channel, new PtyMessage.Err("The dispatch authority may only yield sessions."));
            continue;
          }
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
                    channel,
                    new PtyMessage.Err("No live session '" + m.session() + "' to attach."));
                break;
              }
              if (!admitted(who, session)) {
                log("refused", principal, m.session(), "not admitted");
                reply(
                    channel,
                    new PtyMessage.Err("Session '" + m.session() + "' is not yours to attach."));
                break;
              }
              // The cap check and the registration it admits are one step, or concurrent attaches
              // all take the last slot.
              synchronized (session) {
                if (session.attachedCount() >= limits.subscribersPerSession()) {
                  log(
                      "refused",
                      principal,
                      m.session(),
                      "subscriber cap " + limits.subscribersPerSession());
                  reply(
                      channel,
                      new PtyMessage.Err(
                          "Session '"
                              + m.session()
                              + "' is at its subscriber cap of "
                              + limits.subscribersPerSession()
                              + "."));
                  break;
                }
                reply(channel, new PtyMessage.Ok());
                subscriberId = session.attach(msg -> reply(channel, msg), m.write(), who.fde());
                attached = session;
              }
              log("attached", principal, m.session(), m.write() ? "write" : "observe");
            }
            case PtyMessage.Input m -> {
              if (attached == null) {
                reply(channel, new PtyMessage.Err("Attach before writing."));
              } else {
                switch (attached.input(subscriberId, m.seq(), m.bytes())) {
                  case NOT_WRITER ->
                      reply(channel, new PtyMessage.Err("You do not hold the write token."));
                  case BACKLOG ->
                      reply(
                          channel,
                          new PtyMessage.Err("Input backlog is full; the session is not reading."));
                  case ACCEPTED -> {}
                }
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
            case PtyMessage.Yield m ->
                reply(
                    channel,
                    who.dispatchAuthority()
                        ? yieldSession(m.session(), m.reason())
                        : new PtyMessage.Err("Yield requires host dispatch authority."));
            default -> reply(channel, new PtyMessage.Err("Unexpected client frame."));
          }
        }
      } catch (RuntimeException crashed) {
        log("exception", principal, "-", crashed.toString());
        reply(channel, new PtyMessage.Err("The host could not serve that request."));
      }
    } catch (IOException disconnected) {
      var unused = disconnected;
    } finally {
      if (attached != null) {
        attached.detach(subscriberId);
      }
      if (me != null) {
        admitted.remove(me);
      }
      connections.decrementAndGet();
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
   * The environment a session's child inherits: what terminal it is in ({@code TERM} stays {@code
   * xterm-256color} because containers ship no other terminfo; {@code COLORTERM}, {@code
   * TERM_PROGRAM} and {@code TERM_PROGRAM_VERSION} say truecolor, Mast, and this host's version so
   * programs stop probing), plus {@code SAIL_ROOM_ID} when the session is room-bound — the one fact
   * that lets everything the child creates ({@code spec create} above all) land in the room the
   * session serves.
   */
  static Map<String, String> childEnv(String room, String version) {
    var env = new java.util.LinkedHashMap<String, String>();
    env.put("TERM", "xterm-256color");
    env.put("COLORTERM", "truecolor");
    env.put("TERM_PROGRAM", "mast");
    env.put("TERM_PROGRAM_VERSION", version);
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

  private static final PtyMessage.Err SHUTTING_DOWN =
      new PtyMessage.Err("The pty host is shutting down; reconnect and try again.");

  /**
   * Serialized: the per-FDE quota check and the registration of the session it admits must be one
   * step, or concurrent creates each see the same spare slot and all spawn. Refused once shutdown
   * has begun — a handoff has snapshotted the sessions, and a create landing after it would replace
   * a handed-off session's ring and sidecar under the successor's feet.
   */
  private synchronized PtyMessage create(PtyMessage.Create m, PtyIdentity who) {
    if (closed) {
      return SHUTTING_DOWN;
    }
    Path cwd;
    try {
      NameValidator.requireValidSessionName(m.session());
      if (m.project() != null && !m.project().isBlank()) {
        NameValidator.requireValidProjectName(m.project());
      }
      requireValidGeometry(m.cols(), m.rows());
      cwd = requireValidCwd(m.cwd());
    } catch (IllegalArgumentException bad) {
      log("refused", who.fde(), m.session(), bad.getMessage());
      return new PtyMessage.Err(bad.getMessage());
    }
    var existing = sessions.get(m.session());
    if (existing != null && !admitted(who, existing)) {
      log("refused", who.fde(), m.session(), "not admitted");
      return new PtyMessage.Err("Session '" + m.session() + "' is not yours.");
    }
    if (existing != null && existing.live()) {
      return new PtyMessage.Err(
          "Session '" + m.session() + "' is already running; attach or kill it.");
    }
    var replacesOwn = existing != null && existing.ownerFde().equals(who.fde());
    if (!replacesOwn && ownedBy(who.fde()) >= limits.sessionsPerFde()) {
      log("refused", who.fde(), m.session(), "session cap " + limits.sessionsPerFde());
      return new PtyMessage.Err(
          "You are at your session cap of " + limits.sessionsPerFde() + "; kill one first.");
    }
    if (existing == null && sessions.size() >= limits.sessions()) {
      log("refused", who.fde(), m.session(), "host session cap " + limits.sessions());
      return new PtyMessage.Err(
          "The host is at its session cap of " + limits.sessions() + "; kill one first.");
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
    var files = SessionFiles.in(sessionsDir, m.session());
    try {
      files.deleteAll();
      var origin =
          new PtySession.Origin(
              m.session(),
              Ids.newId().toString(),
              who.fde(),
              m.project(),
              room,
              requestedOrShell(m.command()));
      var env = childEnv(room, version);
      var argv = new ArrayList<>(handoff.shim().apply(files.exit()));
      argv.addAll(childCommand(origin, env));
      var session =
          PtySession.start(
              origin,
              events,
              argv,
              env,
              cwd,
              files,
              journalCapacity,
              m.cols(),
              m.rows(),
              version,
              handoff.store());
      sessions.put(m.session(), session);
      return new PtyMessage.Ok();
    } catch (IOException e) {
      deleteFiles(files, who.fde(), m.session());
      return new PtyMessage.Err("Could not start session '" + m.session() + "': " + e.getMessage());
    }
  }

  private static void requireValidGeometry(int cols, int rows) {
    if (cols < 1 || cols > 65535 || rows < 1 || rows > 65535) {
      throw new IllegalArgumentException(
          "Invalid terminal size "
              + cols
              + "x"
              + rows
              + "; each of cols and rows must be 1..65535.");
    }
  }

  private static Path requireValidCwd(String cwd) {
    try {
      return Path.of(java.util.Objects.requireNonNullElse(cwd, ""));
    } catch (java.nio.file.InvalidPathException bad) {
      throw new IllegalArgumentException("Invalid working directory: " + bad.getMessage());
    }
  }

  private long ownedBy(String fde) {
    return sessions.values().stream().filter(s -> s.ownerFde().equals(fde)).count();
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
        session.origin().instanceId(),
        session.live(),
        session.attachedCount(),
        session.writerFde(),
        session.origin().room(),
        session.origin().command());
  }

  private synchronized PtyMessage kill(String name, PtyIdentity who) {
    if (closed) {
      return SHUTTING_DOWN;
    }
    var session = sessions.get(name);
    if (session == null) {
      return new PtyMessage.Err("No session '" + name + "'.");
    }
    if (!admitted(who, session)) {
      log("refused", who.fde(), name, "not admitted");
      return new PtyMessage.Err("Session '" + name + "' is not yours.");
    }
    remove(name, session);
    return new PtyMessage.Ok();
  }

  /**
   * Ends a live session that a reservation displaced — the reason lands in the stream and on the
   * ended event. Idempotent: a session that is not live has nothing to end, so the answer is {@code
   * Ok}. Ownership is not consulted: the dispatch authority ends what the claim displaced,
   * whichever FDE opened it. The ring goes with the session, as it does on a kill.
   */
  private synchronized PtyMessage yieldSession(String name, String reason) {
    if (closed) {
      return SHUTTING_DOWN;
    }
    var session = sessions.get(name);
    if (session == null || !session.live()) {
      return new PtyMessage.Ok();
    }
    sessions.remove(name, session);
    session.end(reason);
    deleteFiles(SessionFiles.in(sessionsDir, name), session.ownerFde(), name);
    return new PtyMessage.Ok();
  }

  private void remove(String name, PtySession session) {
    sessions.remove(name, session);
    session.close();
    deleteFiles(SessionFiles.in(sessionsDir, name), session.ownerFde(), name);
  }

  private void deleteFiles(SessionFiles files, String fde, String name) {
    try {
      files.deleteAll();
    } catch (IOException e) {
      log("ring-delete-failed", fde, name, e.toString());
    }
  }

  /**
   * One reaping pass at {@code nowNanos}: revoked credentials severed, then the mosh grace and
   * retention rules, nothing else. Nothing once shutdown has begun: the sessions belong to the
   * successor or are ending.
   */
  public synchronized void sweep(long nowNanos) {
    if (closed) {
      return;
    }
    severRevoked();
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

  /**
   * Closes every connection whose credential the resolver now refuses; its serve loop fails on the
   * next read and drops the attachment. Each distinct token is resolved once per pass. A resolver
   * that cannot answer at all (the store is down) severs nothing — that is an outage, not a
   * revocation.
   */
  private void severRevoked() {
    var verdicts = new HashMap<String, Boolean>();
    for (var connection : admitted) {
      if (verdicts.computeIfAbsent(connection.token(), this::revoked)) {
        log("severed", connection.principal(), "-", "credential revoked");
        try {
          connection.channel().close();
        } catch (IOException ignored) {
          var unused = ignored;
        }
      }
    }
  }

  private boolean revoked(String token) {
    try {
      identify(token);
      return false;
    } catch (IOException refused) {
      return true;
    } catch (RuntimeException unavailable) {
      log("revalidation-skipped", "-", "-", unavailable.toString());
      return false;
    }
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

  /**
   * Steps aside for a successor: stops serving, then hands every live session off without ending it
   * — its master stays open for the store's duplicate to be handed on, its ring and sidecar stay on
   * disk. Returns the masters by session name, which is what the successor will be told as {@code
   * LISTEN_FDNAMES}. Corpses are released here; the successor sweeps their files. Serialized with
   * every operation that removes a session or its files, and closing the host first, so a request
   * already in flight on an open connection either completes before the snapshot or is refused.
   */
  public synchronized Map<String, Integer> handoff() {
    closeServer();
    var masters = new LinkedHashMap<String, Integer>();
    sessions.forEach(
        (name, session) -> {
          var fd = session.handoff();
          if (fd >= 0) {
            masters.put(name, fd);
          }
        });
    sessions.clear();
    return masters;
  }

  /**
   * Stops for good: every live session ends with {@code reason} in its stream and on its ending,
   * all of them at once, so the last one is told within the same seconds as the first. This is the
   * loud path a {@code systemctl stop} takes; a restart takes {@link #handoff()}.
   */
  public synchronized void stop(String reason) {
    closeServer();
    var endings =
        sessions.values().stream()
            .map(session -> Thread.ofVirtual().start(() -> session.end(reason)))
            .toList();
    for (var ending : endings) {
      try {
        ending.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    sessions.clear();
  }

  @Override
  public synchronized void close() {
    closeServer();
    sessions.forEach((name, session) -> session.close());
    sessions.clear();
  }

  private void closeServer() {
    closed = true;
    try {
      if (server != null) {
        server.close();
      }
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(dispatchCredentialOf(socketPath));
    } catch (IOException e) {
      throw new IllegalStateException("pty host close failed", e);
    }
  }
}
