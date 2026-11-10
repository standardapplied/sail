/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
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
 * ask, each on its own copy. A spec is pruned ({@link Action#PRUNE}) by its owner ({@link
 * SpecStore.LastKnown#owner}) or an admin, once it is archived, cancelled or deleted; a whole
 * project, or a policy's sweep, is purged ({@link Action#PURGE}), whoever's work is in it. Nothing
 * is erased while a run in it has not finished, bar a project purged whole ({@link #idle}). What
 * lies outside the actor's audience is, to it, not there, and a spec with anything belonging to it
 * outside is not its to erase.
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
   * Why {@code actor} may not erase at all, asked before what it erases is read: whether its role
   * and lane may prune specs, or, for a {@code wholesale} erasure — a whole project, or a policy's
   * sweep — purge ({@link Access#atAll}). A prune by id is a {@link Decision} ({@link #prune}),
   * since the spec it names may not be found; main's answer to a node's request ({@link
   * #requested}) and the wait for a run ({@link #idle}) are worded refusals.
   */
  public Optional<Refusal> request(Actor actor, boolean wholesale) {
    return Access.atAll(
        actor,
        wholesale ? Action.PURGE : Action.PRUNE,
        wholesale ? Resource.Kind.PROJECT : Resource.Kind.SPEC);
  }

  /**
   * Why main refuses a node's request, made as {@code actor}, to erase {@code type} {@code id},
   * decided on main's own copy. Only specs and projects are erased on request. What main holds
   * nothing of, or holds outside the actor's audience, has no owner main can establish. A spec is
   * decided as a local prune decides it ({@link #prune}).
   */
  public Optional<Refusal> requested(Actor actor, String type, String id) {
    if (Erasure.SPEC.equals(type)) {
      return switch (prune(actor, id)) {
        case Decision.Allowed allowed -> Optional.empty();
        case Decision.NotFound absent -> unheld(type, id);
        case Decision.Refused refused -> Optional.of(refused.refusal());
      };
    }
    if (!Erasure.PROJECT.equals(type)) {
      return request(actor, false)
          .or(
              () ->
                  Refusal.of(
                      Refusal.Kind.NOT_PRUNABLE,
                      "only specs and projects are pruned on request, not a " + type,
                      "Prune the spec or project it belongs to."));
    }
    var target = new Erasure.Target(type, id);
    return sees(actor, type, id) && (erasure.isErased(target) || erasure.holds(target))
        ? request(actor, true)
        : unheld(type, id);
  }

  /**
   * Whether {@code actor} may prune spec {@code id}, one decision on what this box holds of it. One
   * it holds no history of, or whose head is filed outside the actor's audience, is not found. One
   * it erased already leaves nothing to own, so only whether the actor may prune at all is asked
   * ({@link Access#atAll}): pruning it again is nothing to do. Any other is pruned by its owner or
   * an admin, as this box last knew it ({@link #spec}).
   */
  public Decision prune(Actor actor, String id) {
    if (erasure.isErased(new Erasure.Target(Erasure.SPEC, id))) {
      return sees(actor, Erasure.SPEC, id)
          ? Access.atAll(actor, Action.PRUNE, Resource.Kind.SPEC)
              .<Decision>map(Decision.Refused::new)
              .orElseGet(Decision.Allowed::new)
          : new Decision.NotFound();
    }
    return specs.lastKnown(id).map(spec -> spec(actor, spec)).orElseGet(Decision.NotFound::new);
  }

  /**
   * Whether {@code actor} reads what this box holds of {@code type} {@code id}, by the project its
   * head carries, which outlives the row and its erasure.
   */
  private boolean sees(Actor actor, String type, String id) {
    return ReadFilter.reads(actor, () -> changeLog.headProject(type, id).orElse(null));
  }

  /**
   * Whether this box holds {@code target} outside {@code actor}'s audience. What it holds no
   * history of — the room a spec would have minted and never did — is nothing an erasure takes from
   * anyone.
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
   * Whether {@code actor} may prune {@code spec}, as this box last knew it: {@link Access}'s
   * decision on {@link Action#PRUNE}, and then the rule's facts. One it may is erased with
   * everything that belongs to it ({@link Erasure#closure}), so it is refused whole while it is
   * still on the board, or while any of that — a run, a review, the room it minted, left in the
   * project the spec moved from — lies outside the actor's audience, and the refusal names none of
   * it.
   */
  Decision spec(Actor actor, SpecStore.LastKnown spec) {
    var decision =
        Access.decide(
            actor,
            Action.PRUNE,
            Resources.spec(
                spec.id(),
                changeLog.headProject(Erasure.SPEC, spec.id()).orElse(null),
                spec.assignee(),
                spec.createdBy()));
    if (!(decision instanceof Decision.Allowed)) {
      return decision;
    }
    if (!spec.prunable()) {
      return refused(
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
    if (erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, spec.id()))).stream()
        .anyMatch(target -> beyond(actor, target))) {
      return refused(
          "Pruning spec '" + spec.id() + "' would erase work outside your project scope.",
          Remedy.of("Ask an admin to prune it."));
    }
    return decision;
  }

  private static Decision refused(String message, Remedy fix) {
    return new Decision.Refused(new Refusal(Refusal.Kind.NOT_PRUNABLE, message, fix));
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
