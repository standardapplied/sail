/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PtyConcurrencyTest {

  /**
   * Types continuously while the child echoes and exits on its own — the read half (a gather
   * thread) and the write half (a keystroke thread) run at once, the normal interactive case. Every
   * line must round-trip intact; a shared native buffer between {@link Pty#read} and {@link
   * Pty#write} corrupts one stream or the other and drops or garbles lines.
   */
  @Test
  void concurrentReadAndWriteNeverCorruptEitherStream() throws Exception {
    var lines = 1000;
    try (var pty = Pty.open(80, 24)) {
      pty.spawn(
          List.of(
              "sh",
              "-c",
              "i=0; while [ $i -lt "
                  + lines
                  + " ]; do IFS= read -r l; printf 'R:%s\\n' \"$l\";"
                  + " i=$((i+1)); done"),
          Map.of("TERM", "dumb"),
          java.nio.file.Path.of("/tmp"));

      var collected = new ByteArrayOutputStream();
      var readerDone = new CountDownLatch(1);
      Thread.ofPlatform()
          .start(
              () -> {
                var buf = new byte[8192];
                try {
                  int n;
                  while ((n = pty.read(buf)) >= 0) {
                    collected.write(buf, 0, n);
                  }
                } catch (Exception e) {
                  throw new RuntimeException(e);
                } finally {
                  readerDone.countDown();
                }
              });

      for (var i = 0; i < lines; i++) {
        pty.write(("line-" + String.format("%06d", i) + "\n").getBytes(StandardCharsets.UTF_8));
      }

      assertTrue(readerDone.await(30, TimeUnit.SECONDS), "the child echoed and exited");
      var out = collected.toString(StandardCharsets.UTF_8);
      var missing = new StringBuilder();
      for (var i = 0; i < lines; i++) {
        var expected = "R:line-" + String.format("%06d", i);
        if (!out.contains(expected)) {
          missing.append(expected).append(' ');
        }
      }
      assertTrue(
          missing.isEmpty(), "every echoed line must arrive intact; corrupted/lost: " + missing);
    }
  }

  /**
   * Closing the master while a reader is actively draining a flood must never throw an arena-in-use
   * error and must let the reader observe end of stream — the property that broke when read, write,
   * and close shared one arena.
   */
  @Test
  void closeWhileAReaderIsActiveIsCleanAndEndsTheStream() throws Exception {
    var pty = Pty.open(80, 24);
    pty.spawn(
        List.of("sh", "-c", "while true; do echo flood; done"),
        Map.of("TERM", "dumb"),
        java.nio.file.Path.of("/tmp"));

    var sawFlood = new CountDownLatch(1);
    var readerDone = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    Thread.ofPlatform()
        .start(
            () -> {
              var buf = new byte[4096];
              try {
                int n;
                while ((n = pty.read(buf)) >= 0) {
                  if (n > 0) {
                    sawFlood.countDown();
                  }
                }
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                readerDone.countDown();
              }
            });

    assertTrue(sawFlood.await(10, TimeUnit.SECONDS), "the flood is streaming");
    assertDoesNotThrow(pty::close, "close never throws, even mid-read");
    assertTrue(readerDone.await(10, TimeUnit.SECONDS), "the reader observes end of stream");
    assertTrue(
        failure.get() == null,
        "a close during read is end of stream, not an error: " + failure.get());
  }
}
