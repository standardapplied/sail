/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.Lane;
import java.util.List;

/**
 * Seeds a reviewer's or a fix agent's run the way the review loop records one: reserved through the
 * dispatch gate ({@link RunStore#reserveForReview}), under the unit and log every run launches as.
 */
public final class ReviewRuns {

  private ReviewRuns() {}

  /**
   * Reserves run {@code id} in {@code lane} serving {@code reviewId} and returns its credential. A
   * reservation the gate or a container lease refuses fails the test: the state it was seeded
   * beside is one the loop never reaches.
   */
  public static String reserve(
      RunStore runs,
      String id,
      String reviewId,
      String project,
      String specId,
      String node,
      Lane lane,
      String agent,
      List<String> repos) {
    var reservation =
        runs.reserveForReview(
            id,
            reviewId,
            project,
            specId,
            node,
            lane,
            repos,
            agent,
            "feat/" + specId,
            lane.wire(),
            "/home/dev/.sail/runs/" + id + "/agent.log",
            "sail-agent-" + id,
            null);
    if (reservation instanceof RunStore.Reservation.Reserved reserved) {
      return reserved.credential();
    }
    throw new AssertionError(
        "The " + lane.wire() + " run " + id + " was not reserved: " + reservation);
  }
}
