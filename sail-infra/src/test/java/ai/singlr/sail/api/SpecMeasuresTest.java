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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link SpecMeasures#of} on plain rows: every number is computed from the rows it is handed, a
 * number missing an input is unknown with its reason, never a smaller number (M8), a run is counted
 * once (M9), a negative interval is an error naming both instants (M11), and nothing names a person
 * (M12). {@link SpecMeasuresList#of} summarizes what it lists over the numbers known exactly,
 * counting what it left out of each.
 */
class SpecMeasuresTest {

  private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");

  private static Instant at(int minutes) {
    return T0.plusSeconds(60L * minutes);
  }

  /**
   * Revision {@code seq} of the spec written on this box, in {@code status}, at {@code minutes}.
   */
  private static ChangeLog.Entry revision(long seq, SpecStatus status, int minutes) {
    return revision(seq, status, at(0), at(minutes));
  }

  private static ChangeLog.Entry revision(
      long seq, SpecStatus status, Instant created, Instant updated) {
    return entry(seq, status, created, updated, ChangeLog.Entry.LOCAL);
  }

  /** Revision {@code seq} heard by sync from another box, made at {@code minutes}. */
  private static ChangeLog.Entry heard(long seq, SpecStatus status, int minutes) {
    return entry(seq, status, at(0), at(minutes), ChangeLog.Entry.SYNC);
  }

  private static ChangeLog.Entry entry(
      long seq, SpecStatus status, Instant created, Instant updated, String origin) {
    return entry(seq, status, created, updated, origin, "acme");
  }

  private static ChangeLog.Entry heard(
      long seq, SpecStatus status, Instant created, Instant updated) {
    return entry(seq, status, created, updated, ChangeLog.Entry.SYNC);
  }

  private static ChangeLog.Entry entry(
      long seq,
      SpecStatus status,
      Instant created,
      Instant updated,
      String origin,
      String project) {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("id", LoopRows.SPEC);
    snapshot.put("project", project);
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
        origin,
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

  private static ReviewStore.ReviewRow errored(String id, Instant completed) {
    return new ReviewStore.ReviewRow(
        id,
        LoopRows.SPEC,
        1,
        "failed",
        at(0),
        completed,
        null,
        null,
        "reviewer could not start",
        null);
  }

  private static ReviewStore.ReviewRow approved(String id, Instant completed, Instant superseded) {
    return new ReviewStore.ReviewRow(
        id, LoopRows.SPEC, 1, "passed", at(0), completed, "uday", superseded, null, null);
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
      MeasureRows.Seen<ReviewStore.ReviewRow> reviews,
      List<Finding> findings,
      MeasureRows.Seen<RunStore.RunRow> runs,
      Map<String, Long> erasures) {

    static Given done() {
      return new Given(
          SpecStatus.DONE,
          shipped(),
          Set.of(),
          MeasureRows.Seen.all(List.of(review("r1", 1, "passed", at(70), null))),
          List.of(),
          MeasureRows.Seen.all(List.of()),
          Map.of());
    }

    Given history(List<ChangeLog.Entry> history, Set<Long> gaps) {
      return new Given(status, history, gaps, reviews, findings, runs, erasures);
    }

    Given reviewed(ReviewStore.ReviewRow... reviews) {
      return new Given(
          status, history, gaps, MeasureRows.Seen.all(List.of(reviews)), findings, runs, erasures);
    }

    Given reviewsHidden() {
      return new Given(
          status,
          history,
          gaps,
          new MeasureRows.Seen<>(reviews.rows(), true),
          findings,
          runs,
          erasures);
    }

    Given found(Finding... findings) {
      return new Given(status, history, gaps, reviews, List.of(findings), runs, erasures);
    }

    Given ran(RunStore.RunRow... runs) {
      return new Given(
          status, history, gaps, reviews, findings, MeasureRows.Seen.all(List.of(runs)), erasures);
    }

    Given runsHidden() {
      return new Given(
          status,
          history,
          gaps,
          reviews,
          findings,
          new MeasureRows.Seen<>(runs.rows(), true),
          erasures);
    }

    Given erased(Map<String, Long> erasures) {
      return new Given(status, history, gaps, reviews, findings, runs, erasures);
    }

    Given status(SpecStatus status) {
      return new Given(status, history, gaps, reviews, findings, runs, erasures);
    }

    SpecMeasures measured() {
      return SpecMeasures.of(
          new MeasureRows(
              LoopRows.SPEC,
              "acme",
              status,
              "box-7",
              history,
              gaps,
              reviews,
              findings,
              runs,
              erasures));
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

  private static Map<String, Object> count(long count) {
    return Map.of("count", count);
  }

  private static Map<String, Object> atLeast(long count) {
    return Map.of("count", count, "at_least", true);
  }

  private static Map<String, Object> unknown(String reason) {
    return Map.of("unknown", reason);
  }

  private static Map<String, Object> missing(int count) {
    return Map.of("unknown", "inputs_missing", "missing", count);
  }

  private static Map<String, Object> rework(
      Object rounds, Object errors, Object builds, Object fixes, Object carried) {
    return Map.of(
        "review_rounds",
        rounds,
        "review_errors",
        errors,
        "build_runs",
        builds,
        "fix_runs",
        fixes,
        "carried_findings",
        carried);
  }

  private static Map<String, Object> moment(int minutes, String source) {
    return Map.of("at", "2026-11-01T" + time(minutes) + ".000Z", "source", source);
  }

  private static String time(int minutes) {
    return String.format("%02d:%02d:00", 9 + minutes / 60, minutes % 60);
  }

  private static final Map<String, Object> NOT_REACHED = unknown("not_reached");
  private static final Map<String, Object> INCOMPLETE = unknown("history_incomplete");
  private static final Map<String, Object> COLLAPSED = unknown("collapsed_by_sync");
  private static final Map<String, Object> HIDDEN = unknown("hidden_rows");

  @Test
  void aShippedChangeStatesItsTimesFromThisBoxsChangeLogAndItsPassingReview() {
    var wire = Given.done().wire();

    assertEquals(
        Map.of(
            "box",
            Map.of("sail_id", "box-7"),
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

  /** Scenario 1, with a resumed build counted as the build run it is. */
  @Test
  void aSpecReviewedThreeTimesAcrossTwoDispatchesCountsItsRoundsBuildRunsAndFixRuns() {
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
                run("b3", "build"),
                run("v2", "review"),
                run("x1", "fix"),
                run("v3", "review"),
                run("w1", "room"));

    assertEquals(
        rework(count(3), count(0), count(3), count(1), count(1)), at(given.wire(), "rework"));
  }

  /** An errored review is retried as a new row of the same iteration: no round, an error. */
  @Test
  void anErroredReviewIsNoRoundAndIsServedApart() {
    var given =
        Given.done()
            .reviewed(
                errored("r1", at(40)),
                errored("r2", at(50)),
                review("r3", 1, "passed", at(60), null),
                review("r4", 2, "running", null, null));

    assertEquals(count(1), at(given.wire(), "rework", "review_rounds"));
    assertEquals(count(2), at(given.wire(), "rework", "review_errors"));
    assertEquals(moment(60, "review"), at(given.wire(), "times", "verified"));
  }

  /** Scenario 2, an approval in an earlier attempt included. */
  @Test
  void aDoneSpecWithAPassedHumanStageInAnyAttemptIsNotCountedAsNoRecordedHumanCodeAct() {
    var current =
        Given.done()
            .reviewed(review("r1", 1, "failed", at(40), null), approved("r2", at(70), null));
    var superseded =
        Given.done()
            .reviewed(approved("r1", at(40), at(45)), review("r2", 1, "passed", at(70), null));

    assertEquals(
        Map.of("counted", false, "bound", "upper"),
        at(current.wire(), "no_recorded_human_code_act"));
    assertEquals(
        Map.of("counted", false, "bound", "upper"),
        at(superseded.wire(), "no_recorded_human_code_act"),
        "a person reviewed the change's work line by line in an earlier attempt");
  }

  /** Scenario 3. */
  @Test
  void aDoneSpecWithNoHumanStageIsCountedAndLabelledAnUpperBound() {
    assertEquals(
        Map.of("counted", true, "bound", "upper"),
        at(Given.done().wire(), "no_recorded_human_code_act"));
    assertEquals(
        unknown("not_done"),
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
  void aLogThatBeginsAfterTheBirthOrHoldsNothingKnowsNoEarlierMove() {
    var adopted = List.of(revision(9, SpecStatus.PENDING, 30), revision(10, SpecStatus.DONE, 130));

    var wire = Given.done().history(adopted, Set.of()).wire();
    var empty = Given.done().status(SpecStatus.PENDING).history(List.of(), Set.of()).wire();

    assertEquals(INCOMPLETE, at(wire, "times", "ready"), "it may have been ready before");
    assertEquals(NOT_REACHED, at(wire, "times", "awaiting_merge"));
    assertEquals(moment(130, "change_log"), at(wire, "times", "done"));
    assertEquals(INCOMPLETE, at(empty, "times", "ready"), "a log holding nothing");
    assertEquals(INCOMPLETE, at(empty, "times", "done"), "a done stretch may lie outside it");
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
    var cut =
        Given.done()
            .history(
                List.of(
                    revision(1, SpecStatus.DRAFT, 0),
                    revision(30, SpecStatus.DONE, 300),
                    revision(31, SpecStatus.DONE, 310)),
                Set.of(2L, 3L, 4L))
            .wire();
    assertEquals(INCOMPLETE, at(cut, "times", "done"), "compaction cut the move to done");
  }

  @Test
  void doneIsTheMoveThatBeganItsLatestStretchInDoneAndAwaitingMergeTheStretchBeforeIt() {
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
  void anArchivedSpecThatShippedKeepsTheMoveThatBeganItsLastStretchInDone() {
    var archived = new ArrayList<>(shipped());
    archived.add(revision(7, SpecStatus.ARCHIVED, 500));

    var wire = Given.done().status(SpecStatus.ARCHIVED).history(archived, Set.of()).wire();

    assertEquals(moment(130, "change_log"), at(wire, "times", "done"));
    assertEquals(moment(70, "change_log"), at(wire, "times", "awaiting_merge"));
  }

  @Test
  void readyIsTheFirstMoveIntoPendingWhateverCameAfter() {
    var restarted =
        List.of(
            revision(1, SpecStatus.DRAFT, 0),
            revision(2, SpecStatus.CANCELLED, 5),
            revision(3, SpecStatus.PENDING, 10),
            revision(4, SpecStatus.IN_PROGRESS, 20),
            revision(5, SpecStatus.PENDING, 30),
            revision(6, SpecStatus.IN_PROGRESS, 40),
            revision(7, SpecStatus.PENDING, 50),
            revision(8, SpecStatus.AWAITING_MERGE, 70),
            revision(9, SpecStatus.DONE, 130));

    assertEquals(
        moment(10, "change_log"),
        at(Given.done().history(restarted, Set.of()).wire(), "times", "ready"));
  }

  @Test
  void equalInstantsAreAnIntervalOfNoSeconds() {
    var at0 =
        List.of(
            revision(1, SpecStatus.PENDING, at(0), at(0)),
            revision(2, SpecStatus.AWAITING_MERGE, at(0), at(0)),
            revision(3, SpecStatus.DONE, at(0), at(0)));

    var wire =
        Given.done().history(at0, Set.of()).reviewed(review("r1", 1, "passed", at(0), null)).wire();

    assertEquals(
        Map.of(
            "ready_to_verified", seconds(0),
            "verified_to_done", seconds(0),
            "ready_to_done", seconds(0),
            "awaiting_merge", seconds(0)),
        at(wire, "intervals"));
  }

  @Test
  void aSpecNotDoneOrNeverVerifiedOrPastAwaitingMergeSaysWhatItHasNotReached() {
    var wire = Given.done().history(shipped().subList(0, 4), Set.of()).reviewed().wire();

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
    assertEquals(NOT_REACHED, at(bornDone, "times", "ready"));
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
  }

  @Test
  void verifiedIsTheLatestReviewOfTheCurrentAttemptWhenItPassed() {
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
                    review("r1", 1, "passed", at(70), null),
                    review("r2", 2, "passed", at(100), null))
                .wire(),
            "times",
            "verified"),
        "the latest pass of the attempt is what verified it");
    assertEquals(
        NOT_REACHED,
        at(
            Given.done()
                .reviewed(
                    review("r1", 1, "passed", at(60), null),
                    review("r2", 2, "failed", at(90), null))
                .wire(),
            "times",
            "verified"),
        "a pass a later review of the attempt failed verified nothing that shipped");
  }

  /** Main's view of a node's spec: what the node did between two rounds is one revision here. */
  @Test
  void aMoveHeardBySyncIsCollapsedNeverExactNeverNotReachedAndNeverAClockError() {
    var mainsView =
        List.of(
            entry(1, SpecStatus.DRAFT, at(0), at(0), ChangeLog.Entry.SYNC),
            heard(2, SpecStatus.REVIEW, 60),
            heard(3, SpecStatus.PENDING, 500),
            heard(4, SpecStatus.AWAITING_MERGE, 600),
            heard(5, SpecStatus.DONE, 700));

    var wire = Given.done().history(mainsView, Set.of()).wire();

    assertEquals(COLLAPSED, at(wire, "times", "ready"), "the move to ready hides in revision 2");
    assertEquals(COLLAPSED, at(wire, "times", "done"));
    assertEquals(COLLAPSED, at(wire, "times", "awaiting_merge"));
    assertEquals(missing(1), at(wire, "intervals", "ready_to_verified"), "no clock-skew error");

    var doneHere =
        List.of(
            revision(1, SpecStatus.PENDING, 0),
            heard(2, SpecStatus.AWAITING_MERGE, 60),
            revision(3, SpecStatus.DONE, 130));
    var local = Given.done().history(doneHere, Set.of()).wire();
    assertEquals(moment(130, "change_log"), at(local, "times", "done"), "done was written here");
    assertEquals(COLLAPSED, at(local, "times", "awaiting_merge"));
    assertEquals(moment(0, "change_log"), at(local, "times", "ready"));

    var inReview =
        List.of(
            entry(1, SpecStatus.DRAFT, at(0), at(0), ChangeLog.Entry.SYNC),
            heard(2, SpecStatus.REVIEW, 60));
    var open = Given.done().status(SpecStatus.REVIEW).history(inReview, Set.of()).wire();
    assertEquals(COLLAPSED, at(open, "times", "done"), "a stretch in done may hide in revision 2");
    assertEquals(COLLAPSED, at(open, "times", "ready"));
    var reopened = new ArrayList<>(shipped());
    reopened.add(heard(7, SpecStatus.IN_PROGRESS, 200));
    assertEquals(
        COLLAPSED,
        at(Given.done().history(reopened, Set.of()).wire(), "times", "done"),
        "a later stretch in done may hide in the batch after it");
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

    assertEquals(
        Map.of("unknown", "not_recorded", "runs_without_usage", count(5)), wire.get("cost"));
    assertEquals(unknown("not_recorded"), wire.get("human_minutes"));
  }

  /** Scenario 7. */
  @Test
  void aRunLinkedByItsRoomAndItsSpecIsCountedOnce() {
    var build = run("b1", "build");
    var wire = Given.done().ran(build, build, run("x1", "fix"), run("x1", "fix")).wire();

    assertEquals(count(1), at(wire, "rework", "build_runs"));
    assertEquals(count(1), at(wire, "rework", "fix_runs"));
    assertEquals(count(2), at(wire, "cost", "runs_without_usage"));
  }

  @Test
  void aReviewOrRunTheReaderMayNotSeeLeavesWhatItsKindGivesUnknownSayingNoMore() {
    var hiddenReviews =
        Given.done()
            .reviewed(approved("r1", at(70), null))
            .found(carried("f2", "f1"))
            .ran(run("b1", "build"))
            .reviewsHidden()
            .wire();
    var hiddenRuns = Given.done().ran(run("b1", "build")).runsHidden().wire();

    assertEquals(rework(HIDDEN, HIDDEN, count(1), count(0), HIDDEN), at(hiddenReviews, "rework"));
    assertEquals(HIDDEN, at(hiddenReviews, "times", "verified"));
    assertEquals(HIDDEN, at(hiddenReviews, "no_recorded_human_code_act"));
    assertEquals(rework(count(1), count(0), HIDDEN, HIDDEN, count(0)), at(hiddenRuns, "rework"));
    assertEquals(HIDDEN, at(hiddenRuns, "cost", "runs_without_usage"));
  }

  /**
   * Minor 2 of review 2: a run erased after the spec arrived bounds its run counts only when it was
   * filed under a project the spec was filed under, or under none; reviews are never erased away.
   */
  @Test
  void runsAnErasureInTheSpecsProjectMayHaveTakenAreALowerBoundAndOthersMarkNothing() {
    var built = Given.done().ran(run("b1", "build"), run("x1", "fix"));
    var since = built.erased(Map.of("acme", 7L)).wire();
    var elsewhere = built.erased(Map.of("beta", 7L)).wire();
    var before =
        built.history(shipped().subList(1, 6), Set.of(1L)).erased(Map.of("acme", 1L)).wire();
    var unfiled = new HashMap<String, Long>();
    unfiled.put(null, 9L);
    var nowhere = built.erased(unfiled).wire();
    var moved = new ArrayList<>(shipped());
    moved.set(0, entry(1, SpecStatus.DRAFT, at(0), at(0), ChangeLog.Entry.LOCAL, "beta"));
    var once = built.history(moved, Set.of()).erased(Map.of("beta", 7L)).wire();
    var gapped =
        built.history(shipped().subList(1, 6), Set.of(1L)).erased(Map.of("beta", 7L)).wire();
    var nothingHeld = built.history(List.of(), Set.of()).erased(Map.of("acme", 1L)).wire();

    assertEquals(atLeast(1), at(since, "rework", "build_runs"));
    assertEquals(atLeast(2), at(since, "cost", "runs_without_usage"));
    assertEquals(count(1), at(since, "rework", "review_rounds"), "reviews are never erased away");
    assertEquals(count(1), at(elsewhere, "rework", "build_runs"), "another project's run");
    assertEquals(count(1), at(before, "rework", "build_runs"), "erased before it arrived");
    assertEquals(atLeast(1), at(nowhere, "rework", "build_runs"), "a run filed nowhere");
    assertEquals(atLeast(1), at(once, "rework", "build_runs"), "a project it was filed under");
    assertEquals(atLeast(1), at(gapped, "rework", "build_runs"), "its projects are not all known");
    assertEquals(atLeast(1), at(nothingHeld, "rework", "build_runs"));
  }

  /**
   * Major 1 of review 2, on plain rows: a box's own write that main took comes back heard, at the
   * same time and in the same status; it is the move it answers, so a node reads its own moves
   * exact. A heard revision in the same status at another time is another box's write.
   */
  @Test
  void aBoxsOwnWriteComingBackFromMainIsThatMoveNotACollapse() {
    var echoed =
        List.of(
            revision(1, SpecStatus.DRAFT, 0),
            heard(2, SpecStatus.DRAFT, at(0), at(0)),
            revision(3, SpecStatus.PENDING, 10),
            heard(4, SpecStatus.PENDING, at(0), at(10)),
            revision(5, SpecStatus.AWAITING_MERGE, 70),
            heard(6, SpecStatus.AWAITING_MERGE, at(0), at(70)),
            revision(7, SpecStatus.DONE, 130),
            heard(8, SpecStatus.DONE, at(0), at(130)));
    var someoneElses = new ArrayList<>(echoed);
    someoneElses.set(7, heard(8, SpecStatus.DONE, at(0), at(140)));

    var wire = Given.done().history(echoed, Set.of()).wire();

    assertEquals(moment(10, "change_log"), at(wire, "times", "ready"));
    assertEquals(moment(70, "change_log"), at(wire, "times", "awaiting_merge"));
    assertEquals(moment(130, "change_log"), at(wire, "times", "done"));
    assertEquals(
        COLLAPSED,
        at(Given.done().history(someoneElses, Set.of()).wire(), "times", "done"),
        "an edit made on another box after the move");
  }

  /** Minor 4 of review 2: a birth heard unedited is one move, so what follows it here is exact. */
  @Test
  void anUneditedBirthHeardBySyncIsOneMoveSoAReadyMoveAfterItHereIsExact() {
    var bornElsewhere =
        List.of(heard(1, SpecStatus.DRAFT, at(0), at(0)), revision(2, SpecStatus.PENDING, 10));
    var editedElsewhere =
        List.of(heard(1, SpecStatus.DRAFT, at(0), at(5)), revision(2, SpecStatus.PENDING, 10));

    assertEquals(
        moment(10, "change_log"),
        at(Given.done().history(bornElsewhere, Set.of()).wire(), "times", "ready"));
    assertEquals(
        INCOMPLETE,
        at(Given.done().history(editedElsewhere, Set.of()).wire(), "times", "ready"),
        "a birth edited before it was sent is no birth: the log begins after it");
  }

  @Test
  void aBoxWithNoSailIdSaysSoInTheOneShapeEveryUnknownHas() {
    var given = Given.done();
    var unnamed =
        SpecMeasures.of(
                new MeasureRows(
                    LoopRows.SPEC,
                    "acme",
                    SpecStatus.DONE,
                    null,
                    given.history(),
                    given.gaps(),
                    given.reviews(),
                    given.findings(),
                    given.runs(),
                    given.erasures()))
            .toMap();

    assertEquals(unknown("no_box_id"), at(unnamed, "times", "box"));
  }

  @Test
  void theMeasuresNameNoPerson() {
    var wire =
        YamlUtil.dumpJson(
            Given.done().reviewed(approved("r1", at(70), null)).ran(run("b1", "build")).wire());

    assertEquals(false, wire.contains("uday"), wire);
    assertEquals(false, wire.contains(LoopRows.NODE), wire);
  }

  @Test
  void aReadAsksForTheMeasuresOnlyByTrueAndRefusesAnythingButTrueOrFalse() {
    assertEquals(
        List.of(false, false, true),
        List.of(SpecMeasures.asked(null), SpecMeasures.asked("false"), SpecMeasures.asked("true")));
    assertEquals(
        "measures must be true or false, not 'yes'.",
        assertThrows(ApiException.class, () -> SpecMeasures.asked("yes")).reason());
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

  private static Map<String, Object> spread(
      String unit, int n, Long median, Long p90, int unknown, int atLeast, int errors) {
    var spread = new LinkedHashMap<String, Object>();
    spread.put("n", n);
    spread.put("median", median == null ? unknown("none_known") : Map.of(unit, median));
    spread.put("p90", p90 == null ? unknown("none_known") : Map.of(unit, p90));
    spread.put("left_out_unknown", unknown);
    spread.put("left_out_at_least", atLeast);
    spread.put("left_out_errors", errors);
    return spread;
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
        spread("seconds", 34, 17L * 60, 31L * 60, 6, 0, 0), at(wire, "summary", "ready_to_done"));
    assertEquals(spread("count", 40, 0L, 0L, 0, 0, 0), at(wire, "summary", "review_rounds"));
    assertEquals(
        Map.of("counted", 40, "of", 40, "left_out_unknown", 0, "bound", "upper"),
        at(wire, "summary", "no_recorded_human_code_act"));
    assertEquals(null, wire.get("window"), "no window was asked");
  }

  @Test
  void theNearestRankIsTheLeastValueWithThatShareAtOrBelowIt() {
    for (var n : List.of(1, 2, 3, 10)) {
      var rows = IntStream.rangeClosed(1, n).mapToObj(minutes -> measured(0, minutes)).toList();

      var wire = SpecMeasuresList.of(rows, SpecMeasuresList.Window.parse(null, null)).toMap();

      var median = Map.of(1, 60L, 2, 60L, 3, 120L, 10, 300L).get(n);
      var p90 = Map.of(1, 60L, 2, 120L, 3, 180L, 10, 540L).get(n);
      assertEquals(
          spread("seconds", n, median, p90, 0, 0, 0),
          at(wire, "summary", "ready_to_done"),
          "n = " + n);
    }
  }

  @Test
  void aNegativeIntervalAndALowerBoundAreLeftOutOfTheSummaryAndCountedApart() {
    var bounded = Given.done().ran(run("b1", "build")).erased(Map.of("acme", 9L)).measured();
    var rows = List.of(measured(0, 10), measured(0, 20), measured(30, 25), bounded);

    var wire = SpecMeasuresList.of(rows, SpecMeasuresList.Window.parse(null, null)).toMap();

    assertEquals(spread("seconds", 3, 1200L, 7200L, 0, 0, 1), at(wire, "summary", "ready_to_done"));
    assertEquals(spread("count", 3, 0L, 0L, 0, 1, 0), at(wire, "summary", "build_runs"));
    assertEquals(
        spread("seconds", 1, 3600L, 3600L, 3, 0, 0), at(wire, "summary", "ready_to_verified"));
  }

  @Test
  void anEmptyListingStillNamesEveryNumberWithNothingKnown() {
    var wire = SpecMeasuresList.of(List.of(), SpecMeasuresList.Window.parse(null, null)).toMap();

    assertEquals(
        List.of(
            "ready_to_verified",
            "verified_to_done",
            "ready_to_done",
            "awaiting_merge",
            "review_rounds",
            "review_errors",
            "build_runs",
            "fix_runs",
            "carried_findings",
            "no_recorded_human_code_act"),
        List.copyOf(((Map<?, ?>) wire.get("summary")).keySet()));
    assertEquals(spread("count", 0, null, null, 0, 0, 0), at(wire, "summary", "fix_runs"));
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
    var midnight = measured(0, 15 * 60);
    assertEquals(
        List.of(false, true, true),
        List.of(
            SpecMeasuresList.Window.parse(null, "2026-11-01").holds(midnight),
            SpecMeasuresList.Window.parse("2026-11-02", null).holds(midnight),
            SpecMeasuresList.Window.parse(null, "2026-11-02").holds(midnight)),
        "midnight of 11-02 belongs to 11-02 alone");
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
