/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * What one spec's change took, computed from the rows a box holds of it and nothing else (M8): when
 * it was made ready, verified and done, how long it sat awaiting merge, how much rework it took,
 * and whether any person's code act is recorded on it. A number an input is missing for is unknown,
 * with the reason and, for an interval, a count of what is missing; never estimated or zero. A
 * count some of whose rows may be gone is served as a lower bound; one some of whose rows the
 * reader may not see is unknown, saying no more. A run is counted once (M9); an interval whose end
 * precedes its start is an error naming both instants, never a number (M11); and no person is named
 * (M12). The route, {@code sail spec show} and {@code sail spec list --measures} all serve this one
 * computation (M10).
 *
 * <p>Ready is the spec's first move into {@code pending}; done is the move that began its latest
 * stretch in {@code done}; the dwell is the stretch in {@code awaiting_merge} that move ended. Each
 * is the author's time of the revision, read from this box's change log alone, and each is exact
 * only for a move written on this box: a revision heard by sync is one per round, so the moves
 * another box made between two rounds are collapsed into it, and what it or a search across it
 * would say is unknown. Verified is when the latest review of the current dispatch attempt
 * completed, if it passed: a pass a re-dispatch superseded verified other work, and one a later
 * review failed verified nothing that shipped.
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
    Measure runsWithoutUsage)
    implements Mappable {

  static final String CHANGE_LOG = "change_log";
  static final String REVIEW = "review";
  static final String SECONDS = "seconds";
  static final String COUNT = "count";

  private static final Moment.Unknown NOT_REACHED = new Moment.Unknown("not_reached");
  private static final Moment.Unknown INCOMPLETE = new Moment.Unknown("history_incomplete");
  private static final Moment.Unknown COLLAPSED = new Moment.Unknown("collapsed_by_sync");
  private static final Moment.Unknown HIDDEN_MOMENT = new Moment.Unknown(Measure.HIDDEN_ROWS);
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
          Map.entry("carried_findings", COUNT));

  /** An instant a measure starts or ends at, or why it is not known. */
  sealed interface Moment {

    /** The instant {@code at}, as {@code source} records it. */
    record At(Instant at, String source) implements Moment {}

    /**
     * Not known, and why: {@code not_reached} when the rows are whole and hold no such move, {@code
     * history_incomplete} when they lack revisions it could be among, {@code collapsed_by_sync}
     * when it lies in, or could hide in, a revision heard by sync, and {@code hidden_rows} when the
     * reader may not see a row it is read from.
     */
    record Unknown(String reason) implements Moment {}

    default Map<String, Object> toMap() {
      return switch (this) {
        case At(var at, var source) -> Map.of("at", DateTimeUtils.format(at), "source", source);
        case Unknown(var reason) -> Map.of("unknown", reason);
      };
    }
  }

  /**
   * One number of the measures: its value; a value its rows show at least, when some may be gone;
   * why it is unknown, with how many of an interval's ends are missing; or an error.
   */
  sealed interface Measure {

    /** Why a count is unknown when the reader may not see every row it is counted from. */
    String HIDDEN_ROWS = "hidden_rows";

    record Known(long value) implements Measure {}

    record AtLeast(long value) implements Measure {}

    record Unknown(String reason, int missing) implements Measure {}

    /** An interval ending before it starts, which no clock agrees with: never a number. */
    record Negative(String error) implements Measure {}

    /** This number on the wire, a value in {@code unit}: one shape for every number served. */
    default Map<String, Object> toMap(String unit) {
      return switch (this) {
        case Known(var value) -> Map.of(unit, value);
        case AtLeast(var value) -> Map.of(unit, value, "at_least", true);
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
   * Whether a person's code act is recorded on a done change: a person deciding a human review
   * stage, in any attempt, is the one Sail records, so none recorded is an upper bound on changes
   * no person wrote or reviewed line by line.
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
        ErrorCode.INVALID_REQUEST, "measures must be true or false, not '" + value + "'.");
  }

  /** The measures of the spec {@code rows} holds, from those rows alone. */
  static SpecMeasures of(MeasureRows rows) {
    var log = Log.of(rows);
    var done = log.done();
    var reviews = rows.reviews();
    var runs =
        rows.runs().rows().stream()
            .collect(
                Collectors.toMap(RunStore.RunRow::id, Function.identity(), (first, again) -> first))
            .values()
            .stream()
            .toList();
    var rework =
        new Rework(
            fromReviews(reviews, reviews.rows().stream().filter(SpecMeasures::verdict).count()),
            fromReviews(
                reviews, reviews.rows().stream().filter(ReviewStore.ReviewRow::errored).count()),
            fromRuns(rows, runs.stream().filter(RunStore.RunRow::buildRole).count()),
            fromRuns(rows, runs.stream().filter(run -> Lane.FIX.matches(run.role())).count()),
            fromReviews(
                reviews,
                rows.findings().stream().filter(found -> found.carriedFrom() != null).count()));
    return new SpecMeasures(
        rows.specId(),
        rows.box(),
        log.firstInto(SpecStatus.PENDING),
        verified(reviews),
        log.awaitingMergeBefore(done),
        done.moment(),
        rework,
        codeAct(rows),
        fromRuns(rows, runs.size()));
  }

  private static boolean verdict(ReviewStore.ReviewRow review) {
    return !review.errored() && VERDICTS.contains(review.status());
  }

  private static Measure fromReviews(MeasureRows.Seen<ReviewStore.ReviewRow> reviews, long count) {
    return reviews.hidden()
        ? new Measure.Unknown(Measure.HIDDEN_ROWS, 0)
        : new Measure.Known(count);
  }

  /**
   * {@code count} runs, as this box can vouch for it: unknown when the reader may not see a run, or
   * on a box that cannot say which runs retention erased; a lower bound when a run was erased here
   * after the spec arrived, which may have been one of its own.
   */
  private static Measure fromRuns(MeasureRows rows, long count) {
    if (rows.runs().hidden()) {
      return new Measure.Unknown(Measure.HIDDEN_ROWS, 0);
    }
    var erasures = rows.erasures();
    if (!erasures.authored()) {
      return new Measure.Unknown("retention_unknown", 0);
    }
    var arrived =
        LongStream.concat(
                rows.history().stream().mapToLong(ChangeLog.Entry::seq),
                rows.gaps().stream().mapToLong(Long::longValue))
            .min();
    var erasedSince =
        erasures.latest().isPresent()
            && (arrived.isEmpty() || erasures.latest().getAsLong() > arrived.getAsLong());
    return erasedSince ? new Measure.AtLeast(count) : new Measure.Known(count);
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
    return rows.reviews().rows().stream().anyMatch(review -> review.decidedBy() != null)
        ? CodeAct.RECORDED
        : CodeAct.NONE_RECORDED;
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
    return FIGURES.stream()
        .map(named -> new Figure(named.getKey(), named.getValue(), measures.get(named.getKey())))
        .toList();
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
    times.put("box", box);
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
    m.put(
        "cost",
        Map.of("unknown", "not_recorded", "runs_without_usage", runsWithoutUsage.toMap(COUNT)));
    m.put("human_minutes", Map.of("unknown", "not_recorded"));
    return m;
  }

  /**
   * One revision of the spec as the measures read it: its seq, status and author's time, whether it
   * is the spec's birth, and whether it was heard by sync and so may collapse several moves.
   */
  private record Revision(long seq, SpecStatus status, Instant at, boolean born, boolean heard) {

    static Revision of(ChangeLog.Entry entry) {
      var snapshot = YamlUtil.parseMap(entry.snapshot());
      var at = Snapshots.instant(snapshot, "updated_at");
      return new Revision(
          entry.seq(),
          SpecStatus.fromWire(Snapshots.text(snapshot, "status")),
          at,
          Objects.equals(Snapshots.instant(snapshot, "created_at"), at),
          entry.heardFromMain());
    }

    /**
     * Whether moves another box made may be collapsed into this one revision: one heard by sync,
     * unless it is a birth no write followed before it was sent.
     */
    boolean collapsible() {
      return heard && !born;
    }
  }

  /** A move into a status: when, and the index of its revision, or -1 when it is not known. */
  private record Entered(Moment moment, int index) {}

  /** This box's record of the spec's statuses: the revisions it holds, and what it lacks. */
  private record Log(List<Revision> revisions, Set<Long> gaps) {

    static Log of(MeasureRows rows) {
      return new Log(
          rows.history().stream()
              .filter(entry -> entry.kind() == ChangeLog.Kind.REVISION)
              .map(Revision::of)
              .toList(),
          rows.gaps());
    }

    /**
     * The first move into {@code status}: known while nothing before it is missing and no revision
     * up to it was heard by sync, which could hide an earlier one.
     */
    Moment firstInto(SpecStatus status) {
      for (var index = 0; index < revisions.size(); index++) {
        if (!wholeBefore(index)) {
          return INCOMPLETE;
        }
        var revision = revisions.get(index);
        if (revision.collapsible()) {
          return COLLAPSED;
        }
        if (revision.status() == status) {
          return at(index);
        }
      }
      return born() && gaps.isEmpty() ? NOT_REACHED : INCOMPLETE;
    }

    /**
     * The move that began the latest stretch in {@code done}; not reached only when the whole log
     * holds none and no revision of it heard by sync could hide one.
     */
    Entered done() {
      var last = revisions.size() - 1;
      while (last >= 0 && revisions.get(last).status() != SpecStatus.DONE) {
        last--;
      }
      if (last < 0) {
        if (!born() || !gaps.isEmpty()) {
          return new Entered(INCOMPLETE, -1);
        }
        return new Entered(
            revisions.stream().anyMatch(Revision::collapsible) ? COLLAPSED : NOT_REACHED, -1);
      }
      if (revisions.subList(last + 1, revisions.size()).stream().anyMatch(Revision::collapsible)) {
        return new Entered(COLLAPSED, -1);
      }
      return stretch(last, SpecStatus.DONE);
    }

    /**
     * The move into the stretch in {@code awaiting_merge} that {@code done} ended, not reached when
     * the revision before it was in another status.
     */
    Moment awaitingMergeBefore(Entered done) {
      if (done.index() < 0) {
        return done.moment();
      }
      var before = done.index() - 1;
      return before >= 0 && revisions.get(before).status() == SpecStatus.AWAITING_MERGE
          ? stretch(before, SpecStatus.AWAITING_MERGE).moment()
          : NOT_REACHED;
    }

    private Entered stretch(int last, SpecStatus status) {
      var start = last;
      while (start > 0 && revisions.get(start - 1).status() == status && adjacent(start)) {
        start--;
      }
      if (!adjacent(start)) {
        return new Entered(INCOMPLETE, -1);
      }
      if (revisions.subList(start, last + 1).stream().anyMatch(Revision::collapsible)) {
        return new Entered(COLLAPSED, -1);
      }
      return new Entered(at(start), start);
    }

    /**
     * Whether nothing is missing between revision {@code index} and the one before it; before the
     * first, whether the log begins with the spec's birth.
     */
    private boolean adjacent(int index) {
      if (index == 0) {
        return wholeBefore(0);
      }
      var previous = revisions.get(index - 1).seq();
      var seq = revisions.get(index).seq();
      return gaps.stream().noneMatch(gap -> gap > previous && gap < seq);
    }

    /** Whether the log holds everything before revision {@code index}, from the spec's birth. */
    private boolean wholeBefore(int index) {
      var seq = revisions.get(index).seq();
      return born() && gaps.stream().noneMatch(gap -> gap < seq);
    }

    /** Whether the first revision held is the spec's birth: made when the spec was. */
    private boolean born() {
      return !revisions.isEmpty() && revisions.getFirst().born();
    }

    private Moment at(int index) {
      return new Moment.At(revisions.get(index).at(), CHANGE_LOG);
    }
  }
}
