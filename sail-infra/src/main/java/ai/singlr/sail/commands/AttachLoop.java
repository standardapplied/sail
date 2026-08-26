/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The interactive half of {@code sail session attach}, free of terminal-mode side effects so it is
 * testable with plain streams: pumps stdin to {@code Input} frames (detaching on Ctrl-]), renders
 * {@code Output} bytes to stdout, narrates flow control inline. Returns the ending reason, or null
 * when the operator detached and the session lives on.
 */
public final class AttachLoop {

  public static final int DETACH_KEY = 0x1D;

  /**
   * A source of terminal-size changes to forward to the remote pty. {@link #next()} blocks until
   * the next size, returning {@code {cols, rows}}, or {@code null} once there are no more — so the
   * loop's resize thread ends cleanly. {@link #NONE} never resizes, for callers with no live
   * terminal.
   */
  @FunctionalInterface
  public interface Resizes {
    int[] next() throws InterruptedException;

    Resizes NONE = () -> null;
  }

  private AttachLoop() {}

  public static String run(ByteChannel channel, InputStream stdin, OutputStream stdout)
      throws IOException {
    return run(channel, stdin, stdout, Resizes.NONE);
  }

  public static String run(
      ByteChannel channel, InputStream stdin, OutputStream stdout, Resizes resizes)
      throws IOException {
    var seq = new AtomicLong();
    var writeLock = new Object();
    var pump =
        Thread.ofVirtual()
            .start(
                () -> {
                  var buf = new byte[4096];
                  try {
                    while (true) {
                      var n = stdin.read(buf);
                      if (n < 0) {
                        return;
                      }
                      for (var i = 0; i < n; i++) {
                        if ((buf[i] & 0xFF) == DETACH_KEY) {
                          synchronized (writeLock) {
                            PtyWire.write(channel, new PtyMessage.Detach());
                          }
                          return;
                        }
                      }
                      var chunk = new byte[n];
                      System.arraycopy(buf, 0, chunk, 0, n);
                      synchronized (writeLock) {
                        PtyWire.write(channel, new PtyMessage.Input(seq.incrementAndGet(), chunk));
                      }
                    }
                  } catch (IOException ignored) {
                    var unused = ignored;
                  }
                });
    var resizer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    int[] size;
                    while ((size = resizes.next()) != null) {
                      synchronized (writeLock) {
                        PtyWire.write(channel, new PtyMessage.Resize(size[0], size[1]));
                      }
                    }
                  } catch (InterruptedException | IOException ignored) {
                    var unused = ignored;
                  }
                });
    try {
      while (true) {
        var message = PtyWire.read(channel);
        switch (message) {
          case PtyMessage.Output(var inputSeq, var bytes) -> {
            stdout.write(bytes);
            stdout.flush();
          }
          case PtyMessage.SessionEnded(var reason) -> {
            return reason;
          }
          case PtyMessage.Ok ok -> {
            return null;
          }
          case PtyMessage.Paused paused ->
              stdout.write(
                  "\r\n[sail: output paused — falling behind]\r\n"
                      .getBytes(StandardCharsets.UTF_8));
          case PtyMessage.Continued resumed ->
              stdout.write("\r\n[sail: output resumed]\r\n".getBytes(StandardCharsets.UTF_8));
          default -> {}
        }
      }
    } catch (java.io.EOFException ended) {
      return "connection closed";
    } finally {
      pump.interrupt();
      resizer.interrupt();
    }
  }
}
