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
 * The differential's own rules: a corpus answers as its baseline does, but for the cells listed as
 * moved, each of which stays denied, answers what its line says, and is still answered.
 */
class BaselineTest {

  private static final String READ_ONLY = "denied READ_ONLY read-only | Ask an admin.";
  private static final String NOT_FOUND = "denied OUT_OF_SCOPE not found | Ask for a grant.";

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
    assertEquals(List.of(), Baseline.differences(BASELINE, List.of(), BASELINE));
  }

  @Test
  void aChangedVerdictAMissingCellAndANewOneAreEachADifference() {
    var now = answers("a", READ_ONLY, "b", READ_ONLY, "d", Corpus.ALLOWED);

    assertEquals(
        List.of(
            Baseline.difference("a", Corpus.ALLOWED, READ_ONLY),
            Baseline.difference("c", READ_ONLY, "(absent)"),
            Baseline.difference("d", "(absent)", Corpus.ALLOWED)),
        Baseline.differences(BASELINE, List.of(), now));
  }

  @Test
  void aCellWhoseReasonMovedAnswersWhatItsLineSays() {
    var moved = List.of(new Baseline.Moved("b", NOT_FOUND, "audience before role"));

    assertEquals(
        List.of(),
        Baseline.differences(
            BASELINE, moved, answers("a", "allowed", "b", NOT_FOUND, "c", READ_ONLY)));
    assertEquals(
        List.of(Baseline.difference("b", NOT_FOUND, READ_ONLY)),
        Baseline.differences(BASELINE, moved, BASELINE),
        "a moved line the corpus answers as the baseline does is stale");
  }

  @Test
  void aMoveNeverChangesAVerdict() {
    var moved =
        List.of(
            new Baseline.Moved("a", NOT_FOUND, "would deny what was allowed"),
            new Baseline.Moved("b", Corpus.ALLOWED, "would allow what was denied"));
    var now = answers("a", NOT_FOUND, "b", Corpus.ALLOWED, "c", READ_ONLY);

    assertEquals(
        List.of(
            "a\n  moved:    a move keeps a cell denied, and this one goes from 'allowed' to '"
                + NOT_FOUND
                + "'",
            "b\n  moved:    a move keeps a cell denied, and this one goes from '"
                + READ_ONLY
                + "' to 'allowed'",
            Baseline.difference("a", Corpus.ALLOWED, NOT_FOUND),
            Baseline.difference("b", READ_ONLY, Corpus.ALLOWED)),
        Baseline.differences(BASELINE, moved, now));
  }

  @Test
  void aCellNotFoundIsNeverAnsweredByARefusal() {
    var baseline =
        answers(
            "scope", NOT_FOUND,
            "door", "denied SPEC_NOT_FOUND 404 Spec 's' was not found.",
            "box", "denied UNHELD_PROJECT Project 'p' isn't on this box.");
    var moved =
        List.of(
            new Baseline.Moved("scope", READ_ONLY, "a refusal before the audience"),
            new Baseline.Moved("door", "denied ROOM_NOT_FOUND 404 Room 'r' was not found.", "x"),
            new Baseline.Moved("box", NOT_FOUND, "the actor's audience before the box's"));
    var now =
        answers(
            "scope", READ_ONLY,
            "door", "denied ROOM_NOT_FOUND 404 Room 'r' was not found.",
            "box", NOT_FOUND);

    assertEquals(
        List.of(
            "scope\n  moved:    a cell not found stays not found, and this one goes from '"
                + NOT_FOUND
                + "' to '"
                + READ_ONLY
                + "'",
            Baseline.difference("scope", NOT_FOUND, READ_ONLY)),
        Baseline.differences(baseline, moved, now));
  }

  @Test
  void aMovedLineNamesACellOfTheBaselineOnceWithANewAnswerAndAWhy() {
    var moved =
        List.of(
            new Baseline.Moved("z", NOT_FOUND, "no such cell"),
            new Baseline.Moved("b", READ_ONLY, "the same answer"),
            new Baseline.Moved("c", NOT_FOUND, " "),
            new Baseline.Moved("b", NOT_FOUND, "audience before role"));

    assertEquals(
        List.of(
            "z\n  moved:    the baseline answers no such cell",
            "b\n  moved:    it answers what the baseline answers",
            "c\n  moved:    names no why",
            "b\n  moved:    listed twice"),
        Baseline.differences(BASELINE, moved, BASELINE));
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
