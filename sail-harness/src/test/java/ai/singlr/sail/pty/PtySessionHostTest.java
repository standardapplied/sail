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
import java.nio.file.Files;
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

  private static final PtyRooms ROOMS =
      (room, project, who) -> {
        if (!room.equals("design-talk")) {
          throw new IOException("Room '" + room + "' was not found.");
        }
        if (!who.admin() && !who.fde().equals("uday")) {
          throw new IOException("Room 'design-talk' is assigned to 'uday', not you.");
        }
      };

  private PtySessionHost startHost() throws IOException {
    host =
        new PtySessionHost(
            dir.resolve("host.sock"),
            dir.resolve("sessions"),
            64 * 1024,
            RESOLVER,
            ROOMS,
            new PtyEvents() {
              @Override
              public void sessionStarted(PtySession.Origin origin) {
                events.add("started:" + origin.name() + ":" + origin.ownerFde());
              }

              @Override
              public void sessionAttached(PtySession.Origin origin, String fde) {
                events.add("attached:" + origin.name() + ":" + fde);
              }

              @Override
              public void sessionEnded(PtySession.Origin origin, String reason) {
                events.add("ended:" + origin.name());
              }
            });
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

        try (var owner = connect("tok-uday")) {
          PtyWire.write(owner, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
          var listed = (PtyMessage.Sessions) PtyWire.read(owner);
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
        assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(owner));
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
              "the dispatch authority yields and does nothing else");
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
        assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
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
        assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
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
        var command = new java.util.ArrayList<>(List.of("/bin/true"));
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
}
