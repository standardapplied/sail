/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The in-process handoff: host A steps aside exactly as a {@code SIGTERM}'d or {@code SIGKILL}'d
 * host would leave things — masters open, rings and sidecars on disk, nothing closed — and host B,
 * in the same JVM, adopts from those descriptors under the names the store would hand back. The
 * children are wrapped in the real {@link PtyChildShim} (run from this class path), so exit
 * statuses cross the gap the way they do in production. A datagram receiver stands in for systemd
 * and proves every push and removal.
 */
@EnabledOnOs(OS.LINUX)
class PtySessionHostHandoffTest {

  @TempDir Path dir;

  private static final Function<Path, List<String>> JVM_SHIM = PtyChildShimTest::shim;

  private static final PtyIdentity.Resolver RESOLVER =
      token ->
          switch (token) {
            case "", "tok-uday" -> new PtyIdentity("uday", false);
            case "tok-mady" -> new PtyIdentity("mady", false);
            case "tok-root" -> new PtyIdentity("root", true);
            default -> throw new IOException("Session token is not valid or has expired.");
          };

  private final List<String> ended = new java.util.concurrent.CopyOnWriteArrayList<>();

  private PtySessionHost host(PtySessionHost.Handoff handoff) throws IOException {
    var host =
        new PtySessionHost(
            dir.resolve("host.sock"),
            dir.resolve("sessions"),
            64 * 1024,
            RESOLVER,
            PtyRooms.NONE,
            new PtyEvents() {
              @Override
              public void sessionStarted(PtySession.Origin origin) {}

              @Override
              public void sessionAttached(PtySession.Origin origin, String fde) {}

              @Override
              public void sessionEnded(PtySession.Origin origin, String reason) {
                ended.add(origin.name() + ":" + reason);
              }
            },
            "0.0.0-test",
            handoff);
    host.start();
    return host;
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

  private static void create(SocketChannel channel, String name, String script) throws IOException {
    PtyWire.write(
        channel, new PtyMessage.Create(name, List.of("sh", "-c", script), "/tmp", "", "", 80, 24));
    assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel), "create " + name);
  }

  private static void attach(SocketChannel channel, String name, boolean write) throws IOException {
    PtyWire.write(channel, new PtyMessage.Attach(name, write));
    var reply = PtyWire.read(channel);
    assertInstanceOf(PtyMessage.Ok.class, reply, "attach " + name + ": " + reply);
    assertInstanceOf(PtyMessage.Resized.class, PtyWire.read(channel));
    assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
  }

  private static String awaitText(SocketChannel channel, String marker) throws IOException {
    var seen = new StringBuilder();
    while (!seen.toString().contains(marker)) {
      var message = PtyWire.read(channel);
      if (message instanceof PtyMessage.Output(var seq, var bytes)) {
        seen.append(new String(bytes, StandardCharsets.UTF_8));
      }
      if (message instanceof PtyMessage.SessionEnded(var reason)) {
        throw new AssertionError("ended (" + reason + ") before '" + marker + "': " + seen);
      }
    }
    return seen.toString();
  }

  private static PtyMessage.SessionEnded awaitEnd(SocketChannel channel) throws IOException {
    while (true) {
      if (PtyWire.read(channel) instanceof PtyMessage.SessionEnded ended) {
        return ended;
      }
    }
  }

  private static Map<String, PtyMessage.SessionInfo> list(SocketChannel channel)
      throws IOException {
    PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
    while (true) {
      if (PtyWire.read(channel) instanceof PtyMessage.Sessions(var page, var next)) {
        var byName = new HashMap<String, PtyMessage.SessionInfo>();
        page.forEach(info -> byName.put(info.name(), info));
        return byName;
      }
    }
  }

  private static void await(BooleanSupplier condition, String what) {
    var deadline = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.onSpinWait();
    }
  }

  private static boolean alive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private long childPid(String name) throws IOException {
    return SessionMeta.read(SessionFiles.in(dir.resolve("sessions"), name).meta()).childPid();
  }

  /** Reads {@code count} store messages, closing the duplicates they carry; returns by name. */
  private static Map<String, String> drain(SdNotify.Receiver receiver, int count)
      throws IOException {
    var messages = new HashMap<String, String>();
    for (var i = 0; i < count; i++) {
      var message = receiver.receive();
      for (var fd : message.fds()) {
        Pty.closeFd(fd);
      }
      messages.put(
          message.field("FDNAME"), message.field("FDSTORE").isEmpty() ? "remove" : "store");
    }
    return messages;
  }

  @Test
  void aSuccessorAdoptsLiveSessionsWithHistoryKeyboardAndExitStatus() throws Exception {
    try (var receiver = SdNotify.Receiver.bind(dir.resolve("notify.sock"))) {
      var store = SdNotify.to(receiver.path().toString());
      var a = host(new PtySessionHost.Handoff(store, Map.of(), JVM_SHIM));
      String bootA;
      Map<String, PtyMessage.SessionInfo> before;
      Map<String, Integer> masters;
      try (var owner = connect("tok-uday")) {
        create(owner, "s1", "echo ready; read a; echo got:$a; exit 7");
        create(owner, "s2", "read a");
        create(owner, "s3", "read a");
        assertEquals(
            Map.of("s1", "store", "s2", "store", "s3", "store"),
            drain(receiver, 3),
            "every master goes into the store at create");
        attach(owner, "s1", true);
        awaitText(owner, "ready");
        try (var keyboard = connect("tok-uday")) {
          attach(keyboard, "s3", true);
          bootA = a.bootId();
          before = list(keyboard);
          assertEquals("uday", before.get("s3").writerFde());
          masters = a.handoff();
        }
      }
      assertEquals(java.util.Set.of("s1", "s2", "s3"), masters.keySet());
      for (var name : masters.keySet()) {
        assertTrue(alive(childPid(name)), name + "'s child outlives the handoff");
      }
      Files.delete(SessionFiles.in(dir.resolve("sessions"), "s2").ring());

      var stderr = new ByteArrayOutputStream();
      var original = System.err;
      System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
      PtySessionHost b;
      try {
        b = host(new PtySessionHost.Handoff(store, masters, JVM_SHIM));
      } finally {
        System.setErr(original);
      }
      try (b) {
        assertEquals(bootA, b.bootId(), "an adopting host keeps its predecessor's boot id");
        assertTrue(
            stderr.toString(StandardCharsets.UTF_8).contains("lost in handoff"), stderr.toString());
        assertTrue(
            stderr.toString(StandardCharsets.UTF_8).contains("session=s2"), stderr.toString());
        assertEquals(Map.of("s2", "remove"), drain(receiver, 1), "a lost session leaves the store");
        try (var owner = connect("tok-uday")) {
          var after = list(owner);
          assertEquals(java.util.Set.of("s1", "s3"), after.keySet());
          assertEquals(before.get("s1").instanceId(), after.get("s1").instanceId());
          assertEquals(before.get("s3").instanceId(), after.get("s3").instanceId());
          assertTrue(after.get("s1").live());
          assertEquals("uday", after.get("s3").writerFde(), "the keyboard's holder is restored");

          attach(owner, "s1", true);
          assertTrue(awaitText(owner, "ready").contains("ready"), "A's journal replays through B");
          PtyWire.write(owner, new PtyMessage.Input(1, "hello\n".getBytes(StandardCharsets.UTF_8)));
          awaitText(owner, "got:hello");
          assertEquals(
              "exited(7)", awaitEnd(owner).reason(), "the status crosses via the exit file");
          assertEquals(Map.of("s1", "remove"), drain(receiver, 1));
          assertTrue(ended.contains("s1:exited(7)"), ended.toString());
        }
        try (var stranger = connect("tok-root");
            var same = connect("tok-uday")) {
          attach(stranger, "s3", true);
          PtyWire.write(stranger, new PtyMessage.Input(1, "x".getBytes(StandardCharsets.UTF_8)));
          assertInstanceOf(
              PtyMessage.Err.class,
              readControl(stranger),
              "a different FDE, even an admin, does not take the keyboard from the ghost");
          attach(same, "s3", true);
          assertEquals("uday", list(same).get("s3").writerFde());
          PtyWire.write(same, new PtyMessage.Input(1, "\n".getBytes(StandardCharsets.UTF_8)));
          assertEquals("exited(0)", awaitEnd(same).reason(), "the same FDE reclaims and converses");
        }
      }
      assertFalse(Files.exists(SessionFiles.in(dir.resolve("sessions"), "s2").meta()));
      await(() -> !alive(s2Pid(masters)), "s2's child to die of the hangup");
    }
  }

  private long s2Pid(Map<String, Integer> masters) {
    try {
      return childPid("s2");
    } catch (IOException swept) {
      return -1;
    }
  }

  private static PtyMessage readControl(SocketChannel channel) throws IOException {
    while (true) {
      var message = PtyWire.read(channel);
      if (message instanceof PtyMessage.Ok || message instanceof PtyMessage.Err) {
        return message;
      }
    }
  }

  @Test
  void aConnectionStillOpenAfterTheHandoffCannotTouchWhatWasHandedOff() throws Exception {
    var a = host(new PtySessionHost.Handoff(SdNotify.NONE, Map.of(), JVM_SHIM));
    var files = SessionFiles.in(dir.resolve("sessions"), "late");
    Map<String, Integer> masters;
    try (var owner = connect("tok-uday");
        var dispatcher = connect("tok-root")) {
      create(owner, "late", "read a");
      var meta = SessionMeta.read(files.meta());
      masters = a.handoff();

      PtyWire.write(
          owner,
          new PtyMessage.Create("late", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(owner), "create after handoff");
      PtyWire.write(owner, new PtyMessage.Kill("late"));
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(owner), "kill after handoff");
      PtyWire.write(dispatcher, new PtyMessage.Yield("late", "displaced"));
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(dispatcher), "yield after handoff");
      a.sweep(Long.MAX_VALUE);

      assertEquals(meta, SessionMeta.read(files.meta()), "the sidecar is the successor's now");
      assertTrue(Files.exists(files.ring()), "and so is the ring");
      assertTrue(alive(meta.childPid()), "the child was never ended");
    }
    try (var b = host(new PtySessionHost.Handoff(SdNotify.NONE, masters, JVM_SHIM));
        var owner = connect("tok-uday")) {
      assertEquals(a.bootId(), b.bootId());
      assertTrue(list(owner).get("late").live(), "the successor adopts it intact");
    }
  }

  @Test
  void aHostThatInheritsNothingMintsANewBootIdAndSweepsWhatWasLeft() throws Exception {
    var a = host(new PtySessionHost.Handoff(SdNotify.NONE, Map.of(), JVM_SHIM));
    String bootA;
    Map<String, Integer> masters;
    try (var owner = connect("tok-uday")) {
      create(owner, "s1", "read a");
      bootA = a.bootId();
      masters = a.handoff();
    }
    var files = SessionFiles.in(dir.resolve("sessions"), "s1");
    assertTrue(Files.exists(files.ring()) && Files.exists(files.meta()));
    var child = childPid("s1");

    try (var b = host(PtySessionHost.Handoff.NONE);
        var owner = connect("tok-uday")) {
      assertNotEquals(bootA, b.bootId(), "nothing adopted: a new boot");
      assertTrue(list(owner).isEmpty());
      assertFalse(Files.exists(files.ring()), "the orphan ring is swept");
      assertFalse(Files.exists(files.meta()), "with its sidecar");
    }
    for (var fd : masters.values()) {
      Pty.closeFd(fd);
    }
    await(() -> !alive(child), "the unadopted child to die once its master closes");
  }

  @Test
  void anUnsoundInheritanceIsClosedRemovedFromTheStoreAndLoggedNeverLeaked() throws Exception {
    try (var receiver = SdNotify.Receiver.bind(dir.resolve("notify.sock"))) {
      var store = SdNotify.to(receiver.path().toString());
      var a = host(new PtySessionHost.Handoff(store, Map.of(), JVM_SHIM));
      Map<String, Integer> masters;
      try (var owner = connect("tok-uday")) {
        create(owner, "ok", "read a");
        create(owner, "no-ring", "read a");
        create(owner, "no-meta", "read a");
        create(owner, "bad-meta", "read a");
        create(owner, "not-a-pty", "read a");
        drain(receiver, 5);
        masters = new HashMap<>(a.handoff());
      }
      var pids = new HashMap<String, Long>();
      for (var name : masters.keySet()) {
        pids.put(name, childPid(name));
      }
      var sessions = dir.resolve("sessions");
      Files.delete(SessionFiles.in(sessions, "no-ring").ring());
      Files.delete(SessionFiles.in(sessions, "no-meta").meta());
      Files.writeString(SessionFiles.in(sessions, "bad-meta").meta(), "{\"version\": 1}");
      var decoy = SdNotify.Receiver.bind(dir.resolve("decoy.sock"));
      Pty.closeFd(masters.get("not-a-pty"));
      masters.put("not-a-pty", decoy.fd());
      masters.put("../escape", 0);

      var stderr = new ByteArrayOutputStream();
      var original = System.err;
      System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
      PtySessionHost b;
      try {
        b = host(new PtySessionHost.Handoff(store, masters, JVM_SHIM));
      } finally {
        System.setErr(original);
      }
      var log = stderr.toString(StandardCharsets.UTF_8);
      try (b;
          var owner = connect("tok-uday")) {
        assertEquals(java.util.Set.of("ok"), list(owner).keySet(), log);
        for (var lost : List.of("no-ring", "no-meta", "bad-meta", "not-a-pty", "../escape")) {
          assertTrue(log.contains("lost in handoff") && log.contains("session=" + lost), log);
        }
        var removed = drain(receiver, 5);
        assertEquals(
            java.util.Set.of("no-ring", "no-meta", "bad-meta", "not-a-pty", "../escape"),
            removed.keySet());
        assertTrue(removed.values().stream().allMatch("remove"::equals), removed.toString());
        for (var name : List.of("no-ring", "no-meta", "bad-meta")) {
          assertFalse(Files.exists(SessionFiles.in(sessions, name).ring()), name + " swept");
          assertFalse(Files.exists(SessionFiles.in(sessions, name).meta()), name + " swept");
          await(() -> !alive(pids.get(name)), name + "'s child to die of the hangup");
        }
        assertTrue(alive(pids.get("ok")), "the sound session's child is untouched");
        await(() -> !alive(pids.get("not-a-pty")), "the closed master to hang its child up");
      }
      decoy.close();
    }
  }

  @Test
  void anUnreadableBootFileMintsANewIdButStillAdopts() throws Exception {
    var a = host(new PtySessionHost.Handoff(SdNotify.NONE, Map.of(), JVM_SHIM));
    String bootA;
    Map<String, Integer> masters;
    try (var owner = connect("tok-uday")) {
      create(owner, "s1", "read a");
      bootA = a.bootId();
      masters = a.handoff();
    }
    var bootFile = dir.resolve("sessions").resolve(PtySessionHost.BOOT_ID_FILE);
    Files.delete(bootFile);
    Files.createDirectory(bootFile);

    try (var b = host(new PtySessionHost.Handoff(SdNotify.NONE, masters, JVM_SHIM));
        var owner = connect("tok-uday")) {
      assertNotEquals(bootA, b.bootId());
      assertFalse(b.bootId().isBlank());
      assertEquals(java.util.Set.of("s1"), list(owner).keySet(), "the session is adopted anyway");
    }
  }

  @Test
  void aCreateDiscardsAStaleExitFileAndSidecarFromAPreviousLife() throws Exception {
    var sessions = Files.createDirectories(dir.resolve("sessions"));
    var files = SessionFiles.in(sessions, "again");
    try (var ignored = host(new PtySessionHost.Handoff(SdNotify.NONE, Map.of(), JVM_SHIM));
        var owner = connect("tok-uday")) {
      Files.writeString(files.exit(), "42");
      Files.writeString(files.meta(), "stale");
      create(owner, "again", "exit 0");
      attach(owner, "again", true);
      assertEquals("exited(0)", awaitEnd(owner).reason(), "never the previous life's 42");
      assertNotEquals("stale", Files.readString(files.meta()));
      PtyWire.write(owner, new PtyMessage.Kill("again"));
      assertInstanceOf(PtyMessage.Ok.class, readControl(owner));
      assertFalse(Files.exists(files.exit()), "a kill removes the exit file with the ring");
      assertFalse(Files.exists(files.meta()));
    }
  }

  @Test
  void aStopEndsEverySessionLoudlyWithTheReasonAndKillsTheChildren() throws Exception {
    var host = host(new PtySessionHost.Handoff(SdNotify.NONE, Map.of(), JVM_SHIM));
    try (var one = connect("tok-uday");
        var two = connect("tok-uday")) {
      create(one, "s1", "echo up1; read a");
      create(two, "s2", "echo up2; read a");
      attach(one, "s1", true);
      attach(two, "s2", false);
      awaitText(one, "up1");
      awaitText(two, "up2");
      var pid1 = childPid("s1");
      var pid2 = childPid("s2");

      host.stop("pty host stopped");

      awaitText(one, "[sail: session ended — pty host stopped]");
      assertEquals("pty host stopped", awaitEnd(one).reason());
      awaitText(two, "[sail: session ended — pty host stopped]");
      assertEquals("pty host stopped", awaitEnd(two).reason());
      await(() -> !alive(pid1) && !alive(pid2), "the children to be gone");
      assertEquals(0, host.sessionCount());
      assertTrue(ended.contains("s1:pty host stopped") && ended.contains("s2:pty host stopped"));
    }
  }
}
