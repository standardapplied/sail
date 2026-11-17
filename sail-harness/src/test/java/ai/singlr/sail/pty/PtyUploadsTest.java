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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The upload frames through a real host, its sessions real processes, into a {@link
 * ContainerDirectory}: what the protocol answers, when, and to whom, and how it orders its copies,
 * commits and removals. Each test tries the wrong thing the rule it names forbids. What the scripts
 * do in a container is {@link UploadCopyTest}'s and {@link UploadCleanupTest}'s.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PtyUploadsTest {

  private static final String SESSION_COMMAND = "while read a; do :; done";

  @TempDir Path dir;

  private ContainerDirectory c;

  @BeforeEach
  void container() {
    c = new ContainerDirectory(dir);
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
    uploads = new PtyUploads(c.copies(), targets);
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
    for (var offset = 0; offset < bytes.length; offset += UploadAllowance.CHUNK) {
      var chunk =
          Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + UploadAllowance.CHUNK));
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

  private Path inRoot(String path) {
    return Path.of(path);
  }

  @Test
  void aFileIsStoredWholeInItsSessionsOwnDirectoryAsTheDevUsersAloneAndNothingIsKeptOnTheHost()
      throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var bytes = new byte[UploadAllowance.CHUNK * 2 + 5];
      bytes[bytes.length - 1] = 9;

      var stored =
          assertInstanceOf(PtyMessage.PutStored.class, upload(channel, 3, "shot.png", bytes));

      assertEquals(3, stored.id());
      assertTrue(stored.path().matches(c.root() + "/[0-9a-f-]{36}/1-shot\\.png"), stored.path());
      var file = inRoot(stored.path());
      assertArrayEquals(bytes, Files.readAllBytes(file));
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
      assertEquals(
          "rwx------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
      assertEquals(
          "rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.root())));
      assertEquals(
          List.of("1-shot.png"), ContainerDirectory.held(file.getParent()), "no temporary is left");
      assertFalse(Files.exists(dir.resolve("staging")), "nothing of the file sits on the host");
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
      c.awaitFinished(UploadCopy.ABORT);

      PtyWire.write(writer, new PtyMessage.TakeWrite());
      PtyWire.write(writer, new PtyMessage.PutBegin(3, "shot.png", 1));
      assertEquals(new PtyMessage.PutAck(3, 0), answer(writer));
      PtyWire.write(writer, new PtyMessage.PutChunk(3, 0, new byte[] {1}));
      assertEquals(new PtyMessage.PutAck(3, 1), answer(writer));
      PtyWire.write(watcher, new PtyMessage.TakeWrite());
      handled(watcher);
      PtyWire.write(writer, new PtyMessage.PutEnd(3, sha256(new byte[] {1})));
      assertEquals(new PtyMessage.PutRefused(3, PutRefusal.NOT_WRITER, 0), answer(writer));
      c.awaitFinished(UploadCopy.ABORT);
      assertEquals(List.of(), ContainerDirectory.held(onlySession()));
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
    try (Stream<Path> entries = Files.list(c.root())) {
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
    assertFalse(Files.exists(c.root()));
  }

  @Test
  void aFileOverTheLargestOrAChunkOverItsBoundIsRefused() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "big.mov", UploadAllowance.ITEM + 1));
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.TOO_LARGE, UploadAllowance.ITEM),
          answer(channel));
      PtyWire.write(channel, new PtyMessage.PutBegin(2, "less.mov", -1));
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.TOO_LARGE, UploadAllowance.ITEM),
          answer(channel));
      PtyWire.write(channel, new PtyMessage.PutBegin(3, "a", UploadAllowance.CHUNK + 1));
      assertEquals(new PtyMessage.PutAck(3, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(3, 0, new byte[UploadAllowance.CHUNK + 1]));
      assertEquals(new PtyMessage.PutRefused(3, PutRefusal.FAILED, 0), answer(channel));
    }
  }

  @Test
  void aSessionHoldsAtMostItsBytesAcrossItsConnections() throws Exception {
    try (var ignored = startHost();
        var first = writer("study")) {
      var holders = new ArrayList<SocketChannel>();
      try {
        for (var i = 0; i < UploadAllowance.SESSION / UploadAllowance.ITEM; i++) {
          var next = attached(connect(), "study", true);
          holders.add(next);
          PtyWire.write(next, new PtyMessage.PutBegin(1, "part", UploadAllowance.ITEM));
          assertEquals(new PtyMessage.PutAck(1, 0), answer(next));
        }
        PtyWire.write(first, new PtyMessage.TakeWrite());
        PtyWire.write(first, new PtyMessage.PutBegin(1, "one-more", 1));
        assertEquals(
            new PtyMessage.PutRefused(1, PutRefusal.SESSION_FULL, UploadAllowance.SESSION),
            answer(first));
      } finally {
        for (var holder : holders) {
          holder.close();
        }
      }
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
        c.awaitFinished(UploadCopy.ABORT);
      }
      var session = onlySession();
      assertEquals(List.of(), ContainerDirectory.held(session), "no temporary and no file is left");
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
      c.awaitFinished(UploadCopy.ABORT);
      var session = onlySession();
      assertEquals(List.of(), ContainerDirectory.held(session));

      PtyWire.write(channel, new PtyMessage.PutBegin(2, "b", 4));
      assertEquals(new PtyMessage.PutAck(2, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(2, 0, new byte[] {1, 2}));
      assertEquals(new PtyMessage.PutAck(2, 2), answer(channel));
      channel.close();
      c.awaitFinished(UploadCopy.ABORT);
      assertEquals(List.of(), ContainerDirectory.held(session));
    }
  }

  @Test
  void aCancelAfterTheEndKeepsNothingUntilTheFileIsStoredAndNothingOnceItIs() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      c.copyHeld = new CountDownLatch(1);
      PtyWire.write(channel, new PtyMessage.PutBegin(1, "a", 1));
      assertEquals(new PtyMessage.PutAck(1, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(1, 0, new byte[] {7}));
      assertEquals(new PtyMessage.PutAck(1, 1), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutEnd(1, sha256(new byte[] {7})));
      c.copyReached.await();
      PtyWire.write(channel, new PtyMessage.PutCancel(1));
      PtyWire.write(channel, new PtyMessage.PutQuery(99));
      assertEquals(new PtyMessage.PutRefused(99, PutRefusal.NOT_KNOWN, 0), answer(channel));
      c.copyHeld.countDown();
      assertEquals(new PtyMessage.PutRefused(1, PutRefusal.CANCELLED, 0), answer(channel));
      assertEquals(List.of(), ContainerDirectory.held(onlySession()));

      c.copyHeld = null;
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
      c.commitHeld = new CountDownLatch(1);
      PtyWire.write(channel, new PtyMessage.PutBegin(5, "a", 1));
      assertEquals(new PtyMessage.PutAck(5, 0), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutChunk(5, 0, new byte[] {7}));
      assertEquals(new PtyMessage.PutAck(5, 1), answer(channel));
      PtyWire.write(channel, new PtyMessage.PutEnd(5, sha256(new byte[] {7})));
      c.awaitCommand(UploadCopy.COMMIT);
      channel.close();
      try (var again = attached(connect(), "lounge", true)) {
        PtyWire.write(again, new PtyMessage.PutQuery(5));
        c.commitHeld.countDown();
        var stored = assertInstanceOf(PtyMessage.PutStored.class, answer(again));
        assertTrue(Files.exists(inRoot(stored.path())));
      }
    }
  }

  @Test
  void theContainersRefusalIsToldByItsExitStatus() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      c.openExits = UploadCopy.NO_SPACE;
      assertEquals(
          new PtyMessage.PutRefused(1, PutRefusal.NO_SPACE, 0),
          upload(channel, 1, "a", new byte[] {1}));
      c.openExits = UploadCopy.UNSAFE;
      assertEquals(
          new PtyMessage.PutRefused(2, PutRefusal.FAILED, 0),
          upload(channel, 2, "a", new byte[] {1}));
      c.openExits = null;
      c.commitExits = 1;
      assertEquals(
          new PtyMessage.PutRefused(3, PutRefusal.FAILED, 0),
          upload(channel, 3, "a", new byte[] {1}));
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
      c.openExits = UploadCopy.NO_SPACE;
      for (var i = 0; i <= UploadAllowance.FILES; i++, id++) {
        assertEquals(
            new PtyMessage.PutRefused(id, PutRefusal.NO_SPACE, 0),
            upload(channel, id, "a", new byte[] {1}));
      }
      c.openExits = null;
      c.commitExits = 1;
      for (var i = 0; i <= UploadAllowance.FILES; i++, id++) {
        assertEquals(
            new PtyMessage.PutRefused(id, PutRefusal.FAILED, 0),
            upload(channel, id, "a", new byte[] {1}));
      }
      c.commitExits = null;
      assertInstanceOf(PtyMessage.PutStored.class, upload(channel, id, "a", new byte[] {1}));
    }
  }

  /**
   * Major 7: a commit the session's end came during keeps nothing and says so, its directory goes,
   * and an upload begun after the end is refused.
   */
  @Test
  void aCommitThatOutlivesItsSessionKeepsNothingAndAnUploadAfterIsRefused() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var first = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      var session = inRoot(first.path()).getParent();
      c.commitHeld = new CountDownLatch(1);
      try {
        PtyWire.write(channel, new PtyMessage.PutBegin(2, "b", 1));
        assertEquals(new PtyMessage.PutAck(2, 0), answer(channel));
        PtyWire.write(channel, new PtyMessage.PutChunk(2, 0, new byte[] {2}));
        assertEquals(new PtyMessage.PutAck(2, 1), answer(channel));
        PtyWire.write(channel, new PtyMessage.PutEnd(2, sha256(new byte[] {2})));
        c.awaitCommand(UploadCopy.COMMIT);
        try (var killer = connect()) {
          PtyWire.write(killer, new PtyMessage.Kill("lounge"));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
        }
      } finally {
        c.commitHeld.countDown();
      }
      assertEquals(new PtyMessage.PutRefused(2, PutRefusal.SESSION_ENDED, 0), answer(channel));
      c.awaitFinished(UploadCleanup.REMOVE);
      assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
      PtyWire.write(channel, new PtyMessage.PutBegin(3, "c", 1));
      assertEquals(new PtyMessage.PutRefused(3, PutRefusal.SESSION_ENDED, 0), answer(channel));
      assertFalse(Files.exists(session), "nothing was made again after the session ended");
    }
  }

  /** Major 7: an upload whose session ended while it waited to open makes nothing. */
  @Test
  void anUploadWhoseSessionEndedBeforeItOpenedMakesNothing() throws Exception {
    try (var ignored = startHost();
        var study = writer("study");
        var lounge = writer("lounge")) {
      c.openHeld = new CountDownLatch(1);
      var release = c.openHeld;
      PtyWire.write(study, new PtyMessage.PutBegin(1, "a", 1));
      c.awaitCommand(UploadCopy.OPEN);
      PtyWire.write(lounge, new PtyMessage.PutBegin(2, "b", 1));
      try (var killer = connect()) {
        PtyWire.write(killer, new PtyMessage.Kill("lounge"));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
      }
      release.countDown();
      assertEquals(new PtyMessage.PutAck(1, 0), answer(study));
      assertEquals(new PtyMessage.PutRefused(2, PutRefusal.SESSION_ENDED, 0), answer(lounge));
      try (Stream<Path> entries = Files.list(c.root())) {
        assertEquals(1, entries.count(), "only the live session's directory was made");
      }
    }
  }

  /** An open the container never answers is given up, and holds no lock past its patience. */
  @Test
  void anOpenThatNeverAnswersIsGivenUpAndTheNextUploadIsStored() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      c.openHangs = true;
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
      c.sweepHeld = new CountDownLatch(1);
      var release = c.sweepHeld;
      try (var study = writer("study")) {
        c.awaitCommand(UploadCleanup.SWEEP);
        try (var lounge = writer("lounge")) {
          PtyWire.write(lounge, new PtyMessage.PutBegin(1, "a", 1));
          release.countDown();
          c.awaitFinished(UploadCleanup.SWEEP);
          assertEquals(new PtyMessage.PutAck(1, 0), answer(lounge));
          var stored =
              assertInstanceOf(PtyMessage.PutStored.class, send(lounge, 1, new byte[] {1}));
          assertTrue(Files.exists(inRoot(stored.path())), "a live session's file is kept");
          assertNotNull(study);
        }
      }
    }
  }

  /** Major 2: a container's slow removal holds no other session's create. */
  @Test
  void aSlowRemovalHoldsNoOtherSessionsCreate() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      upload(channel, 1, "a", new byte[] {1});
      c.removeHeld = new CountDownLatch(1);
      try {
        try (var killer = connect()) {
          PtyWire.write(killer, new PtyMessage.Kill("lounge"));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
        }
        c.awaitCommand(UploadCleanup.REMOVE);
        try (var another = writer("study")) {
          assertTrue(another.isOpen(), "the create was answered while the removal was held");
        }
      } finally {
        c.removeHeld.countDown();
      }
    }
  }

  /** Minor 9: a session whose shell exited and whose removal failed is swept by the next one. */
  @Test
  void aSessionThatExitedIsSweptByTheNextSessionWhenItsRemovalFailed() throws Exception {
    try (var ignored = startHost();
        var channel = writer("lounge")) {
      var stored = (PtyMessage.PutStored) upload(channel, 1, "a", new byte[] {1});
      c.removeExits = 1;
      PtyWire.write(channel, new PtyMessage.Input(1, new byte[] {4}));
      c.awaitFinished(UploadCleanup.REMOVE);
      assertTrue(Files.exists(inRoot(stored.path())), "the failed removal kept the file");
      c.removeExits = null;
      try (var next = writer("study")) {
        c.awaitFinished(UploadCleanup.SWEEP);
        assertNotNull(next);
      }
      assertFalse(Files.exists(inRoot(stored.path()).getParent()));
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
