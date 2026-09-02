/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The lane a run executes in — the canonical type behind what the {@code runs.role} column stores
 * and every reactor, gate, and store once matched as a bare string. Each constant owns its wire
 * form and the classifications that used to live as hand-copied string checks: whether a lane's
 * stop triggers the review pipeline, whether it is a chat turn, whether it is an agent session (as
 * opposed to a review execution), and whether it runs under the read-only room contract. One
 * source, so the two classifications that once drifted with no compiler help cannot.
 *
 * <p>The static classifiers take the stored role string and cover what the enum cannot name: the
 * retired invite lane. Older releases wrote {@code invite} and {@code invite-full} rows and the
 * {@code runs} CHECK still admits them, so a run launched before the upgrade may still be running
 * after it. No constant exists to launch one, but such a row keeps the contract it was minted under
 * — viewer-tier for a plain invite, never review-triggering, session-managed so stop and the reaper
 * still address it — instead of falling to the unknown-role defaults, which would upgrade its
 * credential and let its stop push a spec whose build is still running into review.
 */
public enum Lane {
  BUILD("build"),
  ADHOC("adhoc"),
  ROOM("room"),
  ROOM_FULL("room-full"),
  REVIEW("review"),
  FIX("fix");

  private static final String RETIRED_READ_ONLY_ROLE = "invite";
  private static final Set<String> RETIRED_ROLES = Set.of(RETIRED_READ_ONLY_ROLE, "invite-full");

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

  /** Whether {@code role} names the retired invite lane — a historical row, not a live lane. */
  public static boolean retired(String role) {
    return role != null && RETIRED_ROLES.contains(role);
  }

  /**
   * Whether a stop under the stored {@code role} hands the spec to review: a build or ad-hoc does,
   * an unknown or null role conservatively does, a retired invite never did.
   */
  public static boolean triggersReview(String role) {
    return of(role).map(Lane::triggersReview).orElse(!retired(role));
  }

  /**
   * Whether the stored {@code role} is a session the run-scoped machinery owns, live or retired.
   */
  public static boolean isSession(String role) {
    return of(role).map(Lane::isSession).orElse(retired(role));
  }

  /** Whether the stored {@code role} runs under the read-only contract, live or retired. */
  public static boolean readOnly(String role) {
    return of(role).map(Lane::readOnly).orElse(RETIRED_READ_ONLY_ROLE.equals(role));
  }

  /** Every stored role {@link #isSession(String)} admits, live lanes first, in a stable order. */
  public static List<String> sessionRoles() {
    return Stream.concat(
            Arrays.stream(values()).filter(Lane::isSession).map(Lane::wire),
            RETIRED_ROLES.stream().sorted())
        .toList();
  }

  /**
   * Whether {@code role} is this lane's wire form — the null-safe check the string compares were.
   */
  public boolean matches(String role) {
    return wire.equals(role);
  }

  /**
   * Whether a stop in this lane hands the spec to the review pipeline. Only a dispatch build and an
   * ad-hoc run do; every chat and review execution stays out of the loop.
   */
  public boolean triggersReview() {
    return this == BUILD || this == ADHOC;
  }

  /** A conversational turn of the room lane, either mode — a wake or an engaged agent's turn. */
  public boolean isChat() {
    return this == ROOM || this == ROOM_FULL;
  }

  /**
   * An agent session the run-scoped machinery owns — a build, ad-hoc, or chat — as opposed to a
   * pipeline-driven review execution. These are the rows the stop, status, log, reaper, and
   * missed-stop lanes address.
   */
  public boolean isSession() {
    return this == BUILD || this == ADHOC || isChat();
  }

  /**
   * Whether this lane runs under the read-only room contract — a room wake: viewer-tier credential,
   * harness tool cut, no repo reservation.
   */
  public boolean readOnly() {
    return this == ROOM;
  }
}
