/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.access.Policy.Cell;
import ai.singlr.sail.access.Policy.Column;
import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.RunStore;
import java.util.EnumSet;
import java.util.Set;
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
 *   <li>Ownership: the cell says owner, and the actor neither is an admin nor acts for an owner; or
 *       it starts work in the room a spec was born in ({@link Resource#parent}) — creates or claims
 *       the spec there, or seats an agent there for it — and does not act for that room's owners:
 *       refused, naming the owners.
 *   <li>Otherwise allowed.
 * </ol>
 *
 * <p>A push over sync is its person's: main decides it by the pusher's role as it commits it, once,
 * and the journal records it without deciding it again.
 */
public final class Access {

  private static final Decision ALLOWED = new Decision.Allowed();
  private static final Decision NOT_FOUND = new Decision.NotFound();
  private static final Set<Action> SEATED =
      EnumSet.of(Action.CREATE, Action.CLAIM, Action.DISPATCH);

  private Access() {}

  /** Whether {@code actor} may take {@code action} on {@code resource}, and if not, why. */
  public static Decision decide(Actor actor, Action action, Resource resource) {
    if (actor.isSail()) {
      return ALLOWED;
    }
    if (resource.kind().filed() && !audience(actor).admits(resource.project())) {
      return NOT_FOUND;
    }
    var role = Policy.cell(action, Column.role(actor.role()));
    var lane = lane(actor, action);
    if (lane == Cell.OWN_WORK) {
      return ownWork(actor, resource) ? ALLOWED : refused(outsideWork(actor, action, resource));
    }
    if (role == Cell.NO) {
      return refused(belowRole(actor, action, resource));
    }
    if (lane == Cell.NO) {
      return refused(laneForbidden(action, resource.kind()));
    }
    if (EnumSet.of(role, lane).contains(Cell.OWNER) && !owns(actor, resource)) {
      return refused(notOwner(resource));
    }
    if (startsWorkIn(action, resource) && !owns(actor, resource.parent())) {
      return refused(notSeated(action, resource.parent()));
    }
    return ALLOWED;
  }

  /**
   * Whether {@code actor} may take {@code action} at all, on some resource of {@code kind} it
   * reads: Sail itself, the role and the lane, as {@link #decide(Actor, Action, Resource)} decides
   * them, for a question asked before there is one resource to read — an erasure asked for before
   * the specs it names are read, or again once nothing of it is left to own. Whose the resource is,
   * and whether it is an agent's own work, is that decision's once the resource is in hand.
   */
  public static Decision decide(Actor actor, Action action, Resource.Kind kind) {
    if (actor.isSail()) {
      return ALLOWED;
    }
    var lane = lane(actor, action);
    if (lane == Cell.OWN_WORK) {
      return ALLOWED;
    }
    if (Policy.cell(action, Column.role(actor.role())) == Cell.NO) {
      return refused(belowRole(actor, action, kind));
    }
    return lane == Cell.NO ? refused(laneForbidden(action, kind)) : ALLOWED;
  }

  /** What {@code actor}'s lane allows of {@code action}: a person's lane narrows nothing. */
  private static Cell lane(Actor actor, Action action) {
    return Column.lane(actor.lane()).map(column -> Policy.cell(action, column)).orElse(Cell.YES);
  }

  /**
   * The projects whose resources {@code actor} may read: the audience step of {@link #decide} as a
   * scope, every project for Sail itself. A read that filters many rows inside one query, rather
   * than deciding each row in hand, filters by this scope, so the Read rule is the same whichever
   * way it is asked.
   */
  public static ProjectScope audience(Actor actor) {
    return actor.isSail() ? ProjectScope.all() : actor.scope();
  }

  /**
   * Whether {@code actor} may read row {@code id} of {@code kind}, filed under the project {@code
   * project} names: {@link Action#READ} of the row as it is filed ({@link Resources#filed}). The
   * project is asked only when the actor's audience is restricted: working it out may cost a query,
   * and no project hides a row from an unrestricted reader, so a list read by one adds no query per
   * row.
   */
  public static boolean reads(
      Actor actor, Resource.Kind kind, String id, Supplier<String> project) {
    var filed = audience(actor).unrestricted() ? null : project.get();
    return decide(actor, Action.READ, Resources.filed(kind, id, filed)) instanceof Decision.Allowed;
  }

  /**
   * Whether {@code resource} is {@code actor}'s own work. Until an agent's actor carries its work,
   * that is what its credential can show: the run its principal names, and otherwise work owned by
   * the FDE it acts for.
   */
  private static boolean ownWork(Actor actor, Resource resource) {
    return resource.kind() == Resource.Kind.RUN
        ? RunStore.namesRun(actor.handle(), resource.id())
        : resource.owners().stream().anyMatch(actor::actsFor);
  }

  private static boolean owns(Actor actor, Resource resource) {
    return actor.isAdmin() || resource.owners().stream().anyMatch(actor::actsFor);
  }

  /**
   * Whether {@code action} starts work in the room {@code resource} was born in: a create there, a
   * claim of a spec that lives there, which give the actor a voice in it, or a dispatch that seats
   * an agent there, which changes its roster.
   */
  private static boolean startsWorkIn(Action action, Resource resource) {
    return resource.parent() != null && SEATED.contains(action);
  }

  private static Decision refused(Refusal refusal) {
    return new Decision.Refused(refusal);
  }

  private static String act(Action action, Resource.Kind kind) {
    return action.verb() + " " + kind.plural();
  }

  private static Refusal belowRole(Actor actor, Action action, Resource resource) {
    if (!actor.canWrite()) {
      return belowRole(actor, action, resource.kind());
    }
    return switch (action) {
      case ASSIGN -> notReassignable(resource);
      case PURGE -> notPurgeable(resource);
      default -> adminOnly(action, resource.kind());
    };
  }

  private static Refusal belowRole(Actor actor, Action action, Resource.Kind kind) {
    return actor.canWrite()
        ? adminOnly(action, kind)
        : Refusal.readOnly(act(action, kind)).orElseThrow();
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
   * The refusal of a purge asked before there is one resource to name: a prune by policy, or of a
   * whole project, whose fix is the prune a member may make.
   */
  private static Refusal adminOnly(Action action, Resource.Kind kind) {
    return action == Action.PURGE
        ? new Refusal(
            Refusal.Kind.ADMIN_ONLY,
            "Pruning by policy or a whole project is admin-only.",
            Remedy.of("Name the specs you own by id: ", Remedy.sail("spec prune", "<id...>"), "."))
        : new Refusal(
            Refusal.Kind.ADMIN_ONLY,
            "Only an admin may " + act(action, kind) + ".",
            "Ask an admin to do it.");
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
        "Agent principals cannot " + act(action, kind) + ".",
        "Only an operator lane (CLI or API token) may do this.");
  }

  private static Refusal outsideWork(Actor actor, Action action, Resource resource) {
    if (action == Action.POST) {
      return notPosting(resource);
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

  private static Refusal notPosting(Resource conversation) {
    var id = conversation.id();
    if (conversation.owners().isEmpty()) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "No one owns '" + id + "' yet, so only an admin may post there.",
          "Claim its spec first with --assignee <you>, or have an admin post.");
    }
    var named = String.join(" or ", conversation.owners());
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        "'"
            + id
            + "' belongs to "
            + named
            + ": only "
            + named
            + " or an admin may post there, not you.",
        "Ask " + named + " to post it, or have an admin do it.");
  }

  private static Refusal notSeated(Action action, Resource room) {
    var id = room.id();
    if (room.owners().isEmpty()) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "No one owns '" + id + "' yet, so only an admin may start work there.",
          action == Action.DISPATCH
              ? "Have an admin seat it."
              : "Have an admin create or assign the spec.");
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
            + " or an admin may start work there, not you.",
        "Ask "
            + named
            + (action == Action.DISPATCH ? " to seat it there" : " to create it there")
            + ", or have an admin do it.");
  }

  /**
   * The refusal of an action only {@code resource}'s owners or an admin may take, naming them: the
   * ownership step's wording, for a door that still decides that step itself.
   */
  public static Refusal notOwner(Resource resource) {
    return switch (resource.kind()) {
      case SPEC -> notSpecOwner(resource);
      case RUN -> notRunOwner(resource);
      case REVIEW -> notReviewOwner(resource);
      default -> notTheOwner(resource);
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

  private static Refusal notRunOwner(Resource run) {
    var owners = run.owners();
    var owned =
        owners.isEmpty()
            ? null
            : owners.stream().map(owner -> "'" + owner + "'").collect(Collectors.joining(" and "));
    var spec = specOf(run);
    var message =
        spec == null
            ? "Run "
                + run.id()
                + " is an ad-hoc session"
                + (owned != null ? " launched by " + owned + ", not you." : ".")
            : "Run "
                + run.id()
                + " belongs to spec '"
                + spec
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
            + specOf(review)
            + "'"
            + (owner != null ? ", owned by '" + owner + "', not you." : ", which has no owner."),
        "Only "
            + (owner != null ? owner : "the spec's owner")
            + " or an admin may approve or dismiss it.");
  }

  private static Refusal notTheOwner(Resource resource) {
    var noun = resource.kind().title() + " '" + resource.id() + "'";
    if (resource.owners().isEmpty()) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          noun + " has no owner, so only an admin may change it.",
          "Have an admin change it.");
    }
    var owners = resource.owners();
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        noun
            + " belongs to "
            + owners.stream().map(owner -> "'" + owner + "'").collect(Collectors.joining(" or "))
            + ", not you.",
        "Ask " + String.join(" or ", owners) + " to change it, or have an admin do it.");
  }

  private static String specOf(Resource resource) {
    return resource.parent() == null ? null : resource.parent().id();
  }
}
