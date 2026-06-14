/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pure navigation engine for the interactive "pick files to share" prompt. It holds no I/O: the
 * command reads a directory listing and a line of input, asks {@link #step} for the next {@link
 * State}, and renders it — so every transition (descend, go up, toggle a file, select a whole
 * folder, confirm, cancel) is deterministically testable without a terminal.
 *
 * <p>Navigation is fenced to the chosen {@code root}: {@code ..} never climbs above it, so a shared
 * file's path is always meaningful relative to that root.
 */
public final class FilePicker {

  private FilePicker() {}

  /** One row in a directory listing. */
  public record Entry(Path path, boolean directory, long size) {}

  public enum Status {
    BROWSING,
    CONFIRMED,
    CANCELLED
  }

  /** The current location and the set of files/folders picked so far. */
  public record State(Path root, Path cwd, LinkedHashSet<Path> picked) {
    public static State at(Path root) {
      return new State(root, root, new LinkedHashSet<>());
    }
  }

  /** The outcome of applying one line of input: the next state, the status, and a message. */
  public record Step(State state, Status status, String message) {}

  /** Lists a directory: sub-folders first, then files, each alphabetical. */
  public static List<Entry> list(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .map(FilePicker::toEntry)
          .sorted(
              Comparator.comparing(Entry::directory)
                  .reversed()
                  .thenComparing(e -> e.path().getFileName().toString()))
          .toList();
    }
  }

  private static Entry toEntry(Path p) {
    var dir = Files.isDirectory(p);
    long size = 0;
    if (!dir) {
      try {
        size = Files.size(p);
      } catch (IOException ignored) {
        size = 0;
      }
    }
    return new Entry(p, dir, size);
  }

  /**
   * Applies one line of input against the {@code entries} of the current directory. A lone
   * directory number descends; any other number(s) toggle the referenced files/folders; {@code a}
   * picks the whole current folder; {@code ..} goes up; {@code done} confirms; {@code q} cancels.
   */
  public static Step step(State state, List<Entry> entries, String input) {
    var in = input == null ? "" : input.strip();
    if (in.isEmpty()) {
      return browsing(state, "");
    }
    return switch (in) {
      case "q" -> new Step(state, Status.CANCELLED, "");
      case "done", "d" -> new Step(state, Status.CONFIRMED, "");
      case ".." -> up(state);
      case "a" -> pick(state, List.of(state.cwd()), "Selected this folder.");
      default -> numbers(state, entries, in);
    };
  }

  private static Step up(State state) {
    if (state.cwd().equals(state.root())) {
      return browsing(state, "Already at the top.");
    }
    return browsing(new State(state.root(), state.cwd().getParent(), state.picked()), "");
  }

  private static Step numbers(State state, List<Entry> entries, String in) {
    var tokens = in.split("\\s+");
    var chosen = new ArrayList<Entry>();
    for (var token : tokens) {
      var index = parseIndex(token, entries.size());
      if (index < 0) {
        return browsing(state, "Not a choice: " + token);
      }
      chosen.add(entries.get(index));
    }
    if (chosen.size() == 1 && chosen.get(0).directory()) {
      return browsing(new State(state.root(), chosen.get(0).path(), state.picked()), "");
    }
    return pick(state, chosen.stream().map(Entry::path).toList(), null);
  }

  private static Step pick(State state, List<Path> paths, String message) {
    var picked = new LinkedHashSet<>(state.picked());
    for (var path : paths) {
      if (!picked.remove(path)) {
        picked.add(path);
      }
    }
    var note = message != null ? message : picked.size() + " selected.";
    return browsing(new State(state.root(), state.cwd(), picked), note);
  }

  private static Step browsing(State state, String message) {
    return new Step(state, Status.BROWSING, message);
  }

  private static int parseIndex(String token, int size) {
    try {
      var n = Integer.parseInt(token) - 1;
      return n >= 0 && n < size ? n : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Renders the current folder as a numbered listing with picked entries marked. */
  public static String render(State state, List<Entry> entries) {
    var here = state.root().relativize(state.cwd()).toString();
    var out = new StringBuilder();
    out.append("  ").append(here.isEmpty() ? "." : here).append("  —  pick files to share\n");
    for (var i = 0; i < entries.size(); i++) {
      var e = entries.get(i);
      var mark = state.picked().contains(e.path()) ? "*" : " ";
      var name = e.path().getFileName() + (e.directory() ? "/" : "");
      var detail = e.directory() ? "" : "  " + humanSize(e.size());
      out.append(String.format("  %s [%d] %-28s%s%n", mark, i + 1, name, detail));
    }
    out.append("    number(s) toggle · a = this folder · .. up · done · q quit");
    if (!state.picked().isEmpty()) {
      out.append("   (").append(state.picked().size()).append(" picked)");
    }
    return out.toString();
  }

  private static String humanSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024 * 1024) {
      return bytes / 1024 + " KB";
    }
    return bytes / (1024 * 1024) + " MB";
  }

  /**
   * Expands the picked set to concrete regular files (folders walked recursively), de-duplicated.
   */
  public static List<Path> selectedFiles(State state) throws IOException {
    var files = new LinkedHashSet<Path>();
    for (var picked : state.picked()) {
      if (Files.isDirectory(picked)) {
        try (Stream<Path> walk = Files.walk(picked)) {
          walk.filter(Files::isRegularFile).forEach(files::add);
        }
      } else if (Files.isRegularFile(picked)) {
        files.add(picked);
      }
    }
    return files.stream().sorted().toList();
  }
}
