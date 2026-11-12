/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.SqliteException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class PtySessionHostTest {

  @TempDir Path dir;

  private PtySessionHost host;
  private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
  private volatile CountDownLatch startGate;
  private final CountDownLatch startGated = new CountDownLatch(1);

  private final Set<String> revoked = ConcurrentHashMap.newKeySet();

  private final Map<String, PtyIdentity.Person> reresolved = new ConcurrentHashMap<>();
  private volatile boolean storeDown;
  private PtySessionHost.Network network = PtySessionHost.Network.NONE;

  /** A ticket's credential, by its hash, resolved as the same token would be at a hello. */
  private final PtyIdentity.Holders holders =
      hash -> {
        for (var token : List.of("tok-uday", "tok-mady", "tok-root", "tok-vic")) {
          if (Secrets.hash(token).equals(hash)) {
            return this.resolver.resolve(token);
          }
        }
        throw new IOException("The credential is not valid or has expired.");
      };

  private final AtomicLong now = new AtomicLong(1_000_000_000L);

  private final PtyIdentity.Resolver resolver =
      token -> {
        if (storeDown) {
          throw new IllegalStateException("the control plane is unavailable");
        }
        if (revoked.contains(token)) {
          throw new IOException("Session token is not valid or has expired.");
        }
        if (reresolved.containsKey(token)) {
          return reresolved.get(token);
        }
        return switch (token) {
          case "", "tok-uday" ->
              new PtyIdentity.Person(new Actor("uday", Role.MEMBER, Actor.Lane.API));
          case "tok-mady" -> new PtyIdentity.Person(new Actor("mady", Role.MEMBER, Actor.Lane.API));
          case "tok-root" -> new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));
          case "tok-vic" -> new PtyIdentity.Person(new Actor("vic", Role.VIEWER, Actor.Lane.API));
          default -> throw new IOException("Session token is not valid or has expired.");
        };
      };

  private final Set<String> unheld = ConcurrentHashMap.newKeySet();

  private final Set<String> boxless = ConcurrentHashMap.newKeySet();

  private final PtyAdmission admission =
      new PtyAdmission() {
        @Override
        public void box(PtyIdentity.Person who) throws IOException {
          if (boxless.contains(who.fde())) {
            throw new IOException(
                "This box is uday's, so " + who.fde() + " opens no terminal on it.");
          }
        }

        @Override
        public void project(String project, PtyIdentity.Person who) throws IOException {
          if (project.equals("outage")) {
            throw new SqliteException("database is locked", 5);
          }
          if (unheld.contains(project + ":" + who.fde())) {
            throw new IOException("Project '" + project + "' is not in the catalog.");
          }
        }

        @Override
        public void room(String room, String project, PtyIdentity.Person who) throws IOException {
          if (!room.equals("design-talk")) {
            throw new IOException("Room '" + room + "' was not found.");
          }
          if (!((PtyIdentity.Person) who).actor().isAdmin() && !who.fde().equals("uday")) {
            throw new IOException("Room 'design-talk' is assigned to 'uday', not you.");
          }
        }
      };

  private PtySessionHost startHost() throws IOException {
    return startHost(PtySessionHost.Limits.DEFAULTS);
  }

  private PtySessionHost startHost(PtySessionHost.Limits limits) throws IOException {
    return startHost(limits, PtySessionHost.Handoff.NONE);
  }

  private PtySessionHost startHost(PtySessionHost.Limits limits, PtySessionHost.Handoff handoff)
      throws IOException {
    host =
        new PtySessionHost(
            dir.resolve("host.sock"),
            dir.resolve("sessions"),
            64 * 1024,
            resolver,
            admission,
            new PtyEvents() {
              @Override
              public void sessionStarted(PtySession.Origin origin) {
                events.add("started:" + origin.name() + ":" + origin.ownerFde());
                var gate = startGate;
                if (gate != null) {
                  startGated.countDown();
                  try {
                    gate.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
              }

              @Override
              public void sessionAttached(PtySession.Origin origin, String fde) {
                events.add("attached:" + origin.name() + ":" + fde);
              }

              @Override
              public void sessionEnded(PtySession.Origin origin, String reason) {
                events.add("ended:" + origin.name());
              }
            },
            "0.0.0-test",
            limits,
            handoff,
            network);
    host.start();
    return host;
  }

  private SocketChannel connect() throws IOException {
    return connect("tok-uday");
  }

  private String dispatchCredential() throws IOException {
    return Files.readString(PtySessionHost.dispatchCredentialOf(dir.resolve("host.sock")));
  }

  private SocketChannel connect(String token) throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
    var reply = hello(channel, token);
    if (reply instanceof PtyMessage.Err err) {
      throw new IOException(err.message());
    }
    return channel;
  }

  private static PtyMessage hello(SocketChannel channel, String token) throws IOException {
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(token));
    return PtyWire.read(channel);
  }

  private String welcome() throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
    try (channel) {
      var reply = hello(channel, "tok-uday");
      if (!(reply instanceof PtyMessage.Welcome(var hostBootId))) {
        throw new AssertionError("Hello was not answered with Welcome: " + reply);
      }
      return hostBootId;
    }
  }

  /**
   * The frames every attach answers with before its replay: the pty's geometry, then the bracket.
   */
  private static void assertAttachPrologue(SocketChannel channel) throws IOException {
    assertInstanceOf(PtyMessage.Resized.class, PtyWire.read(channel));
    assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
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
  void helloIsAnsweredWithTheHostsBootIdWhichEveryConnectionSharesAndARestartChanges()
      throws Exception {
    String first;
    try (var started = startHost()) {
      first = welcome();
      assertEquals(started.bootId(), first, "the Hello reply carries this host's boot id");
      assertEquals(first, welcome(), "every connection to one host sees the same id");
      assertTrue(!first.isBlank(), "a boot id is never blank");
    }
    try (var restarted = startHost()) {
      var second = welcome();
      assertEquals(restarted.bootId(), second);
      assertNotEquals(first, second, "a restarted host is a new boot: the id changes");
    }
  }

  @Test
  void identityComesFirstAndAdmissionIsOwnerOrAdmin() throws Exception {
    try (var ignored = startHost()) {
      var rude = SocketChannel.open(StandardProtocolFamily.UNIX);
      rude.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
      PtyWire.handshake(rude, rude);
      PtyWire.write(rude, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(rude), "hello must come first");
      rude.close();

      assertThrows(IOException.class, () -> connect("tok-forged"), "unknown tokens are refused");

      try (var owner = connect("tok-uday")) {
        PtyWire.write(
            owner,
            new PtyMessage.Create("mine", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
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

        // TakeWrite is not acknowledged: the holder is observable only through a listing, so poll
        // the listing rather than the attach event, which fires before the token moves.
        try (var owner = connect("tok-uday")) {
          PtyWire.write(owner, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
          var listed = (PtyMessage.Sessions) PtyWire.read(owner);
          var taken = System.nanoTime() + 5_000_000_000L;
          while (!"root".equals(listed.sessions().getFirst().writerFde())
              && System.nanoTime() < taken) {
            Thread.onSpinWait();
            PtyWire.write(owner, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
            listed = (PtyMessage.Sessions) PtyWire.read(owner);
          }
          assertEquals(
              "root", listed.sessions().getFirst().writerFde(), "the token names its holder");
        }
      }

      try (var owner = connect("tok-uday")) {
        PtyWire.write(owner, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        var listed = (PtyMessage.Sessions) PtyWire.read(owner);
        var freed = System.nanoTime() + 5_000_000_000L;
        while (!listed.sessions().getFirst().writerFde().isEmpty() && System.nanoTime() < freed) {
          Thread.onSpinWait();
          PtyWire.write(owner, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
          listed = (PtyMessage.Sessions) PtyWire.read(owner);
        }
        assertEquals(
            "", listed.sessions().getFirst().writerFde(), "a departed holder releases the token");
        PtyWire.write(owner, new PtyMessage.Kill("mine"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner), "the owner always may");
      }
    }
  }

  @Test
  void yieldEndsALiveSessionWithTheReasonInTheStreamAndOnTheEnding() throws Exception {
    try (var ignored = startHost()) {
      try (var owner = connect()) {
        PtyWire.write(
            owner,
            new PtyMessage.Create(
                "resume-1", List.of("sh", "-c", "echo up; read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
        PtyWire.write(owner, new PtyMessage.Attach("resume-1", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
        assertAttachPrologue(owner);
        awaitText(owner, "up");

        for (var fde : List.of("tok-mady", "tok-uday", "tok-root")) {
          try (var user = connect(fde)) {
            PtyWire.write(user, new PtyMessage.Yield("resume-1", "yielded to dispatch 2"));
            var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(user));
            assertTrue(
                refused.message().contains("dispatch authority"),
                fde + " — no FDE yields, not the owner, not an admin: " + refused.message());
          }
        }
        try (var dispatch = connect(dispatchCredential())) {
          PtyWire.write(
              dispatch, new PtyMessage.Create("x", List.of("sh"), "/tmp", "", "", 80, 24));
          assertInstanceOf(
              PtyMessage.Err.class,
              PtyWire.read(dispatch),
              "the dispatch authority yields and lists, and does nothing else");
          PtyWire.write(dispatch, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
          var listed = assertInstanceOf(PtyMessage.Sessions.class, PtyWire.read(dispatch));
          assertEquals(
              List.of("resume-1"),
              listed.sessions().stream().map(PtyMessage.SessionInfo::name).toList(),
              "it sees every session, whoever opened it: what a live member asks before it"
                  + " starts over a conversation");
          PtyWire.write(dispatch, new PtyMessage.Yield("ghost", "yielded to dispatch 2"));
          assertInstanceOf(
              PtyMessage.Ok.class,
              PtyWire.read(dispatch),
              "nothing live to yield is nothing to do");
          PtyWire.write(dispatch, new PtyMessage.Yield("resume-1", "yielded to dispatch 2"));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(dispatch));
        }

        awaitText(owner, "[sail: session ended \u2014 yielded to dispatch 2]");
        PtyMessage message;
        do {
          message = PtyWire.read(owner);
        } while (!(message instanceof PtyMessage.SessionEnded));
        assertEquals(
            "yielded to dispatch 2",
            ((PtyMessage.SessionEnded) message).reason(),
            "the ending carries the displacing reason, not the child's exit status");
        assertEquals(0, host.sessionCount(), "a yielded session is gone, like a killed one");
        assertTrue(events.contains("ended:resume-1"), events.toString());
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
      PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      var listed = (PtyMessage.Sessions) readControl(channel);
      if (listed.sessions().stream().anyMatch(s -> s.name().equals(name) && !s.live())) {
        return listed;
      }
    }
    throw new AssertionError("session '" + name + "' never became a corpse");
  }

  @Test
  void aRoomBoundSessionExportsItsRoomToTheChildAndListsIt() throws Exception {
    try (var ignored = startHost()) {
      try (var channel = connect()) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "pinned",
                List.of("sh", "-c", "echo room=$SAIL_ROOM_ID; read a"),
                "/tmp",
                "",
                "design-talk",
                80,
                24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        var listed = (PtyMessage.Sessions) PtyWire.read(channel);
        assertEquals("design-talk", listed.sessions().getFirst().room());
        assertEquals(
            List.of("sh", "-c", "echo room=$SAIL_ROOM_ID; read a"),
            listed.sessions().getFirst().command(),
            "the listing surfaces the command as requested");

        PtyWire.write(channel, new PtyMessage.Attach("pinned", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        assertAttachPrologue(channel);
        awaitText(channel, "room=design-talk");
      }
    }
  }

  @Test
  void anUnboundSessionExportsNoRoomAndListsTheDefaultShell() throws Exception {
    try (var ignored = startHost()) {
      try (var channel = connect()) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "free",
                List.of("sh", "-c", "echo room=[$SAIL_ROOM_ID]; read a"),
                "/tmp",
                "",
                "",
                80,
                24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(channel, new PtyMessage.Attach("free", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        assertAttachPrologue(channel);
        awaitText(channel, "room=[]");
      }
      try (var channel = connect()) {
        PtyWire.write(channel, new PtyMessage.Create("shell", List.of(), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        var listed = (PtyMessage.Sessions) PtyWire.read(channel);
        var shell =
            listed.sessions().stream().filter(info -> info.name().equals("shell")).findFirst();
        assertEquals(List.of("bash", "-l"), shell.orElseThrow().command());
        assertEquals("", shell.orElseThrow().room());
      }
    }
  }

  @Test
  void listingsPageInNameOrderSoNoCountOfSessionsOutgrowsAFrame() throws Exception {
    try (var ignored = startHost()) {
      try (var channel = connect()) {
        for (var name : List.of("charlie", "alpha", "bravo")) {
          PtyWire.write(
              channel,
              new PtyMessage.Create(name, List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        }

        PtyWire.write(channel, new PtyMessage.ListSessions("", 2));
        var first = (PtyMessage.Sessions) PtyWire.read(channel);
        assertEquals(List.of("alpha", "bravo"), names(first));
        assertEquals("bravo", first.next(), "a full page hands back the cursor to continue from");

        PtyWire.write(channel, new PtyMessage.ListSessions(first.next(), 2));
        var second = (PtyMessage.Sessions) PtyWire.read(channel);
        assertEquals(List.of("charlie"), names(second));
        assertEquals("", second.next(), "the last page carries no cursor");

        PtyWire.write(channel, new PtyMessage.ListSessions("", 0));
        assertEquals(
            1,
            ((PtyMessage.Sessions) PtyWire.read(channel)).sessions().size(),
            "a limit below one is clamped up, never an empty page that loops forever");
        PtyWire.write(channel, new PtyMessage.ListSessions("", Integer.MAX_VALUE));
        var clamped = (PtyMessage.Sessions) PtyWire.read(channel);
        assertEquals(List.of("alpha", "bravo", "charlie"), names(clamped));
        assertEquals("", clamped.next());
      }
    }
  }

  @Test
  void anOversizedCommandIsRefusedBeforeAnythingIsSpawned() throws Exception {
    try (var host = startHost()) {
      try (var channel = connect()) {
        var padding = "x".repeat(PtyMessage.MAX_COMMAND_BYTES);
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "huge", List.of("sh", "-c", "read a", padding), "/tmp", "", "", 80, 24));
        var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
        assertTrue(refused.message().contains("cap is"), refused.message());
        PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        assertTrue(
            ((PtyMessage.Sessions) PtyWire.read(channel)).sessions().isEmpty(),
            "nothing was spawned");
        assertTrue(events.isEmpty(), "and no session fact was recorded");
      }
    }
  }

  private static List<String> names(PtyMessage.Sessions page) {
    return page.sessions().stream().map(PtyMessage.SessionInfo::name).toList();
  }

  @Test
  void aCommandOfManyTinyArgumentsIsCappedByItsWireSizeNotItsCharacters() throws Exception {
    try (var host = startHost()) {
      try (var channel = connect()) {
        var command = new ArrayList<>(List.of("/bin/true"));
        for (var i = 0; i < 20_000; i++) {
          command.add("x");
        }
        assertTrue(
            command.stream().mapToInt(String::length).sum() < PtyMessage.MAX_COMMAND_BYTES,
            "by characters alone this command would pass the cap");
        PtyWire.write(channel, new PtyMessage.Create("confetti", command, "/tmp", "", "", 80, 24));
        var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
        assertTrue(refused.message().contains("cap is"), refused.message());
        assertEquals(0, host.sessionCount(), "a page of such commands would outgrow a frame");
      }
    }
  }

  @Test
  void aRoomTheGateRefusesNeverReachesTheChildOrTheEvents() throws Exception {
    try (var host = startHost()) {
      try (var channel = connect("tok-mady")) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "intruder", List.of("sh", "-c", "read a"), "/tmp", "", "design-talk", 80, 24));
        var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
        assertTrue(refused.message().contains("not you"), refused.message());
      }
      try (var channel = connect()) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "ghost", List.of("sh", "-c", "read a"), "/tmp", "", "no-such-room", 80, 24));
        var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
        assertTrue(refused.message().contains("not found"), refused.message());
      }
      assertEquals(0, host.sessionCount(), "nothing is spawned for a room the gate refuses");
      assertTrue(events.isEmpty(), "and no fact lands in anybody's room history");
      try (var channel = connect("tok-root")) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "admin", List.of("sh", "-c", "read a"), "/tmp", "", "design-talk", 80, 24));
        assertInstanceOf(
            PtyMessage.Ok.class, PtyWire.read(channel), "the gate decides, not the host");
      }
    }
  }

  @Test
  void aMalformedRoomIdIsRefusedBeforeAnythingIsSpawned() throws Exception {
    try (var host = startHost()) {
      try (var channel = connect()) {
        PtyWire.write(
            channel,
            new PtyMessage.Create("bad", List.of("sh"), "/tmp", "", "Room; rm -rf /", 80, 24));
        var reply = PtyWire.read(channel);
        assertInstanceOf(PtyMessage.Err.class, reply);
        assertTrue(((PtyMessage.Err) reply).message().contains("room"), reply.toString());
        assertEquals(0, host.sessionCount(), "nothing is spawned for a room id that cannot be");
      }
    }
  }

  @Test
  void attachAnswersWithTheGeometryTheReplayAndTheWriterOnTheWire() throws Exception {
    try (var ignored = startHost()) {
      try (var writer = connect("tok-uday")) {
        PtyWire.write(
            writer,
            new PtyMessage.Create(
                "sized", List.of("sh", "-c", "echo hi; read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(writer));
        PtyWire.write(writer, new PtyMessage.Attach("sized", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(writer));
        assertAttachPrologue(writer);
        awaitText(writer, "hi");
        PtyWire.write(writer, new PtyMessage.Resize(126, 40));
        PtyWire.write(writer, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        assertInstanceOf(
            PtyMessage.Sessions.class,
            readControl(writer),
            "the host answers a connection in order, so the resize has landed");

        try (var observer = connect("tok-root")) {
          PtyWire.write(observer, new PtyMessage.Attach("sized", true));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(observer));
          var frames = new ArrayList<PtyMessage>();
          PtyMessage frame;
          do {
            frame = PtyWire.read(observer);
            frames.add(frame);
          } while (!(frame instanceof PtyMessage.WriterChanged));
          var kinds = frames.stream().map(m -> m.getClass().getSimpleName()).toList();
          assertEquals(
              new PtyMessage.Resized(126, 40),
              frames.getFirst(),
              "the writer's geometry is the first frame: " + kinds);
          assertEquals("ReplayBegin", kinds.get(1), kinds.toString());
          assertEquals(
              List.of("ReplayEnd", "WriterChanged"),
              kinds.subList(kinds.size() - 2, kinds.size()),
              "the token holder is named right after the replay: " + kinds);
          assertEquals(
              new PtyMessage.WriterChanged("uday"),
              frames.getLast(),
              "a different FDE asking for write is told who holds it, not given it");
        }
      }
    }
  }

  @Test
  void aRevokedCredentialIsSeveredAtTheNextSweep() throws Exception {
    try (var ignored = startHost()) {
      try (var mady = connect("tok-mady")) {
        PtyWire.write(
            mady,
            new PtyMessage.Create(
                "hers", List.of("sh", "-c", "echo up; read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(mady));
        PtyWire.write(mady, new PtyMessage.Attach("hers", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(mady));
        awaitText(mady, "up");

        revoked.add("tok-mady");
        host.sweep(System.nanoTime());

        assertThrows(
            IOException.class,
            () -> {
              while (true) {
                PtyWire.read(mady);
              }
            },
            "the severed connection reads nothing more");
        try (var admin = connect("tok-root")) {
          var deadline = System.nanoTime() + 10_000_000_000L;
          while (true) {
            PtyWire.write(admin, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
            var listed = (PtyMessage.Sessions) readControl(admin);
            var hers = listed.sessions().stream().filter(s -> s.name().equals("hers")).findFirst();
            if (hers.isPresent() && hers.get().attached() == 0) {
              break;
            }
            if (System.nanoTime() > deadline) {
              throw new AssertionError("the attachment was never dropped: " + listed);
            }
          }
        }
        assertThrows(
            IOException.class, () -> connect("tok-mady"), "and the credential no longer admits");
      }
    }
  }

  @Test
  void aDemotionOrAScopeChangeSeversAtTheNextSweepAndAnOutageSeversNothing() throws Exception {
    try (var ignored = startHost()) {
      try (var mady = connect("tok-mady");
          var uday = connect("tok-uday");
          var root = connect("tok-root")) {
        PtyWire.write(
            uday,
            new PtyMessage.Create(
                "udays", List.of("sh", "-c", "echo up; read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(uday));
        PtyWire.write(root, new PtyMessage.Attach("udays", false));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(root));
        awaitText(root, "up");

        storeDown = true;
        host.sweep(System.nanoTime());
        storeDown = false;
        PtyWire.write(root, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        assertInstanceOf(
            PtyMessage.Sessions.class, readControl(root), "a store outage severs nothing");

        reresolved.put(
            "tok-root", new PtyIdentity.Person(new Actor("root", Role.MEMBER, Actor.Lane.API)));
        reresolved.put(
            "tok-mady",
            new PtyIdentity.Person(
                new Actor("mady", Role.MEMBER, Actor.Lane.API)
                    .withScope(ProjectScope.of(List.of("acme")))));
        host.sweep(System.nanoTime());

        for (var severed : List.of(root, mady)) {
          assertTimeoutPreemptively(
              java.time.Duration.ofSeconds(10),
              () ->
                  assertThrows(
                      IOException.class,
                      () -> {
                        while (true) {
                          PtyWire.read(severed);
                        }
                      },
                      "a demoted or rescoped connection reads nothing more"));
        }
        PtyWire.write(uday, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        assertInstanceOf(
            PtyMessage.Sessions.class, readControl(uday), "an unchanged principal stays");
        try (var demoted = connect("tok-root")) {
          PtyWire.write(demoted, new PtyMessage.Attach("udays", false));
          assertEquals(
              "Session 'udays' is not yours to attach.",
              assertInstanceOf(PtyMessage.Err.class, readControl(demoted)).message(),
              "a reconnect is admitted as the principal the credential now stands for");
        }
      }
    }
  }

  @Test
  void aTerminalOpensAndAttachesOnlyInAProjectItsPersonHolds() throws Exception {
    var shim =
        new PtySessionHost.Handoff(
            SdNotify.NONE,
            Map.of(),
            exit -> PtyChildShimTest.shim(exit, "sh", "-c", "echo up; read a", "--"));
    try (var ignored = startHost(PtySessionHost.Limits.DEFAULTS, shim)) {
      unheld.add("beta:mady");
      try (var mady = connect("tok-mady")) {
        PtyWire.write(mady, new PtyMessage.Create("hers", List.of(), "/tmp", "beta", "", 80, 24));
        assertEquals(
            "Project 'beta' is not in the catalog.",
            assertInstanceOf(PtyMessage.Err.class, readControl(mady)).message());
        assertEquals(0, host.sessionCount(), "nothing is spawned for a project not held");

        PtyWire.write(mady, new PtyMessage.Create("acmes", List.of(), "/tmp", "acme", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(mady));
      }
      unheld.add("acme:mady");
      try (var mady = connect("tok-mady")) {
        PtyWire.write(mady, new PtyMessage.Attach("acmes", true));
        assertEquals(
            "Project 'acme' is not in the catalog.",
            assertInstanceOf(PtyMessage.Err.class, readControl(mady)).message(),
            "a session in a project its person no longer holds is not reattached");
      }
      try (var root = connect("tok-root")) {
        PtyWire.write(root, new PtyMessage.Attach("acmes", false));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(root));
        awaitText(root, "up");
      }
    }
  }

  @Test
  void aNonWritersInputIsRefusedWithoutKillingTheConnection() throws Exception {
    try (var ignored = startHost()) {
      try (var owner = connect("tok-uday")) {
        PtyWire.write(
            owner,
            new PtyMessage.Create(
                "shared", List.of("sh", "-c", "read a; read b"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(owner));
      }
      try (var observer = connect("tok-uday")) {
        PtyWire.write(observer, new PtyMessage.Attach("shared", false));
        assertInstanceOf(PtyMessage.Ok.class, readControl(observer));

        PtyWire.write(observer, new PtyMessage.Input(1, "nope\n".getBytes(StandardCharsets.UTF_8)));
        assertInstanceOf(
            PtyMessage.Err.class, readControl(observer), "a non-writer's input is refused");

        PtyWire.write(observer, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
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
            new PtyMessage.Create("udays", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(uday));
      }
      try (var mady = connect("tok-mady")) {
        PtyWire.write(
            mady,
            new PtyMessage.Create("madys", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(mady));

        PtyWire.write(mady, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        var listed = (PtyMessage.Sessions) readControl(mady);
        assertEquals(
            List.of("madys"),
            listed.sessions().stream().map(PtyMessage.SessionInfo::name).toList(),
            "a member sees only their own sessions, never another owner's");
      }
      try (var admin = connect("tok-root")) {
        PtyWire.write(admin, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        var listed = (PtyMessage.Sessions) readControl(admin);
        assertEquals(2, listed.sessions().size(), "an admin sees every owner's sessions");
      }
    }
  }

  @Test
  void aViewerOperatesTheirOwnSessionsAndNoOneElses() throws Exception {
    try (var ignored = startHost()) {
      try (var uday = connect("tok-uday")) {
        PtyWire.write(
            uday,
            new PtyMessage.Create("udays", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(uday));
      }
      try (var vic = connect("tok-vic")) {
        PtyWire.write(
            vic,
            new PtyMessage.Create("vics", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(vic));
        PtyWire.write(vic, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        assertEquals(
            List.of("vics"),
            ((PtyMessage.Sessions) readControl(vic))
                .sessions().stream().map(PtyMessage.SessionInfo::name).toList());
        PtyWire.write(vic, new PtyMessage.Kill("udays"));
        assertInstanceOf(PtyMessage.Err.class, readControl(vic), "not another person's");
        PtyWire.write(vic, new PtyMessage.Kill("vics"));
        assertInstanceOf(PtyMessage.Ok.class, readControl(vic), "a viewer's own box is theirs");
      }
    }
  }

  @Test
  void aForeignMemberCannotEvictAnothersCorpseByReusingItsName() throws Exception {
    try (var ignored = startHost()) {
      try (var uday = connect("tok-uday")) {
        PtyWire.write(
            uday,
            new PtyMessage.Create("keep", List.of("sh", "-c", "exit 0"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(uday));
        awaitCorpse(uday, "keep");
      }
      try (var mady = connect("tok-mady")) {
        PtyWire.write(mady, new PtyMessage.Create("keep", List.of("sh"), "/tmp", "", "", 80, 24));
        assertInstanceOf(
            PtyMessage.Err.class,
            readControl(mady),
            "a foreign member cannot reuse an owner's name, even a corpse");
      }
      try (var uday = connect("tok-uday")) {
        PtyWire.write(uday, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
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
                "",
                "",
                80,
                24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Attach("s1", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        assertAttachPrologue(channel);
        awaitText(channel, "hi");

        PtyWire.write(channel, new PtyMessage.Input(1, "world\n".getBytes(StandardCharsets.UTF_8)));
        awaitText(channel, "bye:world");
      }

      try (var again = connect()) {
        PtyWire.write(again, new PtyMessage.Attach("s1", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(again));
        assertAttachPrologue(again);
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
                "dup", List.of("sh", "-c", "echo old-life; read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(channel, new PtyMessage.Create("dup", List.of("sh"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Kill("dup"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "dup", List.of("sh", "-c", "echo new-life; read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        PtyWire.write(channel, new PtyMessage.Attach("dup", false));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        assertAttachPrologue(channel);
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
            new PtyMessage.Create("a", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
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
            new PtyMessage.Create("lonely", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
        PtyWire.write(
            channel,
            new PtyMessage.Create("corpse", List.of("sh", "-c", "exit 0"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));

        var deadline = System.nanoTime() + 10_000_000_000L;
        PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        var listed = (PtyMessage.Sessions) PtyWire.read(channel);
        while (listed.sessions().stream().anyMatch(s -> s.name().equals("corpse") && s.live())) {
          if (System.nanoTime() > deadline) {
            throw new AssertionError("corpse never exited");
          }
          PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
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

  @Test
  void aRecreatedNameIsANewIncarnationTheListingTellsApartByItsInstanceId() throws Exception {
    try (var ignored = startHost();
        var owner = connect()) {
      PtyWire.write(
          owner,
          new PtyMessage.Create("again", List.of("sh", "-c", "exit 0"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
      var first = listed(owner, "again");
      assertFalse(first.instanceId().isBlank(), "an incarnation is minted an id at create");

      var deadline = System.nanoTime() + 5_000_000_000L;
      while (!events.contains("ended:again") && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      var corpse = listed(owner, "again");
      assertFalse(corpse.live(), "the child exited: " + events);
      assertEquals(first.instanceId(), corpse.instanceId(), "a corpse keeps the id it lived under");

      PtyWire.write(
          owner,
          new PtyMessage.Create("again", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner), "a corpse's name may be reused");
      var second = listed(owner, "again");
      assertTrue(second.live());
      assertNotEquals(
          first.instanceId(),
          second.instanceId(),
          "the reused name is a new incarnation — a client that only saw the corpse of one"
              + " and the corpse of the other can still tell them apart");
      PtyWire.write(owner, new PtyMessage.Kill("again"));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
    }
  }

  @Test
  void aPathIshSessionNameIsRefusedBeforeAnythingIsSpawned() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      PtyWire.write(
          channel, new PtyMessage.Create("../escape", List.of("sh"), "/tmp", "", "", 80, 24));
      var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
      assertTrue(refused.message().contains("Invalid session name"), refused.message());
      assertFalse(
          Files.exists(dir.resolve("sessions").resolve("../escape.ring").normalize()),
          "a traversing name never touched the filesystem");
      assertEquals(0, host.sessionCount());
    }
  }

  @Test
  void anInvalidProjectYieldsErrNotAConnectionDrop() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      PtyWire.write(
          channel, new PtyMessage.Create("s", List.of("sh"), "/tmp", "Bad Project", "", 80, 24));
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel), "an Err, never a silent EOF");
      PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      assertInstanceOf(
          PtyMessage.Sessions.class, PtyWire.read(channel), "the connection survives the refusal");
    }
  }

  @Test
  void aStoreThatCannotAnswerTheProjectCheckOpensNothingAndKeepsTheConnection() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      PtyWire.write(
          channel, new PtyMessage.Create("s", List.of("sh"), "/tmp", "outage", "", 80, 24));
      assertEquals(
          PtySessionHost.UNSERVED,
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel)).message());
      assertEquals(0, host.sessionCount());
      PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      assertInstanceOf(
          PtyMessage.Sessions.class, PtyWire.read(channel), "the connection survives the outage");
    }
  }

  @Test
  void aCreateThatFailsToSpawnLeavesNoRingBehind() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(1, 16, 256, 64));
        var channel = connect()) {
      for (var i = 0; i < 5; i++) {
        PtyWire.write(
            channel,
            new PtyMessage.Create(
                "doomed" + i,
                List.of("sh"),
                dir.resolve("no-such-dir").toString(),
                "",
                "",
                80,
                24));
        var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
        assertTrue(refused.message().contains("Could not start session"), refused.message());
      }
      try (var rings = Files.list(dir.resolve("sessions"))) {
        assertEquals(
            List.of(),
            rings.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".ring")).toList(),
            "a failed create owns no session, so it must own no ring either");
      }
      assertEquals(0, host.sessionCount());
    }
  }

  @Test
  void aSessionsRingIsDeletedWhenItIsKilledAndNoOrphanSurvivesAHostRestart() throws Exception {
    var ring = dir.resolve("sessions").resolve("ringed.ring");
    try (var ignored = startHost();
        var channel = connect()) {
      PtyWire.write(
          channel,
          new PtyMessage.Create("ringed", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
      assertTrue(Files.exists(ring), "a live session has a ring on disk");

      PtyWire.write(channel, new PtyMessage.Kill("ringed"));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
      assertFalse(Files.exists(ring), "killing the session deletes its ring");
    }

    var orphan = dir.resolve("sessions").resolve("orphan.ring");
    Files.write(orphan, "stale".getBytes(StandardCharsets.UTF_8));
    try (var ignored = startHost()) {
      assertFalse(Files.exists(orphan), "a restart sweeps every orphan ring — no rehydration");
    }
  }

  @Test
  void aStoppedChildWithAFloodingWriterDoesNotStallOtherConnections() throws Exception {
    try (var ignored = startHost()) {
      try (var flooder = connect()) {
        PtyWire.write(
            flooder,
            new PtyMessage.Create(
                "stuck", List.of("sh", "-c", "sleep 30"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(flooder));
        PtyWire.write(flooder, new PtyMessage.Attach("stuck", true));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(flooder));

        var drain =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        while (true) {
                          PtyWire.read(flooder);
                        }
                      } catch (IOException done) {
                        var unused = done;
                      }
                    });
        var big = new byte[16384];
        for (var i = 0; i < 700; i++) {
          PtyWire.write(flooder, new PtyMessage.Input(i, big));
        }

        try (var other = connect("tok-mady")) {
          PtyWire.write(other, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
          var reply =
              assertTimeoutPreemptively(
                  java.time.Duration.ofSeconds(10),
                  () -> PtyWire.read(other),
                  "a stuck writer must never pin the accept lane or a second connection");
          assertInstanceOf(PtyMessage.Sessions.class, reply);
        }
        drain.interrupt();
      }
    }
  }

  @Test
  void aYieldedSessionsRingIsDeletedLikeAKilledOnes() throws Exception {
    var ring = dir.resolve("sessions").resolve("resume-y.ring");
    try (var ignored = startHost();
        var owner = connect()) {
      PtyWire.write(
          owner,
          new PtyMessage.Create("resume-y", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
      assertTrue(Files.exists(ring), "a live session has a ring on disk");

      try (var dispatch = connect(dispatchCredential())) {
        PtyWire.write(dispatch, new PtyMessage.Yield("resume-y", "yielded to dispatch 2"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(dispatch));
      }
      assertFalse(Files.exists(ring), "a yield leaves no ring behind, exactly like a kill");
      assertEquals(0, host.sessionCount());
    }
  }

  @Test
  void recreatingAnotherOwnersCorpseCountsAgainstTheRecreatorsSessionCap() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(1, 8, 8, 64))) {
      try (var uday = connect("tok-uday")) {
        PtyWire.write(
            uday,
            new PtyMessage.Create("keep", List.of("sh", "-c", "exit 0"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(uday));
        awaitCorpse(uday, "keep");
      }
      try (var admin = connect("tok-root")) {
        PtyWire.write(
            admin,
            new PtyMessage.Create("own", List.of("sh", "-c", "exit 0"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, readControl(admin));
        awaitCorpse(admin, "own");
        PtyWire.write(
            admin,
            new PtyMessage.Create("own", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
        assertInstanceOf(
            PtyMessage.Ok.class,
            readControl(admin),
            "recreating your own corpse replaces it and takes no extra slot");
        PtyWire.write(
            admin,
            new PtyMessage.Create("keep", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
        var refused = assertInstanceOf(PtyMessage.Err.class, readControl(admin));
        assertTrue(
            refused.message().contains("session cap of 1"),
            "another owner's corpse is a new session for the admin: " + refused.message());
      }
      try (var uday = connect("tok-uday")) {
        PtyWire.write(uday, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        var listed = (PtyMessage.Sessions) readControl(uday);
        assertEquals(
            "keep",
            listed.sessions().getFirst().name(),
            "the refused recreate left the corpse intact");
      }
    }
  }

  @Test
  void createRefusesBeyondThePerFdeSessionCap() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(2, 8, 8, 64));
        var channel = connect()) {
      for (var i = 0; i < 2; i++) {
        PtyWire.write(
            channel,
            new PtyMessage.Create("s" + i, List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
      }
      PtyWire.write(
          channel,
          new PtyMessage.Create("s2", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
      var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
      assertTrue(refused.message().contains("session cap of 2"), refused.message());
    }
  }

  @Test
  void attachRefusesBeyondThePerSessionSubscriberCap() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(8, 2, 8, 64))) {
      try (var owner = connect()) {
        PtyWire.write(
            owner,
            new PtyMessage.Create("shared", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
      }
      var held = new ArrayList<SocketChannel>();
      try {
        for (var i = 0; i < 2; i++) {
          var observer = connect();
          held.add(observer);
          PtyWire.write(observer, new PtyMessage.Attach("shared", false));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(observer));
        }
        try (var third = connect()) {
          PtyWire.write(third, new PtyMessage.Attach("shared", false));
          var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(third));
          assertTrue(refused.message().contains("subscriber cap of 2"), refused.message());
        }
      } finally {
        for (var channel : held) {
          channel.close();
        }
      }
    }
  }

  @Test
  void theHostRefusesConnectionsBeyondItsCap() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(8, 8, 1, 64));
        var first = connect()) {
      assertThrows(IOException.class, this::connect);
    }
  }

  @Test
  void aSocketOverTheConnectionCapIsClosedBeforeItSpeaks() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(8, 8, 1, 64));
        var first = connect();
        var silent = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      silent.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
      var read =
          assertTimeoutPreemptively(
              java.time.Duration.ofSeconds(10),
              () -> silent.read(java.nio.ByteBuffer.allocate(PtyWire.MAGIC.length)),
              "an over-cap socket must not be held waiting for its handshake");
      assertEquals(-1, read, "the host closes an excess socket without reading or greeting it");
    }
  }

  @Test
  void concurrentCreatesCannotExceedThePerFdeSessionCap() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(1, 8, 8, 64));
        var first = connect();
        var second = connect()) {
      startGate = new CountDownLatch(1);
      PtyWire.write(
          first,
          new PtyMessage.Create("s0", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
      assertTrue(startGated.await(10, TimeUnit.SECONDS), "the first create is mid-admission");
      PtyWire.write(second, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      PtyWire.write(
          second,
          new PtyMessage.Create("s1", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Sessions.class, PtyWire.read(second));
      try (var fence = connect()) {
        PtyWire.write(fence, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        assertInstanceOf(
            PtyMessage.Sessions.class,
            PtyWire.read(fence),
            "the second create has had a whole round trip to reach admission before the gate opens");
      }
      startGate.countDown();

      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(first));
      var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(second));
      assertTrue(refused.message().contains("session cap of 1"), refused.message());
      assertEquals(1, host.sessionCount(), "a create racing an admitted one spawns nothing");
    }
  }

  @Test
  void concurrentAttachesCannotExceedTheSubscriberCap() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(8, 1, 16, 64))) {
      try (var owner = connect()) {
        PtyWire.write(
            owner,
            new PtyMessage.Create("shared", List.of("sh", "-c", "read x"), "/tmp", "", "", 80, 24));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(owner));
      }
      var contenders = new ArrayList<SocketChannel>();
      try {
        for (var i = 0; i < 8; i++) {
          contenders.add(connect());
        }
        var go = new CountDownLatch(1);
        var replies = new ConcurrentLinkedQueue<PtyMessage>();
        var threads =
            contenders.stream()
                .map(
                    channel ->
                        Thread.ofVirtual()
                            .start(
                                () -> {
                                  try {
                                    go.await();
                                    PtyWire.write(channel, new PtyMessage.Attach("shared", false));
                                    replies.add(PtyWire.read(channel));
                                  } catch (IOException | InterruptedException e) {
                                    throw new AssertionError(e);
                                  }
                                }))
                .toList();
        go.countDown();
        for (var thread : threads) {
          thread.join();
        }
        assertEquals(
            1,
            replies.stream().filter(PtyMessage.Ok.class::isInstance).count(),
            "exactly one contender takes the last slot: " + replies);
        assertEquals(
            7, replies.stream().filter(PtyMessage.Err.class::isInstance).count(), "" + replies);
        try (var lister = connect()) {
          assertEquals(1, listed(lister, "shared").attached());
        }
      } finally {
        for (var channel : contenders) {
          channel.close();
        }
      }
    }
  }

  @Test
  void aMalformedFrameIsAnsweredWithErrBeforeTheConnectionCloses() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      var truncatedInput = java.nio.ByteBuffer.allocate(5).putInt(1).put((byte) 3).flip();
      while (truncatedInput.hasRemaining()) {
        channel.write(truncatedInput);
      }
      var refused = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel));
      assertTrue(refused.message().contains("could not serve"), refused.message());
      assertThrows(IOException.class, () -> PtyWire.read(channel), "then the host hangs up");
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void aHostListeningOnNoTcpAddressIssuesNoTicket() throws Exception {
    try (var ignored = startHost()) {
      assertEquals(
          "unavailable This host listens for no terminal over the network. Name an address with"
              + " 'sail host config set terminal-listen <address>:<port>' and restart the"
              + " sail-pty-host service.",
          said(askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "acme")));
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void aTicketIsDecidedAsTheOpenOrAttachItNamesForTheCredentialItCarries() throws Exception {
    unheld.add("acme:uday");
    boxless.add("mady");
    try (var ignored = startTcpHost()) {
      assertEquals(
          List.of(
              "not_found Project 'acme' is not in the catalog.",
              "invalid Invalid project name: 'Acme'. Must match [a-z0-9][a-z0-9-]*, max 63"
                  + " characters.",
              "not_found There is no session 'ghost' on this box that you may attach.",
              "forbidden A ticket is issued for the credential that asks for it, and this request"
                  + " carries none: the box's own person exists only on the unix socket.",
              "forbidden The credential is not valid or has expired.",
              "forbidden This box is uday's, so mady opens no terminal on it."),
          List.of(
              said(askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "acme")),
              said(askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "Acme")),
              said(askTicket("tok-uday", PtyMessage.TicketAction.ATTACH, "ghost")),
              said(askTicket("", PtyMessage.TicketAction.OPEN, "beta")),
              said(askTicket("tok-nobody", PtyMessage.TicketAction.OPEN, "beta")),
              said(askTicket("tok-mady", PtyMessage.TicketAction.OPEN, "beta"))));
      assertEquals(
          "unavailable " + PtySessionHost.UNSERVED,
          said(askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "outage")));
      try (var dispatch = connect(dispatchCredential())) {
        PtyWire.write(
            dispatch, new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, "beta", "tok-uday"));
        assertEquals(
            "The dispatch authority may only yield and list sessions.",
            ((PtyMessage.Err) PtyWire.read(dispatch)).message());
      }
      assertInstanceOf(
          PtyMessage.Ticket.class, askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "beta"));
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void aTicketIsAskedOnlyOnAConnectionThatPresentedNoToken() throws Exception {
    try (var ignored = startTcpHost();
        var mady = connect("tok-mady")) {
      PtyWire.write(
          mady, new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, "beta", "tok-uday"));

      assertEquals(
          "forbidden A ticket is asked by this box's own process alone, on a connection that"
              + " presented no token.",
          said(PtyWire.read(mady)));
      assertInstanceOf(
          PtyMessage.Ticket.class, askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "beta"));
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void anAttachTicketIsRefusedAsNotFoundWhetherTheSessionIsAbsentOrNotTheCallers()
      throws Exception {
    try (var host = startTcpHost();
        var channel = connect("tok-uday")) {
      PtyWire.write(
          channel,
          new PtyMessage.Create("lounge", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
      assertEquals(
          List.of(
              "not_found There is no session 'lounge' on this box that you may attach.",
              "not_found There is no session 'ghost' on this box that you may attach."),
          List.of(
              said(askTicket("tok-mady", PtyMessage.TicketAction.ATTACH, "lounge")),
              said(askTicket("tok-mady", PtyMessage.TicketAction.ATTACH, "ghost"))));
      assertInstanceOf(
          PtyMessage.Ticket.class, askTicket("tok-uday", PtyMessage.TicketAction.ATTACH, "lounge"));
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void aPresentedTicketIsWelcomedAsItsCredentialResolvesNowAndAdmitsItsActionAlone()
      throws Exception {
    try (var host = startTcpHost()) {
      var ticket = (PtyMessage.Ticket) askTicket("tok-mady", PtyMessage.TicketAction.OPEN, "beta");
      try (var tcp = presentTcp(host, ticket.ticket())) {
        assertEquals(new PtyMessage.Welcome(host.bootId()), PtyWire.read(tcp));
        PtyWire.write(tcp, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
        assertEquals(
            "This connection's ticket is to open a session in project 'beta', and it admits"
                + " nothing else; ask the API for a ticket for that.",
            ((PtyMessage.Err) PtyWire.read(tcp)).message());
        assertThrows(IOException.class, () -> PtyWire.read(tcp), "then the host hangs up");
      }

      var revokedSince =
          (PtyMessage.Ticket) askTicket("tok-mady", PtyMessage.TicketAction.OPEN, "beta");
      revoked.add("tok-mady");
      try (var tcp = presentTcp(host, revokedSince.ticket())) {
        assertEquals(
            "Session token is not valid or has expired.",
            ((PtyMessage.Err) PtyWire.read(tcp)).message());
      }

      var unanswered =
          (PtyMessage.Ticket) askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "beta");
      storeDown = true;
      try (var tcp = presentTcp(host, unanswered.ticket())) {
        assertEquals(PtySessionHost.UNSERVED, ((PtyMessage.Err) PtyWire.read(tcp)).message());
      }
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void aTcpConnectionThatPresentsNoTicketIsClosedAtTheDeadlineAndAnyOtherFrameAtOnce()
      throws Exception {
    try (var host = startTcpHost();
        var silent = served(host);
        var early = SocketChannel.open(host.tcpAddress().orElseThrow())) {
      PtyWire.handshake(early, early);
      PtyWire.write(early, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      assertEquals(-1, early.read(ByteBuffer.allocate(1)));

      host.tick(now.get() + PtySessionHost.UNTICKETED_DEADLINE.toNanos());
      assertTrue(silent.isOpen());
      host.tick(now.get() + PtySessionHost.UNTICKETED_DEADLINE.toNanos() + 1);

      assertEquals(-1, silent.read(ByteBuffer.allocate(1)));
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void everyTcpConnectionReleasesItsSlotSoTheDoorServesForAsLongAsItRuns() throws Exception {
    network =
        new PtySessionHost.Network(
            Optional.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
            now::get,
            holders);
    try (var host = startHost(new PtySessionHost.Limits(32, 16, 4, 256))) {
      for (var i = 0; i < 300; i++) {
        try (var tcp = presentTcp(host, "tkt_" + "0".repeat(64))) {
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp), "connection " + i);
        }
      }
      var ticket = (PtyMessage.Ticket) askTicket("tok-uday", PtyMessage.TicketAction.OPEN, "beta");
      try (var tcp = presentTcp(host, ticket.ticket())) {
        assertEquals(new PtyMessage.Welcome(host.bootId()), PtyWire.read(tcp));
      }
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void aTcpConnectionReleasesItsSlotExactlyOnceWhateverStageItEndsAt() throws Exception {
    network =
        new PtySessionHost.Network(
            Optional.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
            now::get,
            holders);
    try (var host = startHost(new PtySessionHost.Limits(32, 16, 4, 256));
        var asker = connect("")) {
      var address = host.tcpAddress().orElseThrow();
      for (var i = 0; i < 301; i++) {
        endAtStage(host, asker, address, i % 7);
      }
      var held = new ArrayList<SocketChannel>();
      try {
        for (var i = 0; i < 4; i++) {
          held.add(admitted(host, asker));
        }
        try (var fifth = SocketChannel.open(address)) {
          assertEquals(-1, fifth.read(ByteBuffer.allocate(8)), "exactly the cap, never lifted");
        }
      } finally {
        for (var channel : held) {
          channel.close();
        }
      }
      var unix = new ArrayList<SocketChannel>();
      try {
        for (var i = 0; i < 3; i++) {
          unix.add(connect());
        }
        assertThrows(
            IOException.class,
            () -> connect().close(),
            "the unix cap is exactly 4, the asker's one");
      } finally {
        for (var channel : unix) {
          channel.close();
        }
      }
    }
  }

  /**
   * One TCP connection ended at {@code stage}: closed before the magic, after it, mid-length, on an
   * oversized length, on a forged ticket, after a welcome, or reset after a frame once welcomed.
   */
  private void endAtStage(
      PtySessionHost host, SocketChannel asker, InetSocketAddress address, int stage)
      throws IOException {
    if (stage >= 5) {
      try (var tcp = admitted(host, asker)) {
        if (stage == 6) {
          PtyWire.write(tcp, new PtyMessage.Resize(10, 10));
          tcp.setOption(StandardSocketOptions.SO_LINGER, 0);
        }
      }
      return;
    }
    try (var tcp = SocketChannel.open(address)) {
      switch (stage) {
        case 1 -> {
          var magic = ByteBuffer.allocate(8);
          while (magic.hasRemaining() && tcp.read(magic) >= 0) {}
        }
        case 2 -> {
          PtyWire.handshake(tcp, tcp);
          tcp.write(ByteBuffer.wrap(new byte[] {0, 0}));
        }
        case 3 -> {
          PtyWire.handshake(tcp, tcp);
          tcp.write(ByteBuffer.allocate(4).putInt(1 << 20).flip());
          assertEquals(-1, tcp.read(ByteBuffer.allocate(1)));
        }
        case 4 -> {
          PtyWire.handshake(tcp, tcp);
          PtyWire.write(tcp, new PtyMessage.Hello("tkt_" + "0".repeat(64)));
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp));
        }
        default -> {}
      }
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void connectionsFromTheNetworkNeverLiftOrCrowdOutTheUnixSocketsCap() throws Exception {
    network =
        new PtySessionHost.Network(
            Optional.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
            now::get,
            holders);
    try (var host = startHost(new PtySessionHost.Limits(32, 16, 2, 256))) {
      for (var i = 0; i < 10; i++) {
        try (var tcp = presentTcp(host, "tkt_" + "0".repeat(64))) {
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp));
        }
      }
      try (var unixFirst = connect("");
          var first = admitted(host, unixFirst);
          var second = admitted(host, unixFirst);
          var third = SocketChannel.open(host.tcpAddress().orElseThrow());
          var unixSecond = connect()) {
        assertEquals(-1, third.read(ByteBuffer.allocate(8)), "past the TCP cap: closed unread");
        assertInstanceOf(PtyMessage.Sessions.class, listing(unixSecond));
        assertThrows(IOException.class, () -> connect().close(), "past the unix cap: refused");
      }
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void everyUnixConnectionReleasesItsSlotSoTheSocketServesForAsLongAsItRuns() throws Exception {
    try (var host = startHost(new PtySessionHost.Limits(32, 16, 2, 256))) {
      for (var i = 0; i < 10; i++) {
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
        try (channel) {
          PtyWire.handshake(channel, channel);
          PtyWire.write(channel, new PtyMessage.Detach());
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(channel), "connection " + i);
          assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        }
      }
      try (var unix = connect()) {
        assertInstanceOf(PtyMessage.Sessions.class, listing(unix));
      }
    }
  }

  /** A TCP connection the host is serving: it has written the magic, so it counted it. */
  private static SocketChannel served(PtySessionHost host) throws IOException {
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    var magic = ByteBuffer.allocate(8);
    while (magic.hasRemaining()) {
      assertTrue(channel.read(magic) >= 0, "a connection under the cap is served");
    }
    return channel;
  }

  /**
   * A TCP connection welcomed on a fresh ticket that {@code asker}, a unix connection that
   * presented no token, asked for; tried again while slots that ended connections held are still
   * being released. Asking on one held connection keeps the unix socket's count fixed, since a
   * closed one releases its slot only once the host has seen it close.
   */
  private SocketChannel admitted(PtySessionHost host, SocketChannel asker) throws IOException {
    PtyWire.write(
        asker, new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, "beta", "tok-uday"));
    var ticket = (PtyMessage.Ticket) PtyWire.read(asker);
    while (true) {
      var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
      try {
        PtyWire.handshake(channel, channel);
        PtyWire.write(channel, new PtyMessage.Hello(ticket.ticket()));
        assertEquals(new PtyMessage.Welcome(host.bootId()), PtyWire.read(channel));
        return channel;
      } catch (IOException turnedAway) {
        channel.close();
      }
    }
  }

  private static PtyMessage listing(SocketChannel channel) throws IOException {
    PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
    return PtyWire.read(channel);
  }

  private PtySessionHost startTcpHost() throws IOException {
    network =
        new PtySessionHost.Network(
            Optional.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
            now::get,
            holders);
    return startHost();
  }

  /** What the host answers a ticket for the holder of {@code credential}, asked as the box. */
  private PtyMessage askTicket(String credential, PtyMessage.TicketAction action, String target)
      throws IOException {
    try (var channel = connect("")) {
      PtyWire.write(channel, new PtyMessage.IssueTicket(action, target, credential));
      return PtyWire.read(channel);
    }
  }

  /** A ticket refusal as its kind and words. */
  private static String said(PtyMessage answer) {
    var refused = (PtyMessage.TicketRefused) answer;
    return refused.kind().wire() + " " + refused.detail().plain();
  }

  private static SocketChannel presentTcp(PtySessionHost host, String ticket) throws IOException {
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(ticket));
    return channel;
  }

  private static PtyMessage.SessionInfo listed(SocketChannel channel, String name)
      throws IOException {
    PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
    var listed = (PtyMessage.Sessions) PtyWire.read(channel);
    return listed.sessions().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
  }
}
