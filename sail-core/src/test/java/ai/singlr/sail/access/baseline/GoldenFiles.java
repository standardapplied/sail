/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A directory of golden files held to what is written now, by {@link Baseline}'s own switch,
 * difference and report: the directory must hold exactly the files written now, each with exactly
 * its text, and a change that alters one on purpose re-records the directory under {@code
 * -Dsail.access.baseline=record}. A file is a cell of the snapshot; its failure names how many of
 * its lines differ and shows the first.
 */
public final class GoldenFiles {

  private GoldenFiles() {}

  /**
   * Asserts that {@code directory} holds exactly {@code now}, each file's name to its text, or,
   * under {@code -Dsail.access.baseline=record}, makes it hold that.
   */
  public static void check(Path directory, SortedMap<String, String> now) {
    if (Baseline.recording()) {
      record(directory, now);
      return;
    }
    Baseline.failOn(directory.toString(), differences(read(directory), now));
  }

  /** Every file {@code now} writes otherwise than {@code recorded}, missing or new included. */
  static List<String> differences(
      SortedMap<String, String> recorded, SortedMap<String, String> now) {
    var problems = new ArrayList<String>();
    var names = new TreeSet<>(recorded.keySet());
    names.addAll(now.keySet());
    for (var name : names) {
      var lines = Baseline.differences(lines(recorded.get(name)), lines(now.get(name)));
      if (!lines.isEmpty()) {
        problems.add(
            name + ": " + lines.size() + " lines differ, the first is line " + lines.getFirst());
      }
    }
    return problems;
  }

  /**
   * The lines of {@code text} by their number, none for a file that is absent. A line ends at its
   * line feed alone, so a terminator that changes is a line that changes.
   */
  private static SortedMap<Integer, String> lines(String text) {
    var lines = new TreeMap<Integer, String>();
    if (text != null) {
      for (var line : text.split("\n", -1)) {
        lines.put(lines.size() + 1, line);
      }
    }
    return lines;
  }

  /** The files {@code directory} holds, by name; none when it is not there. */
  static SortedMap<String, String> read(Path directory) {
    var files = new TreeMap<String, String>();
    if (!Files.isDirectory(directory)) {
      return files;
    }
    try (var listing = Files.list(directory)) {
      for (var file : listing.toList()) {
        files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return files;
  }

  static void record(Path directory, SortedMap<String, String> now) {
    try {
      Files.createDirectories(directory);
      for (var stale : read(directory).keySet()) {
        if (!now.containsKey(stale)) {
          Files.delete(directory.resolve(stale));
        }
      }
      for (var file : now.entrySet()) {
        Files.writeString(
            directory.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
