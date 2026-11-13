/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The measures of the specs a list names, one {@link SpecMeasures} each, and over them the median
 * and the 90th percentile of every number, nearest-rank, each over the specs whose number is known
 * exactly, saying how many it left out as unknown, as a lower bound and as an error (M8, M11). A
 * {@link Window} lists only the specs whose move into {@code done} falls inside it, and counts
 * those with no known move it could test.
 */
record SpecMeasuresList(List<SpecMeasures> specs, Window window, int undated) implements Mappable {

  private static final SpecMeasures.Measure NONE_KNOWN =
      new SpecMeasures.Measure.Unknown("none_known", 0);

  /**
   * The days, UTC and inclusive at both ends, a listing's specs were done within; either end, or
   * both, may be open.
   */
  record Window(LocalDate since, LocalDate until) {

    /**
     * The window {@code since} and {@code until} name, each a date or blank; refused when either is
     * not a date or the window ends before it begins.
     */
    static Window parse(String since, String until) {
      var window = new Window(date("done_since", since), date("done_until", until));
      if (window.since != null && window.until != null && window.since.isAfter(window.until)) {
        throw new ApiException(
            ErrorCode.INVALID_REQUEST,
            "done_since " + window.since + " is after done_until " + window.until + ".");
      }
      return window;
    }

    private static LocalDate date(String name, String value) {
      if (Strings.isBlank(value)) {
        return null;
      }
      try {
        return LocalDate.parse(value.strip());
      } catch (DateTimeParseException notADate) {
        throw new ApiException(
            ErrorCode.INVALID_REQUEST,
            name + " must be a date, YYYY-MM-DD, not '" + value + "'.",
            notADate);
      }
    }

    boolean asked() {
      return since != null || until != null;
    }

    /** Whether the change {@code measured} was done inside this window. */
    boolean holds(SpecMeasures measured) {
      if (!asked()) {
        return true;
      }
      if (!(measured.done() instanceof SpecMeasures.Moment.At(var done, _))) {
        return false;
      }
      return (since == null || !done.isBefore(since.atStartOfDay(ZoneOffset.UTC).toInstant()))
          && (until == null
              || done.isBefore(until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
  }

  /** The listing of {@code measured} that {@code window} holds. */
  static SpecMeasuresList of(List<SpecMeasures> measured, Window window) {
    var undated =
        window.asked()
            ? (int)
                measured.stream()
                    .filter(spec -> spec.done() instanceof SpecMeasures.Moment.Unknown)
                    .count()
            : 0;
    return new SpecMeasuresList(measured.stream().filter(window::holds).toList(), window, undated);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("specs", specs.stream().map(SpecMeasures::toMap).toList());
    m.put("total", specs.size());
    if (window.asked()) {
      var asked = new LinkedHashMap<String, Object>();
      if (window.since() != null) {
        asked.put("done_since", window.since().toString());
      }
      if (window.until() != null) {
        asked.put("done_until", window.until().toString());
      }
      asked.put("left_out_undated", undated);
      m.put("window", asked);
    }
    m.put("summary", summary());
    return m;
  }

  private Map<String, Object> summary() {
    var summary = new LinkedHashMap<String, Object>();
    var figures = specs.stream().map(SpecMeasures::figures).toList();
    for (var index = 0; index < SpecMeasures.FIGURES.size(); index++) {
      var at = index;
      var named = SpecMeasures.FIGURES.get(index);
      summary.put(
          named.getKey(),
          spread(named.getValue(), figures.stream().map(each -> each.get(at).measure()).toList()));
    }
    var acts = specs.stream().map(SpecMeasures::codeAct).toList();
    var counted = new LinkedHashMap<String, Object>();
    counted.put(
        "counted", (int) acts.stream().filter(SpecMeasures.CodeAct.NONE_RECORDED::equals).count());
    counted.put(
        "of",
        (int)
            acts.stream()
                .filter(
                    act ->
                        act == SpecMeasures.CodeAct.NONE_RECORDED
                            || act == SpecMeasures.CodeAct.RECORDED)
                .count());
    counted.put(
        "left_out_unknown",
        (int)
            acts.stream()
                .filter(
                    act ->
                        act == SpecMeasures.CodeAct.NOT_DONE || act == SpecMeasures.CodeAct.HIDDEN)
                .count());
    counted.put("bound", "upper");
    summary.put("no_recorded_human_code_act", counted);
    return summary;
  }

  /**
   * The spread of {@code measures} in {@code unit}: over the values known exactly, with how many
   * were left out as unknown, as lower bounds and as errors.
   */
  private static Map<String, Object> spread(String unit, List<SpecMeasures.Measure> measures) {
    var known = SpecMeasures.exactly(measures);
    var m = new LinkedHashMap<String, Object>();
    m.put("n", known.size());
    m.put("median", rank(known, 50).toMap(unit));
    m.put("p90", rank(known, 90).toMap(unit));
    m.put("left_out_unknown", count(measures, SpecMeasures.Measure.Unknown.class));
    m.put("left_out_at_least", count(measures, SpecMeasures.Measure.AtLeast.class));
    m.put("left_out_errors", count(measures, SpecMeasures.Measure.Negative.class));
    return m;
  }

  private static int count(List<SpecMeasures.Measure> measures, Class<?> kind) {
    return (int) measures.stream().filter(kind::isInstance).count();
  }

  /** The value at rank {@code percent} of {@code sorted}: the least with that share at or below. */
  private static SpecMeasures.Measure rank(List<SpecMeasures.Measure> sorted, int percent) {
    return sorted.isEmpty() ? NONE_KNOWN : sorted.get((percent * sorted.size() + 99) / 100 - 1);
  }
}
