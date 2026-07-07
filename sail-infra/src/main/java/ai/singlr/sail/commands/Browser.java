/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Opens the engineer's default browser — {@code open} on macOS, {@code xdg-open} elsewhere. A
 * ceremony never depends on this succeeding: the caller prints the URL first, so when no opener is
 * available the engineer pastes it by hand.
 */
final class Browser {

  private Browser() {}

  static void open(String url) {
    var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    var command = os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
    try {
      new ProcessBuilder(command).start();
    } catch (IOException ignored) {
      System.out.println("  (Could not open a browser automatically — open the URL above.)");
    }
  }
}
