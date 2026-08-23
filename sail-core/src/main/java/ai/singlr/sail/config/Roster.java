/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.List;

/**
 * A room's agent members: who is in the conversation, each with their own access mode. Stored on
 * the room row as one compact JSON array so sync merges the membership atomically — many members by
 * schema, even while the current interaction surface seats one standing agent per room. An empty
 * roster serializes to {@code null}, matching the "no engagement" convention of the column it
 * supersedes, so "nobody is here" reads identically across the old and new homes.
 */
public record Roster(List<Engagement> members) {

  public static final Roster EMPTY = new Roster(List.of());

  public Roster {
    members = List.copyOf(members);
  }

  /** A roster seating exactly one member — the current single-standing-agent surface. */
  public static Roster solo(Engagement member) {
    if (member == null) {
      throw new IllegalArgumentException("A solo roster needs its member; got null.");
    }
    return new Roster(List.of(member));
  }

  /** The room's standing agent — the first member — or {@code null} for an empty room. */
  public Engagement standing() {
    return members.isEmpty() ? null : members.getFirst();
  }

  public boolean isEmpty() {
    return members.isEmpty();
  }

  /** This roster as the compact JSON array the room row stores; {@code null} when empty. */
  public String toJson() {
    if (members.isEmpty()) {
      return null;
    }
    return YamlUtil.dumpJson(members.stream().map(m -> YamlUtil.parseMap(m.toJson())).toList());
  }

  /**
   * The roster a room row's stored value describes. A blank value is an empty room; a corrupt value
   * or a member missing its identity reads as empty rather than taking the store down — the same
   * tolerance {@link Engagement#fromJson} established for the column this supersedes.
   */
  public static Roster fromJson(String json) {
    if (Strings.isBlank(json)) {
      return EMPTY;
    }
    try {
      var members =
          YamlUtil.parseList(json).stream()
              .map(m -> Engagement.fromJson(YamlUtil.dumpJson(m)))
              .toList();
      if (members.stream().anyMatch(java.util.Objects::isNull)) {
        return EMPTY;
      }
      return new Roster(members);
    } catch (RuntimeException e) {
      return EMPTY;
    }
  }
}
