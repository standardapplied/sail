/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Whether a run's log may be read on this box: the one admission of every log door, the buffered
 * tail and the stream alike, each of which says the answer in its own envelope.
 */
final class LogAdmission {

  private LogAdmission() {}

  /**
   * Run {@code runId}, {@code found}, when {@code actor} may read its log on this box. A run the
   * actor may not read is not there, a run that executed elsewhere ({@link
   * RunStore.RunRow#ownedBy}) is that box's to serve ({@code run_on_other_node}), and the rest is
   * the ownership step ({@link Access#notRunOwner}), its spec's owner as the actor sees it.
   */
  static Result<RunStore.RunRow> of(
      Actor actor,
      Optional<RunStore.RunRow> found,
      String runId,
      String localHandle,
      Function<String, Optional<SpecStore.SpecRow>> specs) {
    var run = ReadFilter.readable(actor, found, RunStore.RunRow::project).orElse(null);
    if (run == null) {
      return Refusals.noRun(runId);
    }
    if (!run.ownedBy(localHandle)) {
      return foreign(run);
    }
    return Access.notRunOwner(actor, run.resource(actor, specs))
        .map(Refusals::<RunStore.RunRow>failure)
        .orElseGet(() -> Result.success(run));
  }

  /**
   * The structured {@code run_on_other_node} refusal: the worst case becomes an honest, actionable
   * no ("logs live on {node}'s box") rather than a foreign box's local file tailed as if it were
   * this run's. The clashing {@code node}/{@code spec}/{@code project} ride as field errors so a
   * client can offer to switch.
   */
  private static <T> Result<T> foreign(RunStore.RunRow run) {
    var node = Strings.isBlank(run.node()) ? "an unknown node" : run.node();
    return Result.failure(
        ErrorCode.RUN_ON_OTHER_NODE,
        "Run " + run.id() + " executed on " + node + "; its logs live there, not on this box.",
        List.of(
            new FieldError("node", Objects.toString(run.node(), "")),
            new FieldError("spec", Objects.toString(run.specId(), "")),
            new FieldError("project", Objects.toString(run.project(), ""))));
  }
}
