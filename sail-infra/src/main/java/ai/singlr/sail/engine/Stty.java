/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The one seam to the controlling terminal's mode: save ({@code stty -g}), set, and size, always
 * against {@code /dev/tty} so redirected stdio never confuses it. Callers gate on {@link #saved()}
 * being present before entering raw mode and restore in a finally — the picker and the session
 * attach loop share exactly this discipline.
 */
public final class Stty {

  private Stty() {}

  /** The current mode as a restorable token, or empty when there is no usable terminal. */
  public static Optional<String> saved() {
    var state = run("-g");
    return state.isBlank() ? Optional.empty() : Optional.of(state.strip());
  }

  /** Applies {@code args} (a saved token, or e.g. {@code "raw -echo"}). */
  public static void set(String args) {
    run(args);
  }

  /** The terminal geometry as {@code {rows, cols}}, or {@code fallback} when unknowable. */
  public static int[] size(int[] fallback) {
    var size = run("size").strip().split("\\s+");
    if (size.length == 2) {
      try {
        return new int[] {Integer.parseInt(size[0]), Integer.parseInt(size[1])};
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static String run(String args) {
    try {
      var process =
          new ProcessBuilder("sh", "-c", "stty " + args + " </dev/tty")
              .redirectErrorStream(false)
              .start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      process.waitFor();
      return output;
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }
}
