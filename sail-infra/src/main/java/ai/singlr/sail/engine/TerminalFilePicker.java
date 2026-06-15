/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * Drives a real terminal against the pure {@link CheckboxPicker}: puts the TTY in raw mode so arrow
 * keys and the space bar are read a keystroke at a time, redraws the checkbox list in place, and
 * always restores the terminal — on confirm, cancel, exception, or Ctrl-C. Reads the directory tree
 * through a {@link FileSource}, so it browses a host path or a container workspace identically.
 *
 * <p>Raw mode needs an interactive terminal and the {@code stty} utility; callers gate on {@link
 * #isAvailable()} and fall back to the typed {@link FilePicker} when it returns false.
 */
public final class TerminalFilePicker {

  private static final String ESC = String.valueOf((char) 27);
  private static final int IGNORED_KEY = -99;

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
      out.print(ESC + "[?25l");
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
    var screen = CheckboxPicker.Screen.of(root, root, source.children(root), picked);
    var previousLines = 0;
    while (true) {
      previousLines = draw(screen, previousLines);
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
      return CheckboxPicker.Screen.of(root, target, source.children(target), screen.picked());
    } catch (IOException unreadable) {
      return screen;
    }
  }

  private int draw(CheckboxPicker.Screen screen, int previousLines) {
    var lines = CheckboxPicker.render(screen);
    var sb = new StringBuilder();
    if (previousLines > 0) {
      sb.append(ESC).append('[').append(previousLines).append('A');
    }
    sb.append('\r').append(ESC).append("[0J");
    for (var line : lines) {
      sb.append(line).append("\r\n");
    }
    out.print(sb);
    out.flush();
    return lines.size();
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
    out.print(ESC + "[?25h");
    out.flush();
    stty(saved);
  }

  private static Optional<String> sttyState() {
    var result = stty("-g");
    return result.isBlank() ? Optional.empty() : Optional.of(result.strip());
  }

  private static String stty(String args) {
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
