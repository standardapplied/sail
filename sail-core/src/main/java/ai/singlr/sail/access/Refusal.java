/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.common.Remedy;
import java.util.Objects;
import java.util.Optional;

/**
 * Why a rule refuses a write: its {@link Kind}, the message a client shows, and the action that
 * unblocks it ({@code fix}, {@link Remedy#NONE} when there is none to name), its commands kept as
 * data until a door renders them for its reader. Every door translates the kind to its own error
 * envelope, and main answers the message as its denial, so one refusal reads the same wherever the
 * rule decides.
 */
public record Refusal(Kind kind, String message, Remedy fix) {

  /** What a refusal is about. */
  public enum Kind {
    /** The actor's role cannot write. */
    READ_ONLY,
    /** The actor neither owns the row nor acts for its owner. */
    NOT_OWNER,
    /** The conversation a post or a birth names is not on this box. */
    NO_CONVERSATION,
    /** Only an admin may make this change. */
    ADMIN_ONLY,
    /** The credential's lane may never take this action, whatever the role behind it. */
    LANE_FORBIDDEN,
    /** The revision names an author or creator the actor may not write as. */
    NOT_AUTHOR,
    /** The revision changes a field that never changes after create. */
    FIXED,
    /** The work cannot be erased yet: not archived, cancelled or deleted, or still running. */
    NOT_PRUNABLE,
    /** The event is of a type no client, or no client on the actor's lane, may publish. */
    NOT_PUBLISHABLE,
    /** The write lies outside the projects the actor may see. */
    OUT_OF_SCOPE,
    /** The write puts a row into a project this box does not hold. */
    UNHELD_PROJECT
  }

  public Refusal {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(message, "message");
    fix = Objects.requireNonNullElse(fix, Remedy.NONE);
  }

  /** A refusal whose fix names no command. */
  public Refusal(Kind kind, String message, String fix) {
    this(kind, message, fix == null ? Remedy.NONE : Remedy.of(fix));
  }

  /** A refusal of {@code kind}, as the optional every rule answers. */
  public static Optional<Refusal> of(Kind kind, String message, Remedy fix) {
    return Optional.of(new Refusal(kind, message, fix));
  }

  /** A refusal of {@code kind} whose fix names no command. */
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

  /**
   * The refusal of a write into conversation {@code roomId}, which this box does not hold: it has
   * no owner here to ask.
   */
  public static Optional<Refusal> noConversation(String roomId) {
    return of(
        Kind.NO_CONVERSATION,
        "No conversation '" + roomId + "' is on this box: it was deleted, or never here.",
        "Check the spec or room id.");
  }

  /**
   * The refusal of a dispatch of spec {@code specId}, which is unassigned: it runs on no one's box
   * until it is claimed, so no one dispatches it, whoever asks.
   */
  public static Refusal unclaimed(String specId) {
    return new Refusal(
        Kind.NOT_OWNER,
        "Spec '" + specId + "' is unassigned, so no one may dispatch it until it is claimed.",
        "Claim it first with --assignee <you>, then dispatch it.");
  }

  /** The refusal of a field that never changes after create. */
  public static Optional<Refusal> fixed(String type, String id, String field) {
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
