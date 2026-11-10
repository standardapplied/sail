/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.stream.Collectors;

/**
 * The permission snapshot: every answer a corpus gives, recorded at {@code
 * access/baseline/<name>.tsv}, one cell per line, its key, a tab, its answer, sorted by key. The
 * live corpus must answer every cell exactly as the snapshot does; any difference fails, naming the
 * cell and both answers.
 *
 * <p>A change that alters an answer re-records the snapshot, and the diff of the {@code .tsv} files
 * in its pull request is the list of what changed, cell by cell, for its review:
 *
 * <pre>
 * mvn -o -pl sail-core test -Dtest='*BaselineTest' -Dsail.access.baseline=record
 * mvn -o -pl sail-infra -am test -Dtest=AccessDoorsBaselineTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dsail.access.baseline=record
 * </pre>
 */
public final class Baseline {

  /** The system property whose value {@value #RECORD} records the live corpus as the snapshot. */
  public static final String SWITCH = "sail.access.baseline";

  /** The value of {@link #SWITCH} that records. */
  public static final String RECORD = "record";

  private static final String DIRECTORY = "access/baseline/";
  private static final Path SOURCES = Path.of("src", "test", "resources");
  private static final int SHOWN = 40;

  private Baseline() {}

  /**
   * Asserts that {@code corpus} answers exactly as snapshot {@code name}, or, under {@code
   * -Dsail.access.baseline=record}, records it as that snapshot.
   */
  public static void check(String name, Corpus corpus) {
    if (RECORD.equals(System.getProperty(SWITCH))) {
      record(name, corpus);
      return;
    }
    var problems = differences(baseline(name), corpus.answers());
    if (!problems.isEmpty()) {
      fail(report(name, problems));
    }
  }

  /** Every cell {@code now} answers otherwise than {@code baseline}, missing or new included. */
  static List<String> differences(
      SortedMap<String, String> baseline, SortedMap<String, String> now) {
    var problems = new ArrayList<String>();
    var cells = new TreeSet<>(baseline.keySet());
    cells.addAll(now.keySet());
    for (var cell : cells) {
      var want = baseline.getOrDefault(cell, "(absent)");
      var got = now.getOrDefault(cell, "(absent)");
      if (!want.equals(got)) {
        problems.add(difference(cell, want, got));
      }
    }
    return problems;
  }

  /** One differing cell as the failure shows it: the cell, then each answer on its own line. */
  static String difference(String cell, String baseline, String now) {
    return cell + "\n  baseline: " + baseline + "\n  now:      " + now;
  }

  private static String report(String name, List<String> problems) {
    var shown = problems.stream().limit(SHOWN).collect(Collectors.joining("\n"));
    return name
        + ": "
        + problems.size()
        + " cells differ from the snapshot; the first "
        + Math.min(SHOWN, problems.size())
        + ", each as its cell, the snapshot's answer and the answer now:\n"
        + shown
        + "\nA change that alters an answer on purpose re-records "
        + DIRECTORY
        + name
        + ".tsv with -D"
        + SWITCH
        + "="
        + RECORD
        + "; any other difference is a change of behaviour.";
  }

  /** The snapshot {@code name} as recorded, sorted by cell. */
  static SortedMap<String, String> baseline(String name) {
    return parsed(name, required(name + ".tsv"));
  }

  /**
   * The cells {@code lines} of snapshot {@code name} answer, each a key, a tab and its answer.
   *
   * @throws IllegalStateException for a line with no answer, or a cell answered twice
   */
  static SortedMap<String, String> parsed(String name, List<String> lines) {
    var answers = new TreeMap<String, String>();
    for (var line : lines) {
      var tab = line.indexOf('\t');
      if (tab < 0) {
        throw new IllegalStateException(name + ".tsv: a line with no answer: " + line);
      }
      var cell = line.substring(0, tab);
      if (answers.putIfAbsent(cell, line.substring(tab + 1)) != null) {
        throw new IllegalStateException(name + ".tsv: a cell answered twice: " + cell);
      }
    }
    return answers;
  }

  private static List<String> required(String file) {
    try (var in = Baseline.class.getClassLoader().getResourceAsStream(DIRECTORY + file)) {
      if (in == null) {
        throw new IllegalStateException(
            "No "
                + DIRECTORY
                + file
                + " on the test classpath: record it with -D"
                + SWITCH
                + "="
                + RECORD
                + ".");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void record(String name, Corpus corpus) {
    if (!Files.isDirectory(SOURCES)) {
      fail("Record from the module's own directory, where " + SOURCES + " is.");
    }
    var text =
        corpus.answers().entrySet().stream()
            .map(cell -> cell.getKey() + "\t" + cell.getValue() + "\n")
            .collect(Collectors.joining());
    try {
      var file = SOURCES.resolve(DIRECTORY + name + ".tsv");
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
