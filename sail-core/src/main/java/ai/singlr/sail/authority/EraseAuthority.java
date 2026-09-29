/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Optional;

/**
 * Who may erase — the one rule a local prune and main's decision on a node's request to erase both
 * ask, each on its own copy. A read-only role erases nothing. A whole project, or a policy's sweep,
 * is an admin's alone. A spec is erased by its owner ({@link SpecStore.LastKnown#owner}) or an
 * admin, once it is archived, cancelled or deleted. Nothing is erased while a run in it has not
 * finished.
 */
public final class EraseAuthority {

  private final RunStore runs;

  public EraseAuthority(Sqlite db) {
    this.runs = new RunStore(db);
  }

  /**
   * Why {@code actor} may not erase at all: a read-only role never may, and a {@code wholesale}
   * erasure — a whole project, or a policy's sweep — is admin-only.
   */
  public Optional<Refusal> request(Actor actor, boolean wholesale) {
    if (!actor.canWrite()) {
      return Refusal.of(
          Refusal.Kind.READ_ONLY,
          "Your role is read-only: it cannot prune.",
          "Ask an admin to prune, or for a member role.");
    }
    if (wholesale && !actor.isAdmin()) {
      return Refusal.of(
          Refusal.Kind.ADMIN_ONLY,
          "Pruning by policy or a whole project is admin-only.",
          "Name the specs you own by id: sail spec prune <id...>.");
    }
    return Optional.empty();
  }

  /** Why {@code actor} may not erase {@code spec}, as this box last knew it. */
  public Optional<Refusal> spec(Actor actor, SpecStore.LastKnown spec) {
    var denied = request(actor, false);
    if (denied.isPresent()) {
      return denied;
    }
    if (!actor.isAdmin() && !actor.actsFor(spec.owner())) {
      return SpecAuthority.notOwner(spec.id(), spec.assignee(), spec.createdBy());
    }
    if (!spec.prunable()) {
      return Refusal.of(
          Refusal.Kind.NOT_PRUNABLE,
          "Spec '"
              + spec.id()
              + "' is "
              + spec.status().wire()
              + ": only archived, cancelled or deleted specs are pruned.",
          "Archive it first: sail spec update " + spec.id() + " --status archived.");
    }
    return Optional.empty();
  }

  /** Why {@code plan} may not be erased yet: a run in it has not finished. */
  public Optional<Refusal> idle(List<Erasure.Target> plan) {
    var unfinished =
        runs.unfinished(
            plan.stream()
                .filter(target -> Erasure.RUN.equals(target.type()))
                .map(Erasure.Target::id)
                .toList());
    if (unfinished.isEmpty()) {
      return Optional.empty();
    }
    return Refusal.of(
        Refusal.Kind.NOT_PRUNABLE,
        "Run '" + unfinished.getFirst() + "' has not finished; a prune never erases work going on.",
        "Stop it first: sail agent stop, then prune.");
  }
}
