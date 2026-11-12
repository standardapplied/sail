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
import java.util.OptionalLong;
import java.util.Set;

/**
 * Every row {@link SpecMeasures#of} reads of one spec, as its reader sees them on the box {@code
 * box} names (its Sail id, null on a box that has none): the revisions this box's change log holds
 * of it, oldest first, and the seqs of {@code gaps}, the entries before or between them it does not
 * (compacted, or filed where the reader does not read); its reviews, oldest first, and their
 * findings; its runs, those naming the spec and those of its room that name none; and what this box
 * can say of runs erased by retention.
 */
record MeasureRows(
    String specId,
    SpecStatus status,
    String box,
    List<ChangeLog.Entry> history,
    Set<Long> gaps,
    Seen<ReviewStore.ReviewRow> reviews,
    List<Finding> findings,
    Seen<RunStore.RunRow> runs,
    RunErasures erasures) {

  MeasureRows {
    history = List.copyOf(history);
    gaps = Set.copyOf(gaps);
    findings = List.copyOf(findings);
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

  /**
   * What this box can say of runs erased by retention: a box that authors erasures ({@code
   * authored}) records each one, so the seq of its latest says whether any could be this spec's; a
   * node hears of an erasure only for a row it held, so it cannot say.
   */
  record RunErasures(boolean authored, OptionalLong latest) {

    /** A box that authors erasures and has erased no run by retention. */
    static final RunErasures NONE = new RunErasures(true, OptionalLong.empty());

    /** A box that does not author erasures, so cannot say which runs were erased. */
    static final RunErasures UNTOLD = new RunErasures(false, OptionalLong.empty());
  }
}
