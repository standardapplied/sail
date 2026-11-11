/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.List;
import java.util.Map;

/**
 * A router's routes, each with the {@link Gate} it declares (A3): the table the router dispatches
 * through ({@link #serve}), and the one the route sweep reads, so a route cannot be served without
 * a gate.
 */
interface RouteTable {

  /** Every route this router serves, in the order it matches them. */
  List<Route<?>> routes();

  /** What a table answers a request: the route that serves it, or why none does. */
  sealed interface Served<H> {

    /** {@code route} serves the request, its path filling {@code params}. */
    record Match<H>(Route<H> route, Map<String, String> params) implements Served<H> {}

    /**
     * The path matches a pattern, filling {@code params}, but no route of it serves the request's
     * method.
     */
    record MethodNotAllowed<H>(Map<String, String> params) implements Served<H> {}

    /** No route's pattern matches the path. */
    record NotFound<H>() implements Served<H> {}
  }

  /**
   * The one dispatch every router asks: of {@code routes}, the first whose pattern matches {@code
   * path} decides; the route of that pattern that serves {@code method} serves it, and when none
   * does the method is not allowed there.
   */
  static <H> Served<H> serve(List<Route<H>> routes, String method, String path) {
    for (var first : routes) {
      var params = first.match(path);
      if (params.isPresent()) {
        return routes.stream()
            .filter(route -> route.pattern().equals(first.pattern()) && route.serves(method))
            .findFirst()
            .<Served<H>>map(route -> new Served.Match<>(route, params.get()))
            .orElseGet(() -> new Served.MethodNotAllowed<>(params.get()));
      }
    }
    return new Served.NotFound<>();
  }
}
