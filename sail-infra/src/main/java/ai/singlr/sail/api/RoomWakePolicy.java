/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;

/**
 * The pure wake decision: whether a room message should wake the spec's standing agent. Kept
 * I/O-free so the whole matrix is table-testable — the reactor supplies the facts (the stored wake
 * mode, whether the spec was ever dispatched, the message's author and body) and this class owns
 * the rules.
 *
 * <p>Author classes are structural, not looked up: an agent principal always carries a {@code /}
 * ({@code claude/room-<uuid>}), the orchestrator posts as the literal {@code sail}, and FDE handles
 * never contain a slash — so a message wakes only when a human wrote it, and an agent answering in
 * a room can never wake another agent (no storm loops, by construction).
 *
 * <p>The wake mode vocabulary is deliberately tiny: {@code on} wakes on any human message, {@code
 * mention} only when the message addresses {@code @agent}, {@code off} never. An unset mode
 * defaults to {@code on} for any spec that has been dispatched at least once — the agent joined the
 * room when it first worked there — and {@code off} for a spec no agent has ever touched.
 */
public final class RoomWakePolicy {

  /** The literal a {@code mention}-mode room message must address to wake the agent. */
  public static final String MENTION = "@agent";

  private RoomWakePolicy() {}

  /** Whether {@code author} is a human FDE — never the orchestrator, never a run principal. */
  public static boolean humanAuthor(String author) {
    return Strings.isNotBlank(author) && !Event.SAIL_AGENT.equals(author) && !author.contains("/");
  }

  /**
   * The mode the spec effectively runs under: an explicit stored mode verbatim, else {@code on} for
   * a spec that has been dispatched at least once and {@code off} for one that never was.
   */
  public static String effectiveMode(String storedWake, boolean dispatchedAtLeastOnce) {
    if (Strings.isNotBlank(storedWake)) {
      return storedWake;
    }
    return dispatchedAtLeastOnce ? "on" : "off";
  }

  /** Whether a human-authored {@code body} under this spec's mode should wake the agent. */
  public static boolean shouldWake(
      String storedWake, boolean dispatchedAtLeastOnce, String author, String body) {
    if (!humanAuthor(author)) {
      return false;
    }
    return switch (effectiveMode(storedWake, dispatchedAtLeastOnce)) {
      case "on" -> true;
      case "mention" -> body != null && body.contains(MENTION);
      default -> false;
    };
  }
}
