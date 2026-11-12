/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The measures the server answered, as {@code sail spec show} and {@code sail spec list --measures}
 * print them: every line says what the answer says, and nothing is computed here (M10).
 */
final class MeasuresText {

  private static final String COUNT = "count";

  private static final List<List<String>> REWORK =
      List.of(
          List.of("review_rounds", "review round"),
          List.of("review_errors", "review error"),
          List.of("build_runs", "build run"),
          List.of("fix_runs", "fix run"),
          List.of("carried_findings", "carried finding"));

  private MeasuresText() {}

  /** The Measures section of one spec's {@code measures} answer. */
  @SuppressWarnings("unchecked")
  static List<String> section(Map<String, Object> measures) {
    var times = (Map<String, Object>) measures.get("times");
    var lines = new ArrayList<String>();
    lines.add("  @|bold Measures|@ (" + record(times) + "):");
    for (var moment : List.of("ready", "verified", "awaiting_merge", "done")) {
      lines.add(line(label(moment), moment((Map<String, Object>) times.get(moment))));
    }
    var intervals = (Map<String, Object>) measures.get("intervals");
    intervals.forEach(
        (name, value) ->
            lines.add(line(intervalLabel(name), interval((Map<String, Object>) value))));
    lines.add(line("Rework", rework((Map<String, Object>) measures.get("rework"))));
    lines.add(
        line(
            "No recorded human code act",
            codeAct((Map<String, Object>) measures.get("no_recorded_human_code_act"))));
    var cost = (Map<String, Object>) measures.get("cost");
    lines.add(
        line(
            "Cost",
            "unknown: "
                + reason(cost)
                + " ("
                + count((Map<String, Object>) cost.get("runs_without_usage"), "run")
                + " without usage)"));
    lines.add(
        line(
            "Human minutes",
            "unknown: " + reason((Map<String, Object>) measures.get("human_minutes"))));
    return lines;
  }

  /** A {@code measures} listing: a line per spec, then the spread of each number. */
  @SuppressWarnings("unchecked")
  static List<String> listing(Map<String, Object> listing) {
    var specs = (List<Map<String, Object>>) listing.get("specs");
    var lines = new ArrayList<String>();
    var times =
        specs.isEmpty()
            ? Map.<String, Object>of()
            : (Map<String, Object>) specs.getFirst().get("times");
    lines.add(
        "  @|bold Measures of "
            + plural(listing.get("total"), "spec")
            + "|@ ("
            + record(times)
            + ")"
            + window((Map<String, Object>) listing.get("window"))
            + ":");
    for (var spec : specs) {
      var intervals = (Map<String, Object>) spec.get("intervals");
      var parts = new ArrayList<String>();
      parts.add(rework((Map<String, Object>) spec.get("rework")));
      intervals.forEach(
          (name, value) ->
              parts.add(intervalLabel(name) + " " + interval((Map<String, Object>) value)));
      parts.add(
          "no recorded human code act: "
              + codeAct((Map<String, Object>) spec.get("no_recorded_human_code_act")));
      lines.add("    • @|bold " + spec.get("spec_id") + "|@  " + String.join(" · ", parts));
    }
    var summary = (Map<String, Object>) listing.get("summary");
    lines.add("  @|bold Median and p90|@, nearest rank, over the numbers known exactly:");
    summary.forEach(
        (name, value) -> lines.add(line(summaryLabel(name), spread((Map<String, Object>) value))));
    return lines;
  }

  private static String record(Map<String, Object> times) {
    return times.get("box") == null
        ? "this box's record"
        : "this box's record, " + times.get("box");
  }

  private static String line(String label, String value) {
    return "    " + label + ": " + value;
  }

  private static String window(Map<String, Object> window) {
    if (window == null) {
      return "";
    }
    return ", done "
        + Objects.toString(window.get("done_since"), "any time")
        + " to "
        + Objects.toString(window.get("done_until"), "now")
        + "; "
        + plural(window.get("left_out_undated"), "spec")
        + " with no known move to done left out";
  }

  private static String moment(Map<String, Object> moment) {
    return moment.containsKey("at")
        ? moment.get("at") + " (" + moment.get("source").toString().replace('_', ' ') + ")"
        : "unknown: " + reason(moment);
  }

  private static String interval(Map<String, Object> interval) {
    if (interval.containsKey("seconds")) {
      return duration(((Number) interval.get("seconds")).longValue());
    }
    if (interval.containsKey("error")) {
      return "error: " + interval.get("error");
    }
    var missing = (Number) interval.get("missing");
    return "unknown ("
        + (missing.intValue() == 1 ? "1 input missing" : missing + " inputs missing")
        + ")";
  }

  @SuppressWarnings("unchecked")
  private static String rework(Map<String, Object> rework) {
    var parts = new ArrayList<String>();
    for (var named : REWORK) {
      parts.add(count((Map<String, Object>) rework.get(named.get(0)), named.get(1)));
    }
    return String.join(", ", parts);
  }

  /** A count as the answer serves it: a number, a lower bound, or unknown and why. */
  private static String count(Map<String, Object> count, String noun) {
    if (!count.containsKey(COUNT)) {
      return noun + "s unknown (" + reason(count) + ")";
    }
    return (Boolean.TRUE.equals(count.get("at_least")) ? "at least " : "")
        + plural(count.get(COUNT), noun);
  }

  private static String codeAct(Map<String, Object> act) {
    if (act.containsKey("unknown")) {
      return "unknown: " + reason(act);
    }
    return (Boolean.TRUE.equals(act.get("counted")) ? "yes" : "no") + " (an upper bound)";
  }

  @SuppressWarnings("unchecked")
  private static String spread(Map<String, Object> spread) {
    if (spread.containsKey("counted")) {
      return spread.get("counted")
          + " of "
          + spread.get("of")
          + " done (an upper bound); left out "
          + spread.get("left_out_unknown")
          + " unknown";
    }
    return "median "
        + value((Map<String, Object>) spread.get("median"))
        + ", p90 "
        + value((Map<String, Object>) spread.get("p90"))
        + ", over "
        + spread.get("n")
        + "; left out "
        + spread.get("left_out_unknown")
        + " unknown, "
        + spread.get("left_out_at_least")
        + " lower bounds, "
        + plural(spread.get("left_out_errors"), "error");
  }

  private static String value(Map<String, Object> value) {
    if (value.containsKey("seconds")) {
      return duration(((Number) value.get("seconds")).longValue());
    }
    return value.containsKey(COUNT) ? value.get(COUNT).toString() : "unknown";
  }

  /** {@code seconds} in days, hours, minutes and seconds, from its largest unit, none dropped. */
  static String duration(long seconds) {
    var minutes = seconds / 60;
    var hours = minutes / 60;
    var days = hours / 24;
    if (days > 0) {
      return days + "d " + hours % 24 + "h " + minutes % 60 + "m " + seconds % 60 + "s";
    }
    if (hours > 0) {
      return hours + "h " + minutes % 60 + "m " + seconds % 60 + "s";
    }
    return minutes > 0 ? minutes + "m " + seconds % 60 + "s" : seconds + "s";
  }

  private static String reason(Map<String, Object> unknown) {
    return unknown.get("unknown").toString().replace('_', ' ');
  }

  private static String plural(Object count, String noun) {
    return count + " " + noun + (((Number) count).intValue() == 1 ? "" : "s");
  }

  private static String label(String name) {
    var words = name.replace('_', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private static String intervalLabel(String name) {
    return name.equals("awaiting_merge") ? "In awaiting merge" : label(name.replace("_to_", " → "));
  }

  private static String summaryLabel(String name) {
    return name.equals("no_recorded_human_code_act")
        ? "No recorded human code act"
        : intervalLabel(name);
  }
}
