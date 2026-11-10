/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The handle a run's agent principal is minted with, {@code <family>/<marker><run id>}, and how one
 * is read back: the agent family (the yaml name up to its first dash) over the full run id, marked
 * by the kind of invocation. No FDE handle has a {@code /}, so a principal is never mistaken for a
 * person, and a run carries no principal but its own.
 */
public final class PrincipalHandle {

  /** The kind of invocation a principal is minted for, which its handle marks. */
  public enum Marker {
    /** A build: no marker, {@code claude/<run id>}. */
    BUILD(""),
    /** A review: {@code claude/review-<run id>}. */
    REVIEW("review-"),
    /** A fix: {@code claude/fix-<run id>}. */
    FIX("fix-"),
    /** A room's wake: {@code claude/room-<run id>}. */
    ROOM("room-");

    private final String prefix;

    Marker(String prefix) {
      this.prefix = prefix;
    }
  }

  private PrincipalHandle() {}

  /**
   * The handle of run {@code runId}'s principal, for an invocation of {@code agent} marked {@code
   * marker}. The whole run id, never a truncation: the handle is a security identity compared in
   * ownership checks and audit rows, so it must be exactly as collision-proof as the run id itself.
   */
  public static String mint(String agent, Marker marker, String runId) {
    var family = Objects.toString(agent, "");
    var dash = family.indexOf('-');
    return (dash > 0 ? family.substring(0, dash) : family)
        + "/"
        + marker.prefix
        + Objects.requireNonNull(runId, "run id");
  }

  /**
   * Whether {@code handle} has the shape {@link #mint} gives a principal, which no FDE handle has.
   * How an assignee that names a run rather than an FDE is told apart.
   */
  public static boolean matches(String handle) {
    return handle != null && handle.contains("/");
  }

  /** Whether {@code principal} names run {@code runId} in the shape {@link #mint} gives it. */
  public static boolean namesRun(String principal, String runId) {
    return runId != null && runOf(principal).filter(runId::equals).isPresent();
  }

  /** The run {@code principal} names, when it has the shape {@link #mint} gives it. */
  public static Optional<String> runOf(String principal) {
    if (!matches(principal) || principal.indexOf('/') != principal.lastIndexOf('/')) {
      return Optional.empty();
    }
    var tail = principal.substring(principal.indexOf('/') + 1);
    var marker =
        Arrays.stream(Marker.values())
            .map(each -> each.prefix)
            .filter(prefix -> !prefix.isEmpty() && tail.startsWith(prefix))
            .findFirst()
            .orElse("");
    var runId = tail.substring(marker.length());
    return runId.isEmpty() ? Optional.empty() : Optional.of(runId);
  }
}
