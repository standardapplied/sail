/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.access.Policy.Cell;
import ai.singlr.sail.access.Policy.Column;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.PrincipalHandle;
import ai.singlr.sail.identity.ProjectScope;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The one decision: whether {@code actor} may take {@code action} on {@code resource}, answered the
 * same at every door and sync lane, every refusal worded by {@link Wording}. It reads nothing but
 * its three arguments and {@link Policy}, so a list decides each of its rows from the row in hand.
 *
 * <p>The answer is reached in this order, so one principal hears the same reason everywhere:
 *
 * <ol>
 *   <li>Sail itself (main's decisions as a node adopts them, this box's machinery) is allowed: it
 *       was decided already.
 *   <li>Audience: a resource filed under a project outside the actor's scope is not found. A write
 *       has had this step already, from the project the journal files its row under ({@link
 *       #withinAudience}).
 *   <li>Role: the cell of the actor's role says no: refused, read-only or admin-only; unless the
 *       actor's lane grants the action on its own work, whatever its role, which decides it alone —
 *       a room agent, read-only by construction, posts in its room and narrates its run.
 *   <li>Lane: an agent's lane says no: refused, naming the lane.
 *   <li>Ownership: the role's cell says owner, and the actor neither is an admin nor acts for an
 *       owner; or it starts work in the room a spec was born in ({@link Resource#bornIn}) — creates
 *       or claims the spec there — and does not act for that room's owners: refused, naming the
 *       owners.
 *   <li>Otherwise allowed.
 * </ol>
 *
 * <p>A push over sync is its person's: main decides it by the pusher's role as it commits it, once,
 * and the journal records it without deciding it again.
 */
public final class Access {

  private static final Decision ALLOWED = new Decision.Allowed();
  private static final Decision NOT_FOUND = new Decision.NotFound();
  private static final Set<Action> SEATED = EnumSet.of(Action.CREATE, Action.CLAIM);

  private Access() {}

  /** Whether {@code actor} may take {@code action} on {@code resource}, and if not, why. */
  public static Decision decide(Actor actor, Action action, Resource resource) {
    if (actor.isSail()) {
      return ALLOWED;
    }
    if (resource.kind().filed() && !audience(actor).admits(resource.project())) {
      return NOT_FOUND;
    }
    return answer(steps(actor, action, resource.kind(), resource));
  }

  /**
   * Why {@code actor} may not take {@code action} on {@code resource}, whose audience was decided
   * already: every step of {@link #decide(Actor, Action, Resource)} but the audience. A write's
   * audience is the scope rule's ({@code ScopeRule}), asked first on every lane from the project
   * the journal files the row under, so the write path decides it once, on the one project the
   * row's head and snapshot name, and a write is never not found here.
   */
  public static Optional<Refusal> withinAudience(Actor actor, Action action, Resource resource) {
    return actor.isSail() ? Optional.empty() : steps(actor, action, resource.kind(), resource);
  }

  /**
   * Why {@code actor} may not take {@code action} at all, on any resource of {@code kind} it reads:
   * Sail itself, the role and the lane, as {@link #decide} decides them, for a question asked
   * before there is one resource to read — an erasure asked for before the specs it names are read,
   * or again once nothing of it is left to own, a conflict's resolution. No resource is read, so
   * nothing is not found. Whose the resource is is {@link #decide}'s once the resource is in hand;
   * a lane that grants the action on its own work alone is refused, since no work is in hand to be
   * its own.
   */
  public static Optional<Refusal> atAll(Actor actor, Action action, Resource.Kind kind) {
    return actor.isSail() ? Optional.empty() : steps(actor, action, kind, null);
  }

  /**
   * Why {@code actor} may not write what no one here can own, which {@code fact} says it is not: a
   * conversation this box holds no row of, whose owners it cannot ask, or a post by a credential
   * that acts for no FDE, which would be no one's. What no one here can own is an admin's to write,
   * as the ownership step passes an admin whoever owns what it acts on; everyone else is refused by
   * the fact.
   */
  public static Optional<Refusal> unowned(Actor actor, Refusal fact) {
    return overrulesOwners(actor) ? Optional.empty() : Optional.of(fact);
  }

  /**
   * The ownership step alone: none when {@code actor} overrules owners or acts for one of {@code
   * resource}'s, a refusal naming them otherwise. For the one door that still decides that step
   * itself, a run's log, which the people the run works for read whatever their role, until
   * sail-every-door-asks-access asks {@link Action#OPERATE_RUN} of it.
   */
  public static Optional<Refusal> owned(Actor actor, Resource resource) {
    return owns(actor, resource)
        ? Optional.empty()
        : Optional.of(Wording.notOwner(Action.OPERATE_RUN, resource));
  }

  /**
   * The projects whose resources {@code actor} may read: the audience step of {@link #decide} as a
   * scope, every project for Sail itself. Rows in hand are read through {@link ReadFilter}, and a
   * read that filters many rows inside one query filters by this scope, so the Read rule is the
   * same whichever way it is asked.
   */
  public static ProjectScope audience(Actor actor) {
    return actor.isSail() ? ProjectScope.all() : actor.scope();
  }

  /**
   * The role, lane and ownership steps, of {@code resource}, or of some resource of {@code kind}
   * when it is null: a question of a kind has no work to be the actor's own and no owners to ask.
   */
  private static Optional<Refusal> steps(
      Actor actor, Action action, Resource.Kind kind, Resource resource) {
    var role = Policy.cell(action, Column.role(actor.role()));
    var lane =
        Column.lane(actor.lane()).map(column -> Policy.cell(action, column)).orElse(Cell.YES);
    if (lane == Cell.OWN_WORK) {
      return resource != null && ownWork(actor, resource)
          ? Optional.empty()
          : Optional.of(Wording.outsideWork(actor, action, kind, resource));
    }
    if (role == Cell.NO) {
      return Optional.of(Wording.belowRole(actor, action, kind, resource));
    }
    if (lane == Cell.NO) {
      return Optional.of(Wording.laneForbidden(action, kind));
    }
    if (resource == null) {
      return Optional.empty();
    }
    if (role == Cell.OWNER && !owns(actor, resource)) {
      return Optional.of(Wording.notOwner(action, resource));
    }
    var bornIn = resource.bornIn();
    return bornIn != null && SEATED.contains(action) && !owns(actor, bornIn)
        ? Optional.of(Wording.notSeated(action, bornIn))
        : Optional.empty();
  }

  /**
   * Whether {@code resource} is {@code actor}'s own work. Until an agent's actor carries its work,
   * that is what its credential can show: the run its principal names, and otherwise work owned by
   * the FDE it acts for.
   */
  private static boolean ownWork(Actor actor, Resource resource) {
    return resource.kind() == Resource.Kind.RUN
        ? PrincipalHandle.namesRun(actor.handle(), resource.id())
        : resource.owners().stream().anyMatch(actor::actsFor);
  }

  private static boolean owns(Actor actor, Resource resource) {
    return overrulesOwners(actor) || resource.owners().stream().anyMatch(actor::actsFor);
  }

  /** Whether no owner binds {@code actor}: Sail itself, and an admin. */
  private static boolean overrulesOwners(Actor actor) {
    return actor.isSail() || actor.isAdmin();
  }

  private static Decision answer(Optional<Refusal> refusal) {
    return refusal.<Decision>map(Decision.Refused::new).orElse(ALLOWED);
  }
}
