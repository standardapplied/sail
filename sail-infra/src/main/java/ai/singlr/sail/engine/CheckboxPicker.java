/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Pure state engine for the raw-mode checkbox file picker: cursor movement, checking/unchecking,
 * and the intent to open a folder or go up. It holds no I/O — {@link TerminalFilePicker} drives a
 * real terminal against it — so every transition is deterministically testable without a TTY.
 *
 * <p>Keys arrive as integer codes: printable bytes as-is, arrows as the negative {@code ARROW_*}
 * constants the driver substitutes for escape sequences. {@link #key} maps a code to a {@link Key};
 * {@link #apply} maps a {@code (Screen, Key)} to a {@link Move} whose {@link Outcome} tells the
 * driver whether to redraw, navigate, share, or cancel.
 */
public final class CheckboxPicker {

  public static final int ARROW_UP = -1;
  public static final int ARROW_DOWN = -2;
  public static final int ARROW_RIGHT = -3;
  public static final int ARROW_LEFT = -4;

  private static final int ESC = 27;
  private static final int CTRL_C = 3;

  private CheckboxPicker() {}

  public enum Key {
    UP,
    DOWN,
    OPEN,
    PARENT,
    TOGGLE,
    ALL,
    CONFIRM,
    CANCEL,
    NONE
  }

  public enum Outcome {
    BROWSING,
    OPEN,
    PARENT,
    CONFIRMED,
    CANCELLED
  }

  /** The current folder, its listing, the highlighted row, and everything checked so far. */
  public record Screen(
      Path root, Path cwd, List<FilePicker.Entry> entries, int cursor, LinkedHashSet<Path> picked) {

    public static Screen of(
        Path root, Path cwd, List<FilePicker.Entry> entries, LinkedHashSet<Path> picked) {
      return new Screen(root, cwd, entries, 0, picked);
    }

    FilePicker.Entry current() {
      return entries.isEmpty() ? null : entries.get(cursor);
    }
  }

  /**
   * The result of one key: the next screen, what the driver should do, and any navigation target.
   */
  public record Move(Screen screen, Outcome outcome, Path target) {}

  /** Maps a raw key code (or synthetic {@code ARROW_*}) to a {@link Key}. */
  public static Key key(int code) {
    return switch (code) {
      case ARROW_UP -> Key.UP;
      case ARROW_DOWN -> Key.DOWN;
      case ARROW_RIGHT -> Key.OPEN;
      case ARROW_LEFT -> Key.PARENT;
      case ' ' -> Key.TOGGLE;
      case '\r', '\n', 's', 'S' -> Key.CONFIRM;
      case 'q', 'Q', ESC, CTRL_C -> Key.CANCEL;
      case 'a', 'A' -> Key.ALL;
      case 'k', 'K' -> Key.UP;
      case 'j', 'J' -> Key.DOWN;
      case 'l', 'L' -> Key.OPEN;
      case 'h', 'H' -> Key.PARENT;
      default -> Key.NONE;
    };
  }

  /** Applies a key to the screen, returning the next screen and what the driver should do next. */
  public static Move apply(Screen s, Key key) {
    return switch (key) {
      case UP -> browsing(withCursor(s, s.cursor() - 1));
      case DOWN -> browsing(withCursor(s, s.cursor() + 1));
      case TOGGLE -> browsing(toggle(s, current(s)));
      case ALL -> browsing(toggleAll(s));
      case CONFIRM -> new Move(s, Outcome.CONFIRMED, null);
      case CANCEL -> new Move(s, Outcome.CANCELLED, null);
      case PARENT ->
          s.cwd().equals(s.root()) ? browsing(s) : new Move(s, Outcome.PARENT, s.cwd().getParent());
      case OPEN -> open(s);
      case NONE -> browsing(s);
    };
  }

  private static Move open(Screen s) {
    var entry = s.current();
    if (entry == null) {
      return browsing(s);
    }
    return entry.directory() ? new Move(s, Outcome.OPEN, entry.path()) : browsing(toggle(s, entry));
  }

  private static Screen toggle(Screen s, FilePicker.Entry entry) {
    if (entry == null) {
      return s;
    }
    var picked = new LinkedHashSet<>(s.picked());
    if (!picked.remove(entry.path())) {
      picked.add(entry.path());
    }
    return new Screen(s.root(), s.cwd(), s.entries(), s.cursor(), picked);
  }

  private static Screen toggleAll(Screen s) {
    var picked = new LinkedHashSet<>(s.picked());
    for (var entry : s.entries()) {
      if (!picked.remove(entry.path())) {
        picked.add(entry.path());
      }
    }
    return new Screen(s.root(), s.cwd(), s.entries(), s.cursor(), picked);
  }

  private static FilePicker.Entry current(Screen s) {
    return s.current();
  }

  private static Screen withCursor(Screen s, int cursor) {
    if (s.entries().isEmpty()) {
      return s;
    }
    var clamped = Math.max(0, Math.min(cursor, s.entries().size() - 1));
    return new Screen(s.root(), s.cwd(), s.entries(), clamped, s.picked());
  }

  private static Move browsing(Screen s) {
    return new Move(s, Outcome.BROWSING, null);
  }

  /** Renders the screen as display lines: a header, the checkbox rows, and the key legend. */
  public static List<String> render(Screen s) {
    var here = s.root().relativize(s.cwd()).toString();
    var lines = new ArrayList<String>();
    lines.add("  " + (here.isEmpty() ? "." : here) + "  —  check files to share");
    if (s.entries().isEmpty()) {
      lines.add("    (empty folder)");
    }
    for (var i = 0; i < s.entries().size(); i++) {
      var e = s.entries().get(i);
      var pointer = i == s.cursor() ? "›" : " ";
      var box = s.picked().contains(e.path()) ? "[x]" : "[ ]";
      var name = e.path().getFileName() + (e.directory() ? "/" : "");
      var detail = e.directory() ? "" : "  " + FilePicker.humanSize(e.size());
      lines.add(String.format("  %s %s %-28s%s", pointer, box, name, detail));
    }
    lines.add(
        "  ↑↓ move · space check · → open · ← up · a all · enter/s share · q quit  ("
            + s.picked().size()
            + " checked)");
    return lines;
  }
}
