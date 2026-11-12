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

  private MeasuresText() {}

  /** The Measures section of one spec's {@code measures} answer. */
  @SuppressWarnings("unchecked")
  static List<String> section(Map<String, Object> measures) {
    var times = (Map<String, Object>) measures.get("times");
    var lines = new ArrayList<String>();
    lines.add("  @|bold Measures|@ (this box's record, " + times.get("box") + "):");
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
                + plural(cost.get("runs_without_usage"), "run")
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
    var box =
        specs.isEmpty()
            ? ""
            : ", " + ((Map<String, Object>) specs.getFirst().get("times")).get("box");
    lines.add(
        "  @|bold Measures of "
            + plural(listing.get("total"), "spec")
            + "|@ (this box's record"
            + box
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
    lines.add("  @|bold Median and p90|@, nearest rank, over the specs listed:");
    summary.forEach(
        (name, value) -> lines.add(line(summaryLabel(name), spread((Map<String, Object>) value))));
    return lines;
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
    return "unknown (" + missing(interval.get("missing")) + ")";
  }

  private static String missing(Object count) {
    return ((Number) count).intValue() == 1 ? "1 input missing" : count + " inputs missing";
  }

  private static String rework(Map<String, Object> rework) {
    return plural(rework.get("review_rounds"), "review round")
        + ", "
        + plural(rework.get("dispatch_attempts"), "dispatch attempt")
        + ", "
        + plural(rework.get("fix_runs"), "fix run")
        + ", "
        + plural(rework.get("carried_findings"), "carried finding");
  }

  private static String codeAct(Map<String, Object> act) {
    if (act.containsKey("unknown")) {
      return "unknown: " + reason(act);
    }
    return (Boolean.TRUE.equals(act.get("counted")) ? "yes" : "no") + " (an upper bound)";
  }

  private static String spread(Map<String, Object> spread) {
    if (spread.containsKey("counted")) {
      return spread.get("counted")
          + " of "
          + spread.get("of")
          + " done (an upper bound); "
          + spread.get("unknown")
          + " not done";
    }
    var seconds = "seconds".equals(spread.get("unit"));
    var known =
        spread.containsKey("median")
            ? "median "
                + value(spread.get("median"), seconds)
                + ", p90 "
                + value(spread.get("p90"), seconds)
                + ", over "
                + spread.get("n")
            : "none known";
    return known
        + "; left out "
        + spread.get("unknown")
        + " unknown, "
        + plural(spread.get("errors"), "error");
  }

  private static String value(Object value, boolean seconds) {
    return seconds ? duration(((Number) value).longValue()) : value.toString();
  }

  /** {@code seconds} as days, hours and minutes, or as seconds under a minute. */
  static String duration(long seconds) {
    if (seconds < 60) {
      return seconds + "s";
    }
    var minutes = seconds / 60;
    var hours = minutes / 60;
    var days = hours / 24;
    if (days > 0) {
      return days + "d " + hours % 24 + "h " + minutes % 60 + "m";
    }
    return hours > 0 ? hours + "h " + minutes % 60 + "m" : minutes + "m";
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
