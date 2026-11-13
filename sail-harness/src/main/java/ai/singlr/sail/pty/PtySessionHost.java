/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.NameValidator;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The per-container session host: owns every {@link PtySession}, serves the {@link PtyWire}
 * protocol on a unix socket, and reaps what nobody wants — a session never attached within the
 * grace window (the mosh rule), and an ended session once its corpse retention passes. Each
 * connection is one attached client at most; commands answer {@code Ok}/{@code Err}; session
 * traffic flows through the subscriber queue onto the same channel, serialized per connection.
 *
 * <p>Reaping is driven by {@link #sweep(long)} with an injected clock — the production timer thread
 * merely calls it; tests call it directly with synthetic time. The same pass resolves every
 * admitted connection's credential again: a token the resolver no longer honors (a revoked FDE
 * session), or one that now stands for a principal other than the one it admitted (a demotion, a
 * changed scope), severs the connections it admitted, so a change of access ends live attachments
 * within one sweep rather than at the next reconnect.
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
 *
 * <p>A host given a TCP address ({@link Network}) also listens there ({@link PtyTcpDoor}), for
 * clients that reach the box over a private network with no SSH. A TCP connection serves nothing
 * until it presents a {@link PtyMessage.Ticket} as its {@code Hello} token: one this host issued on
 * its unix socket ({@link PtyMessage.IssueTicket}) for the credential the request carried, which it
 * resolved itself, deciding it as it decides that open or attach. The ticket's credential is
 * resolved again when it is presented and at every sweep after, as a unix connection's token is,
 * and the connection may take the ticket's one action on its one target and nothing else.
 *
 * <p>Opening a terminal is one decision wherever it is asked ({@link #openRefusal}): the caller
 * operates this box, as attaching needs operating the opener's, and holds the project.
 */
public final class PtySessionHost implements AutoCloseable {

  static final Duration NEVER_ATTACHED_GRACE = Duration.ofSeconds(60);
  static final Duration CORPSE_RETENTION = Duration.ofMinutes(10);
  static final String DISPATCH_CREDENTIAL_FILE = "pty-dispatch.token";
  static final String BOOT_ID_FILE = "host.boot";
  static final String LISTENING_FILE = "pty.listening";
  public static final Duration UNTICKETED_DEADLINE = PtyTcpDoor.UNTICKETED_DEADLINE;

  /**
   * What a request hears when the host could not serve it, the store behind its access checks
   * included: nothing was opened or changed, and asking again is the fix.
   */
  static final String UNSERVED =
      "The host could not serve that request. Nothing was opened or changed; try again, and if it"
          + " keeps failing, check the pty host's log.";

  private static final List<String> SESSION_FILE_SUFFIXES =
      List.of(
          SessionFiles.META_SUFFIX + ".tmp",
          SessionFiles.RING_SUFFIX,
          SessionFiles.META_SUFFIX,
          SessionFiles.EXIT_SUFFIX);

  /**
   * How this host outlives itself: the store it pushes masters to, the descriptors the previous
   * host left it (by store name), and the shim every child is spawned under — the {@code
   * _pty-child} wrapper that makes the child's session and records the exit status a successor
   * cannot collect. {@link #NONE} is a host on its own: no store, no inheritance, children spawned
   * under the shim run from this class path.
   */
  public record Handoff(
      SdNotify store, Map<String, Integer> inheritedFds, Function<Path, List<String>> shim) {
    public static final Handoff NONE =
        new Handoff(SdNotify.NONE, Map.of(), PtyChildShim::onClassPath);

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

  /**
   * Where the host listens beside its unix socket, if anywhere, the clock its tickets and its
   * deadline for connections that present none run on, who resolves a ticket's credential, and what
   * a ticket request hears when it listens nowhere else ({@code closed}). {@link #NONE} is a box
   * that listens nowhere else.
   */
  public record Network(
      Optional<InetSocketAddress> tcp,
      LongSupplier clock,
      PtyIdentity.Holders holders,
      Remedy closed) {
    public static final Network NONE =
        closed(
            Remedy.of(
                "This host listens for no terminal over the network. Name an address with '",
                Remedy.sail("host config set terminal-listen", "<address>:<port>"),
                "' and restart the sail-pty-host service."));

    /** Listening nowhere else, a ticket request hearing {@code why}. */
    public static Network closed(Remedy why) {
      return new Network(Optional.empty(), System::nanoTime, PtyIdentity.Holders.NONE, why);
    }

    /**
     * Listening on {@code tcp} too, on the system's clock, its tickets' credentials resolved by
     * {@code holders}.
     */
    public static Network on(InetSocketAddress tcp, PtyIdentity.Holders holders) {
      return listening(tcp, System::nanoTime, holders);
    }

    /**
     * Listening on {@code tcp} too, on {@code clock}, its tickets' credentials resolved by {@code
     * holders}.
     */
    public static Network listening(
        InetSocketAddress tcp, LongSupplier clock, PtyIdentity.Holders holders) {
      return new Network(Optional.of(tcp), clock, holders, NONE.closed());
    }
  }

  private final Path socketPath;
  private final Path sessionsDir;
  private final long journalCapacity;
  private final PtyIdentity.Resolver identity;
  private final PtyAdmission admission;
  private final PtyEvents events;
  private final String version;
  private final Limits limits;
  private final Handoff handoff;
  private final Network network;
  private final PtyTickets tickets;
  private final Optional<PtyTcpDoor> door;
  private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
  private final Set<Admitted> admitted = ConcurrentHashMap.newKeySet();
  private volatile String bootId = "";
  private final AtomicInteger connections = new AtomicInteger();
  private volatile ServerSocketChannel server;
  private volatile byte[] dispatchCredential = new byte[0];
  private volatile boolean closed;

  private record Admitted(SocketChannel channel, Credential credential, PtyIdentity who) {}

  /**
   * What a connection's principal is resolved again from at every sweep: the token its unix {@code
   * Hello} presented, or the hash of the credential its ticket was issued for.
   */
  sealed interface Credential {
    record Hello(String token) implements Credential {}

    record Holder(String hash) implements Credential {}
  }

  /** A connection that proved who it is: what to resolve again, whom, and what it may do. */
  record Admission(Credential credential, PtyIdentity who, Bound bound) {}

  /**
   * Why a request is refused: its words, what kind of refusal a ticket request hears it as, and
   * whether a ticket request hears it only as a session it may not attach, since the network must
   * not tell a session that exists but is not the caller's from one that does not.
   */
  record Refusal(PtyMessage.TicketRefusal kind, PtyMessage.Err err, boolean concealed) {

    /** This refusal, heard by a ticket request only as a session it may not attach. */
    Refusal concealing() {
      return new Refusal(kind, err, true);
    }

    /** What a ticket request for {@code target} hears. */
    PtyMessage.TicketRefused ticketAnswer(String target) {
      return concealed
          ? new PtyMessage.TicketRefused(
              PtyMessage.TicketRefusal.NOT_FOUND,
              "There is no session '" + target + "' on this box that you may attach.")
          : new PtyMessage.TicketRefused(kind, err.detail());
    }
  }

  public PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyAdmission admission,
      PtyEvents events,
      String version) {
    this(
        socketPath,
        sessionsDir,
        journalCapacity,
        identity,
        admission,
        events,
        version,
        Limits.DEFAULTS,
        Handoff.NONE,
        Network.NONE);
  }

  public PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyAdmission admission,
      PtyEvents events,
      String version,
      Handoff handoff,
      Network network) {
    this(
        socketPath,
        sessionsDir,
        journalCapacity,
        identity,
        admission,
        events,
        version,
        Limits.DEFAULTS,
        handoff,
        network);
  }

  PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyAdmission admission,
      PtyEvents events,
      String version,
      Limits limits) {
    this(
        socketPath,
        sessionsDir,
        journalCapacity,
        identity,
        admission,
        events,
        version,
        limits,
        Handoff.NONE,
        Network.NONE);
  }

  PtySessionHost(
      Path socketPath,
      Path sessionsDir,
      long journalCapacity,
      PtyIdentity.Resolver identity,
      PtyAdmission admission,
      PtyEvents events,
      String version,
      Limits limits,
      Handoff handoff,
      Network network) {
    this.socketPath = socketPath;
    this.sessionsDir = sessionsDir;
    this.journalCapacity = journalCapacity;
    this.identity = identity;
    this.admission = admission;
    this.events = events;
    this.version = version;
    this.limits = limits;
    this.handoff = handoff;
    this.network = network;
    this.tickets = new PtyTickets(limits.connections());
    this.door =
        network
            .tcp()
            .map(
                address ->
                    new PtyTcpDoor(
                        address,
                        limits.connections(),
                        network.clock(),
                        tickets,
                        network.holders(),
                        listeningOf(socketPath),
                        this::converse));
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
    tick(network.clock().getAsLong());
  }

  /**
   * One pass of the TCP door at {@code nowNanos}: it binds its address once it can, and closes the
   * connections that presented no ticket in time ({@link PtyTcpDoor#tick}). The production timer
   * drives it each second; tests drive it with synthetic time. Nothing on a host with no door.
   */
  public void tick(long nowNanos) {
    door.ifPresent(tcp -> tcp.tick(nowNanos));
  }

  /** The address the host listens on over TCP, once its door is bound. */
  public Optional<InetSocketAddress> tcpAddress() throws IOException {
    return door.isPresent() ? door.get().address() : Optional.empty();
  }

  /**
   * The dispatch credential of the host serving {@code socket}: an owner-only file beside it, so
   * exactly the processes that may open the socket may read it — the box's own API and CLI lanes.
   */
  public static Path dispatchCredentialOf(Path socket) {
    return socket.resolveSibling(DISPATCH_CREDENTIAL_FILE);
  }

  /**
   * Where the host serving {@code socket} says what its TCP door is bound to, while it is: the one
   * account of what the running host listens on, which {@code sail host status} reads.
   */
  public static Path listeningOf(Path socket) {
    return socket.resolveSibling(LISTENING_FILE);
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
  static void log(String event, String principal, String session, String reason) {
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
   * blank-token connection resolves to the box owner — so no group or other principal may reach it,
   * and a host that cannot make it so does not start.
   */
  private static void ownerOnly(Path path) throws IOException {
    Files.setPosixFilePermissions(
        path,
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        var channel = server.accept();
        Thread.ofVirtual().name("pty-host-conn").start(() -> serveLocal(channel));
      } catch (IOException e) {
        if (!closed) {
          throw new IllegalStateException("pty host accept failed", e);
        }
        return;
      }
    }
  }

  /**
   * Serves one unix connection from accept to its end, counted against the unix socket's own cap,
   * whose slot it releases whatever happens to it, and before it closes the channel: a client that
   * sees its connection end may connect again at once, and finds the slot free.
   */
  private void serveLocal(SocketChannel channel) {
    var overCap = connections.incrementAndGet() > limits.connections();
    try (channel) {
      try {
        if (overCap) {
          log("refused", "?", "-", "connection cap " + limits.connections());
          return;
        }
        serveAdmitted(channel);
      } finally {
        connections.decrementAndGet();
      }
    } catch (IOException disconnected) {
      var unused = disconnected;
    }
  }

  private void serveAdmitted(SocketChannel channel) throws IOException {
    Admission admission;
    try {
      admission = admitLocal(channel);
    } catch (RuntimeException crashed) {
      log("exception", "?", "-", crashed.toString());
      reply(channel, new PtyMessage.Err(UNSERVED));
      return;
    }
    if (admission != null) {
      converse(channel, admission);
    }
  }

  /**
   * The unix door: a {@code Hello} whose token the resolver honors, a blank one being the box's own
   * person, admitted for everything its person may do.
   */
  private Admission admitLocal(SocketChannel channel) throws IOException {
    PtyWire.handshake(channel, channel);
    if (!(PtyWire.read(channel) instanceof PtyMessage.Hello(var token))) {
      reply(channel, new PtyMessage.Err("The first frame must identify you: send Hello."));
      return null;
    }
    try {
      return new Admission(new Credential.Hello(token), identify(token), Bound.NOTHING);
    } catch (IOException refused) {
      reply(channel, new PtyMessage.Err(Remedied.whole(refused)));
      return null;
    }
  }

  /**
   * Serves an admitted connection, from either door, until it ends: welcomes it, then answers each
   * frame within what it may do, and at its end releases its attachment and its place in the sweep.
   * The door that accepted it closes it and releases its slot.
   */
  void converse(SocketChannel channel, Admission admission) {
    PtySession attached = null;
    var subscriberId = -1L;
    var typedIn = Long.MIN_VALUE;
    var who = admission.who();
    var bound = admission.bound();
    var principal = who.fde();
    String opened = null;
    var me = new Admitted(channel, admission.credential(), who);
    admitted.add(me);
    try {
      try {
        reply(channel, new PtyMessage.Welcome(bootId));
        while (true) {
          var message = PtyWire.read(channel);
          var uncovered = bound.refusal(message, opened);
          if (uncovered != null) {
            log("refused", principal, "-", "outside its ticket");
            reply(channel, uncovered);
            return;
          }
          if (who instanceof PtyIdentity.Dispatch
              && !(message instanceof PtyMessage.Yield)
              && !(message instanceof PtyMessage.ListSessions)) {
            reply(
                channel,
                new PtyMessage.Err("The dispatch authority may only yield and list sessions."));
            continue;
          }
          switch (message) {
            case PtyMessage.Create m when who instanceof PtyIdentity.Person person -> {
              var answer = create(m, person);
              if (answer instanceof PtyMessage.Ok) {
                opened = m.session();
              }
              reply(channel, answer);
            }
            case PtyMessage.IssueTicket m when who instanceof PtyIdentity.Person ->
                reply(
                    channel,
                    admission.credential() instanceof Credential.Hello(var token)
                            && Strings.isBlank(token)
                        ? issueTicket(m)
                        : ASKED_ELSEWHERE);
            case PtyMessage.Attach m when who instanceof PtyIdentity.Person person -> {
              if (attached != null) {
                reply(channel, new PtyMessage.Err("This connection is already attached."));
                break;
              }
              var session = sessions.get(m.session());
              var refused = attachRefusal(m.session(), session, person);
              if (refused != null) {
                reply(channel, refused.err());
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
                  case ACCEPTED -> typedIn = typed(attached, who, typedIn);
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
                    who instanceof PtyIdentity.Dispatch
                        ? yieldSession(m.session(), m.reason())
                        : new PtyMessage.Err("Yield requires host dispatch authority."));
            default -> reply(channel, new PtyMessage.Err("Unexpected client frame."));
          }
        }
      } catch (RuntimeException crashed) {
        log("exception", principal, "-", crashed.toString());
        reply(channel, new PtyMessage.Err(UNSERVED));
      }
    } catch (IOException disconnected) {
      var unused = disconnected;
    } finally {
      if (attached != null) {
        attached.detach(subscriberId);
      }
      admitted.remove(me);
    }
  }

  /**
   * Why {@code who} may not attach to {@code session}, found under {@code name}, or null when it
   * may: the session must be live, {@code who} must operate the box of the person who opened it
   * ({@link #admitted}), and hold the project it runs in. One decision, asked by an attach and by a
   * ticket to attach, which hears each refusal only as a session it may not attach.
   */
  private Refusal attachRefusal(String name, PtySession session, PtyIdentity.Person who) {
    if (session == null || !session.live()) {
      return new Refusal(
          PtyMessage.TicketRefusal.NOT_FOUND,
          new PtyMessage.Err("No live session '" + name + "' to attach."),
          true);
    }
    if (!admitted(who, session)) {
      log("refused", who.fde(), name, "not admitted");
      return new Refusal(
          PtyMessage.TicketRefusal.FORBIDDEN,
          new PtyMessage.Err("Session '" + name + "' is not yours to attach."),
          true);
    }
    var unheld = projectRefusal(session.origin().project(), who);
    if (unheld == null) {
      return null;
    }
    log("refused", who.fde(), name, "project not held");
    return unheld.concealing();
  }

  /**
   * Why {@code who} may not open a terminal in {@code project} on this box, or null when it may:
   * the one open decision, asked by a create and by a ticket to open alike. The caller must operate
   * this box ({@link PtyAdmission#box}), as attaching needs operating the opener's, and hold the
   * project when the session names one ({@link #projectRefusal}).
   */
  private Refusal openRefusal(String project, PtyIdentity.Person who) {
    try {
      admission.box(who);
    } catch (IOException refused) {
      log("refused", who.fde(), project, "not this box's person");
      return new Refusal(
          PtyMessage.TicketRefusal.FORBIDDEN, new PtyMessage.Err(Remedied.whole(refused)), false);
    } catch (RuntimeException unavailable) {
      log("box-check-failed", who.fde(), project, unavailable.toString());
      return new Refusal(PtyMessage.TicketRefusal.UNAVAILABLE, new PtyMessage.Err(UNSERVED), false);
    }
    return projectRefusal(project, who);
  }

  /**
   * A ticket for the holder of the credential {@code request} carries, to take its action on its
   * target over TCP. The host resolves the credential itself ({@link Network#holders}), for this
   * decision alone, and decides it as that open ({@link #openRefusal}, a project being required:
   * the network opens no shell on the host) or that attach ({@link #attachRefusal}) is. Refused on
   * a host with no TCP door or one not yet listening, and for a request that carries no credential:
   * the box's own person, a blank token, exists only on the unix socket.
   */
  private PtyMessage issueTicket(PtyMessage.IssueTicket request) {
    if (door.isEmpty()) {
      return new PtyMessage.TicketRefused(PtyMessage.TicketRefusal.UNAVAILABLE, network.closed());
    }
    if (!door.get().listening()) {
      return new PtyMessage.TicketRefused(
          PtyMessage.TicketRefusal.UNAVAILABLE,
          "The terminal's private address ('terminal-listen') is not listening yet; ask again"
              + " once the private network is up.");
    }
    if (Strings.isBlank(request.credential())) {
      return new PtyMessage.TicketRefused(
          PtyMessage.TicketRefusal.FORBIDDEN,
          "A ticket is issued for the credential that asks for it, and this request carries none:"
              + " the box's own person exists only on the unix socket.");
    }
    var target = request.target();
    try {
      switch (request.action()) {
        case OPEN -> NameValidator.requireValidProjectName(target);
        case ATTACH -> NameValidator.requireValidSessionName(target);
      }
    } catch (IllegalArgumentException bad) {
      return new PtyMessage.TicketRefused(PtyMessage.TicketRefusal.INVALID, Remedied.whole(bad));
    }
    var holder = Secrets.hash(request.credential());
    PtyIdentity.Person who;
    try {
      who = network.holders().holder(holder);
    } catch (IOException refused) {
      return new PtyMessage.TicketRefused(
          PtyMessage.TicketRefusal.FORBIDDEN, Remedied.whole(refused));
    } catch (RuntimeException unavailable) {
      log("holder-check-failed", "?", target, unavailable.toString());
      return new PtyMessage.TicketRefused(PtyMessage.TicketRefusal.UNAVAILABLE, UNSERVED);
    }
    var refused =
        switch (request.action()) {
          case OPEN -> openRefusal(target, who);
          case ATTACH -> attachRefusal(target, sessions.get(target), who);
        };
    if (refused != null) {
      return refused.ticketAnswer(target);
    }
    try {
      return new PtyMessage.Ticket(
          tickets.issue(
              new PtyTickets.Ticket(request.action(), target, holder),
              network.clock().getAsLong()));
    } catch (IOException full) {
      return new PtyMessage.TicketRefused(PtyMessage.TicketRefusal.LIMITED, Remedied.whole(full));
    }
  }

  /**
   * What a connection may do beyond what its person may: everything on the unix socket ({@link
   * #NOTHING}), and on TCP what its ticket admits ({@link PtyTickets.Ticket#refusal}). Asked of
   * every frame before it is served, with the session the connection opened, if any.
   */
  @FunctionalInterface
  interface Bound {
    Bound NOTHING = (message, opened) -> null;

    /** Why {@code message} lies outside what this connection may do, or null when it does not. */
    PtyMessage.Err refusal(PtyMessage message, String opened);
  }

  /**
   * Tells the events of {@code session} that {@code who} typed in it, once in each minute: a
   * keystroke whose minute is {@code toldIn}, the last one told, costs a clock read and a compare,
   * and the one write a minute runs on its own thread, so input never waits on it. Returns the
   * minute last told. A session bound to no room tells nothing, and nor does a writer who is no
   * person on a person's own lane ({@link Access#attendee}).
   */
  private long typed(PtySession session, PtyIdentity who, long toldIn) {
    var minute = DateTimeUtils.now().getEpochSecond() / 60;
    if (minute == toldIn
        || !session.origin().roomBound()
        || !(who instanceof PtyIdentity.Person(var writer))) {
      return toldIn;
    }
    var person = Access.attendee(writer);
    if (person.isEmpty()) {
      return minute;
    }
    var origin = session.origin();
    Thread.ofVirtual()
        .name("pty-typed-" + origin.name())
        .start(
            () -> {
              try {
                events.typed(origin, person.get());
              } catch (RuntimeException failed) {
                log("typed-not-recorded", who.fde(), origin.name(), failed.toString());
              }
            });
    return minute;
  }

  /**
   * Whether {@code who} may attach to, replace or kill {@code session}: operating the box of the
   * person who opened it, which that person and an admin may.
   */
  private static boolean admitted(PtyIdentity who, PtySession session) {
    return who instanceof PtyIdentity.Person(var actor)
        && Access.decide(actor, Action.OPERATE_BOX, Resources.box(session.ownerFde()))
            instanceof Decision.Allowed;
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

  /**
   * What a ticket request hears on a connection that presented a token: tickets are asked by this
   * box's own process alone, on a connection that presented none, for the credential the request
   * carries.
   */
  private static final PtyMessage.TicketRefused ASKED_ELSEWHERE =
      new PtyMessage.TicketRefused(
          PtyMessage.TicketRefusal.FORBIDDEN,
          "A ticket is asked by this box's own process alone, on a connection that presented no"
              + " token.");

  private static final PtyMessage.Err SHUTTING_DOWN =
      new PtyMessage.Err("The pty host is shutting down; reconnect and try again.");

  /**
   * Admits the open {@code m} asks for, outside the host's lock: its names and size, the open
   * decision ({@link #openRefusal}), and then, once, the instance its project would run in ({@link
   * PtyAdmission#instance}), which may wait on Incus. Refused once shutdown has begun.
   */
  private PtyMessage create(PtyMessage.Create m, PtyIdentity.Person who) {
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
      return new PtyMessage.Err(Remedied.whole(bad));
    }
    var unopened = openRefusal(m.project(), who);
    if (unopened != null) {
      log("refused", who.fde(), m.session(), "not opened");
      return unopened.err();
    }
    if (m.project() != null && !m.project().isBlank()) {
      try {
        admission.instance(m.project());
      } catch (IOException refused) {
        log("refused", who.fde(), m.session(), "not the project's instance");
        return new PtyMessage.Err(Remedied.whole(refused));
      } catch (RuntimeException unavailable) {
        log("instance-check-failed", who.fde(), m.project(), unavailable.toString());
        return new PtyMessage.Err(UNSERVED);
      }
    }
    return create(m, who, cwd);
  }

  /**
   * Serialized: the per-FDE quota check and the registration of the session it admits must be one
   * step, or concurrent creates each see the same spare slot and all spawn. Refused once shutdown
   * has begun — a handoff has snapshotted the sessions, and a create landing after it would replace
   * a handed-off session's ring and sidecar under the successor's feet.
   */
  private synchronized PtyMessage create(PtyMessage.Create m, PtyIdentity.Person who, Path cwd) {
    if (closed) {
      return SHUTTING_DOWN;
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
        admission.room(room, m.project(), who);
      } catch (IllegalArgumentException | IOException refused) {
        return new PtyMessage.Err(
            Remedy.of("Refusing room for session '" + m.session() + "': ")
                .then(Remedied.whole(refused)));
      }
    }
    PtyAdmission.Folder folder;
    try {
      folder = admission.folder(cwd, who);
    } catch (IOException refused) {
      log("refused", who.fde(), m.session(), "folder");
      return new PtyMessage.Err(
          Remedy.of("Refusing folder for session '" + m.session() + "': ")
              .then(Remedied.whole(refused)));
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
              folder.command(m.command()));
      var env = childEnv(room, version);
      env.putAll(folder.env());
      var argv = new ArrayList<>(handoff.shim().apply(files.exit()));
      argv.addAll(childCommand(origin, env));
      var session =
          PtySession.start(
              origin,
              events,
              argv,
              env,
              folder.path(),
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
      return new PtyMessage.Err(
          Remedy.of("Could not start session '" + m.session() + "': ").then(Remedied.whole(e)));
    }
  }

  /**
   * Why {@code who} may not open a terminal in {@code project}, or null when it may or the session
   * names none: a project must be one the caller holds on this box ({@link PtyAdmission#project}).
   * A store that cannot answer opens nothing and keeps the connection, said as {@link #UNSERVED}.
   */
  private Refusal projectRefusal(String project, PtyIdentity.Person who) {
    if (project == null || project.isBlank()) {
      return null;
    }
    try {
      admission.project(project, who);
      return null;
    } catch (IOException refused) {
      return new Refusal(
          PtyMessage.TicketRefusal.NOT_FOUND, new PtyMessage.Err(Remedied.whole(refused)), false);
    } catch (RuntimeException unavailable) {
      log("project-check-failed", who.fde(), project, unavailable.toString());
      return new Refusal(PtyMessage.TicketRefusal.UNAVAILABLE, new PtyMessage.Err(UNSERVED), false);
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
   * One name-ordered page of the caller's sessions, or of every session for the dispatch authority:
   * a live member must not start over a conversation a terminal holds, whoever opened it. A page is
   * bounded by {@link PtyMessage#PAGE_LIMIT} entries of at most {@link
   * PtyMessage#MAX_COMMAND_BYTES} of encoded command each (names are file names, so a filesystem
   * bounds them), which keeps every page well under the wire's frame cap no matter how many
   * sessions the host holds.
   */
  private PtyMessage listSessions(PtyIdentity who, PtyMessage.ListSessions request) {
    var after = java.util.Objects.toString(request.after(), "");
    var limit = Math.clamp(request.limit(), 1, PtyMessage.PAGE_LIMIT);
    var mine =
        sessions.values().stream()
            .filter(session -> who instanceof PtyIdentity.Dispatch || admitted(who, session))
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
   * One reaping pass at {@code nowNanos}: connections whose access changed severed, then the mosh
   * grace and retention rules, nothing else. Nothing once shutdown has begun: the sessions belong
   * to the successor or are ending.
   */
  public synchronized void sweep(long nowNanos) {
    if (closed) {
      return;
    }
    severChanged();
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
   * Closes every connection whose credential the resolver now refuses, or now resolves to a
   * principal other than the one it admitted — another role, another scope — so a demotion or a
   * scope change reaches an open connection as a revocation does; its serve loop fails on the next
   * read and drops the attachment, and a reconnect is admitted as the principal the credential now
   * stands for. Each distinct token is resolved once per pass. A resolver that cannot answer at all
   * (the store is down) severs nothing — that is an outage, not a change of access.
   */
  private void severChanged() {
    var answers = new HashMap<Credential, Answer>();
    for (var connection : admitted) {
      var reason =
          answers.computeIfAbsent(connection.credential(), this::answer).severs(connection);
      if (reason != null) {
        log("severed", connection.who().fde(), "-", reason);
        try {
          connection.channel().close();
        } catch (IOException ignored) {
          var unused = ignored;
        }
      }
    }
  }

  /**
   * What a credential stands for at a sweep: the principal it resolves to now, or {@code refused}
   * when the resolver no longer honors it; neither when the resolver could not answer.
   */
  private record Answer(PtyIdentity who, boolean refused) {
    static final Answer UNANSWERED = new Answer(null, false);

    /** Why {@code connection} is severed on this answer, or null when it stays. */
    String severs(Admitted connection) {
      if (refused) {
        return "credential revoked";
      }
      return who == null || who.equals(connection.who()) ? null : "access changed";
    }
  }

  private Answer answer(Credential credential) {
    try {
      return new Answer(
          switch (credential) {
            case Credential.Hello(var token) -> identify(token);
            case Credential.Holder(var hash) -> network.holders().holder(hash);
          },
          false);
    } catch (IOException refused) {
      return new Answer(null, true);
    } catch (RuntimeException unavailable) {
      log("revalidation-skipped", "-", "-", unavailable.toString());
      return Answer.UNANSWERED;
    }
  }

  static void reply(SocketChannel channel, PtyMessage message) {
    try {
      synchronized (channel.blockingLock()) {
        PtyWire.write(channel, message);
      }
    } catch (IOException e) {
      closeQuietly(channel);
    }
  }

  static void closeQuietly(SocketChannel channel) {
    try {
      channel.close();
    } catch (IOException ignored) {
      var unused = ignored;
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
      if (door.isPresent()) {
        door.get().close();
      }
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(dispatchCredentialOf(socketPath));
    } catch (IOException e) {
      throw new IllegalStateException("pty host close failed", e);
    }
  }
}
