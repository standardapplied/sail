/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.access.Policy.Cell;
import ai.singlr.sail.access.Policy.Column;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.PrincipalHandle;
import ai.singlr.sail.identity.ProjectScope;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The one decision: whether {@code actor} may take {@code action} on {@code resource}, answered the
 * same at every door and sync lane, and the one place every refusal is worded. It reads nothing but
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
      return ownWork(actor, resource) ? ALLOWED : refused(outsideWork(actor, action, resource));
    }
    var capped = capped(actor, action, resource.kind(), () -> adminOnly(action, resource));
    if (capped.isPresent()) {
      return refused(capped.get());
    }
    if (role(actor, action) == Cell.OWNER && !owns(actor, resource)) {
      return refused(notOwner(action, resource));
    }
    var bornIn = resource.bornIn();
    if (bornIn != null && SEATED.contains(action) && !owns(actor, bornIn)) {
      return refused(notSeated(action, bornIn));
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
    return capped(actor, action, kind, () -> adminOnly(action, kind))
        .map(Access::refused)
        .orElse(ALLOWED);
  }

  /**
   * The ownership step alone: allowed when {@code actor} overrules owners or acts for one of {@code
   * resource}'s, refused naming them otherwise. For the one door that still decides that step
   * itself, a run's log, which the people the run works for read whatever their role, until
   * sail-every-door-asks-access asks {@link Action#OPERATE_RUN} of it.
   */
  public static Decision owned(Actor actor, Resource resource) {
    return owns(actor, resource) ? ALLOWED : refused(notOwner(Action.OPERATE_RUN, resource));
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
   * The role and lane steps: why {@code actor}'s role, then its lane, refuses {@code action} on a
   * resource of {@code kind}, a writer's role in the words {@code adminOnly} gives it.
   */
  private static Optional<Refusal> capped(
      Actor actor, Action action, Resource.Kind kind, Supplier<Refusal> adminOnly) {
    if (role(actor, action) == Cell.NO) {
      return actor.canWrite() ? Optional.of(adminOnly.get()) : Refusal.readOnly(action.act(kind));
    }
    return lane(actor, action) == Cell.NO
        ? Optional.of(laneForbidden(action, kind))
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

  private static Decision refused(Refusal refusal) {
    return new Decision.Refused(refusal);
  }

  /** A writer's admin-only refusal of {@code action} on {@code resource}, naming it. */
  private static Refusal adminOnly(Action action, Resource resource) {
    return switch (action) {
      case ASSIGN -> notReassignable(resource);
      case PURGE -> notPurgeable(resource);
      default -> adminOnly(action, resource.kind());
    };
  }

  /**
   * A writer's admin-only refusal of {@code action} on some resource of {@code kind}. A purge asked
   * before there is one project to name is a prune by policy or of a whole project, whose fix is
   * the prune a member may make; a purge of a named project is {@link #notPurgeable}.
   */
  private static Refusal adminOnly(Action action, Resource.Kind kind) {
    return action == Action.PURGE
        ? new Refusal(
            Refusal.Kind.ADMIN_ONLY,
            "Pruning by policy or a whole project is admin-only.",
            Remedy.of("Name the specs you own by id: ", Remedy.sail("spec prune", "<id...>"), "."))
        : new Refusal(
            Refusal.Kind.ADMIN_ONLY,
            "Only an admin may " + action.act(kind) + ".",
            "Ask an admin to do it.");
  }

  private static Refusal notReassignable(Resource spec) {
    return new Refusal(
        Refusal.Kind.ADMIN_ONLY,
        "Reassigning spec '"
            + spec.id()
            + "' moves work between FDEs and is an admin-only action"
            + (spec.assignee() != null ? " (currently '" + spec.assignee() + "')" : "")
            + ".",
        "Ask an admin to reassign it. You may grab a spec only while it is unassigned.");
  }

  /**
   * The refusal of a purge of {@code project} by name: purging it and renaming it alike erase its
   * name everywhere, whoever's work is filed under it.
   */
  private static Refusal notPurgeable(Resource project) {
    return new Refusal(
        Refusal.Kind.ADMIN_ONLY,
        "Only an admin may erase the name of project '"
            + project.id()
            + "' everywhere, as purging or renaming it does.",
        "Ask an admin to do it.");
  }

  private static Refusal laneForbidden(Action action, Resource.Kind kind) {
    return new Refusal(
        Refusal.Kind.LANE_FORBIDDEN,
        "Agent principals cannot " + action.act(kind) + ".",
        "Only an operator lane (CLI or API token) may do this.");
  }

  private static Refusal outsideWork(Actor actor, Action action, Resource resource) {
    if (action == Action.POST) {
      return ownedBy(
          resource,
          "post",
          "Claim its spec first with --assignee <you>, or have an admin post.",
          named -> "Ask " + named + " to post it, or have an admin do it.");
    }
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        "'"
            + actor.handle()
            + "' may "
            + action.verb()
            + " only its own work, not "
            + resource.kind().noun()
            + " '"
            + resource.id()
            + "'.",
        "An agent acts only on the work sail launched it for.");
  }

  /**
   * The refusal of a create or a claim of a spec born in {@code room} by one who does not act for
   * its owners: a create is theirs to make there, and a claim, which would make the claimer one of
   * them, an admin's to grant as an assignment.
   */
  private static Refusal notSeated(Action action, Resource room) {
    return ownedBy(
        room,
        "start work",
        "Have an admin create or assign the spec.",
        action == Action.CLAIM
            ? named -> "Ask an admin to assign it to you."
            : named -> "Ask " + named + " to create it there, or have an admin do it.");
  }

  /**
   * The refusal of {@code doing} ("post") in conversation {@code room} by one who acts for none of
   * its owners, naming them: {@code unowned} is the fix where it has none, {@code owned} the fix
   * naming them.
   */
  private static Refusal ownedBy(
      Resource room, String doing, String unowned, Function<String, String> owned) {
    var id = room.id();
    if (room.owners().isEmpty()) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "No one owns '" + id + "' yet, so only an admin may " + doing + " there.",
          unowned);
    }
    var named = String.join(" or ", room.owners());
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        "'"
            + id
            + "' belongs to "
            + named
            + ": only "
            + named
            + " or an admin may "
            + doing
            + " there, not you.",
        owned.apply(named));
  }

  /** The ownership step's refusal of {@code action} on {@code resource}, naming its owners. */
  private static Refusal notOwner(Action action, Resource resource) {
    return switch (resource.kind()) {
      case SPEC -> action == Action.DISPATCH ? notDispatcher(resource) : notSpecOwner(resource);
      case RUN -> notRunOwner(resource);
      case REVIEW -> notReviewOwner(resource);
      default -> notTheOwner(action, resource);
    };
  }

  private static Refusal notSpecOwner(Resource spec) {
    var id = spec.id();
    if (spec.assignee() != null) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "Spec '" + id + "' is assigned to '" + spec.assignee() + "', not you.",
          "Ask " + spec.assignee() + " to make this change, or have an admin do it.");
    }
    var creator = spec.owners().isEmpty() ? "its creator" : spec.owners().getFirst();
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        "Spec '" + id + "' is unassigned; only " + creator + " or an admin may change it.",
        "Have an admin change it, or claim it first with --assignee <you>.");
  }

  /**
   * The refusal of a dispatch of {@code spec} by someone it is not assigned to: an assigned spec is
   * its assignee's to dispatch, and an unassigned one no one's until it is claimed.
   */
  private static Refusal notDispatcher(Resource spec) {
    var id = spec.id();
    return spec.assignee() != null
        ? new Refusal(
            Refusal.Kind.NOT_OWNER,
            "Spec '" + id + "' is assigned to '" + spec.assignee() + "', not you.",
            "Ask "
                + spec.assignee()
                + " to dispatch it, or have an admin reassign it before dispatching.")
        : new Refusal(
            Refusal.Kind.NOT_OWNER,
            "Spec '" + id + "' is unassigned, so no one may dispatch it until it is claimed.",
            "Claim it first with --assignee <you>, then dispatch it.");
  }

  private static Refusal notRunOwner(Resource run) {
    var owners = run.owners();
    var owned =
        owners.isEmpty()
            ? null
            : owners.stream().map(owner -> "'" + owner + "'").collect(Collectors.joining(" and "));
    var message =
        run.spec() == null
            ? "Run "
                + run.id()
                + " is an ad-hoc session"
                + (owned != null ? " launched by " + owned + ", not you." : ".")
            : "Run "
                + run.id()
                + " belongs to spec '"
                + run.spec()
                + "'"
                + (owned != null ? ", owned by " + owned + ", not you." : ", which has no owner.");
    var named = owners.isEmpty() ? "its owner" : String.join(" or ", owners);
    return new Refusal(
        Refusal.Kind.NOT_OWNER, message, "Only " + named + " or an admin may access this run.");
  }

  private static Refusal notReviewOwner(Resource review) {
    var owner = review.owners().isEmpty() ? null : review.owners().getFirst();
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        "Review "
            + review.id()
            + " is for spec '"
            + review.spec()
            + "'"
            + (owner != null ? ", owned by '" + owner + "', not you." : ", which has no owner."),
        "Only "
            + (owner != null ? owner : "the spec's owner")
            + " or an admin may approve or dismiss it.");
  }

  /**
   * The refusal of {@code action} on a room or a box by one who acts for none of its owners, in
   * what the action does to it: operating a box, and changing a room, its roster included.
   */
  private static Refusal notTheOwner(Action action, Resource resource) {
    var doing = action == Action.OPERATE_BOX ? "operate it" : "change it";
    var owners = resource.owners();
    if (owners.isEmpty()) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          resource.named() + " has no owner, so only an admin may " + doing + ".",
          "Have an admin " + doing + ".");
    }
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        resource.named()
            + " belongs to "
            + owners.stream().map(owner -> "'" + owner + "'").collect(Collectors.joining(" or "))
            + ", not you.",
        "Ask " + String.join(" or ", owners) + " to " + doing + ", or have an admin do it.");
  }
}
