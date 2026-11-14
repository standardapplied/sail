/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What {@link Access} says when a step refuses, in the words a person reads. The sentences a rule
 * outside this package says as well live in {@link Refusal}: read-only ({@link Refusal#readOnly})
 * and a conversation this box does not hold ({@link Refusal#noConversation}). Owners are joined as
 * each sentence has always joined them: a conversation's and a run's fix bare with "or", a run's
 * message quoted with "and", a room's or a box's message quoted with "or".
 */
final class Wording {

  private Wording() {}

  /**
   * The role step's refusal of {@code action} on {@code resource}, or on some resource of {@code
   * kind} when it is null: read-only for a credential that writes nothing, admin-only else, naming
   * the spec a reassignment would move and the project a purge would erase when one is in hand.
   */
  static Refusal belowRole(Actor actor, Action action, Resource.Kind kind, Resource resource) {
    if (!Access.mayWrite(actor)) {
      return Refusal.readOnly(action.act(kind));
    }
    if (resource != null && action == Action.ASSIGN) {
      return notReassignable(resource);
    }
    return resource != null && action == Action.PURGE
        ? notPurgeable(resource)
        : adminOnly(action, kind);
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

  /**
   * The lane step's refusal of {@code action} on {@code resource}, or on some resource of {@code
   * kind} when it is null, in the words of {@code actor}'s lane: the in-container credential is
   * told the door outside the container that takes it, and an agent's move of a spec's status, or a
   * spec it bears ready, what an agent may make instead.
   */
  static Refusal laneForbidden(Actor actor, Action action, Resource.Kind kind, Resource resource) {
    if (actor.lane() == Actor.Lane.CONTAINER) {
      return new Refusal(
          Refusal.Kind.LANE_FORBIDDEN,
          "The credential in a project container cannot " + action.act(kind) + ".",
          fromOutside(action, kind, resource));
    }
    if (action == Action.APPROVE && kind == Resource.Kind.SPEC) {
      return new Refusal(
          Refusal.Kind.LANE_FORBIDDEN,
          "An agent creates only drafts and never sets a status.",
          "Omit --status; its person promotes it on the board.");
    }
    return new Refusal(
        Refusal.Kind.LANE_FORBIDDEN,
        "Agent principals cannot " + action.act(kind) + ".",
        "Only an operator lane (CLI or API token) may do this.");
  }

  /**
   * Where a person takes {@code action}, which the credential in a project container may not: on
   * the host, naming the command for a spec's status or assignee, and for a project's skills both
   * sharing one and removing one, since a refusal on the project cannot tell which was asked; or in
   * Mast. The command is rendered as words, not kept as a command a door renders for its reader:
   * the reader is in the container, and the command runs on the host.
   */
  private static Remedy fromOutside(Action action, Resource.Kind kind, Resource resource) {
    var id = resource == null ? "<id>" : resource.id();
    var commands =
        switch (action) {
          case APPROVE ->
              kind == Resource.Kind.SPEC
                  ? List.of(Remedy.sail("spec update", id, "--status", "<status>"))
                  : List.<Remedy.Command>of();
          case ASSIGN -> List.of(Remedy.sail("spec update", id, "--assignee", "<fde>"));
          case SHARE ->
              List.of(
                  StageSkill.shareFromContainer("<folder>", id), StageSkill.unshare("<skill>", id));
          case KEEP_LOGINS -> List.of(Remedy.sail("agent login", "<harness>"));
          default -> List.<Remedy.Command>of();
        };
    return Remedy.of(
        commands.isEmpty()
            ? "Do it from outside the container: with sail on the host, or in Mast."
            : "Do it from outside the container: "
                + commands.stream()
                    .map(command -> "'" + Remedy.of(command).plain() + "'")
                    .collect(Collectors.joining(" or "))
                + " on the host, or Mast.");
  }

  /**
   * The refusal of {@code action}, which {@code actor}'s lane grants on its own work alone, on
   * {@code resource}, or on some resource of {@code kind} when it is null, which names no work to
   * be its own: a conflict, which no agent resolves, its person's to settle. Work it may not touch
   * it may draft a follow-up spec for; a run it may not narrate is simply not its own.
   */
  static Refusal outsideWork(Actor actor, Action action, Resource.Kind kind, Resource resource) {
    var fix =
        "An agent acts only on the work sail launched it for"
            + (action == Action.NARRATE ? "." : "; draft a follow-up spec for anything else.");
    if (resource == null) {
      return kind == Resource.Kind.CONFLICT
          ? new Refusal(
              Refusal.Kind.NOT_OWNER,
              "'" + actor.handle() + "' resolves no conflicts: an agent acts only on its own work.",
              "Ask the FDE the agent works for to resolve it.")
          : new Refusal(
              Refusal.Kind.NOT_OWNER,
              "'"
                  + actor.handle()
                  + "' acts only on its own work, and no "
                  + kind.noun()
                  + " of it is named.",
              fix);
    }
    if (action == Action.POST) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "'" + actor.handle() + "' may post only in its own work, not in '" + resource.id() + "'.",
          fix);
    }
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        "'"
            + actor.handle()
            + "' may "
            + action.verb()
            + " only its own work, not "
            + kind.noun()
            + " '"
            + resource.id()
            + "'.",
        fix);
  }

  /**
   * The refusal of a create or a claim of a spec born in {@code room} by one who does not act for
   * its owners: a create is theirs to make there, and a claim, which would make the claimer one of
   * them, an admin's to grant as an assignment, so it is admin-only.
   */
  static Refusal notSeated(Action action, Resource room) {
    if (action != Action.CLAIM) {
      return ownedBy(
          room,
          "start work",
          "Have an admin create or assign the spec.",
          named -> "Ask " + named + " to create it there, or have an admin do it.");
    }
    return new Refusal(
        Refusal.Kind.ADMIN_ONLY,
        ownedMessage(room, "start work"),
        "Ask an admin to assign it to you.");
  }

  /**
   * The refusal of {@code doing} ("post") in conversation {@code room} by one who acts for none of
   * its owners, naming them: {@code unowned} is the fix where it has none, {@code owned} the fix
   * naming them.
   */
  private static Refusal ownedBy(
      Resource room, String doing, String unowned, Function<String, String> owned) {
    var named = String.join(" or ", room.owners());
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        ownedMessage(room, doing),
        room.owners().isEmpty() ? unowned : owned.apply(named));
  }

  /** What {@code doing} in {@code room} is refused as: its owners', naming them, or an admin's. */
  private static String ownedMessage(Resource room, String doing) {
    var id = room.id();
    if (room.owners().isEmpty()) {
      return "No one owns '" + id + "' yet, so only an admin may " + doing + " there.";
    }
    var named = String.join(" or ", room.owners());
    return "'"
        + id
        + "' belongs to "
        + named
        + ": only "
        + named
        + " or an admin may "
        + doing
        + " there, not you.";
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
          assignedElsewhere(spec),
          "Ask " + spec.assignee() + " to make this change, or have an admin do it.");
    }
    var creator = spec.owners().isEmpty() ? "its creator" : spec.owners().getFirst();
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        "Spec '" + id + "' is unassigned; only " + creator + " or an admin may change it.",
        "Have an admin change it, or claim it first with --assignee <you>.");
  }

  /**
   * The refusal of a dispatch of {@code spec}, an assigned one, by someone it is not assigned to.
   */
  private static Refusal notDispatcher(Resource spec) {
    return new Refusal(
        Refusal.Kind.NOT_OWNER,
        assignedElsewhere(spec),
        "Ask "
            + spec.assignee()
            + " to dispatch it, or have an admin reassign it before dispatching.");
  }

  /** What a spec assigned to someone else says to one who is not them. */
  private static String assignedElsewhere(Resource spec) {
    return "Spec '" + spec.id() + "' is assigned to '" + spec.assignee() + "', not you.";
  }

  private static Refusal notRunOwner(Resource run) {
    var owners = run.owners();
    if (run.spec() == null && owners.isEmpty()) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "Run " + run.id() + " names no spec and no one it acts for.",
          "Only an admin may access this run.");
    }
    var owned =
        owners.isEmpty()
            ? null
            : owners.stream().map(owner -> "'" + owner + "'").collect(Collectors.joining(" and "));
    var message =
        run.spec() == null
            ? "Run " + run.id() + " is an ad-hoc session launched by " + owned + ", not you."
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
    if (review.spec() == null) {
      return new Refusal(
          Refusal.Kind.NOT_OWNER,
          "Review " + review.id() + " names no spec, so it has no owner.",
          "Only an admin may approve or dismiss it.");
    }
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
