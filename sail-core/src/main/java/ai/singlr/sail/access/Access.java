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
 *   <li>Audience: a resource filed under a project outside the actor's scope is not found.
 *   <li>Role: the cell of the actor's role says no: refused, read-only or admin-only.
 *   <li>Lane: an agent's lane says no: refused, naming the lane. An agent's own work is its lane's
 *       to grant, whatever its role: a room agent, read-only by construction, posts in its room and
 *       narrates its run.
 *   <li>Ownership: the role's cell says owner, and the actor neither overrules owners ({@link
 *       #overrulesOwners}) nor acts for an owner; or it starts work in the room a spec was born in
 *       ({@link Resource#bornIn}) — creates or claims the spec there — and does not act for that
 *       room's owners: refused, naming the owners.
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
    if (lane(actor, action) == Cell.OWN_WORK) {
      return ownWork(actor, resource)
          ? ALLOWED
          : refused(Wording.outsideWork(actor, action, resource));
    }
    if (role(actor, action) == Cell.NO) {
      return refused(Wording.belowRole(actor, action, resource));
    }
    if (lane(actor, action) == Cell.NO) {
      return refused(Wording.laneForbidden(action, resource.kind()));
    }
    if (role(actor, action) == Cell.OWNER && !owns(actor, resource)) {
      return refused(Wording.notOwner(action, resource));
    }
    var bornIn = resource.bornIn();
    if (bornIn != null && SEATED.contains(action) && !owns(actor, bornIn)) {
      return refused(Wording.notSeated(action, bornIn));
    }
    return ALLOWED;
  }

  /**
   * Whether {@code actor} may take {@code action} at all, on some resource of {@code kind} it
   * reads: Sail itself, the role and the lane, as {@link #decide(Actor, Action, Resource)} decides
   * them, for a question asked before there is one resource to read — an erasure asked for before
   * the specs it names are read, or again once nothing of it is left to own — or where a revision
   * names none. Whose the resource is is that decision's once the resource is in hand.
   *
   * @throws IllegalArgumentException for an action the actor's lane grants on its own work alone,
   *     which only the work itself can decide
   */
  public static Decision decide(Actor actor, Action action, Resource.Kind kind) {
    if (actor.isSail()) {
      return ALLOWED;
    }
    if (lane(actor, action) == Cell.OWN_WORK) {
      throw new IllegalArgumentException(
          action + " is " + actor.lane() + "'s on its own work alone, which a kind does not name.");
    }
    if (role(actor, action) == Cell.NO) {
      return refused(Wording.belowRole(actor, action, kind));
    }
    return lane(actor, action) == Cell.NO ? refused(Wording.laneForbidden(action, kind)) : ALLOWED;
  }

  /**
   * The ownership step alone: allowed when {@code actor} overrules owners or acts for one of {@code
   * resource}'s, refused naming them otherwise. For the one door that still decides that step
   * itself, a run's log, which the people the run works for read whatever their role, until
   * sail-every-door-asks-access asks {@link Action#OPERATE_RUN} of it.
   */
  public static Decision owned(Actor actor, Resource resource) {
    return owns(actor, resource)
        ? ALLOWED
        : refused(Wording.notOwner(Action.OPERATE_RUN, resource));
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
   * Whether no owner binds {@code actor}, so the ownership step passes it whoever owns what it acts
   * on: Sail itself, and an admin. A rule's facts that stand where no owner can be asked — a
   * conversation this box does not hold, a post that would be no one's, a room a spec's id would
   * take from its owner — bind everyone else.
   */
  public static boolean overrulesOwners(Actor actor) {
    return actor.isSail() || actor.isAdmin();
  }

  /** What the column of {@code actor}'s role allows of {@code action}. */
  private static Cell role(Actor actor, Action action) {
    return Policy.cell(action, Column.role(actor.role()));
  }

  /** What {@code actor}'s lane allows of {@code action}: a person's lane narrows nothing. */
  private static Cell lane(Actor actor, Action action) {
    return Column.lane(actor.lane()).map(column -> Policy.cell(action, column)).orElse(Cell.YES);
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

  private static Decision refused(Refusal refusal) {
    return new Decision.Refused(refusal);
  }
}
