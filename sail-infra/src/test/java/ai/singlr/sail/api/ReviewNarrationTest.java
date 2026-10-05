/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.Finding;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReviewNarrationTest {

  private static Finding finding(Finding.Severity severity, String title, String file) {
    return new Finding(
        title,
        severity,
        Finding.Category.SECURITY,
        file,
        7,
        7,
        title,
        "d",
        "e",
        null,
        0.9,
        Finding.Resolution.OPEN,
        null,
        null,
        null);
  }

  private static Finding disputed(String title, String argument) {
    return new Finding(
        title,
        Finding.Severity.HIGH,
        Finding.Category.SECURITY,
        "Auth.java",
        3,
        3,
        title,
        "d",
        "e",
        null,
        0.9,
        Finding.Resolution.DISPUTED,
        argument,
        null,
        null);
  }

  @Test
  void aFailedVerdictCountsBySeverityInOrderAndNamesEachFindingWhereItIs() {
    var said =
        ReviewNarration.failed(
            2,
            List.of(
                finding(Finding.Severity.HIGH, "Race", "Worker.java"),
                finding(Finding.Severity.CRITICAL, "Leak", "Auth.java"),
                finding(Finding.Severity.HIGH, "Whole design", null)),
            List.of());

    assertEquals(
        """
        Review failed (iteration 2): 1 critical, 2 high.
        - [HIGH] Race (Worker.java:7)
        - [CRITICAL] Leak (Auth.java:7)
        - [HIGH] Whole design"""
            .stripIndent(),
        said);
  }

  @Test
  void aNoisyReviewStaysOneMessageNamingTenFindingsAndCountingTheRest() {
    var findings =
        IntStream.rangeClosed(1, 12)
            .mapToObj(n -> finding(Finding.Severity.LOW, "Nit " + n, "A.java"))
            .toList();

    var said = ReviewNarration.failed(1, findings, List.of());

    assertTrue(said.startsWith("Review failed (iteration 1): 12 low."), said);
    assertTrue(said.contains("- [LOW] Nit 10 (A.java:7)\n- +2 more"), said);
    assertEquals(11, said.lines().filter(line -> line.startsWith("- ")).count());
  }

  @Test
  void aCleanPassSaysSoAndAwaitsMerge() {
    assertEquals(
        "Review passed (iteration 1).\nAwaiting merge.",
        ReviewNarration.passed(1, List.of(), List.of()));
  }

  @Test
  void aPassWithFindingsBelowItsGateNamesThemBeforeTheMerge() {
    var said =
        ReviewNarration.passed(
            3, List.of(finding(Finding.Severity.MEDIUM, "Slow path", "Q.java")), List.of());

    assertEquals(
        """
        Review passed (iteration 3) with 1 medium below the gate — worth a look before merge.
        - [MEDIUM] Slow path (Q.java:7)
        Awaiting merge."""
            .stripIndent(),
        said);
  }

  @Test
  void everyDisputedFindingIsSurfacedWithItsArgumentInEveryVerdict() {
    var disputes =
        List.of(disputed("Token in log", "it is redacted upstream"), disputed("No argument", " "));
    var section =
        """

        Disputed (excluded from the gate — needs your ruling):
        - [HIGH] Token in log (Auth.java:3) — it is redacted upstream
        - [HIGH] No argument (Auth.java:3)"""
            .stripIndent();

    assertEquals(
        "Review failed (iteration 1): no findings." + section,
        ReviewNarration.failed(1, List.of(), disputes));
    assertEquals(
        "Review passed (iteration 1)." + section + "\nAwaiting merge.",
        ReviewNarration.passed(1, List.of(), disputes));
    assertEquals(
        "Automated review stages passed." + section + "\nAwaiting human approval.",
        ReviewNarration.awaitingHuman(disputes));
    assertEquals(
        "Automated review stages passed.\nAwaiting human approval.",
        ReviewNarration.awaitingHuman(List.of()));
  }

  @Test
  void theNotesCountWhatTheyAreAboutInTheSingularAndThePlural() {
    assertTrue(ReviewNarration.alreadyResolved(1).contains("ruled on 1 finding someone resolved"));
    assertTrue(ReviewNarration.alreadyResolved(2).contains("ruled on 2 findings someone"));
    assertTrue(ReviewNarration.misaddressedVerdicts(1).contains("1 finding id that match"));
    assertTrue(ReviewNarration.misaddressedVerdicts(3).contains("3 finding ids that match"));
  }

  @Test
  void aRescueNamesEachRepoAndItsFilesCappedAtFive() {
    var said =
        ReviewNarration.rescues(
            List.of(
                new ReviewLanes.Rescue("api", List.of("A.java")),
                new ReviewLanes.Rescue(
                    "web", List.of("1.ts", "2.ts", "3.ts", "4.ts", "5.ts", "6.ts", "7.ts"))));

    assertEquals("api (1 file: A.java), web (7 files: 1.ts, 2.ts, 3.ts, 4.ts, 5.ts, +2)", said);
  }

  @Test
  void severityCountsOmitWhatWasNotFoundAndKeepSeverityOrder() {
    var counts =
        ReviewNarration.severityCounts(
            List.of(
                finding(Finding.Severity.LOW, "a", "A.java"),
                finding(Finding.Severity.CRITICAL, "b", "A.java"),
                finding(Finding.Severity.LOW, "c", "A.java")));

    assertEquals(Map.of("critical", 1, "low", 2), counts);
    assertEquals(List.of("critical", "low"), List.copyOf(counts.keySet()));
    assertTrue(ReviewNarration.severityCounts(List.of()).isEmpty());
  }

  @Test
  void anUnreadablePipelineIsSaidInOneLineWithAllOfWhatWentWrongAndWhatToDo() {
    var parse =
        ReviewNarration.pipelineUnreadable(
            "sail.yaml of project 'acme' could not be read: while parsing a flow sequence\n"
                + " in 'reader', line 3, column 9:\n\n"
                + "expected ',' or ']', but got <stream end>\n");

    assertEquals(
        "sail.yaml of project 'acme' could not be read: while parsing a flow sequence in"
            + " 'reader', line 3, column 9: expected ',' or ']', but got <stream end>; fix the"
            + " project's sail.yaml with `sail project edit`, then re-dispatch with --restart",
        parse,
        "a parser's message runs over lines and its last says what is wrong: all of it, as one");
    for (var nothingSaid : new String[] {null, "", " \n "}) {
      assertTrue(
          ReviewNarration.pipelineUnreadable(nothingSaid)
              .startsWith("the project's review pipeline could not be read; fix"));
    }
  }
}
