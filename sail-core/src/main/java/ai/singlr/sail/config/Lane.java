/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.Optional;

/**
 * The lane a run executes in — the canonical type behind what the {@code runs.role} column stores
 * and every reactor, gate, and store once matched as a bare string. Each constant owns its wire
 * form and the classifications that used to live as hand-copied string checks: whether a lane's
 * stop triggers the review pipeline, whether it is a chat or invite turn, whether it is an agent
 * session (as opposed to a review execution), and whether it runs under the read-only room
 * contract. One source, so the two classifications that once drifted with no compiler help cannot.
 */
public enum Lane {
  BUILD("build"),
  ADHOC("adhoc"),
  ROOM("room"),
  ROOM_FULL("room-full"),
  INVITE("invite"),
  INVITE_FULL("invite-full"),
  REVIEW("review"),
  FIX("fix");

  private final String wire;

  Lane(String wire) {
    this.wire = wire;
  }

  /** The stored/transmitted role string for this lane. */
  public String wire() {
    return wire;
  }

  /** The lane for a role string, or empty for an unknown or null role. */
  public static Optional<Lane> of(String role) {
    for (var lane : values()) {
      if (lane.wire.equals(role)) {
        return Optional.of(lane);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether {@code role} is this lane's wire form — the null-safe check the string compares were.
   */
  public boolean matches(String role) {
    return wire.equals(role);
  }

  /**
   * Whether a stop in this lane hands the spec to the review pipeline. Only a dispatch build and an
   * ad-hoc run do; every chat, invite, and review execution stays out of the loop.
   */
  public boolean triggersReview() {
    return this == BUILD || this == ADHOC;
  }

  /** A conversational turn of the room lane, either mode — a wake or an engaged agent's turn. */
  public boolean isChat() {
    return this == ROOM || this == ROOM_FULL;
  }

  /** An invited agent session, either mode. */
  public boolean isInvite() {
    return this == INVITE || this == INVITE_FULL;
  }

  /**
   * An agent session the run-scoped machinery owns — a build, ad-hoc, chat, or invite — as opposed
   * to a pipeline-driven review execution. These are the rows the stop, status, log, reaper, and
   * missed-stop lanes address.
   */
  public boolean isSession() {
    return this == BUILD || this == ADHOC || isChat() || isInvite();
  }

  /**
   * Whether this lane runs under the read-only room contract — a room wake or a read-only invite:
   * viewer-tier credential, harness tool cut, no repo reservation.
   */
  public boolean readOnly() {
    return this == ROOM || this == INVITE;
  }
}
