/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.common.Strings;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What an action is taken on, as {@link Access} needs it: its {@code kind}, its {@code id}, the
 * {@code project} it is filed under (its audience; null for a row of no known project, which only
 * an unrestricted scope sees), and its {@code owners}, each named once and never blank. Three
 * fields belong to one kind each, null for every other: {@code assignee}, the FDE a spec is
 * assigned to (null while it is unassigned), which a spec's refusals name; {@code spec}, the id of
 * the spec a run or a review is for, which theirs name; and {@code bornIn}, the conversation of the
 * room a spec was born in when it is not its own, whose owners a create or a claim of the spec also
 * takes. {@code madeBy}, of a spec or a conversation only, is the principal whose write made the
 * spec, or the spec whose own room the conversation is, as the journal's first revision of it
 * records: an agent's work includes what it made. Built by {@link Resources}, from the row already
 * in hand.
 */
public record Resource(
    Kind kind,
    String id,
    String project,
    List<String> owners,
    String assignee,
    String spec,
    Resource bornIn,
    String madeBy) {

  /** What a resource is, which decides whether a project files it and how a refusal names it. */
  public enum Kind {
    /** A spec and its content. */
    SPEC("spec", "specs", true),
    /** A room: its settings, or its conversation. */
    ROOM("room", "rooms", true),
    /** A review of a spec, and its findings. */
    REVIEW("review", "reviews", true),
    /** A run and its log. */
    RUN("run", "runs", true),
    /** A project: its definition, files, skills, processes and snapshots. */
    PROJECT("project", "projects", true),
    /**
     * A conflict a sync parked on a row. Resolving one is asked of the kind ({@link Access#atAll});
     * the row it is parked on is found in the reader's audience first.
     */
    CONFLICT("conflict", "conflicts", true),
    /** A box: its sync health, its bus stats, the main it points at. Filed under no project. */
    BOX("box", "boxes", false),
    /** The org's people, tokens and pairings. Filed under no project. */
    PEOPLE("person", "people", false);

    private final String noun;
    private final String plural;
    private final boolean filed;

    Kind(String noun, String plural, boolean filed) {
      this.noun = noun;
      this.plural = plural;
      this.filed = filed;
    }

    /** One of this kind, as a refusal names it: "spec". */
    String noun() {
      return noun;
    }

    /** This kind in the plural, as a refusal names it: "specs". */
    String plural() {
      return plural;
    }

    /**
     * Whether a resource of this kind is filed under a project, so its audience is that project's.
     * A box and the org's people are no project's: no scope hides them.
     */
    boolean filed() {
      return filed;
    }
  }

  public Resource {
    Objects.requireNonNull(kind, "kind");
    owners = owners.stream().filter(Strings::isNotBlank).distinct().toList();
    if (bornIn != null && kind != Kind.SPEC) {
      throw new IllegalArgumentException("Only a spec is born in a room, not " + kind + " " + id);
    }
    if (madeBy != null && kind != Kind.SPEC && kind != Kind.ROOM) {
      throw new IllegalArgumentException(
          "Only a spec or its conversation is made by a principal, not " + kind + " " + id);
    }
  }

  /**
   * This resource at the start of a sentence: "Room 'lobby'", or "This box" for one with no id, a
   * box that names no FDE.
   */
  String named() {
    return id == null
        ? "This " + kind.noun
        : kind.noun.substring(0, 1).toUpperCase(Locale.ROOT)
            + kind.noun.substring(1)
            + " '"
            + id
            + "'";
  }
}
