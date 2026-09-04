/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyRooms;
import ai.singlr.sail.pty.PtySessionHost;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the pty session host reaches <em>inside</em> a real Incus container: a session created
 * with a {@code project} runs its child as {@code incus exec <project> -t}, so bytes and terminal
 * resizes cross the node pty, the incus tty, and the container pty. The boundary unit tests cover
 * the argv; only a live daemon can show that the three-layer tty forwarding actually carries a
 * keystroke and a SIGWINCH. Self-cleaning: the throwaway container is always removed.
 */
class PtyContainerSessionIT extends AbstractIncusIT {

  @TempDir Path dir;

  private PtySessionHost startHost() throws Exception {
    var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new PtyIdentity("uday", true),
            PtyRooms.NONE,
            PtyEvents.NONE,
            "0.0.0-test");
    host.start();
    return host;
  }

  private void prepareDevWorkspace(String container) throws Exception {
    var prepared =
        exec(
            container,
            List.of("bash", "-c", "mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev"));
    assertTrue(prepared.ok(), "could not prepare the dev workspace: " + prepared.stderr());
  }

  @Test
  void aKeystrokeCrossesIntoTheContainerAndItsOutputCrossesBack() throws Exception {
    ensureIncusOrSkip();
    var container = "sail-it-pty-echo";
    try {
      launch(container);
      prepareDevWorkspace(container);

      try (var host = startHost();
          var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create(
            "c1",
            List.of("sh", "-c", "read a; echo pty-says:$a@$(hostname); exit 0"),
            "/tmp",
            container,
            "",
            80,
            24);
        var channel = client.attach("c1", true);

        var stdinFeed = new PipedOutputStream();
        var stdin = new PipedInputStream(stdinFeed);
        var stdout = new ByteArrayOutputStream();
        stdinFeed.write("hello\n".getBytes(StandardCharsets.UTF_8));
        stdinFeed.flush();

        var reason = AttachLoop.run(channel, stdin, stdout);
        var rendered = stdout.toString(StandardCharsets.UTF_8);

        assertEquals("exited(0)", reason);
        assertTrue(
            rendered.contains("pty-says:hello@" + container),
            "the keystroke ran inside the container and its output came back: " + rendered);
      }
    } finally {
      deleteContainerQuietly(container);
    }
  }

  @Test
  void aTerminalResizeReachesTheContainerPty() throws Exception {
    ensureIncusOrSkip();
    var container = "sail-it-pty-resize";
    try {
      launch(container);
      prepareDevWorkspace(container);

      try (var host = startHost();
          var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create(
            "c1",
            List.of("sh", "-c", "trap 'stty size; exit 0' WINCH; echo ready; read a"),
            "/tmp",
            container,
            "",
            80,
            24);
        var channel = client.attach("c1", true);

        var ready = new CountDownLatch(1);
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
        var fired = new AtomicBoolean();
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
        var rendered = stdout.toString(StandardCharsets.UTF_8);

        assertEquals("exited(0)", reason);
        assertTrue(
            rendered.contains("40 100"),
            "the container child's SIGWINCH trap saw the forwarded 100x40 geometry: " + rendered);
      }
    } finally {
      deleteContainerQuietly(container);
    }
  }
}
