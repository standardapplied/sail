/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
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
 * finished, bar a project purged whole ({@link #idle}). An actor scoped to its granted projects
 * erases only inside them: what lies outside is, to it, not there, and a spec with anything
 * belonging to it outside them is not its to erase.
 */
public final class EraseAuthority {

  private final RunStore runs;
  private final SpecStore specs;
  private final Erasure erasure;
  private final ChangeLog changeLog;

  /** The erase rule, deciding on {@code db}'s copy. */
  public EraseAuthority(Sqlite db) {
    this.runs = new RunStore(db);
    this.specs = new SpecStore(db);
    this.erasure = new Erasure(db);
    this.changeLog = new ChangeLog(db);
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
          Remedy.of("Name the specs you own by id: ", Remedy.sail("spec prune", "<id...>"), "."));
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
    if (!sees(actor, type, id)) {
      return unheld(type, id);
    }
    if (erasure.isErased(target)) {
      return Optional.empty();
    }
    if (project) {
      return erasure.holds(target) ? Optional.empty() : unheld(type, id);
    }
    return specs.lastKnown(id).map(spec -> spec(actor, spec)).orElseGet(() -> unheld(type, id));
  }

  /**
   * Whether {@code actor}'s scope admits what this box holds of {@code type} {@code id}, by the
   * project its head carries, which outlives the row and its erasure.
   */
  private boolean sees(Actor actor, String type, String id) {
    var scope = actor.scope();
    return scope.unrestricted() || scope.admits(changeLog.headProject(type, id).orElse(null));
  }

  /**
   * Whether this box holds {@code target} outside {@code actor}'s scope. What it holds no history
   * of — the room a spec would have minted and never did — is nothing an erasure takes from anyone.
   */
  private boolean beyond(Actor actor, Erasure.Target target) {
    return changeLog.head(target.type(), target.id()).isPresent()
        && !sees(actor, target.type(), target.id());
  }

  /**
   * The refusal of a request to erase what main holds nothing of, and, in the same words, what it
   * holds outside the requester's scope.
   */
  private static Optional<Refusal> unheld(String type, String id) {
    return Erasure.PROJECT.equals(type)
        ? Refusal.of(
            Refusal.Kind.NOT_PRUNABLE,
            "main holds no project '" + id + "'",
            "Sync the project before pruning it.")
        : Refusal.of(
            Refusal.Kind.NOT_PRUNABLE,
            "main holds no spec '"
                + id
                + "', so it cannot tell whose it is; sync it before pruning",
            "Sync the spec before pruning it.");
  }

  /**
   * Why {@code actor} may not erase {@code spec}, as this box last knew it. A spec outside the
   * actor's scope is one it cannot find. One inside it is erased with everything that belongs to it
   * ({@link Erasure#closure}), so it is refused whole while any of that — a run, a review, the room
   * it minted, left in the project the spec moved from — is filed outside the scope, and the
   * refusal names none of it.
   */
  public Optional<Refusal> spec(Actor actor, SpecStore.LastKnown spec) {
    var denied = request(actor, false);
    if (denied.isPresent()) {
      return denied;
    }
    if (!sees(actor, Erasure.SPEC, spec.id())) {
      return Refusal.of(
          Refusal.Kind.OUT_OF_SCOPE, "Spec '" + spec.id() + "' was not found.", Remedy.NONE);
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
          Remedy.of(
              "Archive it first: ",
              Remedy.sail("spec update", spec.id(), "--status", "archived"),
              "."));
    }
    if (!actor.scope().unrestricted()
        && erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, spec.id()))).stream()
            .anyMatch(target -> beyond(actor, target))) {
      return Refusal.of(
          Refusal.Kind.NOT_PRUNABLE,
          "Pruning spec '" + spec.id() + "' would erase work outside your project scope.",
          "Ask an admin to prune it.");
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
        Remedy.of("Stop it first: ", Remedy.sail("agent stop"), ", then prune."));
  }
}
