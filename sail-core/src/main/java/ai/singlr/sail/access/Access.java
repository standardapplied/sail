/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.access.Policy.Cell;
import ai.singlr.sail.access.Policy.Column;
import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.RunStore;
import java.util.EnumSet;
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
 *       it creates or claims a spec born in a room whose owners it does not act for: refused,
 *       naming the owners.
 *   <li>Otherwise allowed.
 * </ol>
 *
 * <p>A push over sync is its person's: main decides it by the pusher's role as it commits it, once,
 * and the journal records it without deciding it again ({@link WriteAuthority#local}).
 */
public final class Access {

  private static final Decision ALLOWED = new Decision.Allowed();
  private static final Decision NOT_FOUND = new Decision.NotFound();

  private Access() {}

  /** Whether {@code actor} may take {@code action} on {@code resource}, and if not, why. */
  public static Decision decide(Actor actor, Action action, Resource resource) {
    if (WriteAuthority.decided(actor)) {
      return ALLOWED;
    }
    if (resource.kind().filed() && !audience(actor).admits(resource.project())) {
      return NOT_FOUND;
    }
    var role = Policy.cell(action, Column.role(actor.role()));
    var lane =
        Column.lane(actor.lane()).map(column -> Policy.cell(action, column)).orElse(Cell.YES);
    if (lane == Cell.OWN_WORK) {
      return ownWork(actor, resource) ? ALLOWED : refused(outsideWork(actor, action, resource));
    }
    if (role == Cell.NO) {
      return refused(belowRole(actor, action, resource));
    }
    if (lane == Cell.NO) {
      return refused(laneForbidden(action, resource));
    }
    if (EnumSet.of(role, lane).contains(Cell.OWNER) && !owns(actor, resource)) {
      return refused(notOwner(resource));
    }
    if (startsWorkIn(action, resource) && !owns(actor, resource.parent())) {
      return refused(notSeated(resource.parent()));
    }
    return ALLOWED;
  }

  /**
   * The projects whose resources {@code actor} may read: the audience step of {@link #decide} as a
   * scope, every project for Sail itself. A read that filters many rows inside one query, rather
   * than deciding each row in hand, filters by this scope, so the Read rule is the same whichever
   * way it is asked.
   */
  public static ProjectScope audience(Actor actor) {
    return WriteAuthority.decided(actor) ? ProjectScope.all() : actor.scope();
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
   * Whether {@code action} makes the actor a voice in the room {@code resource} was born in: a
   * create there, or a claim of a spec that lives there.
   */
  private static boolean startsWorkIn(Action action, Resource resource) {
    return resource.parent() != null && (action == Action.CREATE || action == Action.CLAIM);
  }

  private static Decision refused(Refusal refusal) {
    return new Decision.Refused(refusal);
  }

  private static String act(Action action, Resource resource) {
    return action.verb() + " " + resource.kind().plural();
  }

  private static Refusal belowRole(Actor actor, Action action, Resource resource) {
    return actor.canWrite()
        ? adminOnly(action, resource)
        : Refusal.readOnly(act(action, resource)).orElseThrow();
  }

  private static Refusal adminOnly(Action action, Resource resource) {
    return switch (action) {
      case ASSIGN ->
          new Refusal(
              Refusal.Kind.ADMIN_ONLY,
              "Reassigning spec '"
                  + resource.id()
                  + "' moves work between FDEs and is an admin-only action"
                  + (resource.assignee() != null
                      ? " (currently '" + resource.assignee() + "')"
                      : "")
                  + ".",
              "Ask an admin to reassign it. You may grab a spec only while it is unassigned.");
      case PURGE ->
          new Refusal(
              Refusal.Kind.ADMIN_ONLY,
              "Pruning by policy or a whole project is admin-only.",
              Remedy.of(
                  "Name the specs you own by id: ", Remedy.sail("spec prune", "<id...>"), "."));
      default ->
          new Refusal(
              Refusal.Kind.ADMIN_ONLY,
              "Only an admin may " + act(action, resource) + ".",
              "Ask an admin to do it.");
    };
  }

  private static Refusal laneForbidden(Action action, Resource resource) {
    return new Refusal(
        Refusal.Kind.LANE_FORBIDDEN,
        "Agent principals cannot " + act(action, resource) + ".",
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

  private static Refusal notSeated(Resource room) {
    var id = room.id();
    if (room.owners().isEmpty()) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "No one owns '" + id + "' yet, so only an admin may start work there.",
          "Have an admin create or assign the spec.");
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
        "Ask " + named + " to create it there, or have an admin do it.");
  }

  private static Refusal notOwner(Resource resource) {
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
