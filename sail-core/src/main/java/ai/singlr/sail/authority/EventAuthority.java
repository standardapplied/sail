/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
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

/**
 * Which events a client may publish: the one rule every door that takes an event asks, as the actor
 * bound there, before the event reaches this box's bus. The bus's subscribers act on every event as
 * this box's machinery, which the write rules let through, so this is where the machinery is held
 * to moving, launching, notifying and reading as evidence only what the event's sender may drive.
 *
 * <p>Deny by default: a type {@link #publishable} does not list is the server's own, emitted after
 * the write it describes, and no client publishes it. Each listed type has one {@link Rule}, read
 * against the event's {@link Subject}: what the event is about as this box knows it, never as its
 * sender says.
 *
 * <p>Two kinds of sender speak about work. This box's FDE, whom the host CLI and the watcher act
 * as, reports what this box itself did and observed. Anyone else retells what another box did,
 * which is how main's sync server relays a node's transitions, as the FDE who pushed them: an event
 * marked as retold starts no review and finishes no run here. So a member is never taken at their
 * word for what this box observed, and a run another box executed is never a key to the spec it
 * names: its pusher wrote that row, and only the spec's own owner speaks for the spec.
 */
public final class EventAuthority {

  /** What the sender of a publishable type must be able to drive. */
  public enum Rule {
    /**
     * The machinery acts on the event or reads it as evidence. Accepted from an admin; from this
     * box's FDE for a run this box executed ({@link RunStore.RunRow#ownedBy}), as the host CLI and
     * the watcher speak, or for a spec or room it owns; and, retold, from an actor who acts for the
     * owner of the spec ({@link Ownership#ownerOf}) or room ({@link RoomStore#owners}) it names, or
     * that the run it names works. A run that works no spec or room is its own owners' ({@link
     * RunAuthority#owners}). Never from a run's own principal: a stop it published would finish a
     * run still running.
     */
    DRIVES,
    /**
     * What a run does as it works, said on the box that executes it: accepted from an admin, from
     * this box's FDE for a run this box executed, and from the run's own principal for that run
     * alone, which is how the in-container hooks report.
     */
    NARRATES,
    /**
     * A message this box holds, announced so its room wakes and live clients see it: accepted from
     * an admin, from this box's FDE for what its sync pulled, and, retold, from an actor who acts
     * for an owner of the conversation the message is in ({@link RoomStore#owners}), who may post
     * there ({@link PostingRule}): wider than who drives a spec, since the owner of a spec born in
     * its room has a voice there.
     */
    ANNOUNCES,
    /**
     * The event speaks for this box rather than for a piece of work: accepted from an admin or this
     * box's FDE.
     */
    BOX
  }

  private static final Map<String, Rule> PUBLISHABLE =
      Map.ofEntries(
          Map.entry("agent_session_started", Rule.NARRATES),
          Map.entry("agent_stop_nudged", Rule.NARRATES),
          Map.entry("agent_tool_started", Rule.NARRATES),
          Map.entry("agent_tool_finished", Rule.NARRATES),
          Map.entry("agent_session_stopped", Rule.DRIVES),
          Map.entry("agent_cancelled", Rule.DRIVES),
          Map.entry("agent_failed", Rule.DRIVES),
          Map.entry("spec_dispatched", Rule.DRIVES),
          Map.entry("spec_restarted", Rule.DRIVES),
          Map.entry("review_iteration_started", Rule.DRIVES),
          Map.entry("review_stage_started", Rule.DRIVES),
          Map.entry("review_stage_passed", Rule.DRIVES),
          Map.entry("review_stage_failed", Rule.DRIVES),
          Map.entry("review_completed", Rule.DRIVES),
          Map.entry("review_errored", Rule.DRIVES),
          Map.entry("review_escalated", Rule.DRIVES),
          Map.entry("spec_message_posted", Rule.ANNOUNCES),
          Map.entry("snapshot_created", Rule.BOX),
          Map.entry("board_updated", Rule.BOX));

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

  /** Every type a client may publish, each with the rule its sender is held to. */
  public static Map<String, Rule> publishable() {
    return PUBLISHABLE;
  }

  /**
   * What an event is about, as this box knows it. A run this box holds decides its conversation, so
   * an event never pairs a run its sender owns with a spec it does not; the spec or room, worked by
   * the run or named directly, decides the project, and a run that works neither its own. {@code
   * runId} is null when no run this box holds is named. {@code owners} are who drives it, whom a
   * sender who is not this box's FDE must act for: the owners of the spec or room, named directly
   * or worked by the run; for a run that works neither, the run's own; and none for work this box
   * does not hold. {@code voices} are who may post in its conversation ({@link RoomStore#owners}),
   * wider than who drives it, whom an announcement is held to. {@code executedHere} is whether this
   * box executed the run.
   */
  public record Subject(
      String project,
      String conversation,
      String runId,
      List<String> owners,
      List<String> voices,
      boolean executedHere) {

    public Subject {
      owners = List.copyOf(owners);
      voices = List.copyOf(voices);
    }
  }

  /**
   * The {@link Subject} of an event its sender addressed to {@code project}, {@code conversation}
   * (a spec or room id, null for none) and {@code runId} (null for none).
   */
  public Subject subject(String project, String conversation, String runId) {
    var run = Strings.isBlank(runId) ? Optional.<RunStore.RunRow>empty() : runs.findById(runId);
    if (run.isEmpty()) {
      return about(project, conversation);
    }
    var held = run.get();
    var worked = about(held.project(), held.conversationId());
    return new Subject(
        worked.project(),
        worked.conversation(),
        held.id(),
        worked.conversation() == null
            ? RunAuthority.owners(held, this::specOwner)
            : worked.owners(),
        worked.voices(),
        held.ownedBy(boxFde.get()));
  }

  private Subject about(String project, String conversation) {
    if (Strings.isBlank(conversation)) {
      return new Subject(project, null, null, List.of(), List.of(), false);
    }
    var voices = rooms.owners(conversation);
    var spec = specs.findById(conversation);
    if (spec.isPresent()) {
      var owner = spec.get().owner();
      return new Subject(
          spec.get().project(),
          conversation,
          null,
          Strings.isBlank(owner) ? List.of() : List.of(owner),
          voices,
          false);
    }
    return rooms
        .findById(conversation)
        .map(room -> new Subject(room.project(), conversation, null, voices, voices, false))
        .orElseGet(() -> new Subject(project, conversation, null, List.of(), voices, false));
  }

  /**
   * Why {@code actor} may not publish an event of {@code type} about {@code subject}; empty if it
   * may. {@code retold} is whether the event says it retells what another box did rather than
   * reporting what this box observed.
   */
  public Optional<Refusal> decide(Actor actor, String type, Subject subject, boolean retold) {
    var rule = PUBLISHABLE.get(type);
    if (rule == null) {
      return Refusal.of(
          Refusal.Kind.NOT_PUBLISHABLE,
          "Event type '"
              + type
              + "' is not one a client may publish: the server emits its own events after the"
              + " writes they describe.",
          "Make the change through its own route; the server publishes the event.");
    }
    if (actor.agentLane()) {
      return principal(actor, type, rule, subject);
    }
    if (!actor.canWrite()) {
      return Refusal.readOnly("publish events");
    }
    if (actor.isAdmin()) {
      return Optional.empty();
    }
    var box = actor.actsFor(boxFde.get());
    var owners = rule == Rule.ANNOUNCES ? subject.voices() : subject.owners();
    var owner = owners.stream().anyMatch(actor::actsFor);
    var admitted =
        switch (rule) {
          case BOX -> box;
          case NARRATES -> box && subject.executedHere();
          case ANNOUNCES -> box || (owner && retold);
          case DRIVES -> subject.executedHere() ? box : owner && (box || retold);
        };
    if (admitted) {
      return Optional.empty();
    }
    if (rule == Rule.BOX) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "Event type '"
              + type
              + "' speaks for this box, and you may not drive what it does: only this box's FDE or"
              + " an admin publishes it.",
          null);
    }
    if (owner || subject.executedHere() || rule == Rule.NARRATES) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "Event type '"
              + type
              + "' reports what this box observed of "
              + described(subject, owners)
              + ", which you may not drive: only this box's FDE or an admin reports that.",
          "What another box did reaches this one by sync.");
    }
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "Event type '"
            + type
            + "' makes this box act on "
            + described(subject, owners)
            + ", which you may not drive.",
        "Only its owner or an admin may publish it.");
  }

  private static Optional<Refusal> principal(Actor actor, String type, Rule rule, Subject subject) {
    if (rule != Rule.NARRATES) {
      return Refusal.of(
          Refusal.Kind.NOT_PUBLISHABLE,
          "Event type '" + type + "' is not available to agent principals.",
          null);
    }
    if (RunStore.namesRun(actor.handle(), subject.runId())) {
      return Optional.empty();
    }
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "Event type '"
            + type
            + "' narrates "
            + described(subject, subject.owners())
            + ", and '"
            + actor.handle()
            + "' may narrate only its own run.",
        null);
  }

  private Optional<String> specOwner(String specId) {
    return specs.findById(specId).map(SpecStore.SpecRow::owner);
  }

  private static String described(Subject subject, List<String> owners) {
    var owned =
        owners.isEmpty()
            ? " (no owner here)"
            : owners.stream()
                .map(owner -> "'" + owner + "'")
                .collect(Collectors.joining(" and ", " (owned by ", ")"));
    if (subject.runId() != null) {
      return "run "
          + subject.runId()
          + (subject.conversation() == null ? "" : " of '" + subject.conversation() + "'")
          + owned;
    }
    if (subject.conversation() != null) {
      return "'" + subject.conversation() + "'" + owned;
    }
    return "work it does not name";
  }
}
