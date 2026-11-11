/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One route a router serves: requests of {@code method} ({@link #ANY} for every method) on a path
 * {@code pattern} matches, decided by {@code gate}, answered by {@code handler}. A pattern is an
 * absolute path of segments, each literal or a placeholder: {@code {name}} matches one segment, an
 * empty one included, so a path as sent fills it as sent; {@code {name...}} matches the rest of the
 * path, slashes and all, up to the literal segments after it, and holds something unless it is
 * last.
 */
record Route<H>(String method, String pattern, Gate gate, H handler) {

  /** The method of a route that serves every method. */
  static final String ANY = "*";

  private static final String REST = "...}";

  Route {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(handler, "handler");
    if (!Objects.requireNonNull(pattern, "pattern").startsWith("/")) {
      throw new IllegalArgumentException("A route's pattern starts with '/': " + pattern);
    }
    if (segments(pattern).stream().filter(Route::rest).count() > 1) {
      throw new IllegalArgumentException("Route " + pattern + " captures the rest more than once.");
    }
  }

  /** Whether this route serves {@code method}. */
  boolean serves(String method) {
    return this.method.equals(ANY) || this.method.equals(method);
  }

  /** The placeholders {@code path} fills, by name, when this route's pattern matches it. */
  Optional<Map<String, String>> match(String path) {
    if (!path.startsWith("/")) {
      return Optional.empty();
    }
    var wanted = segments(pattern);
    var given = segments(path);
    var filled = new LinkedHashMap<String, String>();
    var taken = 0;
    for (var i = 0; i < wanted.size(); i++) {
      var segment = wanted.get(i);
      if (rest(segment)) {
        var after = wanted.size() - i - 1;
        var end = given.size() - after;
        if (end <= taken) {
          return Optional.empty();
        }
        var tail = String.join("/", given.subList(taken, end));
        if (after > 0 && tail.isEmpty()) {
          return Optional.empty();
        }
        fill(filled, segment, tail);
        taken = end;
      } else if (taken >= given.size()
          || !placeholder(segment) && !segment.equals(given.get(taken))) {
        return Optional.empty();
      } else {
        if (placeholder(segment)) {
          fill(filled, segment, given.get(taken));
        }
        taken++;
      }
    }
    return taken == given.size() ? Optional.of(filled) : Optional.empty();
  }

  private static void fill(Map<String, String> filled, String segment, String value) {
    var close = rest(segment) ? REST : "}";
    filled.put(segment.substring(1, segment.length() - close.length()), value);
  }

  private static boolean placeholder(String segment) {
    return segment.startsWith("{") && segment.endsWith("}");
  }

  private static boolean rest(String segment) {
    return segment.startsWith("{") && segment.endsWith(REST);
  }

  private static List<String> segments(String path) {
    return Arrays.asList(path.substring(1).split("/", -1));
  }
}
