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
 * an unrestricted scope sees), and its {@code owners}, each named once and never blank. {@code
 * assignee} is the FDE a spec is assigned to, null when it is unassigned and for every other kind:
 * a spec's refusals name it. {@code parent} is what this one belongs to, null for none: the room a
 * spec was born in when it is not its own, whose owners a create, a claim or a seating there also
 * takes — its conversation's for a voice in it, its own for its roster; the spec a review or run is
 * for. Built by {@link Resources}, from the row already in hand.
 */
public record Resource(
    Kind kind, String id, String project, List<String> owners, String assignee, Resource parent) {

  /** What a resource is, which decides whether a project files it and how a refusal names it. */
  public enum Kind {
    /** A spec and its content. */
    SPEC("spec", "specs", true),
    /** A room: its settings, or its conversation. */
    ROOM("room", "rooms", true),
    /** A message in a conversation. */
    MESSAGE("message", "messages", true),
    /** A review of a spec, and its findings. */
    REVIEW("review", "reviews", true),
    /** A run and its log. */
    RUN("run", "runs", true),
    /** A project: its definition, files, skills, processes and snapshots. */
    PROJECT("project", "projects", true),
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
    public String noun() {
      return noun;
    }

    /** One of this kind at the start of a sentence: "Spec". */
    public String title() {
      return noun.substring(0, 1).toUpperCase(Locale.ROOT) + noun.substring(1);
    }

    /** This kind in the plural, as a refusal names it: "specs". */
    public String plural() {
      return plural;
    }

    /**
     * Whether a resource of this kind is filed under a project, so its audience is that project's.
     * A box and the org's people are no project's: no scope hides them.
     */
    public boolean filed() {
      return filed;
    }
  }

  public Resource {
    Objects.requireNonNull(kind, "kind");
    owners = owners.stream().filter(Strings::isNotBlank).distinct().toList();
  }

  /** This resource as the child of {@code parent}: a spec born in that room. */
  public Resource withParent(Resource parent) {
    return new Resource(kind, id, project, owners, assignee, parent);
  }
}
