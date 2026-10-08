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
 * finished, bar a project purged whole ({@link #idle}).
 */
public final class EraseAuthority {

  private final RunStore runs;
  private final SpecStore specs;
  private final Erasure erasure;

  /** The erase rule, deciding on {@code db}'s copy. */
  public EraseAuthority(Sqlite db) {
    this.runs = new RunStore(db);
    this.specs = new SpecStore(db);
    this.erasure = new Erasure(db);
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

  /**
   * Why main refuses a node's request, made as {@code actor}, to erase {@code type} {@code id},
   * decided on main's own copy. Only specs and projects are erased on request. One main has already
   * erased has nothing left to protect, so asking again only answers the erasure it has; one main
   * holds nothing of has no owner main can establish.
   */
  public Optional<Refusal> requested(Actor actor, String type, String id) {
    var project = Erasure.PROJECT.equals(type);
    var denied = request(actor, project);
    if (denied.isPresent()) {
      return denied;
    }
    if (!Erasure.SPEC.equals(type) && !project) {
      return Refusal.of(
          Refusal.Kind.NOT_PRUNABLE,
          "only specs and projects are pruned on request, not a " + type,
          "Prune the spec or project it belongs to.");
    }
    var target = new Erasure.Target(type, id);
    if (erasure.isErased(target)) {
      return Optional.empty();
    }
    if (project) {
      return erasure.holds(target)
          ? Optional.empty()
          : Refusal.of(
              Refusal.Kind.NOT_PRUNABLE,
              "main holds no project '" + id + "'",
              "Sync the project before pruning it.");
    }
    return specs
        .lastKnown(id)
        .map(spec -> spec(actor, spec))
        .orElseGet(
            () ->
                Refusal.of(
                    Refusal.Kind.NOT_PRUNABLE,
                    "main holds no spec '"
                        + id
                        + "', so it cannot tell whose it is; sync it before pruning",
                    "Sync the spec before pruning it."));
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

  /**
   * Why {@code plan}, an erasure's closure, may not be erased yet: a run in it has not finished. A
   * project is purged whole, its runs with it whether finished or not — it is purged once its
   * container is gone, so nothing in it is going on — and a purge never waits.
   */
  public Optional<Refusal> idle(List<Erasure.Target> plan) {
    if (plan.stream().anyMatch(target -> Erasure.PROJECT.equals(target.type()))) {
      return Optional.empty();
    }
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
