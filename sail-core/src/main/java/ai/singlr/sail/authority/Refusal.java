/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import java.util.Objects;
import java.util.Optional;

/**
 * Why a rule refuses a write: its {@link Kind}, the message a client shows, and the action that
 * unblocks it ({@code fix}, null when there is none to name). Every door translates the kind to its
 * own error envelope, and main answers the message as its denial, so one refusal reads the same
 * wherever the rule decides.
 */
public record Refusal(Kind kind, String message, String fix) {

  /** What a refusal is about. */
  public enum Kind {
    /** The actor's role cannot write. */
    READ_ONLY,
    /** The actor neither owns the row nor acts for its owner. */
    NOT_OWNER,
    /** Only an admin may make this change. */
    ADMIN_ONLY,
    /** The revision names an author or creator the actor may not write as. */
    NOT_AUTHOR,
    /** The revision changes a field that never changes after create. */
    FIXED,
    /** The work cannot be erased yet: not archived, cancelled or deleted, or still running. */
    NOT_PRUNABLE
  }

  public Refusal {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(message, "message");
  }

  /** A refusal of {@code kind}, as the optional every rule answers. */
  public static Optional<Refusal> of(Kind kind, String message, String fix) {
    return Optional.of(new Refusal(kind, message, fix));
  }

  /** The refusal of a read-only role, which cannot {@code act} ("change specs"). */
  public static Optional<Refusal> readOnly(String act) {
    return of(
        Kind.READ_ONLY,
        "Your credential is read-only and cannot " + act + ".",
        "Ask an admin for a member or admin credential.");
  }

  /** The refusal of a field that never changes after create. */
  static Optional<Refusal> fixed(String type, String id, String field) {
    return of(
        Kind.FIXED,
        "The "
            + field
            + " of "
            + type
            + " '"
            + id
            + "' is set when it is created and never changes.",
        "Create a new " + type + " instead.");
  }
}
