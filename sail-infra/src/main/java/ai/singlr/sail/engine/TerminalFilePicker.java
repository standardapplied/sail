/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * Drives a real terminal against the pure {@link CheckboxPicker}: puts the TTY in raw mode so arrow
 * keys and the space bar are read a keystroke at a time, renders into the alternate screen buffer
 * (so a big listing scrolls within a fixed, height-bounded window instead of flooding the
 * scrollback), and always restores the terminal — on confirm, cancel, exception, or Ctrl-C. Reads
 * the directory tree through a {@link FileSource}, so it browses a host path or a container
 * workspace identically.
 *
 * <p>Raw mode needs an interactive terminal and the {@code stty} utility; callers gate on {@link
 * #isAvailable()} and fall back to the typed {@link FilePicker} when it returns false.
 */
public final class TerminalFilePicker {

  private static final String ESC = String.valueOf((char) 27);
  private static final int IGNORED_KEY = -99;
  private static final int FALLBACK_ROWS = 24;
  private static final int FALLBACK_COLS = 80;

  private final FileSource source;
  private final Path root;
  private final InputStream in;
  private final PrintStream out;

  public TerminalFilePicker(FileSource source, Path root) {
    this(source, root, System.in, System.out);
  }

  TerminalFilePicker(FileSource source, Path root, InputStream in, PrintStream out) {
    this.source = source;
    this.root = root;
    this.in = in;
    this.out = out;
  }

  /** Whether a raw-mode picker can run here: an interactive console with a working {@code stty}. */
  public static boolean isAvailable() {
    return System.console() != null && sttyState().isPresent();
  }

  /**
   * Runs the picker, returning the checked paths (files and whole folders) the user confirmed, or
   * empty if they cancelled. The returned set feeds {@link FilePicker#selectedFiles} for expansion.
   */
  public Optional<LinkedHashSet<Path>> run() throws IOException {
    var saved = sttyState().orElse(null);
    if (saved == null) {
      return Optional.empty();
    }
    var restore = new Thread(() -> restore(saved));
    Runtime.getRuntime().addShutdownHook(restore);
    try {
      stty("raw -echo");
      out.print(ESC + "[?1049h" + ESC + "[?25l");
      out.flush();
      return drive();
    } finally {
      restore(saved);
      Runtime.getRuntime().removeShutdownHook(restore);
    }
  }

  /** The key/redraw loop, with no terminal-mode side effects, so it is testable without a TTY. */
  Optional<LinkedHashSet<Path>> drive() throws IOException {
    var picked = new LinkedHashSet<Path>();
    var screen = CheckboxPicker.Screen.of(root, root, FilePicker.list(source, root), picked);
    while (true) {
      draw(screen);
      var move = CheckboxPicker.apply(screen, CheckboxPicker.key(readKey()));
      screen = move.screen();
      switch (move.outcome()) {
        case CONFIRMED -> {
          return Optional.of(screen.picked());
        }
        case CANCELLED -> {
          return Optional.empty();
        }
        case OPEN, PARENT -> screen = navigate(screen, move.target());
        case BROWSING -> {}
      }
    }
  }

  private CheckboxPicker.Screen navigate(CheckboxPicker.Screen screen, Path target) {
    try {
      return CheckboxPicker.Screen.of(
          root, target, FilePicker.list(source, target), screen.picked());
    } catch (IOException unreadable) {
      return screen;
    }
  }

  private void draw(CheckboxPicker.Screen screen) {
    var size = terminalSize();
    var lines = CheckboxPicker.render(screen, size[0]);
    var sb = new StringBuilder(ESC + "[H");
    for (var i = 0; i < lines.size(); i++) {
      sb.append(fit(lines.get(i), size[1])).append(ESC).append("[K");
      if (i < lines.size() - 1) {
        sb.append("\r\n");
      }
    }
    sb.append(ESC).append("[0J");
    out.print(sb);
    out.flush();
  }

  static String fit(String line, int cols) {
    return line.length() <= cols ? line : line.substring(0, Math.max(0, cols - 1)) + "…";
  }

  private int[] terminalSize() {
    var size = stty("size").strip().split("\\s+");
    if (size.length == 2) {
      try {
        return new int[] {
          Math.max(4, Integer.parseInt(size[0])), Math.max(20, Integer.parseInt(size[1]))
        };
      } catch (NumberFormatException ignored) {
        return new int[] {FALLBACK_ROWS, FALLBACK_COLS};
      }
    }
    return new int[] {FALLBACK_ROWS, FALLBACK_COLS};
  }

  private int readKey() throws IOException {
    var b = in.read();
    if (b == -1) {
      return 27;
    }
    if (b != 27) {
      return b;
    }
    if (in.available() == 0 || in.read() != '[') {
      return 27;
    }
    return switch (in.read()) {
      case 'A' -> CheckboxPicker.ARROW_UP;
      case 'B' -> CheckboxPicker.ARROW_DOWN;
      case 'C' -> CheckboxPicker.ARROW_RIGHT;
      case 'D' -> CheckboxPicker.ARROW_LEFT;
      default -> IGNORED_KEY;
    };
  }

  private void restore(String saved) {
    out.print(ESC + "[?25h" + ESC + "[?1049l");
    out.flush();
    stty(saved);
  }

  private static Optional<String> sttyState() {
    return Stty.saved();
  }

  private static String stty(String args) {
    if (args.equals("size")) {
      var size = Stty.size(new int[] {FALLBACK_ROWS, FALLBACK_COLS});
      return size[0] + " " + size[1];
    }
    Stty.set(args);
    return "";
  }
}
