/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;

/**
 * The pure wake decision: whether a room message should wake the room's agent. Kept I/O-free so the
 * whole matrix is table-testable — the reactor supplies the facts (the stored wake mode, the roster
 * size, the message's author and body) and this class owns the rules.
 *
 * <p>Author classes are structural, not looked up: an agent principal always carries a {@code /}
 * ({@code claude/room-<uuid>}), the orchestrator posts as the literal {@code sail}, and FDE handles
 * never contain a slash — so a message wakes only when a human wrote it, and an agent answering in
 * a room can never wake another agent (no storm loops, by construction).
 *
 * <p>The wake mode vocabulary is deliberately tiny: {@code on} wakes on any human message, {@code
 * mention} only when the message addresses {@code @agent}, {@code off} never. An unset mode is
 * derived from the roster, never migrated: a room with one member or none runs {@code on} — the one
 * agent in the room answers a plain message — and a room with two or more members runs {@code
 * mention}, so a multi-member room never becomes a Greek chorus. An explicit mode a human set is
 * never overridden by the roster.
 */
public final class RoomWakePolicy {

  /** The literal a {@code mention}-mode room message must address to wake the agent. */
  public static final String MENTION = "@agent";

  public static final String ON = "on";
  public static final String MENTION_MODE = "mention";
  public static final String OFF = "off";

  private RoomWakePolicy() {}

  /** Whether {@code author} is a human FDE — never the orchestrator, never a run principal. */
  public static boolean humanAuthor(String author) {
    return Strings.isNotBlank(author) && !Event.SAIL_AGENT.equals(author) && !author.contains("/");
  }

  /**
   * The mode the room effectively runs under: an explicit stored mode verbatim, else {@code
   * mention} for a room seating two or more members and {@code on} otherwise.
   */
  public static String effectiveMode(String storedWake, int members) {
    if (Strings.isNotBlank(storedWake)) {
      return storedWake;
    }
    return members >= 2 ? MENTION_MODE : ON;
  }

  /** Whether a human-authored {@code body} under this room's effective mode wakes the agent. */
  public static boolean shouldWake(String storedWake, int members, String author, String body) {
    if (!humanAuthor(author)) {
      return false;
    }
    return switch (effectiveMode(storedWake, members)) {
      case ON -> true;
      case MENTION_MODE -> body != null && body.contains(MENTION);
      default -> false;
    };
  }
}
