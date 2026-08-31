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
            ai.singlr.sail.pty.PtyEvents.NONE)) {
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
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("pty-says:hello"));
      }
    }
  }

  @Test
  void theDetachKeyLeavesTheSessionAliveForTheNextClient() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyEvents.NONE)) {
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
            ai.singlr.sail.pty.PtyEvents.NONE)) {
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
}
