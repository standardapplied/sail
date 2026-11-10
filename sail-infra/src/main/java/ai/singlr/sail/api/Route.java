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
 * One route a router serves: requests of {@code method} on a path {@code pattern} matches, decided
 * by {@code gate}, answered by {@code handler}. A pattern is an absolute path of segments, each
 * literal or a placeholder: {@code {name}} matches one non-empty segment, and {@code {name...}},
 * only last, matches the non-empty rest of the path, slashes and all.
 */
record Route<H>(String method, String pattern, Gate gate, H handler) {

  private static final String REST = "...}";

  Route {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(handler, "handler");
    if (!Objects.requireNonNull(pattern, "pattern").startsWith("/")) {
      throw new IllegalArgumentException("A route's pattern starts with '/': " + pattern);
    }
    var segments = segments(pattern);
    if (segments.subList(0, segments.size() - 1).stream().anyMatch(Route::rest)) {
      throw new IllegalArgumentException(
          "Route " + pattern + " captures the rest of the path before its last segment.");
    }
  }

  /** The placeholders {@code path} fills, by name, when this route's pattern matches it. */
  Optional<Map<String, String>> match(String path) {
    if (!path.startsWith("/")) {
      return Optional.empty();
    }
    var wanted = segments(pattern);
    var given = segments(path);
    var filled = new LinkedHashMap<String, String>();
    for (var i = 0; i < wanted.size(); i++) {
      var segment = wanted.get(i);
      if (rest(segment)) {
        var tail = i < given.size() ? String.join("/", given.subList(i, given.size())) : "";
        return tail.isEmpty() ? Optional.empty() : Optional.of(fill(filled, segment, tail));
      }
      if (i >= given.size()) {
        return Optional.empty();
      }
      var value = given.get(i);
      if (placeholder(segment) ? value.isEmpty() : !segment.equals(value)) {
        return Optional.empty();
      }
      if (placeholder(segment)) {
        fill(filled, segment, value);
      }
    }
    return given.size() == wanted.size() ? Optional.of(filled) : Optional.empty();
  }

  private static Map<String, String> fill(
      Map<String, String> filled, String segment, String value) {
    var close = rest(segment) ? REST : "}";
    filled.put(segment.substring(1, segment.length() - close.length()), value);
    return filled;
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
