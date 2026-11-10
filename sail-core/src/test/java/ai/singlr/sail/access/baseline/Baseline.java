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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The differential proof that moving every permission rule onto one decision changes no answer it
 * does not name. A baseline is a corpus's answers as main's rules gave them at {@code fd091a53},
 * recorded by a commit that changed no rule, at {@code access/baseline/<name>.tsv}: one cell per
 * line, its key, a tab, its answer, sorted by key. Its companion {@code <name>.moved.tsv} lists the
 * cells whose reason is agreed to move toward the decision's order, one per line: the key, the
 * answer now, and one line of why, tab-separated; a line starting with {@code #} is a note.
 *
 * <p>The live corpus must answer every cell exactly as the baseline does, but for the moved cells,
 * which must answer what their line says. A moved cell is one the baseline denies and that is still
 * denied, for a reason other than the baseline's: allowed never becomes denied, nor denied allowed,
 * and a cell not found is never answered by a refusal ({@link #notFound}). A moved line the corpus
 * does not answer, or answers as the baseline does, is stale and fails.
 *
 * <p>A baseline is regenerated only on purpose, from untouched rules: {@code
 * -Dsail.access.baseline=record} writes the live corpus over {@code
 * src/test/resources/access/baseline/<name>.tsv} of the module under test, and is refused while any
 * cell is listed as moved, since the rules it would record are no longer today's:
 *
 * <pre>
 * mvn -o -pl sail-core test -Dtest='*BaselineTest' -Dsail.access.baseline=record
 * mvn -o -pl sail-infra -am test -Dtest=AccessDoorsBaselineTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dsail.access.baseline=record
 * </pre>
 */
public final class Baseline {

  /** The system property whose value {@value #RECORD} records the live corpus as the baseline. */
  public static final String SWITCH = "sail.access.baseline";

  /** The value of {@link #SWITCH} that records. */
  public static final String RECORD = "record";

  private static final String DIRECTORY = "access/baseline/";
  private static final Path SOURCES = Path.of("src", "test", "resources");
  private static final int SHOWN = 40;

  /**
   * The kinds and door codes of a not-found answer: outside the asker's audience, a project or a
   * conversation this box does not hold, and the doors' 404s for an id that is not there. A project
   * whose container this box has not created is there, and the dispatch admission's placement says
   * so last ({@code PROJECT_NOT_CREATED}), so it is not one. A push's answer names no kind, so its
   * corpus is held to this through the rules it shares with the writes'.
   */
  private static final Set<String> NOT_FOUND =
      Set.of(
          "OUT_OF_SCOPE",
          "UNHELD_PROJECT",
          "NO_CONVERSATION",
          "NOT_FOUND",
          "SPEC_NOT_FOUND",
          "ROOM_NOT_FOUND",
          "RUN_NOT_FOUND",
          "PROJECT_NOT_IN_CATALOG");

  private Baseline() {}

  /** A cell whose reason moved: its answer now, and why. */
  public record Moved(String cell, String answer, String why) {}

  /**
   * Asserts that {@code corpus} answers as baseline {@code name} with its moved cells applied, or,
   * under {@code -Dsail.access.baseline=record}, records it as that baseline.
   */
  public static void check(String name, Corpus corpus) {
    if (RECORD.equals(System.getProperty(SWITCH))) {
      record(name, corpus);
      return;
    }
    var problems = differences(baseline(name), moved(name), corpus.answers());
    if (!problems.isEmpty()) {
      fail(report(name, problems));
    }
  }

  /**
   * Every way {@code now} departs from {@code baseline} once {@code moved} is applied, each as the
   * cell and its two answers ({@link #difference}), after every moved line that breaks the rules of
   * a move.
   */
  static List<String> differences(
      SortedMap<String, String> baseline, List<Moved> moved, SortedMap<String, String> now) {
    var problems = new ArrayList<String>();
    var expected = new TreeMap<>(baseline);
    var listed = new TreeSet<String>();
    for (var cell : moved) {
      var refusal = misfit(cell, baseline, listed);
      if (refusal != null) {
        problems.add(cell.cell() + "\n  moved:    " + refusal);
      } else {
        expected.put(cell.cell(), cell.answer());
      }
    }
    var cells = new TreeSet<>(expected.keySet());
    cells.addAll(now.keySet());
    for (var cell : cells) {
      var want = expected.getOrDefault(cell, "(absent)");
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

  /** Why {@code cell} is not a move the proof admits; null when it is one. */
  private static String misfit(Moved cell, Map<String, String> baseline, TreeSet<String> listed) {
    var before = baseline.get(cell.cell());
    if (!listed.add(cell.cell())) {
      return "listed twice";
    }
    if (cell.why().isBlank()) {
      return "names no why";
    }
    if (before == null) {
      return "the baseline answers no such cell";
    }
    if (!denied(before) || !denied(cell.answer())) {
      return "a move keeps a cell denied, and this one goes from '"
          + before
          + "' to '"
          + cell.answer()
          + "'";
    }
    if (before.equals(cell.answer())) {
      return "it answers what the baseline answers";
    }
    if (notFound(before) && !notFound(cell.answer())) {
      return "a cell not found stays not found, and this one goes from '"
          + before
          + "' to '"
          + cell.answer()
          + "'";
    }
    return null;
  }

  private static boolean denied(String answer) {
    return answer.startsWith("denied ");
  }

  /**
   * Whether {@code answer} says what was asked about is not there ({@link #NOT_FOUND}): an answer
   * that may only move earlier, so no refusal ever takes its place and tells the asker it exists.
   */
  private static boolean notFound(String answer) {
    var words = answer.split(" ", 3);
    return words.length > 1 && NOT_FOUND.contains(words[1]);
  }

  private static String report(String name, List<String> problems) {
    var shown = problems.stream().limit(SHOWN).collect(Collectors.joining("\n"));
    return name
        + ": "
        + problems.size()
        + " cells differ from the baseline; the first "
        + Math.min(SHOWN, problems.size())
        + ", each as its cell, the baseline's answer and the answer now:\n"
        + shown
        + "\nA cell whose reason moved toward the agreed order is listed in "
        + DIRECTORY
        + name
        + ".moved.tsv; any other difference is a change of behaviour.";
  }

  /** The baseline {@code name} as recorded, sorted by cell. */
  static SortedMap<String, String> baseline(String name) {
    var answers = new TreeMap<String, String>();
    for (var line : required(name + ".tsv")) {
      var tab = line.indexOf('\t');
      if (tab < 0) {
        throw new IllegalStateException(name + ".tsv: a line with no answer: " + line);
      }
      answers.put(line.substring(0, tab), line.substring(tab + 1));
    }
    return answers;
  }

  /** The moved cells of baseline {@code name}, in the order listed. */
  static List<Moved> moved(String name) {
    return moved(name, required(name + ".moved.tsv"));
  }

  private static List<Moved> moved(String name, List<String> lines) {
    var moved = new ArrayList<Moved>();
    for (var line : lines) {
      if (line.startsWith("#") || line.isBlank()) {
        continue;
      }
      var fields = line.split("\t", -1);
      if (fields.length != 3) {
        throw new IllegalStateException(
            name + ".moved.tsv: a line is cell, answer and why, tab-separated: " + line);
      }
      moved.add(new Moved(fields[0], fields[1], fields[2]));
    }
    return moved;
  }

  private static List<String> required(String file) {
    return lines(file)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No "
                        + DIRECTORY
                        + file
                        + " on the test classpath: record it from untouched rules with -D"
                        + SWITCH
                        + "="
                        + RECORD
                        + "."));
  }

  private static Optional<List<String>> lines(String file) {
    try (var in = Baseline.class.getClassLoader().getResourceAsStream(DIRECTORY + file)) {
      return in == null
          ? Optional.empty()
          : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void record(String name, Corpus corpus) {
    var moved = lines(name + ".moved.tsv").map(lines -> moved(name, lines)).orElse(List.of());
    if (!moved.isEmpty()) {
      fail(
          name
              + ".moved.tsv lists "
              + moved.size()
              + " moved cells, so these rules are no longer the ones the baseline records.");
    }
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
      var companion = SOURCES.resolve(DIRECTORY + name + ".moved.tsv");
      if (!Files.exists(companion)) {
        Files.writeString(companion, MOVED_HEADER, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final String MOVED_HEADER =
      "# cell\tanswer now\twhy: a cell whose reason moved toward the agreed order (Baseline)\n";
}
