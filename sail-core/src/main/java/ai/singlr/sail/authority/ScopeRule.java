/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Snapshots;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What a write of one synced type answers when it reaches outside its actor's audience, and what a
 * birth may name. Whether a project is inside the audience is the read filter's to answer ({@link
 * ReadFilter}): the state a write puts must belong to a project the actor reads, and what it writes
 * over must be filed under one. A deletion names no project of its own and is decided by what it
 * deletes. A row is born naming only rows inside the audience too ({@link #bornBeyond}): the type's
 * rule reads who owns the conversation a spec is born in and the work a run does, and would say so.
 * Asked ahead of the type's own rule on every lane ({@link #before}), so an actor learns nothing of
 * a row outside its audience but that its id is taken — ids are one namespace, and a spec's id is
 * reserved for its own room — or that the project a write names was not found.
 */
public final class ScopeRule {

  private static final Remedy GRANT =
      Remedy.of(
          "Ask an admin on main for the project: ",
          Remedy.sail("fde grant", "<handle>", "<project>"),
          ".");
  private static final String SPEC = "spec";
  private static final String ROOM = "room";
  private static final String RUN = "run";
  private static final String MESSAGE = "message";

  /**
   * The rows a state is born naming beside its project, by the field naming each and the type it
   * names. A review's spec is not here: it gives the review its project, which is asked already. A
   * message's conversation is, under either key a message names it by, since a reply takes the
   * project of the message it answers, wherever its room has moved since.
   */
  private static final Map<String, Map<String, String>> NAMED =
      Map.of(
          SPEC,
          Map.of("room_id", ROOM),
          RUN,
          Map.of("spec_id", SPEC, "room_id", ROOM),
          MESSAGE,
          Map.of("room_id", ROOM, "spec_id", ROOM));

  private final ChangeLog changeLog;
  private final String type;

  /** The rule for {@code type}, reading parents' projects from {@code changeLog}'s heads. */
  public ScopeRule(ChangeLog changeLog, String type) {
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.type = Objects.requireNonNull(type, "type");
  }

  /** {@code rule}, asked only for a write this rule finds inside its actor's audience. */
  public WriteAuthority before(WriteAuthority rule) {
    return (actor, id, held, next) -> {
      var outside = decide(actor, id, held, next);
      return outside.isPresent() ? outside : rule.decide(actor, id, held, next);
    };
  }

  /**
   * Why {@code actor} may not write {@code next} over {@code held} as {@code id}: one of the two
   * lies outside its audience. Empty when both lie inside. What it writes over is asked first, and
   * answered only as a taken id; a project the write itself names is answered as one that does not
   * exist, and one it reaches through a parent is not named at all.
   */
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (held != null && !ReadFilter.reads(actor, () -> changeLog.filed(type, id, held))) {
      return taken(id);
    }
    var project = changeLog.projectOf(type, id, next);
    if (inside(actor, () -> project, next)) {
      return held == null && next != null ? bornBeyond(actor, id, next) : Optional.empty();
    }
    return project != null
            && (project.equals(id) || next != null && project.equals(next.get("project")))
        ? Refusal.of(Refusal.Kind.OUT_OF_SCOPE, "Project '" + project + "' was not found.", GRANT)
        : unplaced(id);
  }

  /**
   * Why {@code next} may not be born as {@code id} though its own project is inside its actor's
   * audience: a row it names lies outside, answered as one that was not found, or its id is that of
   * a conversation outside — a spec and the room on its id are one conversation, so the birth of
   * either takes the id of both — answered as an id that is taken.
   */
  private Optional<Refusal> bornBeyond(Actor actor, String id, Map<String, Object> next) {
    for (var naming : NAMED.getOrDefault(type, Map.of()).entrySet()) {
      var named = Snapshots.text(next, naming.getKey());
      if (Strings.isNotBlank(named)
          && !ownConversation(id, named)
          && beyond(actor, naming.getValue(), named)) {
        return Refusal.of(
            Refusal.Kind.OUT_OF_SCOPE,
            named(naming.getValue(), named) + " was not found.",
            "Name one in a project this FDE is granted.");
      }
    }
    return (SPEC.equals(type) || ROOM.equals(type)) && beyond(actor, ROOM, id)
        ? taken(id)
        : Optional.empty();
  }

  /**
   * Whether a spec born as {@code id} names {@code named} as the room it lives in: its own
   * conversation, asked below as the id of both. A run's id names nothing of the spec or room on
   * the same id, which it reads as any other.
   */
  private boolean ownConversation(String id, String named) {
    return SPEC.equals(type) && named.equals(id);
  }

  /**
   * Whether this box files {@code named}, a row of type {@code of}, outside {@code actor}'s
   * audience: a room by its conversation's project, which is its spec's where no room was minted,
   * any other by its head's. A row this box does not hold is outside nothing.
   */
  private boolean beyond(Actor actor, String of, String named) {
    var project =
        ROOM.equals(of)
            ? changeLog.conversationProject(named)
            : changeLog.headProject(of, named).orElse(null);
    return project != null && !ReadFilter.reads(actor, () -> project);
  }

  /**
   * Why main refuses pusher {@code actor}'s offer of {@code next} as {@code id} before anything
   * else is read of it: the offer belongs to no project the pusher reads, or main already holds the
   * id — live, deleted or erased — outside its audience. Decided from the head's project alone,
   * which an erasure keeps, and naming the id and nothing main holds of it. A pusher that reads
   * every project is answered without reading main's log.
   */
  public Optional<Refusal> pushed(Actor actor, String id, Map<String, Object> next) {
    if (!inside(actor, () -> changeLog.projectOf(type, id, next), next)) {
      return unplaced(id);
    }
    return ReadFilter.reads(actor, () -> changeLog.headProject(type, id).orElse(null))
            || changeLog.head(type, id).isEmpty()
        ? Optional.empty()
        : taken(id);
  }

  /**
   * Whether a write of {@code next}, belonging to {@code project}, stays inside {@code actor}'s
   * audience: a deletion that names no project is decided by what it deletes. The project is read
   * only for an actor that does not read every one.
   */
  private static boolean inside(Actor actor, Supplier<String> project, Map<String, Object> next) {
    return ReadFilter.reads(actor, project)
        || (Snapshots.isDeletion(next) && project.get() == null);
  }

  private Optional<Refusal> unplaced(String id) {
    return Refusal.of(
        Refusal.Kind.OUT_OF_SCOPE,
        named(type, id) + " is in no project this FDE is granted.",
        GRANT);
  }

  private Optional<Refusal> taken(String id) {
    return Refusal.of(
        Refusal.Kind.OUT_OF_SCOPE, named(type, id) + " already exists.", "Pick another id.");
  }

  private static String named(String type, String id) {
    return type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1) + " '" + id + "'";
  }
}
