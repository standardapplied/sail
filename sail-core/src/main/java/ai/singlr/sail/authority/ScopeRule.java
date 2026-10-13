/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

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
 * ProjectScope#admits}): the state it writes must belong to one, and so must what it writes over. A
 * deletion names no project of its own and is decided by what it deletes. Asked ahead of the type's
 * own rule on every lane ({@link #before}), so an actor learns nothing of a row outside its scope
 * but that its id is taken; an actor that sees every project — every one before an FDE is scoped,
 * main's own and this box's machinery — is never refused.
 */
public final class ScopeRule {

  private static final String GRANT =
      "Ask an admin on main for the project: sail fde grant <handle> <project>.";

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
    if (held != null && !scope.admits(changeLog.projectOf(type, id, held))) {
      return taken(id);
    }
    var project = changeLog.projectOf(type, id, next);
    if (inside(scope, project, next)) {
      return Optional.empty();
    }
    return project != null && (project.equals(id) || project.equals(next.get("project")))
        ? Refusal.of(Refusal.Kind.OUT_OF_SCOPE, "Project '" + project + "' was not found.", GRANT)
        : unplaced(id);
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

  /** Whether a write of {@code next}, belonging to {@code project}, stays inside {@code scope}. */
  private static boolean inside(ProjectScope scope, String project, Map<String, Object> next) {
    return scope.admits(project) || (project == null && Snapshots.isDeletion(next));
  }

  private Optional<Refusal> unplaced(String id) {
    return Refusal.of(
        Refusal.Kind.OUT_OF_SCOPE, named(id) + " is in no project this FDE is granted.", GRANT);
  }

  private Optional<Refusal> taken(String id) {
    return Refusal.of(
        Refusal.Kind.OUT_OF_SCOPE, named(id) + " already exists.", "Pick another id.");
  }

  private String named(String id) {
    return type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1) + " '" + id + "'";
  }
}
