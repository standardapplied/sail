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
import java.util.stream.Collectors;

/**
 * The measures of the specs a list names, one {@link SpecMeasures} each, and over them the median
 * and the 90th percentile of every number, nearest-rank, each leaving out the specs whose number is
 * unknown or an error and saying how many it left out of each (M8, M11). A {@link Window} lists
 * only the specs whose move into {@code done}, by this box's record, falls inside it, and counts
 * those with no known move it could test.
 */
record SpecMeasuresList(List<SpecMeasures> specs, Window window, int undated) implements Mappable {

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

    /** Whether the change {@code measured} was done inside this window, as its box records it. */
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
    specs.stream()
        .flatMap(spec -> spec.figures().stream())
        .collect(
            Collectors.groupingBy(
                figure -> List.of(figure.name(), figure.unit()),
                LinkedHashMap::new,
                Collectors.mapping(SpecMeasures.Figure::measure, Collectors.toList())))
        .forEach((named, measures) -> summary.put(named.get(0), spread(named.get(1), measures)));
    var acts = specs.stream().map(SpecMeasures::codeAct).toList();
    var counted = new LinkedHashMap<String, Object>();
    counted.put(
        "counted", (int) acts.stream().filter(SpecMeasures.CodeAct.NONE_RECORDED::equals).count());
    counted.put(
        "of", (int) acts.stream().filter(act -> act != SpecMeasures.CodeAct.NOT_DONE).count());
    counted.put(
        "unknown", (int) acts.stream().filter(SpecMeasures.CodeAct.NOT_DONE::equals).count());
    counted.put("bound", "upper");
    summary.put("no_recorded_human_code_act", counted);
    return summary;
  }

  /** The spread of {@code measures}, in {@code unit}: what is known, and what was left out. */
  private static Map<String, Object> spread(String unit, List<SpecMeasures.Measure> measures) {
    var known =
        measures.stream()
            .filter(SpecMeasures.Measure.Known.class::isInstance)
            .mapToLong(measure -> ((SpecMeasures.Measure.Known) measure).value())
            .sorted()
            .toArray();
    var m = new LinkedHashMap<String, Object>();
    m.put("unit", unit);
    m.put("n", known.length);
    if (known.length > 0) {
      m.put("median", nearestRank(known, 50));
      m.put("p90", nearestRank(known, 90));
    }
    m.put(
        "unknown",
        (int) measures.stream().filter(SpecMeasures.Measure.Unknown.class::isInstance).count());
    m.put(
        "errors",
        (int) measures.stream().filter(SpecMeasures.Measure.Negative.class::isInstance).count());
    return m;
  }

  /** The value at rank {@code percent} of {@code sorted}: the least with that share at or below. */
  private static long nearestRank(long[] sorted, int percent) {
    return sorted[(percent * sorted.length + 99) / 100 - 1];
  }
}
