/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uploads through a real host, its sessions real processes, into a container that is a directory
 * here: each script the host sends a container runs on this machine as this account, under a root
 * of the test's own, with this account standing for both root and the dev user. Each test tries the
 * wrong thing the rule it names forbids. The scripts are a Linux container's (GNU {@code find},
 * {@code stat} and {@code df}), so they run on Linux alone.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PtyUploadsTest {

  private static final String SESSION_COMMAND = "while read a; do :; done";

  @TempDir Path dir;

  private final LinkedBlockingQueue<List<String>> commands = new LinkedBlockingQueue<>();

  /** Whether the next open never answers, as a frozen container's would not. */
  private volatile boolean openHangs;

  /** The status the next open exits with instead of running, when set. */
  private volatile Integer openExits;

  /** Whether each copy into the container keeps only its first byte, as a cut-off stream would. */
  private volatile boolean copyTruncates;

  /** The status every commit exits with instead of running, when set. */
  private volatile Integer commitExits;

  /** Held before a commit runs, when set: a commit the test releases. */
  private volatile CountDownLatch commitHeld;

  /** Held before the next open runs, when set, and cleared as it is taken. */
  private volatile CountDownLatch openHeld;

  /** Held before the next sweep runs, when set, and cleared as it is taken. */
  private volatile CountDownLatch sweepHeld;

  /** Each script but an open, put here once it exited, which its caller waits for anyway. */
  private final LinkedBlockingQueue<String> finished = new LinkedBlockingQueue<>();

  /** The status every removal exits with instead of running, when set. */
  private volatile Integer removeExits;

  /** Held before a removal runs, when set. */
  private volatile CountDownLatch removeHeld;

  /** Held as the host waits for a copy to exit, when set; {@link #copyReached} once it waits. */
  private volatile CountDownLatch copyHeld;

  private final CountDownLatch copyReached = new CountDownLatch(1);

  private Path root() {
    return dir.resolve("var-lib-sail-paste");
  }

  /** The container: each command run here after its {@code incus exec ... --}, every one kept. */
  private final PtyUploads.Processes processes =
      command -> {
        commands.add(List.copyOf(command));
        var inner = new ArrayList<>(command.subList(command.indexOf("--") + 1, command.size()));
        var script = inner.size() > 2 ? inner.get(2) : "";
        if (script.equals(PtyUploads.OPEN)) {
          var held = openHeld;
          openHeld = null;
          await(held);
        }
        if (script.equals(PtyUploads.SWEEP)) {
          var held = sweepHeld;
          sweepHeld = null;
          await(held);
        }
        if (script.equals(PtyUploads.OPEN) && copyTruncates) {
          inner.set(2, script.replace("cat >&3", "{ head -c 1; cat > /dev/null; } >&3"));
        }
        if (script.equals(PtyUploads.OPEN) && openHangs) {
          openHangs = false;
          inner = new ArrayList<>(List.of("sh", "-c", "exec tail -f /dev/null"));
        }
        if (script.equals(PtyUploads.OPEN) && openExits != null) {
          inner = new ArrayList<>(List.of("sh", "-c", "exit " + openExits));
        }
        if (script.equals(PtyUploads.COMMIT)) {
          await(commitHeld);
          if (commitExits != null) {
            inner = new ArrayList<>(List.of("sh", "-c", "exit " + commitExits));
          }
        }
        if (script.equals(PtyUploads.REMOVE)) {
          await(removeHeld);
          if (removeExits != null) {
            inner = new ArrayList<>(List.of("sh", "-c", "exit " + removeExits));
          }
        }
        var process =
            new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!script.equals(PtyUploads.OPEN)) {
          try {
            process.waitFor();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finished.add(script);
        }
        return script.equals(PtyUploads.OPEN) ? new HeldCopy(process) : process;
      };

  /** A copy whose exit the host waits for only once {@link #copyHeld} lets it, when set. */
  private final class HeldCopy extends Process {
    private final Process process;

    HeldCopy(Process process) {
      this.process = process;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      copyReached.countDown();
      await(copyHeld);
      return process.waitFor(timeout, unit);
    }

    @Override
    public int waitFor() throws InterruptedException {
      return waitFor(1, TimeUnit.MINUTES) ? process.exitValue() : -1;
    }

    @Override
    public java.io.OutputStream getOutputStream() {
      return process.getOutputStream();
    }

    @Override
    public java.io.InputStream getInputStream() {
      return process.getInputStream();
    }

    @Override
    public java.io.InputStream getErrorStream() {
      return process.getErrorStream();
    }

    @Override
    public int exitValue() {
      return process.exitValue();
    }

    @Override
    public void destroy() {
      process.destroy();
    }
  }

  private static void await(CountDownLatch latch) {
    if (latch == null) {
      return;
    }
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private PtyUploads.Place place() throws IOException {
    var uid = Files.getAttribute(dir, "unix:uid").toString();
    var gid = Files.getAttribute(dir, "unix:gid").toString();
    Function<String, List<String>> asRoot = container -> List.of("incus", "exec", container, "--");
    Function<String, List<String>> asDev =
        container -> List.of("incus", "exec", container, "--user", uid, "--");
    return new PtyUploads.Place(
        asRoot, asDev, root().toString(), uid, uid, gid, List.of(), Duration.ofSeconds(3));
  }

  private final PtyIdentity.Resolver resolver =
      token ->
          switch (token) {
            case "tok-uday" ->
                new PtyIdentity.Person(new Actor("uday", Role.MEMBER, Actor.Lane.API));
            default -> throw new IOException("Session token is not valid or has expired.");
          };

  private final PtyAdmission admission =
      new PtyAdmission() {
        @Override
        public void box(PtyIdentity.Person who) {}

        @Override
        public void project(String project, PtyIdentity.Person who) {}

        @Override
        public void room(String room, String project, PtyIdentity.Person who) {}
      };

  private PtySessionHost startHost() throws IOException {
    return startHost(origin -> PtyUploads.Target.own("acme"));
  }

  private PtySessionHost startHost(Function<PtySession.Origin, PtyUploads.Target> targets)
      throws IOException {
    uploads = new PtyUploads(processes, place(), targets);
    var host =
        new PtySessionHost(
            dir.resolve("host.sock"),
            dir.resolve("sessions"),
            64 * 1024,
            resolver,
            admission,
            PtyEvents.NONE,
            "0.0.0-test",
            new PtySessionHost.Limits(32, 32, 256, 256),
            PtySessionHost.Handoff.NONE,
            PtySessionHost.Network.NONE,
            uploads);
    host.start();
    return host;
  }

  private PtyUploads uploads;

  private PtyUploads uploadsOf(PtySessionHost host) {
    assertNotNull(host);
    return uploads;
  }

  private SocketChannel connect() throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello("tok-uday"));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  /** A connection that made session {@code name} and writes in it. */
  private SocketChannel writer(String name) throws IOException {
    var channel = connect();
    PtyWire.write(
        channel,
        new PtyMessage.Create(
            name, List.of("sh", "-c", SESSION_COMMAND), "/tmp", "", "", 80, 24, ""));
    assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
    return attached(channel, name, true);
  }

  private static SocketChannel attached(SocketChannel channel, String name, boolean write)
      throws IOException {
    PtyWire.write(channel, new PtyMessage.Attach(name, write));
    assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
    return channel;
  }

  /** The next answer to an upload, past the session's own frames. */
  private static PtyMessage answer(SocketChannel channel) throws IOException {
    while (true) {
      var message = PtyWire.read(channel);
      if (message instanceof PtyMessage.PutAck
          || message instanceof PtyMessage.PutStored
          || message instanceof PtyMessage.PutRefused
          || message instanceof PtyMessage.Err) {
        return message;
      }
    }
  }

  private static PtyMessage send(SocketChannel channel, int id, byte[] bytes) throws Exception {
    for (var offset = 0; offset < bytes.length; offset += PtyUploads.CHUNK) {
      var chunk =
          Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + PtyUploads.CHUNK));
      PtyWire.write(channel, new PtyMessage.PutChunk(id, offset, chunk));
      var acked = answer(channel);
      if (!(acked instanceof PtyMessage.PutAck)) {
        return acked;
      }
    }
    PtyWire.write(channel, new PtyMessage.PutEnd(id, sha256(bytes)));
    return answer(channel);
  }

  /** Uploads {@code bytes} as {@code name} in chunks of the largest size: the host's answer. */
  private static PtyMessage upload(SocketChannel channel, int id, String name, byte[] bytes)
      throws Exception {
    PtyWire.write(channel, new PtyMessage.PutBegin(id, name, bytes.length));
    var begun = answer(channel);
    if (!(begun instanceof PtyMessage.PutAck)) {
      return begun;
    }
    return send(channel, id, bytes);
  }

  private static byte[] sha256(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(bytes);
  }

  /** The files and links the session directory of {@code stored} holds, by name. */
  private static List<String> held(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(directory)) {
      return entries.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private Path inRoot(String path) {
    return Path.of(path);
  }

  private void awaitFinished(String script) throws InterruptedException {
    while (true) {
      var done = finished.poll(30, TimeUnit.SECONDS);
      assertNotNull(done, "no script finished " + script.lines().findFirst().orElse(""));
      if (done.equals(script)) {
        return;
      }
    }
  }

  private void awaitCommand(String script) throws InterruptedException {
    while (true) {
      var command = commands.poll(30, TimeUnit.SECONDS);
      assertNotNull(command, "no command ran " + script.lines().findFirst().orElse(""));
      if (command.contains(script)) {
        return;
      }
    }
  }

  @Test
  void aFileIsStoredWholeInItsSessionsOwnDirectoryAsTheDevUsersAloneAndNothingIsKeptOnTheHost()
      throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var bytes = new byte[PtyUploads.CHUNK * 2 + 5];
      bytes[bytes.length - 1] = 9;

      var stored =
          assertInstanceOf(PtyMessage.PutStored.class, upload(channel, 3, "shot.png", bytes));

      assertEquals(3, stored.id());
      assertTrue(stored.path().matches(root() + "/[0-9a-f-]{36}/1-shot\\.png"), stored.path());
      var file = inRoot(stored.path());
      assertArrayEquals(bytes, Files.readAllBytes(file));
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
      assertEquals(
          "rwx------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
      assertEquals(
          "rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(root())));
      assertEquals(List.of("1-shot.png"), held(file.getParent()), "no temporary is left");
      assertFalse(Files.exists(dir.resolve("staging")), "nothing of the file sits on the host");
    }
  }

  @Test
  void noNameAFileOrALinkHoldsIsWrittenAndALinkAtTheTemporaryIsNeverWrittenThrough()
      throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var first =
          (PtyMessage.PutStored)
              upload(channel, 1, "a.png", "one".getBytes(StandardCharsets.UTF_8));
      var session = inRoot(first.path()).getParent();
      Files.writeString(session.resolve("2-a.png"), "a host before this one stored it");
      var target = dir.resolve("elsewhere");
      Files.createSymbolicLink(session.resolve("3-a.png"), target);
      Files.createSymbolicLink(session.resolve(".4-a.png.part"), target);

      var next =
          (PtyMessage.PutStored)
              upload(channel, 2, "a.png", "two".getBytes(StandardCharsets.UTF_8));

      assertTrue(next.path().endsWith("/5-a.png"), next.path());
      assertEquals("one", Files.readString(inRoot(first.path())));
      assertEquals(
          "a host before this one stored it", Files.readString(session.resolve("2-a.png")));
      assertEquals("two", Files.readString(inRoot(next.path())));
      assertFalse(Files.exists(target), "nothing was written through a planted link");
    }
  }

  @Test
  void aWatcherIsRefusedAndAWriterThatLosesTheTokenCannotFinish() throws Exception {
    try (var ignored = startHost();
        var writer = writer("lounge");
        var watcher = attached(connect(), "lounge", false)) {
      PtyWire.write(watcher, new PtyMessage.PutBegin(1, "shot.png", 5));
      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.NOT_WRITER, 0), answer(watcher));

      PtyWire.write(writer, new PtyMessage.PutBegin(2, "shot.png", 4));
      assertEquals(new PtyMessage.PutAck(2, 0), answer(writer));
      PtyWire.write(watcher, new PtyMessage.TakeWrite());
      handled(watcher);
      PtyWire.write(writer, new PtyMessage.PutChunk(2, 0, new byte[] {1, 2, 3, 4}));
      assertEquals(new PtyMessage.PutRefused(2, PutRefusal.NOT_WRITER, 0), answer(writer));
      awaitFinished(PtyUploads.ABORT);

      PtyWire.write(writer, new PtyMessage.TakeWrite());
      PtyWire.write(writer, new PtyMessage.PutBegin(3, "shot.png", 1));
      assertEquals(new PtyMessage.PutAck(3, 0), answer(writer));
      PtyWire.write(writer, new PtyMessage.PutChunk(3, 0, new byte[] {1}));
      assertEquals(new PtyMessage.PutAck(3, 1), answer(writer));
      PtyWire.write(watcher, new PtyMessage.TakeWrite());
      handled(watcher);
      PtyWire.write(writer, new PtyMessage.PutEnd(3, sha256(new byte[] {1})));
      assertEquals(new PtyMessage.PutRefused(3, PutRefusal.NOT_WRITER, 0), answer(writer));
      awaitFinished(PtyUploads.ABORT);
      assertEquals(List.of(), held(onlySession()));
    }
  }

  /** Returns once the host handled every frame sent on {@code channel} before now. */
  private static void handled(SocketChannel channel) throws IOException {
    PtyWire.write(channel, new PtyMessage.ListSessions("", 1));
    while (true) {
      if (PtyWire.read(channel) instanceof PtyMessage.Sessions) {
        return;
      }
    }
  }

  private Path onlySession() throws IOException {
    try (Stream<Path> entries = Files.list(root())) {
      return entries.findFirst().orElseThrow();
    }
  }

  @Test
  void aSessionOnTheBoxOrInASharedContainerTakesNoFileAndAWorkspaceTakesItsOwn() throws Exception {
    try (var ignored = startHost(PtyUploads::targetOf);
        var channel = writer("lounge")) {
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "shot.png", 5));
      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.NO_CONTAINER, 0), answer(channel));
    }
    var shared = new PtySession.Origin("lounge", "inst-1", "uday", "acme", "", List.of("bash"), "");
    assertEquals(PtyUploads.Target.SHARED, PtyUploads.targetOf(shared));
    assertEquals(PutRefusal.SHARED_CONTAINER, PtyUploads.targetOf(shared).refused());
    var own =
        new PtySession.Origin("lounge", "inst-1", "uday", "acme", "", List.of("bash"), "dock");
    assertEquals(PtyUploads.Target.own("acme-dock"), PtyUploads.targetOf(own));
    assertFalse(Files.exists(root()));
  }

  @Test
  void aFileOverTheLargestOrAChunkOverItsBoundIsRefused() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "big.mov", PtyUploads.ITEM + 1));
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.TOO_LARGE, PtyUploads.ITEM), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutBegin(2, "less.mov", -1));
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.TOO_LARGE, PtyUploads.ITEM), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutBegin(3, "a", PtyUploads.CHUNK + 1));
      assertEquals(new PtyMessage.PutAck(3, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(3, 0, new byte[PtyUploads.CHUNK + 1]));
      assertEquals(new PtyMessage.PutRefused(3, PutRefusal.FAILED, 0), answer(channel));
    }
  }

  @Test
  void aSessionHoldsAtMostItsFilesAndItsBytesAcrossItsConnectionsAndOnItsDisk() throws Exception {
    try (var ignored = startHost();
        var first = writer("study")) {
      var holders = new ArrayList<SocketChannel>();
      try {
        for (var i = 0; i < PtyUploads.SESSION / PtyUploads.ITEM; i++) {
          var next = attached(connect(), "study", true);
          holders.add(next);
          PtyWire.write(next, new PtyMessage.PutBegin(1, "part", PtyUploads.ITEM));
          assertEquals(new PtyMessage.PutAck(1, 0), answer(next));
        }
        PtyWire.write(first, new PtyMessage.TakeWrite());
        PtyWire.write(first, new PtyMessage.PutBegin(1, "one-more", 1));
        assertEquals(
            new PtyMessage.PutRefused(1, PutRefusal.SESSION_FULL, PtyUploads.SESSION),
            answer(first));
      } finally {
        for (var holder : holders) {
          holder.close();
        }
      }
    }
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      var session = inRoot(stored.path()).getParent();
      for (var i = 2; i <= PtyUploads.FILES; i++) {
        Files.createFile(session.resolve(i + "-left"));
      }
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.TOO_MANY, PtyUploads.FILES),
          upload(channel, 2, "b", new byte[] {1}));
    }
  }

  @Test
  void chunksOutOfOrderPastTheSizeOrNotMatchingTheirDigestLeaveNothing() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "a", 4));
      assertEquals(new PtyMessage.PutAck(1, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(1, 2, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.FAILED, 0), answer(channel));

      PtyWire.write(channel, new PtyMessage.PutBegin(2, "a", 1));
      assertEquals(new PtyMessage.PutAck(2, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(2, 0, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutRefused(2, PutRefusal.FAILED, 0), answer(channel));

      PtyWire.write(channel, new PtyMessage.PutBegin(3, "a", 2));
      assertEquals(new PtyMessage.PutAck(3, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(3, 0, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutAck(3, 2), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutEnd(3, sha256(new byte[] {1, 3})));
      assertEquals(new PtyMessage.PutRefused(3, PutRefusal.FAILED, 0), answer(channel));

      PtyWire.write(channel, new PtyMessage.PutBegin(4, "a", 3));
      assertEquals(new PtyMessage.PutAck(4, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(4, 0, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutAck(4, 2), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutEnd(4, sha256(new byte[] {1, 2})));
      assertEquals(new PtyMessage.PutRefused(4, PutRefusal.FAILED, 0), answer(channel));

      for (var aborted = 0; aborted < 4; aborted++) {
        awaitFinished(PtyUploads.ABORT);
      }
      var session = onlySession();
      assertEquals(List.of(), held(session), "no temporary and no file is left");
    }
  }

  @Test
  void anUploadCancelledOrCutOffMidwayLeavesNothing() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "a", 4));
      assertEquals(new PtyMessage.PutAck(1, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(1, 0, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutAck(1, 2), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutCancel(1));
      awaitFinished(PtyUploads.ABORT);
      var session = onlySession();
      assertEquals(List.of(), held(session));

      PtyWire.write(channel, new PtyMessage.PutBegin(2, "b", 4));
      assertEquals(new PtyMessage.PutAck(2, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(2, 0, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutAck(2, 2), answer(channel));
      channel.close();
      awaitFinished(PtyUploads.ABORT);
      assertEquals(List.of(), held(session));
    }
  }

  @Test
  void aCancelAfterTheEndKeepsNothingUntilTheFileIsStoredAndNothingOnceItIs() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      copyHeld = new CountDownLatch(1);
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "a", 1));
      assertEquals(new PtyMessage.PutAck(1, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(1, 0, new byte[] {7}));
      assertEquals(new PtyMessage.PutAck(1, 1), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutEnd(1, sha256(new byte[] {7})));
      copyReached.await();
      PtyWire.write(channel, new PtyMessage.PutCancel(1));
      PtyWire.write(channel, new PtyMessage.PutQuery(99));
      assertEquals(new PtyMessage.PutRefused(99, PutRefusal.UNKNOWN, 0), answer(channel));
      copyHeld.countDown();
      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.CANCELLED, 0), answer(channel));
      assertEquals(List.of(), held(onlySession()));

      copyHeld = null;
      var stored = (PtyMessage.PutStored) upload(channel, 2, "b", new byte[] {8});
      PtyWire.write(channel, new PtyMessage.PutCancel(2));
      PtyWire.write(channel, new PtyMessage.PutQuery(2));
      assertEquals(stored, answer(channel), "a stored file stays stored");
      assertTrue(Files.exists(inRoot(stored.path())));
    }
  }

  @Test
  void aClientThatLostItsConnectionAfterTheEndHearsWhatBecameOfTheUpload() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      commitHeld = new CountDownLatch(1);
      PtyWire.write(channel, new PtyMessage.PutBegin(5, "a", 1));
      assertEquals(new PtyMessage.PutAck(5, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(5, 0, new byte[] {7}));
      assertEquals(new PtyMessage.PutAck(5, 1), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutEnd(5, sha256(new byte[] {7})));
      awaitCommand(PtyUploads.COMMIT);
      channel.close();
      try (var again = attached(connect(), "lounge", true)) {
        PtyWire.write(again, new PtyMessage.PutQuery(5));
        commitHeld.countDown();
        var stored = assertInstanceOf(PtyMessage.PutStored.class, answer(again));
        assertTrue(Files.exists(inRoot(stored.path())));
      }
    }
  }

  @Test
  void theContainersRefusalIsToldByItsExitStatus() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      openExits = PtyUploads.NO_SPACE;
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.NO_SPACE, 0),
          upload(channel, 1, "a", new byte[] {1}));
      openExits = PtyUploads.UNSAFE;
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.FAILED, 0),
          upload(channel, 2, "a", new byte[] {1}));
      openExits = null;
      commitExits = 1;
      assertEquals(
          new PtyMessage.PutRefused(3, PutRefusal.FAILED, 0),
          upload(channel, 3, "a", new byte[] {1}));
    }
  }

  /** Major 1: a copy that kept fewer bytes than were sent never takes the file's name. */
  @Test
  void aCopyShorterThanTheFileIsNeverStored() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      copyTruncates = true;
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.FAILED, 0),
          upload(channel, 1, "a", new byte[] {1, 2, 3}));
      assertEquals(List.of(), held(onlySession()));
    }
  }

  /**
   * Major 7: a failed upload hands back what it took of the session's allowance: more uploads
   * refused by the container, then more whose commit failed, than a session keeps, and the next one
   * is stored.
   */
  @Test
  void failedUploadsHandBackTheirShareOfTheSessionsAllowance() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var id = 0;
      openExits = PtyUploads.NO_SPACE;
      for (var i = 0; i <= PtyUploads.FILES; i++, id++) {
        assertEquals(
            new PtyMessage.PutRefused(id, PutRefusal.NO_SPACE, 0),
            upload(channel, id, "a", new byte[] {1}));
      }
      openExits = null;
      commitExits = 1;
      for (var i = 0; i <= PtyUploads.FILES; i++, id++) {
        assertEquals(
            new PtyMessage.PutRefused(id, PutRefusal.FAILED, 0),
            upload(channel, id, "a", new byte[] {1}));
      }
      commitExits = null;
      assertInstanceOf(PtyMessage.PutStored.class, upload(channel, id, "a", new byte[] {1}));
    }
  }

  /** Major 7: an upload whose session ended while it waited to open makes nothing. */
  @Test
  void anUploadWhoseSessionEndedBeforeItOpenedMakesNothing() throws Exception {
    try (var ignored = startHost();
        var study = writer("study");
        var lounge = writer("lounge")) {
      openHeld = new CountDownLatch(1);
      var release = openHeld;
      PtyWire.write(study, new PtyMessage.PutBegin(1, "a", 1));
      awaitCommand(PtyUploads.OPEN);
      PtyWire.write(lounge, new PtyMessage.PutBegin(2, "b", 1));
      try (var killer = connect()) {
        PtyWire.write(killer, new PtyMessage.Kill("lounge"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
      }
      release.countDown();
      assertEquals(new PtyMessage.PutAck(1, 0), answer(study));
      assertEquals(new PtyMessage.PutRefused(2, PutRefusal.SESSION_ENDED, 0), answer(lounge));
      try (Stream<Path> entries = Files.list(root())) {
        assertEquals(1, entries.count(), "only the live session's directory was made");
      }
    }
  }

  /** An open the container never answers is given up, and holds no lock past its patience. */
  @Test
  void anOpenThatNeverAnswersIsGivenUpAndTheNextUploadIsStored() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      openHangs = true;
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.FAILED, 0),
          upload(channel, 1, "a", new byte[] {1}));
      assertInstanceOf(PtyMessage.PutStored.class, upload(channel, 2, "a", new byte[] {1}));
    }
  }

  /** Major 7: a session's record goes with it. */
  @Test
  void aSessionsRecordGoesWithIt() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var host = ignored;
      upload(channel, 1, "a", new byte[] {1});
      assertEquals(1, uploadsOf(host).records());
      try (var killer = connect()) {
        PtyWire.write(killer, new PtyMessage.Kill("lounge"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
      }
      assertEquals(0, uploadsOf(host).records());
    }
  }

  /**
   * Major 2 and 7: a sweep that read which sessions are live before a session was made does not
   * remove what that session's first upload makes, which waits for the sweep.
   */
  @Test
  void aSweepNeverRemovesWhatALiveSessionsFirstUploadMakes() throws Exception {
    try (var ignored = startHost()) {
      sweepHeld = new CountDownLatch(1);
      var release = sweepHeld;
      try (var study = writer("study")) {
        awaitCommand(PtyUploads.SWEEP);
        try (var lounge = writer("lounge")) {
          PtyWire.write(lounge, new PtyMessage.PutBegin(1, "a", 1));
          release.countDown();
          awaitFinished(PtyUploads.SWEEP);
          assertEquals(new PtyMessage.PutAck(1, 0), answer(lounge));
          var stored =
              assertInstanceOf(PtyMessage.PutStored.class, send(lounge, 1, new byte[] {1}));
          assertTrue(Files.exists(inRoot(stored.path())), "a live session's file is kept");
          assertNotNull(study);
        }
      }
    }
  }

  /** Major 5: a link a session's directory holds is removed as a link, never followed. */
  @Test
  void aLinkInASessionsDirectoryIsRemovedAsALink() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      var session = inRoot(stored.path()).getParent();
      var outside = Files.createDirectories(dir.resolve("home"));
      Files.writeString(outside.resolve("work"), "the person's own");
      Files.createSymbolicLink(session.resolve("2-a"), outside);
      Files.createSymbolicLink(session.resolve("3-a"), outside.resolve("work"));
      try (var killer = connect()) {
        PtyWire.write(killer, new PtyMessage.Kill("lounge"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
      }
      awaitFinished(PtyUploads.REMOVE);
      assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
      assertEquals("the person's own", Files.readString(outside.resolve("work")));
    }
  }

  /** Major 5: a session's directory that is a link is neither written through nor removed. */
  @Test
  void aSessionsDirectoryThatIsALinkIsNeitherWrittenThroughNorFollowed() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      var session = inRoot(stored.path()).getParent();
      Files.delete(inRoot(stored.path()));
      Files.delete(session);
      var outside = Files.createDirectories(dir.resolve("home"));
      Files.writeString(outside.resolve("work"), "the person's own");
      Files.createSymbolicLink(session, outside);

      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.FAILED, 0),
          upload(channel, 2, "b", new byte[] {2}));
      try (var killer = connect()) {
        PtyWire.write(killer, new PtyMessage.Kill("lounge"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
      }
      awaitFinished(PtyUploads.REMOVE);
      assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
      try (Stream<Path> entries = Files.list(outside)) {
        assertEquals(List.of(outside.resolve("work")), entries.toList());
      }
      assertEquals("the person's own", Files.readString(outside.resolve("work")));
    }
  }

  /** Major 2: a container's slow removal holds no other session's create. */
  @Test
  void aSlowRemovalHoldsNoOtherSessionsCreate() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      upload(channel, 1, "a", new byte[] {1});
      removeHeld = new CountDownLatch(1);
      try {
        try (var killer = connect()) {
          PtyWire.write(killer, new PtyMessage.Kill("lounge"));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
        }
        awaitCommand(PtyUploads.REMOVE);
        try (var another = writer("study")) {
          assertTrue(another.isOpen(), "the create was answered while the removal was held");
        }
      } finally {
        removeHeld.countDown();
      }
    }
  }

  @Test
  void aNewSessionSweepsTheUploadsOfSessionsNoLongerLiveAndNothingElse() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      var stale = root().resolve("0f0e0d0c-0000-0000-0000-000000000000");
      Files.createDirectories(stale);
      Files.writeString(stale.resolve("1-left.png"), "left by a stopped container");
      var notAnId = root().resolve("keep-me");
      Files.createDirectories(notAnId);
      var outside = Files.createDirectories(dir.resolve("home"));
      Files.writeString(outside.resolve("work"), "the person's own");
      Files.createSymbolicLink(root().resolve("0a0b0c0d-1111-1111-1111-111111111111"), outside);

      try (var second = writer("study")) {
        awaitFinished(PtyUploads.SWEEP);
        assertFalse(Files.exists(stale));
        assertNotNull(second);
      }

      assertTrue(Files.exists(inRoot(stored.path())), "a live session's file is kept");
      assertTrue(Files.exists(notAnId), "a name that is no instance id is kept");
      assertEquals(
          "the person's own", Files.readString(outside.resolve("work")), "no link followed");
    }
  }

  /** Minor 9: a session whose shell exited and whose removal failed is swept by the next one. */
  @Test
  void aSessionThatExitedIsSweptByTheNextSessionWhenItsRemovalFailed() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      removeExits = 1;
      PtyWire.write(channel, new PtyMessage.Input(1, new byte[] {4}));
      awaitFinished(PtyUploads.REMOVE);
      assertTrue(Files.exists(inRoot(stored.path())), "the failed removal kept the file");
      removeExits = null;
      try (var next = writer("study")) {
        awaitFinished(PtyUploads.SWEEP);
        assertNotNull(next);
      }
      assertFalse(Files.exists(inRoot(stored.path()).getParent()));
    }
  }

  /** Major 5: a root that is a link, or not root's, is neither swept nor written into. */
  @Test
  void aRootThatIsALinkIsNeitherSweptNorWrittenInto() throws Exception {
    var outside = Files.createDirectories(dir.resolve("home"));
    Files.writeString(outside.resolve("work"), "the person's own");
    Files.createDirectories(outside.resolve("0f0e0d0c-0000-0000-0000-000000000000"));
    Files.createSymbolicLink(root(), outside);
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.FAILED, 0),
          upload(channel, 1, "a", new byte[] {1}));
      var opened =
          commands.stream().filter(c -> c.contains(PtyUploads.OPEN)).findFirst().orElseThrow();
      var instanceId = opened.get(opened.indexOf(root().toString()) + 1);
      Files.createDirectories(outside.resolve(instanceId));
      try (var second = writer("study")) {
        awaitFinished(PtyUploads.SWEEP);
        assertNotNull(second);
      }
      try (var killer = connect()) {
        PtyWire.write(killer, new PtyMessage.Kill("lounge"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
      }
      awaitFinished(PtyUploads.REMOVE);
      assertTrue(Files.exists(outside.resolve(instanceId)), "no removal through the link");
      assertTrue(Files.exists(outside.resolve("0f0e0d0c-0000-0000-0000-000000000000")));
      assertEquals("the person's own", Files.readString(outside.resolve("work")));
      try (Stream<Path> entries = Files.list(outside)) {
        assertEquals(3, entries.count(), "nothing was made through the link");
      }
    }
  }

  @Test
  void aNameIsReducedToCharactersNoShellQuotesAndNoPathLeaves() {
    assertEquals("..-..-etc-passwd", PtyUploads.named("../../etc/passwd"));
    assertEquals("a-b-1-.png", PtyUploads.named("a b(1).png"));
    assertEquals(PtyUploads.NAME, PtyUploads.named("x".repeat(300)).length());
    assertEquals("file", PtyUploads.named("."));
    assertEquals("file", PtyUploads.named(".."));
    assertEquals("-", PtyUploads.named("\0"));
    assertEquals("file", PtyUploads.named(""));
    assertEquals("caf--.png", PtyUploads.named("café.png"));
    assertEquals("a-b", PtyUploads.named("a/b"));
    assertEquals("-HOME-x-", PtyUploads.named("$HOME`x'"));
    assertEquals("a--b", PtyUploads.named("a\"\\b"));
  }
}
