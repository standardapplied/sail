/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.List;

/**
 * A router's routes, each with the {@link Gate} it declares (A3): the table the router dispatches
 * through, and the one the route sweep reads, so a route cannot be served without a gate.
 */
interface RouteTable {

  /** Every route this router serves, in the order it matches them. */
  List<Route<?>> routes();
}
