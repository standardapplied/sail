/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.PrintStream;
import picocli.CommandLine.Help.Ansi;

/**
 * Animated terminal spinner for long-running operations. Runs on a virtual thread, redraws in place
 * with the current elapsed seconds, and clears its line on {@link #stop()}.
 *
 * <p>Use the static {@link #run} helper for try-with-resources style:
 *
 * <pre>{@code
 * try (var ignored = Spinner.start(System.out, "Creating snapshot " + label)) {
 *   snapMgr.create(name, label);
 * }
 * }</pre>
 */
public final class Spinner implements AutoCloseable {

  private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
  private static final long FRAME_MS = 80;

  private final PrintStream out;
  private final String message;
  private final long startNanos;
  private volatile boolean spinning;
  private Thread thread;

  private Spinner(PrintStream out, String message) {
    this.out = out;
    this.message = message;
    this.startNanos = System.nanoTime();
  }

  /** Starts a spinner immediately and returns it; close to stop. */
  public static Spinner start(PrintStream out, String message) {
    var s = new Spinner(out, message);
    s.begin();
    return s;
  }

  private void begin() {
    spinning = true;
    thread =
        Thread.ofVirtual()
            .name("sail-spinner")
            .start(
                () -> {
                  var i = 0;
                  while (spinning) {
                    var elapsed = (System.nanoTime() - startNanos) / 1_000_000_000L;
                    var frame = FRAMES[i % FRAMES.length];
                    out.print(
                        "\r  "
                            + Ansi.AUTO.string("@|yellow " + frame + "|@")
                            + " "
                            + message
                            + " ("
                            + elapsed
                            + "s)");
                    out.flush();
                    try {
                      Thread.sleep(FRAME_MS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    i++;
                  }
                });
  }

  /** Stops the spinner and clears its line. */
  public void stop() {
    spinning = false;
    var t = thread;
    if (t != null) {
      try {
        t.join(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      thread = null;
    }
    out.print("\r\033[2K");
    out.flush();
  }

  @Override
  public void close() {
    stop();
  }
}
