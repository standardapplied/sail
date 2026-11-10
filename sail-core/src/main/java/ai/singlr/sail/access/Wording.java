/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Actor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one place a permission refusal is worded: what {@link Access} says when a step refuses, in
 * the words a person reads. Owners are joined as each sentence has always joined them: a
 * conversation's and a run's fix bare with "or", a run's message quoted with "and", a room's or a
 * box's message quoted with "or".
 */
final class Wording {

  private Wording() {}

  /** The role step's refusal: read-only for a credential that writes nothing, admin-only else. */
  static Refusal belowRole(Actor actor, Action action, Resource resource) {
    if (!actor.canWrite()) {
      return Refusal.readOnly(action.act(resource.kind())).orElseThrow();
    }
    return switch (action) {
      case ASSIGN -> notReassignable(resource);
      case PURGE -> notPurgeable(resource);
      default -> adminOnly(action, resource.kind());
    };
  }

  /** As {@link #belowRole(Actor, Action, Resource)}, asked of a kind before one row is in hand. */
  static Refusal belowRole(Actor actor, Action action, Resource.Kind kind) {
    return actor.canWrite()
        ? adminOnly(action, kind)
        : Refusal.readOnly(action.act(kind)).orElseThrow();
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

  static Refusal laneForbidden(Action action, Resource.Kind kind) {
    return new Refusal(
        Refusal.Kind.LANE_FORBIDDEN,
        "Agent principals cannot " + action.act(kind) + ".",
        "Only an operator lane (CLI or API token) may do this.");
  }

  static Refusal outsideWork(Actor actor, Action action, Resource resource) {
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
  static Refusal notSeated(Action action, Resource room) {
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
  static Refusal notOwner(Action action, Resource resource) {
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
        : Refusal.unclaimed(id);
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
