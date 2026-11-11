/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which events a client may publish: the one rule every door that takes an event asks, as the actor
 * bound there, before the event reaches this box's bus. The bus's subscribers act on every event as
 * this box's machinery, which the write rules let through, so this is where the machinery is held
 * to moving, launching, notifying and reading as evidence only what the event's sender may drive.
 *
 * <p>Deny by default: a type {@link #publishable} does not list is the server's own, emitted after
 * the write it describes, and no client publishes it. Each listed type names the one {@link Action}
 * its sender must be allowed ({@link Access#decide}) on what the event is about, its {@link
 * Subject}, as this box knows it, never as its sender says: a type the machinery acts on needs
 * operating the run, dispatching or approving; an announcement of a message, posting; an event
 * about the box, operating it; a run's report of itself, narrating it, which a person may only for
 * a run this box executed.
 *
 * <p>Two kinds of sender speak about work, and which one an event's sender is stays this rule's own
 * fact. Whoever speaks for this box ({@link Action#OPERATE_BOX}), its FDE whom the host CLI and the
 * watcher act as, or an admin, reports what this box itself did and observed. Anyone else retells
 * what another box did, which is how main's sync server relays a node's transitions, as the FDE who
 * pushed them: an event marked as retold starts no review and finishes no run here. So a member is
 * never taken at their word for what this box observed, and a run another box executed is never a
 * key to the spec it names: its pusher wrote that row, and only the spec's own owner speaks for the
 * spec. A run's own principal publishes only the narration of its own run, a fact of its lane until
 * its actor carries its work (sail-agents-act-on-their-own-work). Every refusal names its fix: who
 * may publish the event, to ask.
 */
public final class EventAuthority {

  private static final Map<String, Action> PUBLISHABLE =
      Map.ofEntries(
          Map.entry("agent_session_started", Action.NARRATE),
          Map.entry("agent_stop_nudged", Action.NARRATE),
          Map.entry("agent_tool_started", Action.NARRATE),
          Map.entry("agent_tool_finished", Action.NARRATE),
          Map.entry("agent_session_stopped", Action.OPERATE_RUN),
          Map.entry("agent_cancelled", Action.OPERATE_RUN),
          Map.entry("agent_failed", Action.OPERATE_RUN),
          Map.entry("spec_dispatched", Action.DISPATCH),
          Map.entry("spec_restarted", Action.DISPATCH),
          Map.entry("review_iteration_started", Action.APPROVE),
          Map.entry("review_stage_started", Action.APPROVE),
          Map.entry("review_stage_passed", Action.APPROVE),
          Map.entry("review_stage_failed", Action.APPROVE),
          Map.entry("review_completed", Action.APPROVE),
          Map.entry("review_errored", Action.APPROVE),
          Map.entry("review_escalated", Action.APPROVE),
          Map.entry("spec_message_posted", Action.POST),
          Map.entry("snapshot_created", Action.OPERATE_BOX),
          Map.entry("board_updated", Action.OPERATE_BOX));

  private final SpecStore specs;
  private final RoomStore rooms;
  private final RunStore runs;
  private final Supplier<String> boxFde;

  /** The rule, deciding on {@code db}'s copy for the box whose FDE handle {@code boxFde} reads. */
  public EventAuthority(Sqlite db, Supplier<String> boxFde) {
    this.specs = new SpecStore(db);
    this.rooms = new RoomStore(db);
    this.runs = new RunStore(db);
    this.boxFde = Objects.requireNonNull(boxFde, "boxFde");
  }

  /** Every type a client may publish, each with the action its sender must be allowed. */
  public static Map<String, Action> publishable() {
    return PUBLISHABLE;
  }

  /**
   * What an event is about, as this box knows it. A run this box holds decides its conversation, so
   * an event never pairs a run its sender owns with a spec it does not; the spec or room, worked by
   * the run or named directly, decides the project, and a run that works neither its own. {@code
   * runId} is null when no run this box holds is named. {@code owners} are who drives it, whom a
   * sender who is not this box's FDE must act for: the owners of the spec or room, named directly
   * or worked by the run; for a run that works neither, the run's own; and none for work this box
   * does not hold. {@code executedHere} is whether this box executed the run, and {@code
   * executedOn} the box that did, null when no run is named or it names none.
   */
  public record Subject(
      String project,
      String conversation,
      String runId,
      List<String> owners,
      boolean executedHere,
      String executedOn) {

    public Subject {
      owners = List.copyOf(owners);
    }

    /** Whether the event names a run or a conversation at all. */
    boolean namesWork() {
      return runId != null || conversation != null;
    }
  }

  /**
   * The {@link Subject} of an event its sender addressed to {@code project}, {@code conversation}
   * (a spec or room id, null for none) and {@code runId} (null for none). An event about a run is
   * of the project the run is filed under, where its owner reads it, though its spec has moved
   * since; the spec still decides whose it is.
   */
  public Subject subject(String project, String conversation, String runId) {
    var run = Strings.isBlank(runId) ? Optional.<RunStore.RunRow>empty() : runs.findById(runId);
    if (run.isEmpty()) {
      return about(project, conversation);
    }
    var held = run.get();
    var worked = about(held.project(), held.conversationId());
    return new Subject(
        held.project(),
        worked.conversation(),
        held.id(),
        worked.conversation() == null
            ? held.resource(Actor.system(), specs::findById).owners()
            : worked.owners(),
        held.ownedBy(boxFde.get()),
        held.node());
  }

  private Subject about(String project, String conversation) {
    if (Strings.isBlank(conversation)) {
      return new Subject(project, null, null, List.of(), false, null);
    }
    var spec = specs.findById(conversation);
    if (spec.isPresent()) {
      var row = spec.get();
      return new Subject(
          row.project(),
          conversation,
          null,
          Stream.ofNullable(row.owner()).filter(Strings::isNotBlank).toList(),
          false,
          null);
    }
    return rooms
        .findById(conversation)
        .map(
            room ->
                new Subject(
                    room.project(), conversation, null, rooms.owners(conversation), false, null))
        .orElseGet(() -> new Subject(project, conversation, null, List.of(), false, null));
  }

  /**
   * Why {@code actor} may not publish an event of {@code type} about {@code subject}; empty if it
   * may. {@code retold} is whether the event says it retells what another box did rather than
   * reporting what this box observed.
   */
  public Optional<Refusal> decide(Actor actor, String type, Subject subject, boolean retold) {
    var action = PUBLISHABLE.get(type);
    if (action == null) {
      return Refusal.of(
          Refusal.Kind.NOT_PUBLISHABLE,
          "Event type '"
              + type
              + "' is not one a client may publish: the server emits its own events after the"
              + " writes they describe.",
          "Make the change through its own route; the server publishes the event.");
    }
    if (actor.agentLane()) {
      return principal(actor, type, action, subject);
    }
    return switch (Access.decide(actor, action, resource(action, subject))) {
      case Decision.Allowed allowed ->
          believed(actor, action, subject, retold)
              ? Optional.empty()
              : refused(Refusal.Kind.NOT_OWNER, actor, type, action, subject);
      case Decision.Refused refused ->
          refused(refused.refusal().kind(), actor, type, action, subject);
      case Decision.NotFound notFound -> unseen(type, subject);
    };
  }

  /**
   * What {@code action} is taken on when an event is about {@code subject}. The box, for an event
   * that speaks for it. Otherwise the work it names, answering to who drives it here: a run this
   * box executed is also this box's; and a run's narration is a person's only on the box that
   * executed it.
   */
  private Resource resource(Action action, Subject subject) {
    var executed =
        subject.executedHere()
            ? Stream.ofNullable(boxFde.get()).filter(Strings::isNotBlank).toList()
            : List.<String>of();
    return switch (action) {
      case OPERATE_BOX -> Resources.box(boxFde.get());
      case NARRATE -> work(subject, executed);
      default ->
          work(subject, Stream.concat(subject.owners().stream(), executed.stream()).toList());
    };
  }

  /**
   * {@code subject}'s work, filed under its project and answering to {@code owners}: the
   * conversation the run works or the event names. The decision reads its project and owners alone,
   * and the refusal is this rule's, so nothing a sender sees rests on whether that conversation is
   * a spec's or a room's.
   */
  private static Resource work(Subject subject, List<String> owners) {
    return Resources.conversation(subject.conversation(), subject.project(), owners);
  }

  /**
   * Whether a sender {@link Access} allows is taken at its word: what an event the machinery acts
   * on reports, only whoever speaks for this box reports first-hand, and anyone else only retells,
   * never what this box executed itself; an announcement is retold by a voice, about a
   * conversation; a person's narration, an admin's included, only of a run this box executed, since
   * what another box's run did reaches this one by sync. An event about the box is decided by the
   * action alone.
   */
  private boolean believed(Actor actor, Action action, Subject subject, boolean retold) {
    var firstHand = speaksForBox(actor);
    return switch (action) {
      case OPERATE_BOX -> true;
      case NARRATE -> subject.executedHere();
      case POST ->
          firstHand
              || (retold
                  && Strings.isNotBlank(actor.actingFde())
                  && subject.conversation() != null);
      default -> firstHand || (retold && !subject.executedHere());
    };
  }

  /**
   * The refusal, of the {@code kind} it was decided as, worded as an event's: only this rule knows
   * the event's type and what it would make this box do, as only a door knows its answer for an
   * absent id. A read-only sender may publish nothing. Otherwise the event speaks for this box,
   * which only its FDE or an admin does; or it reports what this box observed of work the sender
   * owns, that this box executed, or that a run narrates, which only they report first-hand; or it
   * makes this box act on work the sender may not drive.
   */
  private static Optional<Refusal> refused(
      Refusal.Kind kind, Actor actor, String type, Action action, Subject subject) {
    if (kind == Refusal.Kind.READ_ONLY) {
      return Optional.of(Refusal.readOnly("publish events"));
    }
    if (action == Action.OPERATE_BOX) {
      return Refusal.of(
          kind,
          "Event type '"
              + type
              + "' speaks for this box, and you may not drive what it does: only this box's FDE or"
              + " an admin publishes it.",
          "Ask this box's FDE or an admin to publish it.");
    }
    if (action == Action.NARRATE && !subject.executedHere()) {
      return Refusal.of(
          kind,
          "Event type '"
              + type
              + "' reports what another box observed of "
              + described(subject)
              + ": only the box that executed it reports that.",
          Strings.isBlank(subject.executedOn())
              ? "What another box did reaches this one by sync."
              : "Publish it from "
                  + subject.executedOn()
                  + ", which executed it; what it reports reaches this box by sync.");
    }
    if (action == Action.NARRATE
        || subject.executedHere()
        || subject.owners().stream().anyMatch(actor::actsFor)) {
      return Refusal.of(
          kind,
          "Event type '"
              + type
              + "' reports what this box observed of "
              + described(subject)
              + ", which you may not drive: only this box's FDE or an admin reports that.",
          "What another box did reaches this one by sync.");
    }
    return Refusal.of(
        kind,
        "Event type '"
            + type
            + "' makes this box act on "
            + described(subject)
            + ", which you may not drive.",
        "Only its owner or an admin may publish it.");
  }

  /** Whether {@code actor} speaks for this box: its FDE, or an admin. */
  private boolean speaksForBox(Actor actor) {
    return Access.decide(actor, Action.OPERATE_BOX, Resources.box(boxFde.get()))
        instanceof Decision.Allowed;
  }

  /**
   * The answer for an event about work its sender may not read: as for work this box does not hold,
   * so it says nothing of whose it is.
   */
  private static Optional<Refusal> unseen(String type, Subject subject) {
    return Refusal.of(
        Refusal.Kind.OUT_OF_SCOPE,
        "Event type '"
            + type
            + (subject.namesWork()
                ? "' names " + named(subject) + ", which this box does not hold."
                : "' names no work this box holds."),
        "Publish events only about work in the projects you are granted.");
  }

  private Optional<Refusal> principal(Actor actor, String type, Action action, Subject subject) {
    if (action != Action.NARRATE) {
      return Refusal.of(
          Refusal.Kind.NOT_PUBLISHABLE,
          "Event type '" + type + "' is not available to agent principals.",
          "Ask the FDE the agent works for, or an admin, to publish it.");
    }
    var work =
        Optional.ofNullable(subject.runId())
            .flatMap(runs::findById)
            .map(run -> run.resource(Actor.system(), specs::findById))
            .orElseGet(() -> work(subject, List.of()));
    return switch (Access.decide(actor, Action.NARRATE, work)) {
      case Decision.Allowed allowed -> Optional.empty();
      case Decision.NotFound notFound -> unseen(type, subject);
      case Decision.Refused refused ->
          Refusal.of(
              Refusal.Kind.NOT_OWNER,
              "Event type '"
                  + type
                  + "' narrates "
                  + described(subject)
                  + ", and '"
                  + actor.handle()
                  + "' may narrate only its own run.",
              refused.refusal().fix());
    };
  }

  private static String described(Subject subject) {
    if (!subject.namesWork()) {
      return named(subject);
    }
    var owners = subject.owners();
    return named(subject)
        + (owners.isEmpty()
            ? " (no owner here)"
            : owners.stream()
                .map(owner -> "'" + owner + "'")
                .collect(Collectors.joining(" and ", " (owned by ", ")")));
  }

  private static String named(Subject subject) {
    if (subject.runId() != null) {
      return "run "
          + subject.runId()
          + (subject.conversation() == null ? "" : " of '" + subject.conversation() + "'");
    }
    if (subject.conversation() != null) {
      return "'" + subject.conversation() + "'";
    }
    return "work it does not name";
  }
}
