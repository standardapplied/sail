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
 */
public final class EventAuthority {

  /** What the sender of a publishable type must be able to drive. */
  public enum Rule {
    /**
     * The machinery acts on the event or reads it as evidence. Accepted from an admin; from an
     * actor who acts for an owner of the run it names ({@link RunAuthority#owners}); when it names
     * no run this box holds, from an actor who acts for the owner of the spec ({@link
     * Ownership#ownerOf}) or room ({@link RoomStore#owners}) it names; and from this box's FDE for
     * a run this box executed ({@link RunStore.RunRow#ownedBy}), as the host CLI and the watcher
     * speak. Never from a run's own principal: a stop it published would finish a run still
     * running.
     */
    DRIVES,
    /**
     * As {@link #DRIVES}, and a run's own principal may publish it for that run alone: what the
     * in-container hooks report as the agent works.
     */
    NARRATES,
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
          Map.entry("spec_message_posted", Rule.BOX),
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
   * What an event is about, as this box knows it. A run this box holds decides its own project and
   * conversation, so an event never pairs a run its sender owns with a spec it does not; otherwise
   * the spec or room named decides the project, and {@code runId} is null. {@code owners} are whom
   * a sender must act for: the run's, else the spec's, else the room's, and none for work this box
   * does not hold. {@code executedHere} is whether this box executed the run.
   */
  public record Subject(
      String project,
      String conversation,
      String runId,
      List<String> owners,
      boolean executedHere) {

    public Subject {
      owners = List.copyOf(owners);
    }
  }

  /**
   * The {@link Subject} of an event its sender addressed to {@code project}, {@code conversation}
   * (a spec or room id, null for none) and {@code runId} (null for none).
   */
  public Subject subject(String project, String conversation, String runId) {
    var run = Strings.isBlank(runId) ? Optional.<RunStore.RunRow>empty() : runs.findById(runId);
    if (run.isPresent()) {
      var held = run.get();
      return new Subject(
          held.project(),
          Strings.isBlank(held.conversationId()) ? null : held.conversationId(),
          held.id(),
          RunAuthority.owners(held, this::specOwner),
          held.ownedBy(boxFde.get()));
    }
    if (Strings.isBlank(conversation)) {
      return new Subject(project, null, null, List.of(), false);
    }
    var spec = specs.findById(conversation);
    if (spec.isPresent()) {
      var owner = spec.get().owner();
      return new Subject(
          spec.get().project(),
          conversation,
          null,
          Strings.isBlank(owner) ? List.of() : List.of(owner),
          false);
    }
    return rooms
        .findById(conversation)
        .map(
            room ->
                new Subject(room.project(), conversation, null, rooms.owners(conversation), false))
        .orElseGet(() -> new Subject(project, conversation, null, List.of(), false));
  }

  /**
   * Why {@code actor} may not publish an event of {@code type} about {@code subject}; empty if it
   * may.
   */
  public Optional<Refusal> decide(Actor actor, String type, Subject subject) {
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
    if (rule == Rule.BOX) {
      return box
          ? Optional.empty()
          : Refusal.of(
              Refusal.Kind.NOT_OWNER,
              "Event type '"
                  + type
                  + "' speaks for this box, and you may not drive what it does: only this box's"
                  + " FDE or an admin publishes it.",
              null);
    }
    if (subject.owners().stream().anyMatch(actor::actsFor) || (box && subject.executedHere())) {
      return Optional.empty();
    }
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "Event type '"
            + type
            + "' makes this box act on "
            + described(subject)
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
            + described(subject)
            + ", and '"
            + actor.handle()
            + "' may narrate only its own run.",
        null);
  }

  private Optional<String> specOwner(String specId) {
    return specs.findById(specId).map(SpecStore.SpecRow::owner);
  }

  private static String described(Subject subject) {
    var owned =
        subject.owners().isEmpty()
            ? " (no owner here)"
            : subject.owners().stream()
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
