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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What one spec's change took, computed from the rows a box holds of it and nothing else (M8): when
 * it was made ready, verified and done, how long it sat awaiting merge, how much rework it took,
 * and whether any person's code act is recorded on it. A number an input is missing for is unknown,
 * with a count of what is missing, never estimated or zero; a run is counted once (M9); an interval
 * whose end precedes its start is an error naming both instants, never a number (M11); and no
 * person is named (M12). The route, {@code sail spec show} and {@code sail spec list --measures}
 * all serve this one computation (M10).
 *
 * <p>Ready is the first move into {@code pending}, known only while this box's change log holds
 * every revision before it from the spec's birth. Done is the move that began the spec's current
 * stretch in {@code done}, known while the revision before that move is held with nothing missing
 * between them; the stretch in {@code awaiting_merge} that move ended is the dwell. Each is the
 * author's time of the revision ({@code updated_at}, T3), read from this box's change log alone,
 * which names {@code box}. Verified is when the latest review of the current dispatch attempt that
 * passed completed: a pass a re-dispatch superseded verified other work.
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
    int runsWithoutUsage)
    implements Mappable {

  static final String CHANGE_LOG = "change_log";
  static final String REVIEW = "review";
  static final String SECONDS = "seconds";
  static final String COUNT = "count";

  private static final Moment.Unknown NOT_REACHED = new Moment.Unknown("not_reached");
  private static final Moment.Unknown INCOMPLETE = new Moment.Unknown("history_incomplete");

  /** An instant a measure starts or ends at, or why it is not known. */
  sealed interface Moment {

    /** The instant {@code at}, as {@code source} records it. */
    record At(Instant at, String source) implements Moment {}

    /**
     * Not known: {@code not_reached} when the rows are whole and hold no such move, {@code
     * history_incomplete} when they lack revisions it could be among.
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
   * One number of the measures: its value, how many of its inputs are missing, or why it is none.
   */
  sealed interface Measure {

    record Known(long value) implements Measure {}

    record Unknown(int missing) implements Measure {}

    /** An interval ending before it starts, which no clock agrees with: never a number. */
    record Negative(String error) implements Measure {}
  }

  /** A number of the measures under its wire name, in {@code unit}, as a listing summarizes it. */
  record Figure(String name, String unit, Measure measure) {}

  /** The rework a change took: review rows, build runs, fix runs and findings carried forward. */
  record Rework(int reviewRounds, int dispatchAttempts, int fixRuns, int carriedFindings) {}

  /**
   * Whether a person's code act is recorded on a done change: a person deciding a human review
   * stage is the one Sail records, so none recorded is an upper bound on changes no person wrote or
   * reviewed line by line.
   */
  enum CodeAct {
    NONE_RECORDED,
    RECORDED,
    NOT_DONE
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
    var runs =
        rows.runs().stream()
            .collect(
                Collectors.toMap(RunStore.RunRow::id, Function.identity(), (first, again) -> first))
            .values();
    var rework =
        new Rework(
            rows.reviews().size(),
            (int) runs.stream().filter(RunStore.RunRow::buildRole).count(),
            (int) runs.stream().filter(run -> Lane.FIX.matches(run.role())).count(),
            (int) rows.findings().stream().filter(found -> found.carriedFrom() != null).count());
    return new SpecMeasures(
        rows.specId(),
        rows.box(),
        log.firstInto(SpecStatus.PENDING),
        verified(rows),
        log.awaitingMergeBefore(done),
        done.moment(),
        rework,
        codeAct(rows),
        runs.size());
  }

  private static Moment verified(MeasureRows rows) {
    return rows.reviews().stream()
        .filter(review -> !review.superseded() && "passed".equals(review.status()))
        .map(ReviewStore.ReviewRow::completedAt)
        .filter(Objects::nonNull)
        .reduce((earlier, later) -> later)
        .<Moment>map(completed -> new Moment.At(completed, REVIEW))
        .orElse(NOT_REACHED);
  }

  private static CodeAct codeAct(MeasureRows rows) {
    if (rows.status() != SpecStatus.DONE) {
      return CodeAct.NOT_DONE;
    }
    return rows.reviews().stream().anyMatch(review -> review.decidedBy() != null)
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

  /** Every number a listing summarizes: the intervals, then the rework. */
  List<Figure> figures() {
    var figures =
        intervals().entrySet().stream()
            .map(interval -> new Figure(interval.getKey(), SECONDS, interval.getValue()))
            .collect(Collectors.toCollection(ArrayList::new));
    figures.add(count("review_rounds", rework.reviewRounds()));
    figures.add(count("dispatch_attempts", rework.dispatchAttempts()));
    figures.add(count("fix_runs", rework.fixRuns()));
    figures.add(count("carried_findings", rework.carriedFindings()));
    return figures;
  }

  private static Figure count(String name, int value) {
    return new Figure(name, COUNT, new Measure.Known(value));
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
    intervals().forEach((name, measure) -> intervals.put(name, interval(measure)));
    var counts = new LinkedHashMap<String, Object>();
    counts.put("review_rounds", rework.reviewRounds());
    counts.put("dispatch_attempts", rework.dispatchAttempts());
    counts.put("fix_runs", rework.fixRuns());
    counts.put("carried_findings", rework.carriedFindings());
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
        });
    m.put("cost", Map.of("unknown", "not_recorded", "runs_without_usage", runsWithoutUsage));
    m.put("human_minutes", Map.of("unknown", "not_recorded"));
    return m;
  }

  private static Map<String, Object> interval(Measure measure) {
    return switch (measure) {
      case Measure.Known(var seconds) -> Map.of(SECONDS, seconds);
      case Measure.Unknown(var missing) -> Map.of("unknown", "inputs_missing", "missing", missing);
      case Measure.Negative(var error) -> Map.of("error", error);
    };
  }

  /** One revision of the spec as the measures read it: its seq, status and author's time. */
  private record Revision(long seq, SpecStatus status, Instant at, boolean born) {

    static Revision of(ChangeLog.Entry entry) {
      var snapshot = YamlUtil.parseMap(entry.snapshot());
      var at = Snapshots.instant(snapshot, "updated_at");
      return new Revision(
          entry.seq(),
          SpecStatus.fromWire(Snapshots.text(snapshot, "status")),
          at,
          Objects.equals(Snapshots.instant(snapshot, "created_at"), at));
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

    /** The first move into {@code status}, known while nothing before it is missing. */
    Moment firstInto(SpecStatus status) {
      for (var index = 0; index < revisions.size(); index++) {
        if (revisions.get(index).status() == status) {
          return wholeBefore(index) ? at(index) : INCOMPLETE;
        }
      }
      return born() && gaps.isEmpty() ? NOT_REACHED : INCOMPLETE;
    }

    /** The move that began the current stretch in {@code done}; not reached for a spec not done. */
    Entered done() {
      if (revisions.isEmpty()) {
        return new Entered(INCOMPLETE, -1);
      }
      var last = revisions.size() - 1;
      return revisions.get(last).status() == SpecStatus.DONE
          ? stretch(last, SpecStatus.DONE)
          : new Entered(NOT_REACHED, -1);
    }

    /** The move into the stretch in {@code awaiting_merge} that {@code done} ended. */
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
      return adjacent(start) ? new Entered(at(start), start) : new Entered(INCOMPLETE, -1);
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
