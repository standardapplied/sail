/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.AttentionStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;

/**
 * Every row {@link SpecMeasures#of} reads of one spec, as its reader sees them on the box {@code
 * box} names (its Sail id, null on a box that has none): the spec's {@code recorded} times and the
 * sums this box kept of its finished runs; its reviews, oldest first, and their findings; the runs
 * of it this box holds, read as {@code GET /v1/runs?spec=} reads them, whose unfinished builds and
 * fixes are rework already under way; the minutes this box saw people give its conversation; and
 * the {@code budget} its project sets, null for none.
 */
record MeasureRows(
    String specId,
    SpecStatus status,
    String box,
    SpecStore.Recorded recorded,
    Seen<ReviewStore.ReviewRow> reviews,
    List<Finding> findings,
    Seen<RunStore.RunRow> runs,
    List<AttentionStore.Minute> minutes,
    ProjectDefinition.Budget budget) {

  MeasureRows {
    findings = List.copyOf(findings);
    minutes = List.copyOf(minutes);
  }

  /**
   * The rows of one kind the reader sees, and whether any row of that kind was left out of its
   * sight: a number counted from them is then unknown, never a smaller one.
   */
  record Seen<T>(List<T> rows, boolean hidden) {

    Seen {
      rows = List.copyOf(rows);
    }

    /** {@code rows}, all seen. */
    static <T> Seen<T> all(List<T> rows) {
      return new Seen<>(rows, false);
    }
  }
}
