/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pasting into a remote session against a real Incus container standing as a workspace's own
 * instance: scenarios 9 to 11 (a file lands whole in the session's directory, the dev user's alone;
 * it goes when the session ends; a new session sweeps what a session no longer live left), and the
 * ways an upload fails there, each leaving nothing: a full disk, a killed copy, links the dev user
 * plants, and a project's shared container. Self-cleaning: every instance is removed.
 */
class PtyUploadIT extends AbstractIncusIT {

  private static final String PROJECT = "sail-it-paste";
  private static final String WORKSPACE = "w";
  private static final String INSTANCE = PROJECT + "-" + WORKSPACE;

  @TempDir Path dir;

  /** A host that admits uday to the workspace, its uploads pushed by incus as production's are. */
  private PtySessionHost startHost() throws Exception {
    var admission =
        new PtyAdmission() {
          @Override
          public void box(PtyIdentity.Person who) {}

          @Override
          public void project(String project, PtyIdentity.Person who) {}

          @Override
          public void workspace(String project, String workspace, PtyIdentity.Person who) {}

          @Override
          public void enter(String project, String workspace, PtyIdentity.Person who) {}

          @Override
          public void room(String room, String project, PtyIdentity.Person who) {}
        };
    var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new PtyIdentity.Person(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
            admission,
            PtyEvents.NONE,
            "0.0.0-test");
    host.start();
    return host;
  }

  private void prepare() throws Exception {
    prepare(INSTANCE);
  }

  private void prepare(String container) throws Exception {
    launch(container);
    var prepared =
        exec(
            container,
            List.of("bash", "-c", "mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev"));
    assertTrue(prepared.ok(), "could not prepare the dev workspace: " + prepared.stderr());
  }

  private SocketChannel connect() throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("h.sock")));
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(""));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  /** A connection writing in a new session of the workspace that waits for a line. */
  private SocketChannel session(String name) throws IOException {
    return session(name, WORKSPACE);
  }

  /** As {@link #session(String)}, in {@code workspace} of the project, or its shared container. */
  private SocketChannel session(String name, String workspace) throws IOException {
    var channel = connect();
    PtyWire.write(
        channel,
        new PtyMessage.Create(
            name, List.of("sh", "-c", "read a"), "/tmp", PROJECT, "", 80, 24, workspace));
    assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
    PtyWire.write(channel, new PtyMessage.Attach(name, true));
    assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
    return channel;
  }

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

  private static PtyMessage.PutStored upload(SocketChannel channel, String name, byte[] bytes)
      throws Exception {
    return assertInstanceOf(
        PtyMessage.PutStored.class, tryUpload(channel, 1, name, bytes, () -> {}));
  }

  /**
   * Uploads {@code bytes} as upload {@code id}, running {@code midway} once half its chunks are
   * acknowledged: the host's answer, the first that is not an acknowledgement.
   */
  private static PtyMessage tryUpload(
      SocketChannel channel, int id, String name, byte[] bytes, Midway midway) throws Exception {
    PtyWire.write(channel, new PtyMessage.PutBegin(id, name, bytes.length));
    var begun = answer(channel);
    if (!(begun instanceof PtyMessage.PutAck)) {
      return begun;
    }
    var half = bytes.length / 2;
    for (var offset = 0; offset < bytes.length; offset += 32 << 10) {
      if (offset >= half && offset - (32 << 10) < half) {
        midway.run();
      }
      var chunk = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + (32 << 10)));
      PtyWire.write(channel, new PtyMessage.PutChunk(id, offset, chunk));
      var acked = answer(channel);
      if (!(acked instanceof PtyMessage.PutAck)) {
        return acked;
      }
    }
    PtyWire.write(
        channel, new PtyMessage.PutEnd(id, MessageDigest.getInstance("SHA-256").digest(bytes)));
    return answer(channel);
  }

  @FunctionalInterface
  private interface Midway {
    void run() throws Exception;
  }

  private String inInstance(String script) throws Exception {
    var ran = exec(INSTANCE, List.of("sh", "-c", script));
    assertTrue(ran.ok(), script + " failed: " + ran.stderr());
    return ran.stdout().strip();
  }

  /** Runs {@code script} in the instance as the dev user: whether it succeeded. */
  private boolean asDev(String script) throws Exception {
    return shell
        .exec(
            List.of(
                "incus", "exec", INSTANCE, "--user", "1000", "--group", "1000", "--cwd", "/", "--",
                "sh", "-c", script))
        .ok();
  }

  private static String directoryOf(String path) {
    return path.substring(0, path.lastIndexOf('/'));
  }

  /** What the session directory holds, temporaries included, by name. */
  private String held(String directory) throws Exception {
    return inInstance("ls -A " + directory + " | tr '\\n' ' '");
  }

  /** Waits, by asking the instance, until `script` prints `expected`. */
  private void awaitInInstance(String script, String expected) throws Exception {
    var deadline = System.nanoTime() + 30_000_000_000L;
    var last = "";
    while (System.nanoTime() < deadline) {
      last = inInstance(script);
      if (last.equals(expected)) {
        return;
      }
    }
    throw new AssertionError(script + " printed '" + last + "', never '" + expected + "'");
  }

  /**
   * Scenarios 9 and 10: a 5 MB file stored whole, the dev user's alone, in a directory of its own
   * under a root the dev user cannot write, then gone with a kill.
   */
  @Test
  void aFileLandsWholeInTheWorkspaceAndGoesWhenTheSessionIsKilled() throws Exception {
    ensureIncusOrSkip();
    try {
      prepare();
      var bytes = new byte[5 << 20];
      new Random(9).nextBytes(bytes);
      try (var host = startHost();
          var channel = session("p9")) {
        var stored = upload(channel, "shot.png", bytes);
        assertTrue(
            stored.path().matches("/var/lib/sail-paste/[0-9a-f-]{36}/1-shot\\.png"), stored.path());
        assertEquals(
            "600 1000 1000 " + (5 << 20), inInstance("stat -c '%a %u %g %s' " + stored.path()));
        assertEquals("700 1000", inInstance("stat -c '%a %u' " + directoryOf(stored.path())));
        assertEquals("755 0", inInstance("stat -c '%a %u' /var/lib/sail-paste"));
        assertEquals("1-shot.png ", held(directoryOf(stored.path())));
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            inInstance("sha256sum " + stored.path() + " | cut -d' ' -f1"));
        assertFalse(asDev("mkdir /var/lib/sail-paste/mine"), "the dev user wrote into the root");

        try (var killer = connect()) {
          PtyWire.write(killer, new PtyMessage.Kill("p9"));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
        }
        awaitInInstance(
            "test -e " + directoryOf(stored.path()) + " && echo kept || echo gone", "gone");
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
    }
  }

  /**
   * Scenarios 10 and 11: a session whose shell exits takes its uploads with it, and a new session
   * in the instance removes what a session no longer live left while keeping a live one's.
   */
  @Test
  void anExitTakesTheUploadsAndANewSessionSweepsWhatNoLiveSessionOwns() throws Exception {
    ensureIncusOrSkip();
    try {
      prepare();
      try (var host = startHost();
          var leaving = session("p10");
          var staying = session("p11")) {
        var gone = upload(leaving, "a.txt", "leaving".getBytes());
        var kept = upload(staying, "b.txt", "staying".getBytes());
        PtyWire.write(leaving, new PtyMessage.Input(1, "\n".getBytes()));
        awaitInInstance(
            "test -e " + directoryOf(gone.path()) + " && echo kept || echo gone", "gone");

        inInstance(
            "mkdir -p -m 0700 /var/lib/sail-paste/0f0e0d0c-0000-7000-8000-000000000000"
                + " && echo left > /var/lib/sail-paste/0f0e0d0c-0000-7000-8000-000000000000/1-a");
        try (var newcomer = session("p12")) {
          awaitInInstance(
              "test -e /var/lib/sail-paste/0f0e0d0c-0000-7000-8000-000000000000"
                  + " && echo kept || echo gone",
              "gone");
          assertEquals("staying", inInstance("cat " + kept.path()));
          assertTrue(newcomer.isOpen());
        }
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
    }
  }

  /**
   * Major 1 and Minor 10: a workspace whose disk fills, before an upload or while it is written,
   * refuses it as {@code no_space} and keeps nothing of it; so does an upload whose copy into the
   * workspace is killed midway. The uploads root is a 1 MiB filesystem of its own here.
   */
  @Test
  void aFullDiskOrAKilledCopyLeavesNothingBehind() throws Exception {
    ensureIncusOrSkip();
    try {
      prepare();
      inInstance(
          "mkdir -p /var/lib/sail-paste && mount -t tmpfs -o size=1m,mode=0755 tmpfs"
              + " /var/lib/sail-paste");
      try (var host = startHost();
          var channel = session("p13")) {
        var small = upload(channel, "first.txt", "first".getBytes());
        var directory = directoryOf(small.path());

        assertEquals(
            new PtyMessage.PutRefused(2, PtyMessage.PutRefusal.NO_SPACE, 0),
            tryUpload(channel, 2, "big.bin", new byte[2 << 20], () -> {}));
        assertEquals("1-first.txt ", held(directory));

        var filling = new byte[600 << 10];
        new Random(13).nextBytes(filling);
        var filled =
            tryUpload(
                channel,
                3,
                "filling.bin",
                filling,
                () -> inInstance("head -c 700000 /dev/zero > /var/lib/sail-paste/filler || true"));
        assertEquals(new PtyMessage.PutRefused(3, PtyMessage.PutRefusal.NO_SPACE, 0), filled);
        awaitInInstance("ls -A " + directory + " | tr '\\n' ' '", "1-first.txt ");
        inInstance("rm /var/lib/sail-paste/filler");

        var killed =
            tryUpload(
                channel,
                4,
                "killed.bin",
                filling,
                () ->
                    ProcessHandle.current()
                        .descendants()
                        .filter(
                            process ->
                                process.info().arguments().stream()
                                    .flatMap(Arrays::stream)
                                    .anyMatch("sail-open"::equals))
                        .forEach(ProcessHandle::destroyForcibly));
        assertEquals(new PtyMessage.PutRefused(4, PtyMessage.PutRefusal.FAILED, 0), killed);
        awaitInInstance("ls -A " + directory + " | tr '\\n' ' '", "1-first.txt ");
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
    }
  }

  /**
   * Major 5: links the dev user plants in its session's directory are never written through and
   * never followed when the directory goes.
   */
  @Test
  void linksInASessionsDirectoryAreNeitherWrittenThroughNorFollowed() throws Exception {
    ensureIncusOrSkip();
    try {
      prepare();
      try (var host = startHost();
          var channel = session("p14")) {
        var first = upload(channel, "a.png", "one".getBytes());
        var directory = directoryOf(first.path());
        assertTrue(
            asDev(
                "echo mine > /home/dev/workspace/keep"
                    + " && ln -s /home/dev/workspace "
                    + directory
                    + "/2-a.png"
                    + " && ln -s /home/dev/workspace/new "
                    + directory
                    + "/.3-a.png.part"),
            "the dev user could not plant its links");

        var next = upload(channel, "a.png", "two".getBytes());
        assertEquals(directory + "/4-a.png", next.path());
        assertEquals("two", inInstance("cat " + next.path()));
        assertEquals("keep", inInstance("ls -A /home/dev/workspace"));

        try (var killer = connect()) {
          PtyWire.write(killer, new PtyMessage.Kill("p14"));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
        }
        awaitInInstance("test -e " + directory + " && echo kept || echo gone", "gone");
        assertEquals("mine", inInstance("cat /home/dev/workspace/keep"));
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
    }
  }

  /** Minor 10: a session in a project's shared container takes no file, and nothing is made. */
  @Test
  void aSessionInASharedContainerTakesNoFile() throws Exception {
    ensureIncusOrSkip();
    try {
      prepare(PROJECT);
      try (var host = startHost();
          var channel = session("p15", "")) {
        assertEquals(
            new PtyMessage.PutRefused(1, PtyMessage.PutRefusal.SHARED_CONTAINER, 0),
            tryUpload(channel, 1, "a.png", new byte[] {1}, () -> {}));
        var looked = exec(PROJECT, List.of("sh", "-c", "test -e /var/lib/sail-paste && echo made"));
        assertEquals("", looked.stdout().strip());
      }
    } finally {
      deleteContainerQuietly(PROJECT);
    }
  }
}
