/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.AttentionStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link SpecMeasures#of} on plain rows: every number is computed from the rows it is handed, a
 * number missing an input is unknown with its reason, never a smaller number (M8), nothing is
 * counted twice (M9), a negative interval is an error naming both instants (M11), and nothing names
 * a person (M12). {@link SpecMeasuresList#of} summarizes what it lists over the numbers known
 * exactly, counting what it left out of each.
 */
class SpecMeasuresTest {

  private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
  private static final SpecStore.KeptRuns NO_RUNS =
      new SpecStore.KeptRuns(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  private static Instant at(int minutes) {
    return T0.plusSeconds(minutes * 60L);
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

  private static AttentionStore.Minute minute(
      String person, int minutes, AttentionStore.Channel channel) {
    return new AttentionStore.Minute(person, LoopRows.SPEC, at(minutes), channel);
  }

  private record Given(
      SpecStatus status,
      SpecStore.Recorded recorded,
      MeasureRows.Seen<ReviewStore.ReviewRow> reviews,
      List<Finding> findings,
      MeasureRows.Seen<RunStore.RunRow> runs,
      List<AttentionStore.Minute> minutes,
      ProjectDefinition.Budget budget) {

    /**
     * A change made ready at 09:10, verified at 10:10, awaiting merge from 10:10, done at 11:10.
     */
    static Given done() {
      return new Given(
          SpecStatus.DONE,
          new SpecStore.Recorded(at(10), at(70), at(130), NO_RUNS, false),
          MeasureRows.Seen.all(List.of(review("r1", 1, "passed", at(70), null))),
          List.of(),
          MeasureRows.Seen.all(List.of()),
          List.of(),
          null);
    }

    Given times(Instant ready, Instant awaitingMerge, Instant done) {
      return new Given(
          status,
          new SpecStore.Recorded(
              ready, awaitingMerge, done, recorded.runs(), recorded.erasedBeforeHeld()),
          reviews,
          findings,
          runs,
          minutes,
          budget);
    }

    Given kept(SpecStore.KeptRuns kept) {
      return new Given(
          status,
          new SpecStore.Recorded(
              recorded.readyAt(),
              recorded.awaitingMergeAt(),
              recorded.doneAt(),
              kept,
              recorded.erasedBeforeHeld()),
          reviews,
          findings,
          runs,
          minutes,
          budget);
    }

    Given heldLate() {
      return new Given(
          status,
          new SpecStore.Recorded(
              recorded.readyAt(),
              recorded.awaitingMergeAt(),
              recorded.doneAt(),
              recorded.runs(),
              true),
          reviews,
          findings,
          runs,
          minutes,
          budget);
    }

    Given reviewed(ReviewStore.ReviewRow... reviews) {
      return new Given(
          status,
          recorded,
          MeasureRows.Seen.all(List.of(reviews)),
          findings,
          runs,
          minutes,
          budget);
    }

    Given reviewsHidden() {
      return new Given(
          status,
          recorded,
          new MeasureRows.Seen<>(reviews.rows(), true),
          findings,
          runs,
          minutes,
          budget);
    }

    Given found(Finding... findings) {
      return new Given(status, recorded, reviews, List.of(findings), runs, minutes, budget);
    }

    Given hideRuns() {
      return new Given(
          status,
          recorded,
          reviews,
          findings,
          new MeasureRows.Seen<>(runs.rows(), true),
          minutes,
          budget);
    }

    Given running(RunStore.RunRow... held) {
      return new Given(
          status,
          recorded,
          reviews,
          findings,
          MeasureRows.Seen.all(List.of(held)),
          minutes,
          budget);
    }

    Given attended(AttentionStore.Minute... minutes) {
      return new Given(status, recorded, reviews, findings, runs, List.of(minutes), budget);
    }

    Given budget(Long tokens, Double usd) {
      return new Given(
          status,
          recorded,
          reviews,
          findings,
          runs,
          minutes,
          new ProjectDefinition.Budget(tokens, usd));
    }

    Given status(SpecStatus status) {
      return new Given(status, recorded, reviews, findings, runs, minutes, budget);
    }

    SpecMeasures measured() {
      return measured("box-7");
    }

    SpecMeasures measured(String box) {
      return SpecMeasures.of(
          new MeasureRows(
              LoopRows.SPEC, status, box, recorded, reviews, findings, runs, minutes, budget));
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

  private static Map<String, Object> cost(
      long runs,
      long withoutUsage,
      long withoutCost,
      long in,
      long out,
      long read,
      long write,
      double usd,
      Object budget,
      Object over) {
    var cost = new LinkedHashMap<String, Object>();
    cost.put("runs", count(runs));
    cost.put("runs_without_usage", count(withoutUsage));
    cost.put("runs_without_cost", count(withoutCost));
    cost.put("tokens", count(in + out + read + write));
    cost.put("tokens_in", count(in));
    cost.put("tokens_out", count(out));
    cost.put("tokens_cache_read", count(read));
    cost.put("tokens_cache_write", count(write));
    cost.put("usd", Map.of("usd", usd));
    cost.put("budget", budget);
    cost.put("over_budget", over);
    return cost;
  }

  private static final Map<String, Object> NOT_REACHED = unknown("not_reached");
  private static final Map<String, Object> HIDDEN = unknown("hidden_rows");
  private static final Map<String, Object> NOT_SET = unknown("not_set");

  @Test
  void aShippedChangeStatesItsOwnTimesAndItsPassingReview() {
    var wire = Given.done().wire();

    assertEquals(
        Map.of(
            "ready",
            moment(10, "spec"),
            "verified",
            moment(70, "review"),
            "awaiting_merge",
            moment(70, "spec"),
            "done",
            moment(130, "spec")),
        at(wire, "times"));
    assertEquals(
        Map.of(
            "ready_to_verified", seconds(3600),
            "verified_to_done", seconds(3600),
            "ready_to_done", seconds(7200),
            "awaiting_merge", seconds(3600)),
        at(wire, "intervals"));
    assertEquals(Map.of("sail_id", "box-7"), wire.get("box"));
    assertEquals(LoopRows.SPEC, wire.get("spec_id"));
  }

  @Test
  void aSpecReviewedThreeTimesCountsItsRoundsAndItsKeptBuildAndFixRuns() {
    var given =
        Given.done()
            .reviewed(
                review("r1", 1, "failed", at(40), at(45)),
                review("r2", 1, "failed", at(60), null),
                review("r3", 2, "passed", at(70), null))
            .found(LoopRows.finding("f1", Finding.Severity.HIGH), carried("f2", "f1"))
            .kept(new SpecStore.KeptRuns(8, 3, 1, 8, 8, 0, 0, 0, 0, 0));

    assertEquals(
        rework(count(3), count(0), count(3), count(1), count(1)), at(given.wire(), "rework"));
  }

  /** A build or fix still running is rework under way, counted once, never in the cost. */
  @Test
  void aBuildOrFixUnderWayIsReworkButNotYetCost() {
    var running = LoopRows.ended("b2", "build", null);
    var live = LoopRows.run("b3", LoopRows.NODE, "build", null, "running", null);
    var given =
        Given.done()
            .kept(new SpecStore.KeptRuns(1, 1, 0, 1, 1, 0, 0, 0, 0, 0))
            .running(running, live, live);

    assertEquals(count(2), at(given.wire(), "rework", "build_runs"), "b1 kept, b3 under way");
    assertEquals(count(1), at(given.wire(), "cost", "runs"), "a run's cost is counted as it ends");
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

  @Test
  void aDoneSpecWithNoHumanActIsCountedAndLabelledAnUpperBound() {
    var posted =
        Given.done()
            .attended(
                minute("ada", 20, AttentionStore.Channel.POST),
                minute("ada", 21, AttentionStore.Channel.FOCUS),
                minute("ada", 22, AttentionStore.Channel.REVIEW_ACT));

    assertEquals(
        Map.of("counted", true, "bound", "upper"),
        at(Given.done().wire(), "no_recorded_human_code_act"));
    assertEquals(
        Map.of("counted", true, "bound", "upper"),
        at(posted.wire(), "no_recorded_human_code_act"),
        "a post, a focus or a status move is no code act");
    assertEquals(
        unknown("not_done"),
        at(Given.done().status(SpecStatus.REVIEW).wire(), "no_recorded_human_code_act"));
  }

  /** Scenario 17. */
  @Test
  void aDoneSpecWithATerminalInputMinuteInItsRoomIsNotCountedAsNoRecordedHumanCodeAct() {
    var typed = Given.done().attended(minute("ada", 20, AttentionStore.Channel.TERMINAL_INPUT));

    assertEquals(
        Map.of("counted", false, "bound", "upper"), at(typed.wire(), "no_recorded_human_code_act"));
  }

  @Test
  void aSpecNotYetReadyOrDoneOrVerifiedSaysWhatItHasNotReached() {
    var wire = Given.done().times(null, null, null).reviewed().wire();

    assertEquals(
        Map.of(
            "ready",
            NOT_REACHED,
            "verified",
            NOT_REACHED,
            "awaiting_merge",
            NOT_REACHED,
            "done",
            NOT_REACHED),
        at(wire, "times"));
    assertEquals(missing(2), at(wire, "intervals", "ready_to_verified"));
    assertEquals(
        missing(1),
        at(Given.done().times(at(10), null, at(130)).wire(), "intervals", "awaiting_merge"),
        "a change done without awaiting merge has no dwell in it");
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

  @Test
  void readyAtTAndDoneFiveMinutesBeforeItIsAnErrorNamingBothInstantsNeverAnInterval() {
    var wire = Given.done().reviewed().times(at(30), null, at(25)).wire();

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
        at(Given.done().times(at(30), null, at(25)).wire(), "intervals", "verified_to_done"));
  }

  /** Scenario 3: a run that reported nothing is counted apart, never as zero tokens or dollars. */
  @Test
  void aRunWithoutUsageIsCountedApartAndAddsNothing() {
    var wire = Given.done().kept(new SpecStore.KeptRuns(1, 1, 0, 1, 1, 0, 0, 0, 0, 0)).wire();

    assertEquals(cost(1, 1, 1, 0, 0, 0, 0, 0, NOT_SET, NOT_SET), wire.get("cost"));
  }

  /** Scenario 13. */
  @Test
  void twoCodexRunsAndThreeClaudeRunsGiveTokensOverFiveAndDollarsOverThree() {
    var kept = new SpecStore.KeptRuns(5, 2, 1, 0, 2, 1000, 200, 30000, 400, 7.2);

    assertEquals(
        cost(5, 0, 2, 1000, 200, 30000, 400, 7.2, NOT_SET, NOT_SET),
        Given.done().kept(kept).wire().get("cost"));
  }

  /** Scenario 14. */
  @Test
  void aCostAboveTheBudgetIsOverItWithBothValues() {
    var claude = new SpecStore.KeptRuns(3, 1, 1, 0, 0, 10, 20, 30, 40, 7.2);
    var withCodex = new SpecStore.KeptRuns(4, 2, 1, 0, 1, 10, 20, 30, 40, 4.0);

    assertEquals(
        cost(3, 0, 0, 10, 20, 30, 40, 7.2, Map.of("usd", 5.0), true),
        Given.done().kept(claude).budget(null, 5.0).wire().get("cost"));
    assertEquals(
        false,
        at(Given.done().kept(claude).budget(null, 8.0).wire(), "cost", "over_budget"),
        "every run reported its dollars, and they are within the budget");
    assertEquals(
        unknown("runs_not_reported"),
        at(Given.done().kept(withCodex).budget(null, 5.0).wire(), "cost", "over_budget"),
        "a run that reported no dollars may have taken it over");
    assertEquals(
        true,
        at(Given.done().kept(withCodex).budget(99L, 5.0).wire(), "cost", "over_budget"),
        "100 tokens are over a budget of 99 whatever the dollars say");
    assertEquals(
        Map.of("tokens", 100L, "usd", 5.0),
        at(Given.done().kept(withCodex).budget(100L, 5.0).wire(), "cost", "budget"));
  }

  @Test
  void aReviewOrRunTheReaderMayNotSeeLeavesWhatItsKindGivesUnknownSayingNoMore() {
    var kept = new SpecStore.KeptRuns(2, 1, 1, 0, 0, 1, 1, 1, 1, 1.0);
    var hiddenReviews =
        Given.done()
            .reviewed(approved("r1", at(70), null))
            .found(carried("f2", "f1"))
            .kept(kept)
            .reviewsHidden()
            .wire();
    var hiddenRuns = Given.done().kept(kept).budget(null, 5.0).hideRuns().wire();

    assertEquals(rework(HIDDEN, HIDDEN, count(1), count(1), HIDDEN), at(hiddenReviews, "rework"));
    assertEquals(HIDDEN, at(hiddenReviews, "times", "verified"));
    assertEquals(HIDDEN, at(hiddenReviews, "no_recorded_human_code_act"));
    assertEquals(rework(count(1), count(0), HIDDEN, HIDDEN, count(0)), at(hiddenRuns, "rework"));
    var cost = new LinkedHashMap<String, Object>();
    for (var name :
        List.of(
            "runs",
            "runs_without_usage",
            "runs_without_cost",
            "tokens",
            "tokens_in",
            "tokens_out",
            "tokens_cache_read",
            "tokens_cache_write",
            "usd")) {
      cost.put(name, HIDDEN);
    }
    cost.put("budget", Map.of("usd", 5.0));
    cost.put("over_budget", HIDDEN);
    assertEquals(cost, hiddenRuns.get("cost"));
  }

  /**
   * The coordinator's ruling: on a box that first held the change after main erased runs of its
   * project, every number its runs give is a lower bound, said so, and left out of a summary.
   */
  @Test
  void aBoxThatHeldTheChangeOnlyAfterRunsOfItsProjectWereErasedServesLowerBounds() {
    var kept = new SpecStore.KeptRuns(2, 1, 1, 0, 1, 1, 2, 3, 4, 1.25);
    var late = Given.done().kept(kept).heldLate();
    var wire = late.budget(null, 5.0).wire();

    var atLeast = Map.of("count", 1L, "at_least", true);
    assertEquals(
        rework(count(1), count(0), atLeast, atLeast, count(0)), at(wire, "rework"), "reviews stay");
    assertEquals(Map.of("count", 2L, "at_least", true), at(wire, "cost", "runs"));
    assertEquals(Map.of("count", 10L, "at_least", true), at(wire, "cost", "tokens"));
    assertEquals(Map.of("usd", 1.25, "at_least", true), at(wire, "cost", "usd"));
    assertEquals(unknown("runs_not_held"), at(wire, "cost", "over_budget"));
    assertEquals(
        true,
        at(late.budget(null, 1.0).wire(), "cost", "over_budget"),
        "already over on what it holds");
    var summary =
        (Map<?, ?>)
            SpecMeasuresList.of(
                    List.of(late.measured(), Given.done().kept(kept).measured()),
                    SpecMeasuresList.Window.parse(null, null))
                .toMap()
                .get("summary");
    assertEquals(spread("count", 1, 1L, 1L, 0, 1, 0), summary.get("build_runs"));
    assertEquals(spread("usd", 1, 1.25, 1.25, 0, 1, 0), summary.get("usd"));
  }

  /** Scenario 10, as the measures count it. */
  @Test
  void humanMinutesAreDistinctPersonMinutesSplitByChannelAndAMinuteSeenTwiceCountsOnce() {
    var wire =
        Given.done()
            .attended(
                minute("ada", 20, AttentionStore.Channel.TERMINAL_INPUT),
                minute("ada", 21, AttentionStore.Channel.TERMINAL_INPUT),
                minute("ada", 22, AttentionStore.Channel.TERMINAL_INPUT),
                minute("ada", 22, AttentionStore.Channel.POST),
                minute("sam", 22, AttentionStore.Channel.FOCUS))
            .wire();

    assertEquals(
        Map.of(
            "minutes",
            count(4),
            "by_channel",
            Map.of(
                "terminal_input", count(3),
                "post", count(1),
                "review_act", count(0),
                "focus", count(1)),
            "people",
            count(2),
            "bound",
            "lower"),
        wire.get("human_minutes"));
    assertEquals(
        count(3),
        at(
            Given.done()
                .attended(
                    minute("ada", 20, AttentionStore.Channel.TERMINAL_INPUT),
                    minute("ada", 21, AttentionStore.Channel.TERMINAL_INPUT),
                    minute("ada", 22, AttentionStore.Channel.TERMINAL_INPUT),
                    minute("ada", 22, AttentionStore.Channel.POST))
                .wire(),
            "human_minutes",
            "minutes"),
        "3 minutes typed and a post in the third are 3 minutes, not 4");
  }

  @Test
  void aBoxWithNoSailIdSaysSoInTheOneShapeEveryUnknownHas() {
    assertEquals(unknown("no_box_id"), Given.done().measured(null).toMap().get("box"));
  }

  @Test
  void theMeasuresNameNoPerson() {
    var wire =
        YamlUtil.dumpJson(
            Given.done()
                .reviewed(approved("r1", at(70), null))
                .attended(minute("ada", 20, AttentionStore.Channel.POST))
                .wire());

    assertEquals(false, wire.contains("uday"), wire);
    assertEquals(false, wire.contains("ada"), wire);
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
    return Given.done().reviewed().times(at(readyMinutes), null, at(doneMinutes)).measured();
  }

  private static SpecMeasures unknownTimes() {
    return Given.done().reviewed().times(null, null, null).measured();
  }

  private static Map<String, Object> spread(
      String unit, int n, Object median, Object p90, int unknown, int errors) {
    return spread(unit, n, median, p90, unknown, 0, errors);
  }

  private static Map<String, Object> spread(
      String unit, int n, Object median, Object p90, int unknown, int atLeast, int errors) {
    var spread = new LinkedHashMap<String, Object>();
    spread.put("n", n);
    spread.put("median", median == null ? unknown("none_known") : Map.of(unit, median));
    spread.put("p90", p90 == null ? unknown("none_known") : Map.of(unit, p90));
    spread.put("left_out_unknown", unknown);
    spread.put("left_out_at_least", atLeast);
    spread.put("left_out_errors", errors);
    return spread;
  }

  @Test
  void fortyDoneSpecsSixNeverReadyListFortyRowsAndSummarizeThirtyFourSayingSixWereLeftOut() {
    var rows = new ArrayList<SpecMeasures>();
    IntStream.rangeClosed(1, 34).forEach(minutes -> rows.add(measured(0, minutes)));
    IntStream.range(0, 6).forEach(unknown -> rows.add(unknownTimes()));

    var wire = SpecMeasuresList.of(rows, SpecMeasuresList.Window.parse(null, null)).toMap();

    assertEquals(40, ((List<?>) wire.get("specs")).size());
    assertEquals(40, wire.get("total"));
    assertEquals(
        spread("seconds", 34, 17L * 60, 31L * 60, 6, 0), at(wire, "summary", "ready_to_done"));
    assertEquals(spread("count", 40, 0L, 0L, 0, 0), at(wire, "summary", "review_rounds"));
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
          spread("seconds", n, median, p90, 0, 0),
          at(wire, "summary", "ready_to_done"),
          "n = " + n);
    }
  }

  @Test
  void dollarsTokensAndMinutesAreSummarizedOverTheChangesThatKnowThem() {
    var cheap = Given.done().kept(new SpecStore.KeptRuns(1, 1, 0, 0, 0, 1, 1, 1, 1, 0.5));
    var dear = Given.done().kept(new SpecStore.KeptRuns(1, 1, 0, 0, 0, 5, 5, 5, 5, 9.25));
    var hidden = Given.done().hideRuns();
    var rows =
        List.of(
            cheap.measured(),
            dear.attended(minute("ada", 1, AttentionStore.Channel.POST)).measured(),
            hidden.measured());

    var wire = SpecMeasuresList.of(rows, SpecMeasuresList.Window.parse(null, null)).toMap();

    assertEquals(spread("usd", 2, 0.5, 9.25, 1, 0), at(wire, "summary", "usd"));
    assertEquals(spread("count", 2, 4L, 20L, 1, 0), at(wire, "summary", "tokens"));
    assertEquals(spread("count", 3, 0L, 1L, 0, 0), at(wire, "summary", "human_minutes"));
  }

  @Test
  void aNegativeIntervalIsLeftOutOfTheSummaryAndCountedApart() {
    var rows = List.of(measured(0, 10), measured(0, 20), measured(30, 25));

    var wire = SpecMeasuresList.of(rows, SpecMeasuresList.Window.parse(null, null)).toMap();

    assertEquals(spread("seconds", 2, 600L, 1200L, 0, 1), at(wire, "summary", "ready_to_done"));
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
            "tokens",
            "usd",
            "human_minutes",
            "no_recorded_human_code_act"),
        List.copyOf(((Map<?, ?>) wire.get("summary")).keySet()));
    assertEquals(spread("count", 0, null, null, 0, 0), at(wire, "summary", "fix_runs"));
  }

  @Test
  void aWindowListsOnlyTheSpecsDoneInItAndCountsThoseWithNoDoneTime() {
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
