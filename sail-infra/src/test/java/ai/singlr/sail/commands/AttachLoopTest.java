/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtySessionHost;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class AttachLoopTest {

  @TempDir Path dir;

  @Test
  void conversesRendersOutputAndReportsTheEnding() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyRooms.NONE,
            ai.singlr.sail.pty.PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create(
            "s1", List.of("sh", "-c", "read a; echo pty-says:$a; exit 0"), "/tmp", "", "", 80, 24);
        var channel = client.attach("s1", true);

        var stdinFeed = new PipedOutputStream();
        var stdin = new PipedInputStream(stdinFeed);
        var stdout = new ByteArrayOutputStream();
        stdinFeed.write("hello\n".getBytes(StandardCharsets.UTF_8));
        stdinFeed.flush();

        var reason = AttachLoop.run(channel, stdin, stdout);

        assertEquals("exited(0)", reason);
        var rendered = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("pty-says:hello"));
        assertTrue(
            rendered.contains("[sail: uday holds the write token]"),
            "the host's answer to the attach names the keyboard's holder: " + rendered);
      }
    }
  }

  @Test
  void anObserverAndAMidSequenceReplayAreNarratedNotDropped() throws Exception {
    var frames = new ByteArrayOutputStream();
    var sink = java.nio.channels.Channels.newChannel(frames);
    ai.singlr.sail.pty.PtyWire.write(sink, new ai.singlr.sail.pty.PtyMessage.ReplayBegin(false));
    ai.singlr.sail.pty.PtyWire.write(
        sink,
        new ai.singlr.sail.pty.PtyMessage.Output(-1, "old\n".getBytes(StandardCharsets.UTF_8)));
    ai.singlr.sail.pty.PtyWire.write(sink, new ai.singlr.sail.pty.PtyMessage.ReplayEnd());
    ai.singlr.sail.pty.PtyWire.write(sink, new ai.singlr.sail.pty.PtyMessage.WriterChanged("mady"));
    ai.singlr.sail.pty.PtyWire.write(sink, new ai.singlr.sail.pty.PtyMessage.WriterChanged(""));
    ai.singlr.sail.pty.PtyWire.write(
        sink, new ai.singlr.sail.pty.PtyMessage.SessionEnded("exited(0)"));
    var stdout = new ByteArrayOutputStream();

    var reason =
        AttachLoop.run(new ScriptedChannel(frames.toByteArray()), new PipedInputStream(), stdout);

    assertEquals("exited(0)", reason);
    var rendered = stdout.toString(StandardCharsets.UTF_8);
    var expectedOrder =
        List.of(
            "[sail: history resumes mid-sequence; the screen settles on the next redraw]",
            "old",
            "[sail: mady holds the write token]",
            "[sail: nobody holds the write token]");
    var at = -1;
    for (var line : expectedOrder) {
      var next = rendered.indexOf(line, at + 1);
      assertTrue(next > at, "expected '" + line + "' in order in: " + rendered);
      at = next;
    }
  }

  /** A host that has already said everything it will: the frames, then end of stream. */
  private static final class ScriptedChannel implements java.nio.channels.ByteChannel {
    private final java.nio.ByteBuffer script;

    ScriptedChannel(byte[] frames) {
      this.script = java.nio.ByteBuffer.wrap(frames);
    }

    @Override
    public int read(java.nio.ByteBuffer dst) {
      if (!script.hasRemaining()) {
        return -1;
      }
      var n = Math.min(dst.remaining(), script.remaining());
      dst.put(script.slice(script.position(), n));
      script.position(script.position() + n);
      return n;
    }

    @Override
    public int write(java.nio.ByteBuffer src) {
      var n = src.remaining();
      src.position(src.limit());
      return n;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }

  @Test
  void theDetachKeyLeavesTheSessionAliveForTheNextClient() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyRooms.NONE,
            ai.singlr.sail.pty.PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create("s1", List.of("sh", "-c", "read a; echo after:$a"), "/tmp", "", "", 80, 24);
        var channel = client.attach("s1", true);

        var stdinFeed = new PipedOutputStream();
        var stdin = new PipedInputStream(stdinFeed);
        stdinFeed.write(new byte[] {AttachLoop.DETACH_KEY});
        stdinFeed.flush();

        var reason = AttachLoop.run(channel, stdin, new ByteArrayOutputStream());
        assertNull(reason, "a detach is not an ending");
      }
      try (var again = SessionClient.connect(dir.resolve("h.sock"))) {
        assertTrue(again.list().getFirst().live(), "the session outlived its client");
      }
    }
  }

  @Test
  void aTerminalResizeReachesTheRemotePty() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyRooms.NONE,
            ai.singlr.sail.pty.PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create(
            "s1",
            List.of("sh", "-c", "trap 'stty size; exit 0' WINCH; echo ready; read a"),
            "/tmp",
            "",
            "",
            80,
            24);
        var channel = client.attach("s1", true);

        var ready = new java.util.concurrent.CountDownLatch(1);
        var stdout =
            new ByteArrayOutputStream() {
              @Override
              public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                if (toString(StandardCharsets.UTF_8).contains("ready")) {
                  ready.countDown();
                }
              }
            };
        var fired = new java.util.concurrent.atomic.AtomicBoolean();
        AttachLoop.Resizes resizes =
            () -> {
              if (!fired.compareAndSet(false, true)) {
                return null;
              }
              ready.await();
              return new int[] {100, 40};
            };

        var stdin = new PipedInputStream(new PipedOutputStream());
        var reason = AttachLoop.run(channel, stdin, stdout, resizes);

        assertEquals("exited(0)", reason);
        assertTrue(
            stdout.toString(StandardCharsets.UTF_8).contains("40 100"),
            "the child's SIGWINCH trap saw the forwarded 100x40 geometry: "
                + stdout.toString(StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void aRefusedInputIsNarratedInsteadOfSilentlyDropped() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyRooms.NONE,
            ai.singlr.sail.pty.PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create("s1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
        var channel = client.attach("s1", false);

        var refused = new java.util.concurrent.CountDownLatch(1);
        var stdout =
            new ByteArrayOutputStream() {
              @Override
              public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                if (toString(StandardCharsets.UTF_8).contains("You do not hold the write token")) {
                  refused.countDown();
                }
              }
            };
        var stdinFeed = new PipedOutputStream();
        var stdin = new PipedInputStream(stdinFeed);
        var loop =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        AttachLoop.run(channel, stdin, stdout);
                      } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                      }
                    });
        stdinFeed.write("x".getBytes(StandardCharsets.UTF_8));
        stdinFeed.flush();

        assertTrue(
            refused.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "the host's Err reaches the operator's screen: " + stdout);
        assertTrue(
            stdout.toString(StandardCharsets.UTF_8).contains("You do not hold the write token"),
            stdout.toString(StandardCharsets.UTF_8));

        stdinFeed.write(new byte[] {AttachLoop.DETACH_KEY});
        stdinFeed.flush();
        loop.join();
      }
    }
  }
}
