/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.AttentionStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one spec's change took, computed from the rows a box holds of it and nothing else (M8): when
 * it was made ready, verified and done, how long it sat awaiting merge, how much rework it took,
 * what its runs cost, the minutes people gave it, and whether any person's code act is recorded on
 * it. A number an input is missing for is unknown, with the reason and, for an interval, a count of
 * what is missing; never estimated or zero. A number some of whose rows the reader may not see is
 * unknown, saying no more; one some of whose rows may be gone is served as a lower bound. Nothing
 * is counted twice (M9); an interval whose end precedes its start is an error naming both instants,
 * never a number (M11); and no person is named (M12). The route, {@code sail spec show} and {@code
 * sail spec list --measures} all serve this one computation (M10).
 *
 * <p>Ready, awaiting merge and done are the spec's own times, written as it made each move and the
 * same on every box (M4). Verified is when the latest review of the current dispatch attempt
 * completed, if it passed. Run counts and cost are the sums this box kept of the spec's finished
 * runs, which erasing a run never lowers (M14), and its builds and fixes under way: exact, unless
 * this box may lack runs (it first held the change after main erased runs of its project, or main
 * erased a run it never saw finish), when they are a lower bound. Tokens and dollars are exact only
 * when every run counted reported them: a lower bound when some did not, or when a run's share of
 * its harness session could not be told, and unknown when none did. Human minutes are the distinct
 * (person, minute) pairs this box saw in the spec's own conversation, which it names: always a
 * lower bound, since a person's reading and thinking leave no row and another box's minutes are not
 * here.
 */
record SpecMeasures(
    String specId,
    String box,
    Moment ready,
    Moment verified,
    Moment awaitingMerge,
    Moment done,
    Rework rework,
    CodeAct codeAct,
    Cost cost,
    Minutes minutes)
    implements Mappable {

  static final String SPEC = "spec";
  static final String REVIEW = "review";
  static final String SECONDS = "seconds";
  static final String COUNT = "count";
  static final String USD = "usd";

  private static final String RUNS_NOT_REPORTED = "runs_not_reported";
  private static final Moment.Unknown NOT_REACHED = new Moment.Unknown("not_reached");
  private static final Moment.Unknown HIDDEN_MOMENT = new Moment.Unknown(Measure.HIDDEN_ROWS);
  private static final Measure HIDDEN = new Measure.Unknown(Measure.HIDDEN_ROWS, 0);
  private static final Set<String> VERDICTS = Set.of("passed", "failed", "escalated");

  /** Every number a listing summarizes, by its wire name and unit, in order. */
  static final List<Map.Entry<String, String>> FIGURES =
      List.of(
          Map.entry("ready_to_verified", SECONDS),
          Map.entry("verified_to_done", SECONDS),
          Map.entry("ready_to_done", SECONDS),
          Map.entry("awaiting_merge", SECONDS),
          Map.entry("review_rounds", COUNT),
          Map.entry("review_errors", COUNT),
          Map.entry("build_runs", COUNT),
          Map.entry("fix_runs", COUNT),
          Map.entry("carried_findings", COUNT),
          Map.entry("tokens", COUNT),
          Map.entry("usd", USD),
          Map.entry("human_minutes", COUNT));

  /** An instant a measure starts or ends at, or why it is not known. */
  sealed interface Moment {

    /** The instant {@code at}, as {@code source} records it. */
    record At(Instant at, String source) implements Moment {}

    /**
     * Not known, and why: {@code not_reached} when the spec has not made the move, and {@code
     * hidden_rows} when the reader may not see a row it is read from.
     */
    record Unknown(String reason) implements Moment {}

    /** The instant a spec's own record holds, or not reached when it holds none. */
    static Moment of(Instant at) {
      return at == null ? NOT_REACHED : new At(at, SPEC);
    }

    default Map<String, Object> toMap() {
      return switch (this) {
        case At(var at, var source) -> Map.of("at", DateTimeUtils.format(at), "source", source);
        case Unknown(var reason) -> Map.of("unknown", reason);
      };
    }
  }

  /** One number of the measures: its value, why it is unknown, or an error. */
  sealed interface Measure {

    /** Why a number is unknown when the reader may not see every row it is counted from. */
    String HIDDEN_ROWS = "hidden_rows";

    record Known(long value) implements Measure {}

    /** An amount of dollars, to the millionth. */
    record Dollars(double value) implements Measure {

      static Dollars of(double value) {
        return new Dollars(
            BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue());
      }
    }

    /**
     * A number its rows show at least, when some may be gone: a box that first held the change
     * after main erased runs of its project cannot vouch it counted them all.
     */
    record AtLeast(Measure shown) implements Measure {}

    record Unknown(String reason, int missing) implements Measure {}

    /** An interval ending before it starts, which no clock agrees with: never a number. */
    record Negative(String error) implements Measure {}

    /** The value of a number known exactly, or null for one that is not. */
    default Number exact() {
      return switch (this) {
        case Known(var value) -> value;
        case Dollars(var value) -> value;
        default -> null;
      };
    }

    /** This number on the wire, a value in {@code unit}: one shape for every number served. */
    default Map<String, Object> toMap(String unit) {
      return switch (this) {
        case Known(var value) -> Map.of(unit, value);
        case Dollars(var value) -> Map.of(unit, value);
        case AtLeast(var shown) -> {
          var bounded = new LinkedHashMap<String, Object>(shown.toMap(unit));
          bounded.put("at_least", true);
          yield bounded;
        }
        case Unknown(var reason, var missing) ->
            missing > 0 ? Map.of("unknown", reason, "missing", missing) : Map.of("unknown", reason);
        case Negative(var error) -> Map.of("error", error);
      };
    }
  }

  /** A number of the measures under its wire name, in {@code unit}, as a listing summarizes it. */
  record Figure(String name, String unit, Measure measure) {}

  /**
   * The rework a change took: reviews that reached a verdict, reviews that errored and were
   * retried, build runs (a resumed build is one more), fix runs and findings carried forward.
   */
  record Rework(
      Measure reviewRounds,
      Measure reviewErrors,
      Measure buildRuns,
      Measure fixRuns,
      Measure carriedFindings) {}

  /**
   * What a change's finished runs cost: how many there were, how many reported no usage and how
   * many no dollars, the tokens the others reported, in all and by kind, and the dollars; beside
   * the project's budget, if it sets one, and whether the cost is over it.
   */
  record Cost(
      Measure runs,
      Measure withoutUsage,
      Measure withoutCost,
      Measure tokens,
      Measure tokensIn,
      Measure tokensOut,
      Measure tokensCacheRead,
      Measure tokensCacheWrite,
      Measure usd,
      ProjectDefinition.Budget budget,
      Over over) {

    /** Whether a cost is over its budget, or why that is not known. */
    enum Over {
      YES,
      NO,
      NO_BUDGET,
      NOT_ALL_REPORTED,
      NOT_ALL_HELD,
      HIDDEN
    }

    static Cost of(MeasureRows rows) {
      var budget = rows.budget();
      if (rows.runs().hidden()) {
        return new Cost(
            HIDDEN,
            HIDDEN,
            HIDDEN,
            HIDDEN,
            HIDDEN,
            HIDDEN,
            HIDDEN,
            HIDDEN,
            HIDDEN,
            budget,
            budget == null ? Over.NO_BUDGET : Over.HIDDEN);
      }
      var kept = rows.recorded().runs();
      var tokens =
          kept.tokensIn() + kept.tokensOut() + kept.tokensCacheRead() + kept.tokensCacheWrite();
      var over = over(budget, kept, tokens);
      var withoutUsage = kept.withoutUsage();
      return new Cost(
          ofRuns(rows, kept.runs()),
          ofRuns(rows, withoutUsage),
          ofRuns(rows, kept.withoutCost()),
          reported(rows, new Measure.Known(tokens), withoutUsage),
          reported(rows, new Measure.Known(kept.tokensIn()), withoutUsage),
          reported(rows, new Measure.Known(kept.tokensOut()), withoutUsage),
          reported(rows, new Measure.Known(kept.tokensCacheRead()), withoutUsage),
          reported(rows, new Measure.Known(kept.tokensCacheWrite()), withoutUsage),
          reported(rows, Measure.Dollars.of(kept.costUsd()), kept.withoutCost()),
          budget,
          over == Over.YES || over == Over.NO_BUDGET || !rows.recorded().erasedBeforeHeld()
              ? over
              : Over.NOT_ALL_HELD);
    }

    /**
     * {@code sum}, what the change's runs reported of a figure, as this box can vouch for it:
     * unknown when every run left it unreported, a lower bound when {@code unreported} runs did or
     * the box may lack runs ({@link #ofRuns}), and exact only when every run it counts reported it.
     */
    private static Measure reported(MeasureRows rows, Measure sum, long unreported) {
      var runs = rows.recorded().runs().runs();
      if (unreported > 0 && unreported == runs) {
        return new Measure.Unknown(RUNS_NOT_REPORTED, (int) unreported);
      }
      return unreported > 0 || rows.recorded().erasedBeforeHeld() ? new Measure.AtLeast(sum) : sum;
    }

    /**
     * Over when what was reported already passes a limit the budget sets; not over only when every
     * run reported the figure that limit is of.
     */
    private static Over over(
        ProjectDefinition.Budget budget, SpecStore.KeptRuns kept, long tokens) {
      if (budget == null) {
        return Over.NO_BUDGET;
      }
      var tokensOver = budget.tokens() != null && tokens > budget.tokens();
      var usdOver =
          budget.usd() != null && Measure.Dollars.of(kept.costUsd()).value() > budget.usd();
      if (tokensOver || usdOver) {
        return Over.YES;
      }
      var unreported =
          (budget.tokens() != null && kept.withoutUsage() > 0)
              || (budget.usd() != null && kept.withoutCost() > 0);
      return unreported ? Over.NOT_ALL_REPORTED : Over.NO;
    }

    Map<String, Object> toMap() {
      var m = new LinkedHashMap<String, Object>();
      m.put("runs", runs.toMap(COUNT));
      m.put("runs_without_usage", withoutUsage.toMap(COUNT));
      m.put("runs_without_cost", withoutCost.toMap(COUNT));
      m.put("tokens", tokens.toMap(COUNT));
      m.put("tokens_in", tokensIn.toMap(COUNT));
      m.put("tokens_out", tokensOut.toMap(COUNT));
      m.put("tokens_cache_read", tokensCacheRead.toMap(COUNT));
      m.put("tokens_cache_write", tokensCacheWrite.toMap(COUNT));
      m.put(USD, usd.toMap(USD));
      m.put("budget", budget == null ? Map.of("unknown", "not_set") : budget.toMap());
      m.put(
          "over_budget",
          switch (over) {
            case YES -> true;
            case NO -> false;
            case NO_BUDGET -> Map.of("unknown", "not_set");
            case NOT_ALL_REPORTED -> Map.of("unknown", RUNS_NOT_REPORTED);
            case NOT_ALL_HELD -> Map.of("unknown", "runs_not_held");
            case HIDDEN -> Map.of("unknown", Measure.HIDDEN_ROWS);
          });
      return m;
    }
  }

  /**
   * The minutes people gave a change's conversation on this box: distinct (person, minute) pairs in
   * all and by where each was seen, and how many people; a minute seen in two ways counts once in
   * all.
   */
  record Minutes(long minutes, Map<AttentionStore.Channel, Long> byChannel, long people) {

    static Minutes of(List<AttentionStore.Minute> seen) {
      var byChannel = new LinkedHashMap<AttentionStore.Channel, Long>();
      for (var channel : AttentionStore.Channel.values()) {
        byChannel.put(
            channel,
            seen.stream()
                .filter(minute -> minute.channel() == channel)
                .map(minute -> List.of(minute.person(), minute.minute()))
                .distinct()
                .count());
      }
      return new Minutes(
          seen.stream().map(minute -> List.of(minute.person(), minute.minute())).distinct().count(),
          byChannel,
          seen.stream().map(AttentionStore.Minute::person).distinct().count());
    }

    Map<String, Object> toMap() {
      var channels = new LinkedHashMap<String, Object>();
      byChannel.forEach((channel, count) -> channels.put(channel.wire(), Map.of(COUNT, count)));
      var m = new LinkedHashMap<String, Object>();
      m.put("minutes", Map.of(COUNT, minutes));
      m.put("by_channel", channels);
      m.put("people", Map.of(COUNT, people));
      m.put("bound", "lower");
      return m;
    }
  }

  /**
   * Whether a person's code act is recorded on a done change: a person deciding a human review
   * stage, in any attempt, or typing in a terminal bound to its own conversation, so none recorded
   * is an upper bound on changes no person wrote or reviewed line by line.
   */
  enum CodeAct {
    NONE_RECORDED,
    RECORDED,
    NOT_DONE,
    HIDDEN
  }

  /**
   * Whether a read of a spec asks for its measures, by {@code measures=true}; anything but {@code
   * true} or {@code false} is refused.
   */
  static boolean asked(String value) {
    if (value == null || value.equals("false")) {
      return false;
    }
    if (value.equals("true")) {
      return true;
    }
    throw new ApiException(
        ErrorCode.INVALID_REQUEST,
        "measures must be true or false, not '" + value + "'.",
        "Send measures=true to read the spec with its measures, or leave it out.");
  }

  /** The measures of the spec {@code rows} holds, from those rows alone. */
  static SpecMeasures of(MeasureRows rows) {
    var reviews = rows.reviews();
    var kept = rows.recorded().runs();
    var rework =
        new Rework(
            fromReviews(reviews, reviews.rows().stream().filter(SpecMeasures::verdict).count()),
            fromReviews(
                reviews, reviews.rows().stream().filter(ReviewStore.ReviewRow::errored).count()),
            rows.runs().hidden()
                ? HIDDEN
                : ofRuns(rows, kept.builds() + underWay(rows, Lane.BUILD)),
            rows.runs().hidden() ? HIDDEN : ofRuns(rows, kept.fixes() + underWay(rows, Lane.FIX)),
            fromReviews(
                reviews,
                rows.findings().stream().filter(found -> found.carriedFrom() != null).count()));
    return new SpecMeasures(
        rows.specId(),
        rows.box(),
        Moment.of(rows.recorded().readyAt()),
        verified(reviews),
        Moment.of(rows.recorded().awaitingMergeAt()),
        Moment.of(rows.recorded().doneAt()),
        rework,
        codeAct(rows),
        Cost.of(rows),
        Minutes.of(rows.minutes()));
  }

  /**
   * {@code count} of the change's runs as this box can vouch for it: a lower bound when it first
   * held the change after main erased runs of its project ({@link SpecStore.Recorded}).
   */
  private static Measure ofRuns(MeasureRows rows, long count) {
    return rows.recorded().erasedBeforeHeld()
        ? new Measure.AtLeast(new Measure.Known(count))
        : new Measure.Known(count);
  }

  /**
   * The runs in {@code lane} this box holds that have not finished, each once: rework under way,
   * which the kept sums count once it finishes.
   */
  private static long underWay(MeasureRows rows, Lane lane) {
    return rows.runs().rows().stream()
        .filter(run -> lane.matches(run.role()) && !RunStatus.isTerminal(run.status()))
        .map(RunStore.RunRow::id)
        .distinct()
        .count();
  }

  private static boolean verdict(ReviewStore.ReviewRow review) {
    return !review.errored() && VERDICTS.contains(review.status());
  }

  private static Measure fromReviews(MeasureRows.Seen<ReviewStore.ReviewRow> reviews, long count) {
    return reviews.hidden() ? HIDDEN : new Measure.Known(count);
  }

  private static Moment verified(MeasureRows.Seen<ReviewStore.ReviewRow> reviews) {
    if (reviews.hidden()) {
      return HIDDEN_MOMENT;
    }
    var current =
        reviews.rows().stream()
            .filter(review -> !review.superseded())
            .filter(SpecMeasures::verdict)
            .toList();
    return current.isEmpty()
            || !"passed".equals(current.getLast().status())
            || current.getLast().completedAt() == null
        ? NOT_REACHED
        : new Moment.At(current.getLast().completedAt(), REVIEW);
  }

  private static CodeAct codeAct(MeasureRows rows) {
    if (rows.status() != SpecStatus.DONE) {
      return CodeAct.NOT_DONE;
    }
    if (rows.reviews().hidden()) {
      return CodeAct.HIDDEN;
    }
    var decided = rows.reviews().rows().stream().anyMatch(review -> review.decidedBy() != null);
    var typed =
        rows.minutes().stream()
            .anyMatch(minute -> minute.channel() == AttentionStore.Channel.TERMINAL_INPUT);
    return decided || typed ? CodeAct.RECORDED : CodeAct.NONE_RECORDED;
  }

  /** The intervals between this change's moments, by their wire names, in seconds. */
  Map<String, Measure> intervals() {
    var intervals = new LinkedHashMap<String, Measure>();
    intervals.put("ready_to_verified", between("ready", ready, "verified", verified));
    intervals.put("verified_to_done", between("verified", verified, "done", done));
    intervals.put("ready_to_done", between("ready", ready, "done", done));
    intervals.put("awaiting_merge", between("awaiting_merge", awaitingMerge, "done", done));
    return intervals;
  }

  /** Every number a listing summarizes, in the order of {@link #FIGURES}. */
  List<Figure> figures() {
    var measures = new LinkedHashMap<>(intervals());
    measures.put("review_rounds", rework.reviewRounds());
    measures.put("review_errors", rework.reviewErrors());
    measures.put("build_runs", rework.buildRuns());
    measures.put("fix_runs", rework.fixRuns());
    measures.put("carried_findings", rework.carriedFindings());
    measures.put("tokens", cost.tokens());
    measures.put("usd", cost.usd());
    measures.put("human_minutes", new Measure.Known(minutes.minutes()));
    return FIGURES.stream()
        .map(named -> new Figure(named.getKey(), named.getValue(), measures.get(named.getKey())))
        .toList();
  }

  /** The exact numbers of {@code measures}, least first. */
  static List<Measure> exactly(List<Measure> measures) {
    return measures.stream()
        .filter(measure -> measure.exact() != null)
        .sorted(Comparator.comparingDouble(measure -> measure.exact().doubleValue()))
        .collect(Collectors.toList());
  }

  private static Measure between(String fromName, Moment from, String toName, Moment to) {
    if (from instanceof Moment.At(var start, _) && to instanceof Moment.At(var end, _)) {
      return end.isBefore(start)
          ? new Measure.Negative(
              toName
                  + " at "
                  + DateTimeUtils.format(end)
                  + " is before "
                  + fromName
                  + " at "
                  + DateTimeUtils.format(start)
                  + "; a negative interval is not served.")
          : new Measure.Known(Duration.between(start, end).toSeconds());
    }
    return new Measure.Unknown(
        "inputs_missing",
        (from instanceof Moment.Unknown ? 1 : 0) + (to instanceof Moment.Unknown ? 1 : 0));
  }

  @Override
  public Map<String, Object> toMap() {
    var times = new LinkedHashMap<String, Object>();
    times.put("ready", ready.toMap());
    times.put("verified", verified.toMap());
    times.put("awaiting_merge", awaitingMerge.toMap());
    times.put("done", done.toMap());
    var intervals = new LinkedHashMap<String, Object>();
    intervals().forEach((name, measure) -> intervals.put(name, measure.toMap(SECONDS)));
    var counts = new LinkedHashMap<String, Object>();
    counts.put("review_rounds", rework.reviewRounds().toMap(COUNT));
    counts.put("review_errors", rework.reviewErrors().toMap(COUNT));
    counts.put("build_runs", rework.buildRuns().toMap(COUNT));
    counts.put("fix_runs", rework.fixRuns().toMap(COUNT));
    counts.put("carried_findings", rework.carriedFindings().toMap(COUNT));
    var m = new LinkedHashMap<String, Object>();
    m.put("spec_id", specId);
    m.put("box", box == null ? Map.of("unknown", "no_box_id") : Map.of("sail_id", box));
    m.put("times", times);
    m.put("intervals", intervals);
    m.put("rework", counts);
    m.put(
        "no_recorded_human_code_act",
        switch (codeAct) {
          case NONE_RECORDED -> Map.of("counted", true, "bound", "upper");
          case RECORDED -> Map.of("counted", false, "bound", "upper");
          case NOT_DONE -> Map.of("unknown", "not_done");
          case HIDDEN -> Map.of("unknown", Measure.HIDDEN_ROWS);
        });
    m.put("cost", cost.toMap());
    m.put("human_minutes", minutes.toMap());
    return m;
  }
}
