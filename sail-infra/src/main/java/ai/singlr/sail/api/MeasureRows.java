/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every row {@link SpecMeasures#of} reads of one spec, as its reader sees them on the box {@code
 * box} names (its Sail id, null on a box that has none): the spec's current {@code project}; the
 * revisions this box's change log holds of it, oldest first, and the seqs of {@code gaps}, the
 * entries before or between them it does not (compacted, or filed where the reader does not read);
 * its reviews, oldest first, and their findings; its runs, those naming the spec and those of its
 * room that name none; and {@code runErasures}, the seq of the latest run erasure this box
 * recorded, by retention or from main, for each project the reader reads, a null key for erasures
 * filed under no project.
 */
record MeasureRows(
    String specId,
    String project,
    SpecStatus status,
    String box,
    List<ChangeLog.Entry> history,
    Set<Long> gaps,
    Seen<ReviewStore.ReviewRow> reviews,
    List<Finding> findings,
    Seen<RunStore.RunRow> runs,
    Map<String, Long> runErasures) {

  MeasureRows {
    history = List.copyOf(history);
    gaps = Set.copyOf(gaps);
    findings = List.copyOf(findings);
    runErasures = Collections.unmodifiableMap(new HashMap<>(runErasures));
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
