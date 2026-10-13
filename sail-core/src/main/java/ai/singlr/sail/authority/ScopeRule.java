/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Snapshots;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a write of one synced type stays inside the projects its actor may see ({@link
 * ProjectScope#admits}): the state it writes must belong to one, and what it writes over must be
 * filed under one. A deletion names no project of its own and is decided by what it deletes. A row
 * is born naming only rows inside the scope too ({@link #bornBeyond}): the type's rule reads who
 * owns the conversation a spec is born in and the work a run does, and would say so. Asked ahead of
 * the type's own rule on every lane ({@link #before}), so an actor learns nothing of a row outside
 * its scope but that its id is taken; an actor that sees every project — every one before an FDE is
 * scoped, main's own and this box's machinery — is never refused.
 */
public final class ScopeRule {

  private static final String GRANT =
      "Ask an admin on main for the project: sail fde grant <handle> <project>.";
  private static final String SPEC = "spec";
  private static final String ROOM = "room";

  /**
   * The rows a state is born naming beside its project, by the field naming each and the type it
   * names. A review's spec and a message's conversation are not here: each gives its child its
   * project, which is asked already.
   */
  private static final Map<String, Map<String, String>> NAMED =
      Map.of(SPEC, Map.of("room_id", ROOM), "run", Map.of("spec_id", SPEC, "room_id", ROOM));

  private final ChangeLog changeLog;
  private final String type;

  /** The rule for {@code type}, reading parents' projects from {@code changeLog}'s heads. */
  public ScopeRule(ChangeLog changeLog, String type) {
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.type = Objects.requireNonNull(type, "type");
  }

  /** {@code rule}, asked only for a write this rule finds inside its actor's scope. */
  public WriteAuthority before(WriteAuthority rule) {
    return (actor, id, held, next) -> {
      var outside = decide(actor, id, held, next);
      return outside.isPresent() ? outside : rule.decide(actor, id, held, next);
    };
  }

  /**
   * Why {@code actor} may not write {@code next} over {@code held} as {@code id}: one of the two
   * lies outside its scope. Empty when both lie inside. What it writes over is asked first, and
   * answered only as a taken id; a project the write itself names is answered as one that does not
   * exist, and one it reaches through a parent is not named at all.
   */
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    var scope = actor.scope();
    if (scope.unrestricted()) {
      return Optional.empty();
    }
    if (held != null && !scope.admits(filed(id, held))) {
      return taken(id);
    }
    var project = changeLog.projectOf(type, id, next);
    if (inside(scope, project, next)) {
      return held == null && next != null ? bornBeyond(scope, id, next) : Optional.empty();
    }
    return project != null && (project.equals(id) || project.equals(next.get("project")))
        ? Refusal.of(Refusal.Kind.OUT_OF_SCOPE, "Project '" + project + "' was not found.", GRANT)
        : unplaced(id);
  }

  /**
   * Why {@code next} may not be born as {@code id} though its own project is inside {@code scope}:
   * a row it names lies outside, answered as one that was not found, or its id is that of a
   * conversation outside — a spec and the room on its id are one conversation, so the birth of
   * either takes the id of both — answered as an id that is taken.
   */
  private Optional<Refusal> bornBeyond(ProjectScope scope, String id, Map<String, Object> next) {
    for (var naming : NAMED.getOrDefault(type, Map.of()).entrySet()) {
      var named = Snapshots.text(next, naming.getKey());
      if (Strings.isNotBlank(named)
          && !named.equals(id)
          && beyond(scope, naming.getValue(), named)) {
        return Refusal.of(
            Refusal.Kind.OUT_OF_SCOPE,
            named(naming.getValue(), named) + " was not found.",
            "Name one in a project this FDE is granted.");
      }
    }
    return (SPEC.equals(type) || ROOM.equals(type)) && beyond(scope, ROOM, id)
        ? taken(id)
        : Optional.empty();
  }

  /**
   * Whether this box files {@code named}, a row of type {@code of}, outside {@code scope}: a room
   * by its conversation's project, which is its spec's where no room was minted, any other by its
   * head's. A row this box does not hold is outside nothing.
   */
  private boolean beyond(ProjectScope scope, String of, String named) {
    var project =
        ROOM.equals(of)
            ? changeLog.conversationProject(named)
            : changeLog.headProject(of, named).orElse(null);
    return project != null && !scope.admits(project);
  }

  /**
   * Why main refuses pusher {@code actor}'s offer of {@code next} as {@code id} before anything
   * else is read of it: the offer belongs to no project in the pusher's scope, or main already
   * holds the id — live, deleted or erased — outside it. Decided from the head's project alone,
   * which an erasure keeps, and naming the id and nothing main holds of it.
   */
  public Optional<Refusal> pushed(Actor actor, String id, Map<String, Object> next) {
    var scope = actor.scope();
    if (scope.unrestricted()) {
      return Optional.empty();
    }
    if (!inside(scope, changeLog.projectOf(type, id, next), next)) {
      return unplaced(id);
    }
    return changeLog.head(type, id).isEmpty()
            || scope.admits(changeLog.headProject(type, id).orElse(null))
        ? Optional.empty()
        : taken(id);
  }

  /**
   * The project what this box holds as {@code id} is filed under: its head's, which a review or a
   * message keeps from its first write wherever its parent has moved since, so a write over one is
   * decided as a read of it and a push over it are; {@code held}'s own where the head names none.
   */
  private String filed(String id, Map<String, Object> held) {
    return changeLog.headProject(type, id).orElseGet(() -> changeLog.projectOf(type, id, held));
  }

  /** Whether a write of {@code next}, belonging to {@code project}, stays inside {@code scope}. */
  private static boolean inside(ProjectScope scope, String project, Map<String, Object> next) {
    return scope.admits(project) || (project == null && Snapshots.isDeletion(next));
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
