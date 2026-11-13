/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    lines.add("  @|bold Measures|@ (" + record(measures) + "):");
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
    lines.add(line("Cost", cost(cost)));
    lines.add(line("Budget", budget(cost)));
    lines.add(line("Human minutes", minutes((Map<String, Object>) measures.get("human_minutes"))));
    return lines;
  }

  /** A {@code measures} listing: a line per spec, then the spread of each number. */
  @SuppressWarnings("unchecked")
  static List<String> listing(Map<String, Object> listing) {
    var specs = (List<Map<String, Object>>) listing.get("specs");
    var lines = new ArrayList<String>();
    var first = specs.isEmpty() ? Map.<String, Object>of() : specs.getFirst();
    lines.add(
        "  @|bold Measures of "
            + plural(listing.get("total"), "spec")
            + "|@ ("
            + record(first)
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
      parts.add("cost " + cost((Map<String, Object>) spec.get("cost")));
      parts.add("human minutes " + minutes((Map<String, Object>) spec.get("human_minutes")));
      lines.add("    • @|bold " + spec.get("spec_id") + "|@  " + String.join(" · ", parts));
    }
    var summary = (Map<String, Object>) listing.get("summary");
    lines.add("  @|bold Median and p90|@, nearest rank, over the numbers known exactly:");
    summary.forEach(
        (name, value) -> lines.add(line(summaryLabel(name), spread((Map<String, Object>) value))));
    return lines;
  }

  /**
   * The box whose runs and minutes are counted: by its Sail id, or saying it has none. The times
   * are the spec's own, the same on every box.
   */
  @SuppressWarnings("unchecked")
  private static String record(Map<String, Object> measures) {
    if (!(measures.get("box") instanceof Map<?, ?> box)) {
      return "counted on this box";
    }
    return box.containsKey("sail_id")
        ? "counted on " + box.get("sail_id")
        : "counted on this box, unknown: " + reason((Map<String, Object>) box);
  }

  /**
   * What a change's runs cost, as the answer says: dollars, tokens, and the runs behind each; a
   * figure some runs left unreported reads as a lower bound, and one none reported as unknown.
   */
  @SuppressWarnings("unchecked")
  private static String cost(Map<String, Object> cost) {
    var runs = (Map<String, Object>) cost.get("runs");
    if (!runs.containsKey(COUNT)) {
      return "unknown (" + reason(runs) + ")";
    }
    var all = ((Number) runs.get(COUNT)).longValue();
    var bound = Boolean.TRUE.equals(runs.get("at_least")) ? "at least " : "";
    var withoutCost = number(cost, "runs_without_cost");
    var withoutUsage = number(cost, "runs_without_usage");
    return figure(cost, "usd")
        + " over "
        + (all - withoutCost)
        + " of "
        + bound
        + plural(all, "run")
        + ", "
        + tokens(cost, all - withoutUsage);
  }

  /** The tokens of the runs that reported them, by kind; said once when none did. */
  @SuppressWarnings("unchecked")
  private static String tokens(Map<String, Object> cost, long reporting) {
    if (((Map<String, Object>) cost.get("tokens")).containsKey("unknown")) {
      return "tokens " + figure(cost, "tokens");
    }
    return figure(cost, "tokens")
        + " tokens over "
        + reporting
        + " (in "
        + part(cost, "tokens_in")
        + ", out "
        + part(cost, "tokens_out")
        + ", cache read "
        + part(cost, "tokens_cache_read")
        + ", cache write "
        + part(cost, "tokens_cache_write")
        + ")";
  }

  /** One kind of the tokens, bare: the total before it says whether the kinds are lower bounds. */
  @SuppressWarnings("unchecked")
  private static String part(Map<String, Object> cost, String key) {
    var part = (Map<String, Object>) cost.get(key);
    return part.containsKey("unknown")
        ? "unknown"
        : String.format(Locale.ROOT, "%,d", ((Number) part.get(COUNT)).longValue());
  }

  /** The figure under {@code key}: dollars or a count, a lower bound, or unknown and why. */
  @SuppressWarnings("unchecked")
  private static String figure(Map<String, Object> cost, String key) {
    var figure = (Map<String, Object>) cost.get(key);
    if (figure.containsKey("unknown")) {
      return "unknown (" + reason(figure) + ")";
    }
    return (Boolean.TRUE.equals(figure.get("at_least")) ? "at least " : "")
        + (figure.containsKey("usd")
            ? dollars(figure)
            : String.format(Locale.ROOT, "%,d", ((Number) figure.get(COUNT)).longValue()));
  }

  @SuppressWarnings("unchecked")
  private static long number(Map<String, Object> block, String key) {
    return ((Number) ((Map<String, Object>) block.get(key)).get(COUNT)).longValue();
  }

  /** The project's budget and whether the cost is over it, as the answer says. */
  @SuppressWarnings("unchecked")
  private static String budget(Map<String, Object> cost) {
    var budget = (Map<String, Object>) cost.get("budget");
    if (budget.containsKey("unknown")) {
      return "none set";
    }
    var limits = new ArrayList<String>();
    if (budget.containsKey("usd")) {
      limits.add(dollars(budget));
    }
    if (budget.containsKey("tokens")) {
      limits.add(String.format(Locale.ROOT, "%,d", budget.get("tokens")) + " tokens");
    }
    var over = cost.get("over_budget");
    return String.join(", ", limits)
        + switch (over) {
          case Boolean overBudget -> overBudget ? ": over" : ": within";
          default -> ": unknown (" + reason((Map<String, Object>) over) + ")";
        };
  }

  /** The minutes people gave a change on the box the answer counted them on. */
  @SuppressWarnings("unchecked")
  private static String minutes(Map<String, Object> minutes) {
    var channels = (Map<String, Object>) minutes.get("by_channel");
    var parts = new ArrayList<String>();
    channels.forEach(
        (channel, count) ->
            parts.add(channel.replace('_', ' ') + " " + ((Map<String, Object>) count).get(COUNT)));
    return number(minutes, "minutes")
        + " from "
        + plural(number(minutes, "people"), "person", "people")
        + " ("
        + String.join(", ", parts)
        + "), a lower bound";
  }

  private static String dollars(Map<String, Object> amount) {
    return String.format(Locale.ROOT, "$%,.2f", ((Number) amount.get("usd")).doubleValue());
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
    if (value.containsKey("usd")) {
      return dollars(value);
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
    return plural(count, noun, noun + "s");
  }

  private static String plural(Object count, String one, String many) {
    return count + " " + (((Number) count).longValue() == 1 ? one : many);
  }

  private static String label(String name) {
    var words = name.replace('_', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private static String intervalLabel(String name) {
    return name.equals("awaiting_merge") ? "In awaiting merge" : label(name.replace("_to_", " → "));
  }

  private static String summaryLabel(String name) {
    return switch (name) {
      case "no_recorded_human_code_act" -> "No recorded human code act";
      case "usd" -> "Dollars";
      default -> intervalLabel(name);
    };
  }
}
