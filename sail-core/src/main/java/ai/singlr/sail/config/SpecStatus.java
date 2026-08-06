/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Lifecycle state of a {@link Spec}. The single source of truth for the spec status vocabulary —
 * persisted, serialized, and compared as its {@link #wire()} form (the lowercased name, e.g. {@code
 * IN_PROGRESS} → {@code "in_progress"}).
 *
 * <p>{@link #AWAITING_MERGE} sits between the review gate and completion: the review passed, the
 * pull request is open, and a human still has to merge it on the forge and mark the spec {@link
 * #DONE}. {@link #DRAFT} is the explicit pre-planning bucket; {@link #ARCHIVED} is hidden from the
 * default board. Only {@link SpecCatalog#CLI_SETTABLE} statuses may be assigned by hand via {@code
 * sail spec status}.
 *
 * <p>{@link #CANCELLED} is the terminal record of an operator's clean stop: the spec's run was
 * deliberately halted, not finished. It is intentionally outside every set the lifecycle machinery
 * acts on — not {@code in_progress}/{@code review} (the missed-stop reconciler and the review
 * pipeline), not {@code pending} (dispatch) — so a cancel is honored by the existing contracts
 * rather than special-cased in each component. Re-running a cancelled spec is an explicit operator
 * re-open (dispatch {@code --restart}), never an automatic transition.
 */
public enum SpecStatus {
  DRAFT,
  PENDING,
  IN_PROGRESS,
  REVIEW,
  AWAITING_MERGE,
  DONE,
  CANCELLED,
  ARCHIVED;

  /** The persisted/serialized form: the lowercased enum name. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Whether a spec in this status may have its owner changed freely. Only pre-dispatch statuses
   * ({@link #DRAFT}, {@link #PENDING}) qualify; once a spec is dispatched its claim is locked, and
   * reassignment requires an explicit force override.
   */
  public boolean isReassignable() {
    return this == DRAFT || this == PENDING;
  }

  /** Parses a wire-form status, rejecting anything outside the vocabulary. */
  public static SpecStatus fromWire(String value) {
    var match = byWire(value);
    if (match == null) {
      throw new IllegalArgumentException(
          "Invalid spec status: '" + value + "'. Must be one of: " + wireValues());
    }
    return match;
  }

  /** Comma-separated wire forms, for error messages and listings. */
  public static String wireValues() {
    return Arrays.stream(values()).map(SpecStatus::wire).collect(Collectors.joining(", "));
  }

  private static SpecStatus byWire(String value) {
    for (var status : values()) {
      if (status.wire().equals(value)) {
        return status;
      }
    }
    return null;
  }
}
