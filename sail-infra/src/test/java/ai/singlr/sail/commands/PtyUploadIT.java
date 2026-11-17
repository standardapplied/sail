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
 * Scenarios 9 to 11 of pasting into a remote session, against a real Incus container standing as a
 * workspace's own instance: a file uploaded on the session's own connection lands whole in the
 * session's directory, the dev user's alone; it goes when the session ends; and a new session in
 * the instance sweeps what a session no longer live left. Self-cleaning: the instance is always
 * removed.
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
    launch(INSTANCE);
    var prepared =
        exec(
            INSTANCE,
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
    var channel = connect();
    PtyWire.write(
        channel,
        new PtyMessage.Create(
            name, List.of("sh", "-c", "read a"), "/tmp", PROJECT, "", 80, 24, WORKSPACE));
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
    PtyWire.write(channel, new PtyMessage.PutBegin(1, name, bytes.length));
    assertEquals(new PtyMessage.PutAck(1, 0), answer(channel));
    for (var offset = 0; offset < bytes.length; offset += 32 << 10) {
      var chunk = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + (32 << 10)));
      PtyWire.write(channel, new PtyMessage.PutChunk(1, offset, chunk));
      assertEquals(new PtyMessage.PutAck(1, offset + chunk.length), answer(channel));
    }
    PtyWire.write(
        channel, new PtyMessage.PutEnd(1, MessageDigest.getInstance("SHA-256").digest(bytes)));
    return assertInstanceOf(PtyMessage.PutStored.class, answer(channel));
  }

  private String inInstance(String script) throws Exception {
    var ran = exec(INSTANCE, List.of("sh", "-c", script));
    assertTrue(ran.ok(), script + " failed: " + ran.stderr());
    return ran.stdout().strip();
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

  /** Scenarios 9 and 10: a 5 MB file stored whole, the dev user's alone, then gone with a kill. */
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
            stored.path().matches("/tmp/sail-paste/[0-9a-f-]{36}/1-shot\\.png"), stored.path());
        assertEquals(
            "600 1000 1000 " + (5 << 20), inInstance("stat -c '%a %u %g %s' " + stored.path()));
        assertEquals("700 1000", inInstance("stat -c '%a %u' $(dirname " + stored.path() + ")"));
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            inInstance("sha256sum " + stored.path() + " | cut -d' ' -f1"));

        try (var killer = connect()) {
          PtyWire.write(killer, new PtyMessage.Kill("p9"));
          assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(killer));
        }
        var directory = stored.path().substring(0, stored.path().lastIndexOf('/'));
        awaitInInstance("test -e " + directory + " && echo kept || echo gone", "gone");
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
        var goneDirectory = gone.path().substring(0, gone.path().lastIndexOf('/'));
        awaitInInstance("test -e " + goneDirectory + " && echo kept || echo gone", "gone");

        var left =
            shell.exec(
                List.of(
                    "incus",
                    "exec",
                    INSTANCE,
                    "--user",
                    "1000",
                    "--group",
                    "1000",
                    "--",
                    "sh",
                    "-c",
                    "mkdir -p -m 0700 /tmp/sail-paste/0f0e0d0c-left && echo left >"
                        + " /tmp/sail-paste/0f0e0d0c-left/1-left.txt"));
        assertTrue(left.ok(), "the dev user could not leave a session's uploads: " + left.stderr());
        try (var newcomer = session("p12")) {
          assertFalse(inInstance("ls -A /tmp/sail-paste").contains("0f0e0d0c-left"));
          assertEquals("staying", inInstance("cat " + kept.path()));
          assertTrue(newcomer.isOpen());
        }
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
    }
  }
}
