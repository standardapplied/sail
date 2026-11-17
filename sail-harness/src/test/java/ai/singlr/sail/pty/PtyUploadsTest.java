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

import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uploads through a real host, its sessions real processes, into a container that is a directory
 * here: {@code incus exec} runs its command on this machine with every {@code /tmp/sail-paste} path
 * moved under the container's directory, and {@code incus file push} copies into it. Each test
 * tries the wrong thing the rule it names forbids.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PtyUploadsTest {

  private static final String SESSION_COMMAND = "while read a; do :; done";

  @TempDir Path dir;

  private final LinkedBlockingQueue<List<String>> commands = new LinkedBlockingQueue<>();
  private volatile String pushFailure;
  private volatile String placeAnswer;

  private Path container() {
    return dir.resolve("container");
  }

  private Path staging() {
    return dir.resolve("staging");
  }

  /** The container: every command it was handed, kept in order. */
  private final ShellExec shell =
      new ShellExec() {
        @Override
        public Result exec(List<String> command) throws IOException, InterruptedException {
          commands.add(List.copyOf(command));
          if (command.subList(0, 3).equals(List.of("incus", "file", "push"))) {
            return push(command);
          }
          if (placeAnswer != null && command.contains("sail-place")) {
            return new Result(0, placeAnswer, "");
          }
          var inner =
              command.subList(command.indexOf("--") + 1, command.size()).stream()
                  .map(arg -> arg.startsWith("/tmp/sail-paste") ? container() + arg : arg)
                  .toList();
          var process = new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD);
          var started = process.start();
          var out = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          return new Result(started.waitFor(), out, "");
        }

        @Override
        public Result exec(List<String> command, Path workDir, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
          return exec(command);
        }

        @Override
        public boolean isDryRun() {
          return false;
        }
      };

  private ShellExec.Result push(List<String> command) throws IOException {
    if (pushFailure != null) {
      return new ShellExec.Result(1, "", pushFailure);
    }
    var source = Path.of(command.get(command.size() - 2));
    var target = command.getLast();
    var into = container().resolve(target.substring(target.indexOf('/') + 1));
    Files.copy(source, into);
    var mode = command.get(command.indexOf("--mode") + 1);
    Files.setPosixFilePermissions(into, PosixFilePermissions.fromString(rwx(mode)));
    return new ShellExec.Result(0, "", "");
  }

  private static String rwx(String octal) {
    var perms = new StringBuilder();
    for (var digit : octal.substring(1).toCharArray()) {
      var bits = digit - '0';
      perms.append((bits & 4) != 0 ? 'r' : '-');
      perms.append((bits & 2) != 0 ? 'w' : '-');
      perms.append((bits & 1) != 0 ? 'x' : '-');
    }
    return perms.toString();
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
    Files.createDirectories(container().resolve("tmp"));
    Files.createDirectories(staging());
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
            new PtyUploads(shell, this::staging, targets));
    host.start();
    return host;
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
        new PtyMessage.Create(name, List.of("sh", "-c", SESSION_COMMAND), "/tmp", "", "", 80, 24));
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

  /** Uploads {@code bytes} as {@code name} in chunks of the largest size: the host's answer. */
  private static PtyMessage upload(SocketChannel channel, int id, String name, byte[] bytes)
      throws Exception {
    PtyWire.write(channel, new PtyMessage.PutBegin(id, name, bytes.length));
    var begun = answer(channel);
    if (!(begun instanceof PtyMessage.PutAck)) {
      return begun;
    }
    for (var offset = 0; offset < bytes.length; offset += PtyUploads.CHUNK) {
      var chunk =
          java.util.Arrays.copyOfRange(
              bytes, offset, Math.min(bytes.length, offset + PtyUploads.CHUNK));
      PtyWire.write(channel, new PtyMessage.PutChunk(id, offset, chunk));
      assertEquals(new PtyMessage.PutAck(id, offset + chunk.length), answer(channel));
    }
    PtyWire.write(channel, new PtyMessage.PutEnd(id, sha256(bytes)));
    return answer(channel);
  }

  private static byte[] sha256(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(bytes);
  }

  private Path inContainer(String path) {
    return container().resolve(path.substring(1));
  }

  private List<Path> staged() throws IOException {
    try (Stream<Path> files = Files.list(staging())) {
      return files.toList();
    }
  }

  private boolean pushed() {
    return commands.stream().anyMatch(command -> command.contains("push"));
  }

  @Test
  void aFileIsStoredWholeInItsSessionsOwnDirectoryAsTheDevUsersAlone() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var bytes = new byte[PtyUploads.CHUNK * 2 + 5];
      bytes[bytes.length - 1] = 9;

      var stored =
          assertInstanceOf(PtyMessage.PutStored.class, upload(channel, 3, "shot.png", bytes));

      assertEquals(3, stored.id());
      assertTrue(
          stored.path().matches("/tmp/sail-paste/[0-9a-f-]{36}/1-shot\\.png"), stored.path());
      assertArrayEquals(bytes, Files.readAllBytes(inContainer(stored.path())));
      assertEquals(
          "rw-------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(inContainer(stored.path()))));
      assertEquals(
          "rwx------",
          PosixFilePermissions.toString(
              Files.getPosixFilePermissions(inContainer(stored.path()).getParent())));
      var push = commands.stream().filter(command -> command.contains("push")).findFirst();
      assertTrue(
          push.orElseThrow().containsAll(List.of("--uid", "1000", "--gid", "1000")),
          "the dev user owns it: " + push);
      assertEquals(List.of(), staged(), "nothing stays staged");
    }
  }

  @Test
  void aSecondFileOfTheSameNameAndOneAHostBeforeStoredAreNeverOverwritten() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var first =
          (PtyMessage.PutStored)
              upload(channel, 1, "a.png", "one".getBytes(StandardCharsets.UTF_8));
      var sessionDir = inContainer(first.path()).getParent();
      Files.writeString(sessionDir.resolve("2-a.png"), "a host before this one stored it");

      var second =
          (PtyMessage.PutStored)
              upload(channel, 2, "a.png", "two".getBytes(StandardCharsets.UTF_8));

      assertTrue(second.path().endsWith("/3-a.png"), second.path());
      assertEquals("one", Files.readString(inContainer(first.path())));
      assertEquals(
          "a host before this one stored it", Files.readString(sessionDir.resolve("2-a.png")));
      assertEquals("two", Files.readString(inContainer(second.path())));
    }
  }

  @Test
  void aWatcherIsRefusedNotWriterAndNothingIsStaged() throws Exception {
    try (var ignored = startHost();
        var writer = writer("lounge");
        var watcher = attached(connect(), "lounge", false)) {
      PtyWire.write(watcher, new PtyMessage.PutBegin(1, "shot.png", 5));

      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.NOT_WRITER, 0), answer(watcher));
      assertEquals(List.of(), staged());
      assertFalse(pushed());
    }
  }

  @Test
  void aFileGoesOnlyIntoAWorkspaceNeverTheBoxNorAProjectsSharedContainer() throws Exception {
    try (var ignored = startHost(PtyUploads::targetOf);
        var channel = writer("lounge")) {
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "shot.png", 5));

      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.NO_CONTAINER, 0), answer(channel));
      assertEquals(List.of(), staged());
    }
    var shared = new PtySession.Origin("lounge", "inst-1", "uday", "acme", "", List.of("bash"), "");
    assertEquals(PtyUploads.Target.SHARED, PtyUploads.targetOf(shared));
    assertEquals(PutRefusal.SHARED_CONTAINER, PtyUploads.targetOf(shared).refused());
    var own =
        new PtySession.Origin("lounge", "inst-1", "uday", "acme", "", List.of("bash"), "dock");
    assertEquals(PtyUploads.Target.own("acme-dock"), PtyUploads.targetOf(own));
  }

  @Test
  void aFileOverTheLargestIsRefusedWithTheBoundAndNothingStaged() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "big.mov", PtyUploads.ITEM + 1));
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.TOO_LARGE, PtyUploads.ITEM), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutBegin(2, "less.mov", -1));
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.TOO_LARGE, PtyUploads.ITEM), answer(channel));
      assertEquals(List.of(), staged());
    }
  }

  @Test
  void aSessionHoldsAtMostItsFilesAndItsBytesAcrossItsConnections() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      for (var i = 0; i < PtyUploads.FILES; i++) {
        assertInstanceOf(PtyMessage.PutStored.class, upload(channel, i, "f", new byte[0]));
      }
      PtyWire.write(channel, new PtyMessage.PutBegin(999, "f", 0));
      assertEquals(
          new PtyMessage.PutRefused(999, PutRefusal.TOO_MANY, PtyUploads.FILES), answer(channel));
    }
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
  }

  @Test
  void aDirectoryAlreadyFullIsRefusedAtThePushAsAHostBeforeLeftIt() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored =
          (PtyMessage.PutStored) upload(channel, 1, "a", "a".getBytes(StandardCharsets.UTF_8));
      var sessionDir = inContainer(stored.path()).getParent();
      try (var big = new RandomAccessFile(sessionDir.resolve("2-big").toFile(), "rw")) {
        big.setLength(PtyUploads.SESSION);
      }
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.SESSION_FULL, PtyUploads.SESSION),
          upload(channel, 2, "b", "b".getBytes(StandardCharsets.UTF_8)));
      Files.delete(sessionDir.resolve("2-big"));
      for (var i = 2; i <= PtyUploads.FILES; i++) {
        Files.createFile(sessionDir.resolve(i + "-left"));
      }
      assertEquals(
          new PtyMessage.PutRefused(3, PutRefusal.TOO_MANY, PtyUploads.FILES),
          upload(channel, 3, "c", "c".getBytes(StandardCharsets.UTF_8)));
      assertEquals(List.of(), staged());
    }
  }

  @Test
  void chunksOutOfOrderPastTheSizeOrNotMatchingTheirDigestAreNeverPushed() throws Exception {
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

      assertFalse(pushed(), "nothing short, long or altered reaches the container");
      assertEquals(List.of(), staged());
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
      assertEquals(1, staged().size());
      PtyWire.write(channel, new PtyMessage.PutCancel(1));
      PtyWire.write(channel, new PtyMessage.PutBegin(2, "b", 4));
      assertEquals(new PtyMessage.PutAck(2, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(2, 0, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutAck(2, 2), answer(channel));
      assertEquals(1, staged().size(), "the cancelled upload's staged file is gone");

      channel.close();
      try (var other = connect()) {
        awaitDetached(other, "lounge");
      }
      assertEquals(List.of(), staged(), "the dropped upload's staged file is gone");
      assertFalse(pushed());
    }
  }

  /** Waits until no connection is attached to {@code name}: each one's ending has run. */
  private static void awaitDetached(SocketChannel channel, String name) throws IOException {
    while (true) {
      PtyWire.write(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      var listed = (PtyMessage.Sessions) PtyWire.read(channel);
      if (listed.sessions().stream().anyMatch(s -> s.name().equals(name) && s.attached() == 0)) {
        return;
      }
    }
  }

  @Test
  void aFullDiskIsSaidAsNoSpaceAndAnyOtherFailureAsFailed() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      pushFailure = "Error: write /tmp/sail-paste/x/1-a: no space left on device";
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.NO_SPACE, 0),
          upload(channel, 1, "a", new byte[] {1}));
      pushFailure = "Error: Instance is not running";
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.FAILED, 0),
          upload(channel, 2, "a", new byte[] {1}));
      pushFailure = null;
      placeAnswer = "1 0";
      assertEquals(
          new PtyMessage.PutRefused(3, PutRefusal.FAILED, 0),
          upload(channel, 3, "a", new byte[] {1}),
          "a directory that answers no count and size is a failure, never a silence");
      assertEquals(List.of(), staged());
    }
  }

  @Test
  void aSessionsUploadsGoWhenItEnds() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      var sessionDir = inContainer(stored.path()).getParent();
      try (var killer = connect()) {
        PtyWire.write(killer, new PtyMessage.Kill("lounge"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
      }
      awaitCommand(List.of("rm", "-rf", "/tmp/sail-paste/" + sessionDir.getFileName()));
      assertFalse(Files.exists(sessionDir), "the session's uploads went with it");
    }
  }

  @Test
  void aCorpseTakesNoUpload() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      PtyWire.write(
          channel, new PtyMessage.Create("brief", List.of("true"), "/tmp", "", "", 80, 24));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
      attached(channel, "brief", true);
      awaitEnded(channel);
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "a", 1));
      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.SESSION_ENDED, 0), answer(channel));
      assertEquals(List.of(), staged());
    }
  }

  private static void awaitEnded(SocketChannel channel) throws IOException {
    var message = PtyWire.read(channel);
    while (!(message instanceof PtyMessage.SessionEnded)) {
      message = PtyWire.read(channel);
    }
  }

  @Test
  void aNewSessionSweepsTheUploadsOfSessionsNoLongerLiveAndKeepsTheLiveOnes() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      var live = inContainer(stored.path()).getParent();
      var stale = container().resolve("tmp/sail-paste/0f0e0d0c-stale");
      Files.createDirectories(stale);
      Files.writeString(stale.resolve("1-left.png"), "left by a stopped container");

      try (var second = writer("study")) {
        assertNotNull(second);
      }

      assertFalse(Files.exists(stale), "a session no longer live leaves nothing");
      assertTrue(Files.exists(inContainer(stored.path())), "a live session's file is kept");
      assertTrue(Files.isDirectory(live));
    }
  }

  private void awaitCommand(List<String> tail) throws InterruptedException {
    while (true) {
      var command = commands.poll(30, TimeUnit.SECONDS);
      assertNotNull(command, "no command ended with " + tail);
      if (command.size() >= tail.size()
          && command.subList(command.size() - tail.size(), command.size()).equals(tail)) {
        return;
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
  }
}
