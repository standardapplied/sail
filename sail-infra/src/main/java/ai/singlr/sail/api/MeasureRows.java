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
import java.util.List;
import java.util.Set;

/**
 * Every row {@link SpecMeasures#of} reads of one spec, as its reader sees them on box {@code box}:
 * the revisions this box's change log holds of it, oldest first, and the seqs of {@code gaps}, the
 * entries before or between them it does not (compacted, or filed where the reader does not read);
 * its reviews, oldest first, and their findings; and its runs, those naming the spec and those of
 * its room that name none.
 */
record MeasureRows(
    String specId,
    SpecStatus status,
    String box,
    List<ChangeLog.Entry> history,
    Set<Long> gaps,
    List<ReviewStore.ReviewRow> reviews,
    List<Finding> findings,
    List<RunStore.RunRow> runs) {

  MeasureRows {
    history = List.copyOf(history);
    gaps = Set.copyOf(gaps);
    reviews = List.copyOf(reviews);
    findings = List.copyOf(findings);
    runs = List.copyOf(runs);
  }
}
