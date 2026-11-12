/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The snapshot's own rules: a corpus answers every cell exactly as its snapshot does, each cell is
 * answered once, and an ask that fails other than by a refusal fails the corpus.
 */
class BaselineTest {

  private static final String READ_ONLY = "denied READ_ONLY read-only | Ask an admin.";

  private static SortedMap<String, String> answers(String... cells) {
    var answers = new TreeMap<String, String>();
    for (var i = 0; i < cells.length; i += 2) {
      answers.put(cells[i], cells[i + 1]);
    }
    return answers;
  }

  private static final SortedMap<String, String> BASELINE =
      answers("a", Corpus.ALLOWED, "b", READ_ONLY, "c", READ_ONLY);

  @Test
  void aCorpusThatAnswersAsItsBaselineDiffersNowhere() {
    assertEquals(List.of(), Baseline.differences(BASELINE, BASELINE));
  }

  @Test
  void aChangedVerdictAMissingCellAndANewOneAreEachADifference() {
    var now = answers("a", READ_ONLY, "b", READ_ONLY, "d", Corpus.ALLOWED);

    assertEquals(
        List.of(
            Baseline.difference("a", Corpus.ALLOWED, READ_ONLY),
            Baseline.difference("c", READ_ONLY, "(absent)"),
            Baseline.difference("d", "(absent)", Corpus.ALLOWED)),
        Baseline.differences(BASELINE, now));
  }

  @Test
  void aFailureCountsTheCellsThatDifferShowsThemAndSaysHowToRecordTheSnapshot() {
    var problems = Baseline.differences(BASELINE, answers("a", READ_ONLY, "b", READ_ONLY));

    assertEquals(
        "access/baseline/x.tsv: 2 cells differ from the snapshot; the first 2, each as its cell,"
            + " the snapshot's answer and the answer now:\n"
            + Baseline.difference("a", Corpus.ALLOWED, READ_ONLY)
            + "\n"
            + Baseline.difference("c", READ_ONLY, "(absent)")
            + "\nA change that alters an answer on purpose re-records access/baseline/x.tsv with"
            + " -Dsail.access.baseline=record; any other difference is a change of behaviour.",
        Baseline.report("access/baseline/x.tsv", problems));
  }

  @Test
  void aSnapshotAnswersEachCellOnceOnALineOfItsOwn() {
    assertEquals(
        answers("a", Corpus.ALLOWED, "b", READ_ONLY),
        Baseline.parsed("x", List.of("b\t" + READ_ONLY, "a\t" + Corpus.ALLOWED)));
    assertEquals(
        "x.tsv: a cell answered twice: a",
        assertThrows(
                IllegalStateException.class,
                () -> Baseline.parsed("x", List.of("a\t" + Corpus.ALLOWED, "a\t" + READ_ONLY)))
            .getMessage());
    assertEquals(
        "x.tsv: a line with no answer: a",
        assertThrows(IllegalStateException.class, () -> Baseline.parsed("x", List.of("a")))
            .getMessage());
  }

  @Test
  void anAskThatFailsOtherThanByARefusalFailsTheCorpus() {
    var corpus = new Corpus();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            corpus.ask(
                "cell",
                () -> {
                  throw new IllegalArgumentException("author is required");
                }));
    assertEquals(answers(), corpus.answers());
  }

  @Test
  void aCellIsAnsweredOnceOnOneLine() {
    var corpus = new Corpus();
    corpus.put("cell", "first line\nsecond\tline");

    assertEquals(answers("cell", "first line\\nsecond\\tline"), corpus.answers());
    assertEquals(
        "Cell answered twice: cell",
        assertThrows(IllegalArgumentException.class, () -> corpus.put("cell", "again"))
            .getMessage());
    assertEquals(
        "A cell key is one non-blank line with no tab: a\tb",
        assertThrows(IllegalArgumentException.class, () -> corpus.put("a\tb", "x")).getMessage());
  }

  @Test
  void anIdMintedAsACellIsAskedIsSpelledTheSameOnEveryRun() {
    var corpus = new Corpus(Map.of("01a1c368-22dc-7000-8906-e65f69ea9826", "review-ada"));
    corpus.put(
        "review 01a1c368-22dc-7000-8906-e65f69ea9826",
        "denied X message 01a1c999-0000-7000-8906-e65f69ea9826 of run"
            + " 019fee00-0000-7000-8000-0000000ada01 | fix");

    assertEquals(
        answers(
            "review review-ada",
            "denied X message <new id> of run 019fee00-0000-7000-8000-0000000ada01 | fix"),
        corpus.answers());
  }
}
