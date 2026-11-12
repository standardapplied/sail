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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
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
 *       actor's lane grants the action on its own work, whatever its role ({@link Cell#ITS_OWN}),
 *       which decides it alone — a room agent, read-only by construction, posts in its room and
 *       narrates its run.
 *   <li>Lane: the lane of an agent or of the in-container credential says no, or an agent acts
 *       outside its own work ({@link #ownWork}): refused, naming the lane or the work.
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
  private static final Set<Action> WRITES_NOTHING =
      EnumSet.of(Action.READ, Action.READ_DEFINITION, Action.OPERATE_BOX);

  private Access() {}

  /** Whether {@code actor} may take {@code action} on {@code resource}, and if not, why. */
  public static Decision decide(Actor actor, Action action, Resource resource) {
    if (actor.isSail()) {
      return ALLOWED;
    }
    if (resource.kind().filed() && !audience(actor).admits(resource.project())) {
      return NOT_FOUND;
    }
    return Decision.of(steps(actor, action, resource.kind(), resource, resource));
  }

  /**
   * Why {@code actor} may not take {@code action} on {@code resource}, whose audience was decided
   * already: every step of {@link #decide(Actor, Action, Resource)} but the audience, so the
   * resource's project is not read, and a write's rule need not name one. A write's audience is the
   * scope rule's ({@code ScopeRule}), asked first on every lane from the project the journal files
   * the row under, so the write path decides it once, and a write is never not found here.
   */
  public static Optional<Refusal> withinAudience(Actor actor, Action action, Resource resource) {
    return actor.isSail()
        ? Optional.empty()
        : steps(actor, action, resource.kind(), resource, resource);
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
    return actor.isSail() ? Optional.empty() : steps(actor, action, kind, null, null);
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
   * Whether {@code actor}'s role may write at all: whether its column allows any action that
   * changes a row. Reading changes none, and operating a box is its own person's whatever their
   * role. A role that may write nothing is read-only: the role step's refusal says so, and main's
   * sync server neither takes content from such a session nor asks what it was served.
   */
  public static boolean mayWrite(Actor actor) {
    var column = Column.role(actor.role());
    return Arrays.stream(Action.values())
        .filter(action -> !WRITES_NOTHING.contains(action))
        .anyMatch(action -> Policy.cell(action, column) != Cell.NO);
  }

  /**
   * Whether {@code actor} is the principal of {@code resource}, a run: the one run an agent
   * narrates, its own. A rule that takes a narration at its word asks it here, as the lane's grant
   * decides it, rather than reading the actor's handle.
   */
  public static boolean ownRun(Actor actor, Resource resource) {
    return resource.kind() == Resource.Kind.RUN
        && PrincipalHandle.namesRun(actor.handle(), resource.id());
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
   * Why {@code actor} may not take {@code action} at all, as {@link #atAll(Actor, Action,
   * Resource.Kind)} asks it of {@code named}'s kind, its refusal naming {@code named} where its
   * words name what is refused: the role and the lane alone are asked, never whose {@code named}
   * is.
   */
  public static Optional<Refusal> atAll(Actor actor, Action action, Resource named) {
    return actor.isSail() ? Optional.empty() : steps(actor, action, named.kind(), null, named);
  }

  /**
   * The role, lane and ownership steps, of {@code resource}, or of some resource of {@code kind}
   * when it is null: a question of a kind has no work to be the actor's own and no owners to ask. A
   * lane's grant on its own work decides alone, before the role; a lane narrowed to its own work
   * refuses outside it after the role, and inside it the owner still decides. A refusal's words
   * name {@code named}, the resource itself or, for a question of a kind, the one it is asked for.
   */
  private static Optional<Refusal> steps(
      Actor actor, Action action, Resource.Kind kind, Resource resource, Resource named) {
    var role = Policy.cell(action, Column.role(actor.role()));
    var lane =
        Column.lane(actor.lane()).map(column -> Policy.cell(action, column)).orElse(Cell.YES);
    if (lane == Cell.ITS_OWN) {
      return resource != null && ownWork(actor, action, resource)
          ? Optional.empty()
          : Optional.of(Wording.outsideWork(actor, action, kind, named));
    }
    if (role == Cell.NO) {
      return Optional.of(Wording.belowRole(actor, action, kind, named));
    }
    if (lane == Cell.NO) {
      return Optional.of(Wording.laneForbidden(actor, action, kind, named));
    }
    if (lane == Cell.OWN_WORK && (resource == null || !ownWork(actor, action, resource))) {
      return Optional.of(Wording.outsideWork(actor, action, kind, named));
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
   * Whether {@code action} on {@code resource} is within {@code actor}'s own work: the run its
   * principal names, the one thing it narrates; the project of its work; and in that project alone,
   * the spec it works, a spec it made, and for an ad-hoc run any spec; its spec's conversation,
   * addressed by the spec or by its room, the conversation of a spec it made, and for an ad-hoc run
   * any. Creating is filing a spec under its work's project, born in no other room or in its work's
   * room; the one room it creates is the own room of a spec it made, the spec's birth. A spec or
   * conversation filed under another project is no agent's work, whoever made it.
   */
  private static boolean ownWork(Actor actor, Action action, Resource resource) {
    var work = actor.work();
    if (resource.kind() == Resource.Kind.RUN) {
      return ownRun(actor, resource);
    }
    if (work == null || action == Action.NARRATE) {
      return false;
    }
    if (resource.kind() == Resource.Kind.PROJECT) {
      return action != Action.CREATE && Objects.equals(work.project(), resource.id());
    }
    if (!Objects.equals(work.project(), resource.project())) {
      return false;
    }
    var wholeProject = work.spec() == null && work.room() == null;
    var made = resource.madeBy() != null && resource.madeBy().equals(actor.handle());
    return switch (resource.kind()) {
      case SPEC ->
          action == Action.CREATE
              ? resource.bornIn() == null || Objects.equals(work.room(), resource.bornIn().id())
              : wholeProject || made || Objects.equals(work.spec(), resource.id());
      case ROOM ->
          made
              || action != Action.CREATE
                  && (wholeProject
                      || Objects.equals(work.spec(), resource.id())
                      || Objects.equals(work.room(), resource.id()));
      default -> false;
    };
  }

  private static boolean owns(Actor actor, Resource resource) {
    return overrulesOwners(actor) || resource.owners().stream().anyMatch(actor::actsFor);
  }

  /** Whether no owner binds {@code actor}: Sail itself, and an admin. */
  private static boolean overrulesOwners(Actor actor) {
    return actor.isSail() || actor.isAdmin();
  }
}
