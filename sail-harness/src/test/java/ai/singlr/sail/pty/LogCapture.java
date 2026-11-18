/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** What the host logs on stderr while it is open, waited for line by line, never by sleeping. */
final class LogCapture extends OutputStream implements AutoCloseable {

  private final PrintStream original = System.err;
  private final StringBuilder text = new StringBuilder();

  LogCapture() {
    System.setErr(new PrintStream(this, true, StandardCharsets.UTF_8));
  }

  @Override
  public synchronized void write(int b) {
    text.append((char) b);
    notifyAll();
  }

  @Override
  public synchronized void write(byte[] bytes, int offset, int length) {
    text.append(new String(bytes, offset, length, StandardCharsets.UTF_8));
    notifyAll();
  }

  /** The first line logged that holds {@code fragment}, waiting up to 30 s for it. */
  synchronized String awaitLine(String fragment) throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (true) {
      for (var line : text.toString().split("\n")) {
        if (line.contains(fragment)) {
          return line;
        }
      }
      var left = deadline - System.nanoTime();
      assertTrue(left > 0, "no line logged holds " + fragment);
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
  }

  @Override
  public void close() {
    System.setErr(original);
  }
}
