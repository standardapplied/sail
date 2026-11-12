/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link SpecMeasures#of} on plain rows: every number is computed from the rows it is handed, a
 * number missing an input is unknown with a count of what is missing (M8), a run is counted once
 * (M9), a negative interval is an error naming both instants (M11), and nothing names a person
 * (M12). {@link SpecMeasuresList#of} summarizes what it lists, leaving out the unknowns and the
 * errors of each number, and counting them.
 */
class SpecMeasuresTest {

  private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");

  private static Instant at(int minutes) {
    return T0.plusSeconds(60L * minutes);
  }

  /** Revision {@code seq} of the spec, in {@code status}, made at {@code minutes}. */
  private static ChangeLog.Entry revision(long seq, SpecStatus status, int minutes) {
    return revision(seq, status, at(0), at(minutes));
  }

  private static ChangeLog.Entry revision(
      long seq, SpecStatus status, Instant created, Instant updated) {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("id", LoopRows.SPEC);
    snapshot.put("status", status.wire());
    snapshot.put("created_at", created.toEpochMilli());
    snapshot.put("updated_at", updated.toEpochMilli());
    return new ChangeLog.Entry(
        seq,
        "spec",
        LoopRows.SPEC,
        seq + "-r",
        "uday",
        updated,
        ChangeLog.Entry.LOCAL,
        false,
        YamlUtil.dumpJson(snapshot),
        null,
        ChangeLog.Kind.REVISION);
  }

  private static ChangeLog.Entry tombstone(long seq, int minutes) {
    return new ChangeLog.Entry(
        seq,
        "spec",
        LoopRows.SPEC,
        seq + "-t",
        "uday",
        at(minutes),
        ChangeLog.Entry.LOCAL,
        true,
        "{}",
        null,
        ChangeLog.Kind.TOMBSTONE);
  }

  /** The loop's usual path: drafted, ready at 10, built and reviewed, passed at 70, done at 130. */
  private static List<ChangeLog.Entry> shipped() {
    return List.of(
        revision(1, SpecStatus.DRAFT, 0),
        revision(2, SpecStatus.PENDING, 10),
        revision(3, SpecStatus.IN_PROGRESS, 20),
        revision(4, SpecStatus.REVIEW, 50),
        revision(5, SpecStatus.AWAITING_MERGE, 70),
        revision(6, SpecStatus.DONE, 130));
  }

  private static ReviewStore.ReviewRow review(
      String id, int iteration, String status, Instant completed, Instant superseded) {
    return new ReviewStore.ReviewRow(
        id, LoopRows.SPEC, iteration, status, at(0), completed, null, superseded, null, null);
  }

  private static ReviewStore.ReviewRow approved(String id, Instant completed) {
    return new ReviewStore.ReviewRow(
        id, LoopRows.SPEC, 1, "passed", at(0), completed, "uday", null, null, null);
  }

  private static Finding carried(String id, String from) {
    var finding = LoopRows.finding(id, Finding.Severity.HIGH);
    return new Finding(
        finding.id(),
        finding.severity(),
        finding.category(),
        finding.file(),
        finding.lineStart(),
        finding.lineEnd(),
        finding.title(),
        finding.description(),
        finding.evidence(),
        finding.suggestion(),
        finding.confidence(),
        finding.resolution(),
        finding.resolutionEvidence(),
        from,
        "still there");
  }

  private static RunStore.RunRow run(String id, String role) {
    return LoopRows.ended(id, role, null);
  }

  private record Given(
      SpecStatus status,
      List<ChangeLog.Entry> history,
      Set<Long> gaps,
      List<ReviewStore.ReviewRow> reviews,
      List<Finding> findings,
      List<RunStore.RunRow> runs) {

    static Given done() {
      return new Given(
          SpecStatus.DONE,
          shipped(),
          Set.of(),
          List.of(review("r1", 1, "passed", at(70), null)),
          List.of(),
          List.of());
    }

    Given history(List<ChangeLog.Entry> history, Set<Long> gaps) {
      return new Given(status, history, gaps, reviews, findings, runs);
    }

    Given reviewed(ReviewStore.ReviewRow... reviews) {
      return new Given(status, history, gaps, List.of(reviews), findings, runs);
    }

    Given found(Finding... findings) {
      return new Given(status, history, gaps, reviews, List.of(findings), runs);
    }

    Given ran(RunStore.RunRow... runs) {
      return new Given(status, history, gaps, reviews, findings, List.of(runs));
    }

    Given status(SpecStatus status) {
      return new Given(status, history, gaps, reviews, findings, runs);
    }

    SpecMeasures measured() {
      return SpecMeasures.of(
          new MeasureRows(LoopRows.SPEC, status, "devbox", history, gaps, reviews, findings, runs));
    }

    Map<String, Object> wire() {
      return measured().toMap();
    }
  }

  @SuppressWarnings("unchecked")
  private static Object at(Map<String, Object> wire, String... path) {
    Object value = wire;
    for (var key : path) {
      value = ((Map<String, Object>) value).get(key);
    }
    return value;
  }

  private static Map<String, Object> seconds(long seconds) {
    return Map.of("seconds", seconds);
  }

  private static Map<String, Object> missing(int count) {
    return Map.of("unknown", "inputs_missing", "missing", count);
  }

  private static Map<String, Object> moment(int minutes, String source) {
    return Map.of("at", "2026-11-01T" + time(minutes) + ".000Z", "source", source);
  }

  private static String time(int minutes) {
    return String.format("%02d:%02d:00", 9 + minutes / 60, minutes % 60);
  }

  private static final Map<String, Object> NOT_REACHED = Map.of("unknown", "not_reached");
  private static final Map<String, Object> INCOMPLETE = Map.of("unknown", "history_incomplete");

  @Test
  void aShippedChangeStatesItsTimesFromThisBoxsChangeLogAndItsPassingReview() {
    var wire = Given.done().wire();

    assertEquals(
        Map.of(
            "box",
            "devbox",
            "ready",
            moment(10, "change_log"),
            "verified",
            moment(70, "review"),
            "awaiting_merge",
            moment(70, "change_log"),
            "done",
            moment(130, "change_log")),
        at(wire, "times"));
    assertEquals(
        Map.of(
            "ready_to_verified", seconds(3600),
            "verified_to_done", seconds(3600),
            "ready_to_done", seconds(7200),
            "awaiting_merge", seconds(3600)),
        at(wire, "intervals"));
    assertEquals(LoopRows.SPEC, wire.get("spec_id"));
  }

  /** Scenario 1. */
  @Test
  void aSpecReviewedThreeTimesAcrossTwoDispatchesCountsThreeRoundsTwoAttemptsAndItsFixRuns() {
    var given =
        Given.done()
            .reviewed(
                review("r1", 1, "failed", at(40), at(45)),
                review("r2", 1, "failed", at(60), null),
                review("r3", 2, "passed", at(70), null))
            .found(LoopRows.finding("f1", Finding.Severity.HIGH), carried("f2", "f1"))
            .ran(
                run("b1", "build"),
                run("v1", "review"),
                run("b2", "build"),
                run("v2", "review"),
                run("x1", "fix"),
                run("v3", "review"),
                run("w1", "room"));

    assertEquals(
        Map.of("review_rounds", 3, "dispatch_attempts", 2, "fix_runs", 1, "carried_findings", 1),
        at(given.wire(), "rework"));
  }

  /** Scenario 2. */
  @Test
  void aDoneSpecWithAPassedHumanStageIsNotCountedAsNoRecordedHumanCodeAct() {
    var given =
        Given.done().reviewed(review("r1", 1, "failed", at(40), null), approved("r2", at(70)));

    assertEquals(
        Map.of("counted", false, "bound", "upper"), at(given.wire(), "no_recorded_human_code_act"));
  }

  /** Scenario 3. */
  @Test
  void aDoneSpecWithNoHumanStageIsCountedAndLabelledAnUpperBound() {
    assertEquals(
        Map.of("counted", true, "bound", "upper"),
        at(Given.done().wire(), "no_recorded_human_code_act"));
    assertEquals(
        Map.of("unknown", "not_done"),
        at(Given.done().status(SpecStatus.REVIEW).wire(), "no_recorded_human_code_act"));
  }

  /** Scenario 4. */
  @Test
  void aSpecWhosePendingSnapshotWasCompactedOutHasNoReadyTimeAndSaysOneInputIsMissing() {
    var kept = shipped().subList(2, 6);
    var wire = Given.done().history(kept, Set.of(1L, 2L)).wire();

    assertEquals(INCOMPLETE, at(wire, "times", "ready"));
    assertEquals(moment(130, "change_log"), at(wire, "times", "done"), "its move to done is held");
    assertEquals(missing(1), at(wire, "intervals", "ready_to_verified"));
    assertEquals(missing(1), at(wire, "intervals", "ready_to_done"));
    assertEquals(seconds(3600), at(wire, "intervals", "verified_to_done"));
  }

  @Test
  void aSyncedBaseOlderThanTheCompactedEntriesStillGivesTheReadyTime() {
    var history = new ArrayList<ChangeLog.Entry>();
    history.add(revision(2, SpecStatus.PENDING, at(10), at(10)));
    history.addAll(shipped().subList(4, 6));

    var wire = Given.done().history(history, Set.of(3L, 4L)).wire();

    assertEquals(moment(10, "change_log"), at(wire, "times", "ready"), "born ready, then kept");
    assertEquals(INCOMPLETE, at(wire, "times", "awaiting_merge"), "a gap sits before its stretch");
  }

  @Test
  void aLogThatBeginsAfterTheBirthOrHidesAnEntryKnowsNoEarlierMove() {
    var adopted = List.of(revision(9, SpecStatus.PENDING, 30), revision(10, SpecStatus.DONE, 130));

    var wire = Given.done().history(adopted, Set.of()).wire();

    assertEquals(INCOMPLETE, at(wire, "times", "ready"), "it may have been ready before");
    assertEquals(NOT_REACHED, at(wire, "times", "awaiting_merge"));
    assertEquals(moment(130, "change_log"), at(wire, "times", "done"));
    assertEquals(
        INCOMPLETE,
        at(Given.done().history(List.of(), Set.of()).wire(), "times", "ready"),
        "a log holding nothing");
    assertEquals(
        INCOMPLETE,
        at(Given.done().history(List.of(), Set.of()).wire(), "times", "done"),
        "a log holding nothing");
  }

  @Test
  void aGapInsideTheStretchInDoneLeavesItsStartUnknown() {
    var history = new ArrayList<>(shipped());
    history.add(revision(8, SpecStatus.DONE, 140));

    var wire = Given.done().history(history, Set.of(7L)).wire();

    assertEquals(INCOMPLETE, at(wire, "times", "done"));
    assertEquals(INCOMPLETE, at(wire, "times", "awaiting_merge"), "what precedes it is unknown");
    assertEquals(missing(2), at(wire, "intervals", "awaiting_merge"));
    assertEquals(moment(10, "change_log"), at(wire, "times", "ready"));
  }

  @Test
  void doneIsTheMoveThatBeganItsCurrentStretchInDoneAndAwaitingMergeTheStretchBeforeIt() {
    var reopened = new ArrayList<>(shipped());
    reopened.add(revision(7, SpecStatus.IN_PROGRESS, 150));
    reopened.add(revision(8, SpecStatus.AWAITING_MERGE, 200));
    reopened.add(revision(9, SpecStatus.AWAITING_MERGE, 210));
    reopened.add(revision(10, SpecStatus.DONE, 240));
    reopened.add(tombstone(11, 245));
    reopened.add(revision(12, SpecStatus.DONE, 250));

    var wire =
        Given.done()
            .history(reopened, Set.of())
            .reviewed(
                review("r1", 1, "passed", at(70), at(150)),
                review("r2", 1, "passed", at(190), null))
            .wire();

    assertEquals(moment(240, "change_log"), at(wire, "times", "done"));
    assertEquals(moment(200, "change_log"), at(wire, "times", "awaiting_merge"));
    assertEquals(moment(190, "review"), at(wire, "times", "verified"), "what shipped passed last");
    assertEquals(moment(10, "change_log"), at(wire, "times", "ready"), "ready is the first move");
  }

  @Test
  void aSpecNotDoneOrNeverVerifiedOrPastAwaitingMergeSaysWhatItHasNotReached() {
    var inReview = Given.done().history(shipped().subList(0, 4), Set.of()).reviewed();
    var wire = inReview.wire();

    assertEquals(NOT_REACHED, at(wire, "times", "done"));
    assertEquals(NOT_REACHED, at(wire, "times", "awaiting_merge"));
    assertEquals(NOT_REACHED, at(wire, "times", "verified"));
    assertEquals(missing(1), at(wire, "intervals", "ready_to_verified"));
    assertEquals(missing(2), at(wire, "intervals", "verified_to_done"));

    var straightToDone =
        List.of(
            revision(1, SpecStatus.PENDING, 0),
            revision(2, SpecStatus.IN_PROGRESS, 5),
            revision(3, SpecStatus.DONE, 9));
    var skipped = Given.done().history(straightToDone, Set.of()).wire();
    assertEquals(NOT_REACHED, at(skipped, "times", "awaiting_merge"));
    assertEquals(missing(1), at(skipped, "intervals", "awaiting_merge"));
    var bornDone = Given.done().history(List.of(revision(1, SpecStatus.DONE, 0)), Set.of()).wire();
    assertEquals(moment(0, "change_log"), at(bornDone, "times", "done"));
    assertEquals(
        moment(0, "change_log"),
        at(
            Given.done()
                .history(
                    List.of(revision(1, SpecStatus.DONE, 0), revision(2, SpecStatus.DONE, 5)),
                    Set.of(3L))
                .wire(),
            "times",
            "done"),
        "a stretch begun at the birth is known whatever lies past it out of sight");
    assertEquals(NOT_REACHED, at(bornDone, "times", "ready"));
    assertEquals(
        NOT_REACHED,
        at(
            Given.done().reviewed(review("r1", 1, "passed", at(70), at(80))).wire(),
            "times",
            "verified"),
        "a pass a re-dispatch superseded verified an earlier attempt");
    assertEquals(
        moment(100, "review"),
        at(
            Given.done()
                .reviewed(
                    review("r1", 1, "passed", at(70), null), review("r2", 2, "passed", at(100), null))
                .wire(),
            "times",
            "verified"),
        "the latest pass of the attempt is what verified it");
  }

  /** Scenario 5. */
  @Test
  void readyAtTAndDoneFiveMinutesBeforeItIsAnErrorNamingBothInstantsNeverAnInterval() {
    var skewed =
        List.of(
            revision(1, SpecStatus.PENDING, at(30), at(30)),
            revision(2, SpecStatus.DONE, at(30), at(25)));

    var wire = Given.done().reviewed().history(skewed, Set.of()).wire();

    assertEquals(
        Map.of(
            "error",
            "done at 2026-11-01T09:25:00.000Z is before ready at 2026-11-01T09:30:00.000Z;"
                + " a negative interval is not served."),
        at(wire, "intervals", "ready_to_done"));
    assertEquals(
        Map.of(
            "error",
            "done at 2026-11-01T09:25:00.000Z is before verified at 2026-11-01T10:10:00.000Z;"
                + " a negative interval is not served."),
        at(Given.done().history(skewed, Set.of()).wire(), "intervals", "verified_to_done"));
  }

  /** Scenario 6. */
  @Test
  void aSpecWithFiveRunsHasAnUnknownCostWithFiveRunsWithoutUsageAndNoHumanMinutes() {
    var wire =
        Given.done()
            .ran(
                run("b1", "build"),
                run("v1", "review"),
                run("x1", "fix"),
                run("v2", "review"),
                run("w1", "room"))
            .wire();

    assertEquals(Map.of("unknown", "not_recorded", "runs_without_usage", 5), wire.get("cost"));
    assertEquals(Map.of("unknown", "not_recorded"), wire.get("human_minutes"));
  }

  /** Scenario 7. */
  @Test
  void aRunLinkedByItsRoomAndItsSpecIsCountedOnce() {
    var build = run("b1", "build");
    var wire = Given.done().ran(build, build, run("x1", "fix"), run("x1", "fix")).wire();

    assertEquals(1, at(wire, "rework", "dispatch_attempts"));
    assertEquals(1, at(wire, "rework", "fix_runs"));
    assertEquals(2, at(wire, "cost", "runs_without_usage"));
  }

  @Test
  void theMeasuresNameNoPerson() {
    var wire =
        YamlUtil.dumpJson(
            Given.done().reviewed(approved("r1", at(70))).ran(run("b1", "build")).wire());

    assertEquals(false, wire.contains("uday"), wire);
    assertEquals(false, wire.contains(LoopRows.NODE), wire);
  }

  private static SpecMeasures measured(int readyMinutes, int doneMinutes) {
    return Given.done()
        .history(
            List.of(
                revision(1, SpecStatus.PENDING, at(readyMinutes), at(readyMinutes)),
                revision(2, SpecStatus.DONE, at(readyMinutes), at(doneMinutes))),
            Set.of())
        .reviewed()
        .measured();
  }

  private static SpecMeasures unknownTimes() {
    return Given.done()
        .history(List.of(revision(9, SpecStatus.DONE, 130)), Set.of(1L))
        .reviewed()
        .measured();
  }

  /** Scenario 8. */
  @Test
  void fortyDoneSpecsSixWithUnknownTimesListFortyRowsAndSummarizeThirtyFourSayingSixWereLeftOut() {
    var rows = new ArrayList<SpecMeasures>();
    IntStream.rangeClosed(1, 34).forEach(minutes -> rows.add(measured(0, minutes)));
    IntStream.range(0, 6).forEach(unknown -> rows.add(unknownTimes()));

    var wire = SpecMeasuresList.of(rows, SpecMeasuresList.Window.parse(null, null)).toMap();

    assertEquals(40, ((List<?>) wire.get("specs")).size());
    assertEquals(40, wire.get("total"));
    assertEquals(
        Map.of(
            "unit", "seconds", "n", 34, "median", 17L * 60, "p90", 31L * 60, "unknown", 6, "errors",
            0),
        at(wire, "summary", "ready_to_done"));
    assertEquals(
        Map.of("unit", "count", "n", 40, "median", 0L, "p90", 0L, "unknown", 0, "errors", 0),
        at(wire, "summary", "review_rounds"));
    assertEquals(
        Map.of("counted", 40, "of", 40, "unknown", 0, "bound", "upper"),
        at(wire, "summary", "no_recorded_human_code_act"));
    assertEquals(null, wire.get("window"), "no window was asked");
  }

  @Test
  void aNegativeIntervalIsLeftOutOfTheSummaryAndCountedApart() {
    var rows = List.of(measured(0, 10), measured(0, 20), measured(30, 25));

    var wire = SpecMeasuresList.of(rows, SpecMeasuresList.Window.parse(null, null)).toMap();

    assertEquals(
        Map.of("unit", "seconds", "n", 2, "median", 600L, "p90", 1200L, "unknown", 0, "errors", 1),
        at(wire, "summary", "ready_to_done"));
    assertEquals(
        Map.of("unit", "seconds", "n", 0, "unknown", 3, "errors", 0),
        at(wire, "summary", "ready_to_verified"));
  }

  /** Scenario 9. */
  @Test
  void aWindowListsOnlyTheSpecsDoneInItByThisBoxsRecordAndCountsThoseWithNoDoneTime() {
    var before = measured(0, -9 * 60 - 1);
    var first = measured(0, -9 * 60);
    var last = measured(0, 14 * 24 * 60 + 14 * 60 + 59);
    var after = measured(0, 14 * 24 * 60 + 15 * 60);

    var listed =
        SpecMeasuresList.of(
            List.of(before, first, unknownTimes(), last, after),
            SpecMeasuresList.Window.parse("2026-11-01", "2026-11-15"));
    var wire = listed.toMap();

    assertEquals(List.of(first, last), listed.specs());
    assertEquals(2, wire.get("total"));
    assertEquals(
        Map.of("done_since", "2026-11-01", "done_until", "2026-11-15", "left_out_undated", 1),
        wire.get("window"));
    assertEquals(
        List.of(first, last, after),
        SpecMeasuresList.of(
                List.of(before, first, last, after),
                SpecMeasuresList.Window.parse("2026-11-01", null))
            .specs());
    assertEquals(
        List.of(before),
        SpecMeasuresList.of(
                List.of(before, first, last, after),
                SpecMeasuresList.Window.parse(null, "2026-10-31"))
            .specs());
  }

  @Test
  void aWindowThatIsNoDateOrEndsBeforeItBeginsIsRefusedNamingWhatToPass() {
    var notADate =
        assertThrows(ApiException.class, () -> SpecMeasuresList.Window.parse("last week", null));
    var backwards =
        assertThrows(
            ApiException.class, () -> SpecMeasuresList.Window.parse("2026-11-12", "2026-11-01"));

    assertEquals(ErrorCode.INVALID_REQUEST, notADate.failure().errorCode());
    assertEquals("done_since must be a date, YYYY-MM-DD, not 'last week'.", notADate.reason());
    assertEquals("done_since 2026-11-12 is after done_until 2026-11-01.", backwards.reason());
    assertEquals(
        "done_until must be a date, YYYY-MM-DD, not '11/12'.",
        assertThrows(ApiException.class, () -> SpecMeasuresList.Window.parse(null, "11/12"))
            .reason());
  }
}
