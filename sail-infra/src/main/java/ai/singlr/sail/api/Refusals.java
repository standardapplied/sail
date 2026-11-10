/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one place a {@link Decision} becomes the API's error envelope. A refusal is the envelope API
 * clients get: its kind picks the {@link ErrorCode}, and its message and fix are carried verbatim;
 * the run-log stream says the same failure in its own envelope, and the pty host as the refusal's
 * own message and fix. A write the journal refused ({@link WriteRefused}) is translated here too,
 * so every door answers a kind with one code. A resource the caller may not read is the door's own
 * answer for an id it does not hold, so a door can never tell a caller that something it may not
 * see exists.
 */
public final class Refusals {

  private Refusals() {}

  /** The code a client sees for a refusal of {@code kind}. */
  static ErrorCode code(Refusal.Kind kind) {
    return switch (kind) {
      case READ_ONLY -> ErrorCode.READ_ONLY_CREDENTIAL;
      case NOT_OWNER -> ErrorCode.FORBIDDEN_NOT_ASSIGNEE;
      case NO_CONVERSATION -> ErrorCode.ROOM_NOT_FOUND;
      case ADMIN_ONLY -> ErrorCode.FORBIDDEN_ADMIN_ONLY;
      case LANE_FORBIDDEN -> ErrorCode.AGENT_LANE_FORBIDDEN;
      case NOT_AUTHOR -> ErrorCode.FORBIDDEN_NOT_AUTHOR;
      case FIXED -> ErrorCode.INVALID_REQUEST;
      case NOT_PRUNABLE -> ErrorCode.SPEC_NOT_PRUNABLE;
      case NOT_PUBLISHABLE -> ErrorCode.FORBIDDEN;
      case OUT_OF_SCOPE, UNHELD_PROJECT -> ErrorCode.NOT_FOUND;
    };
  }

  /** The exception a door throws for {@code refusal}. */
  static ApiException exception(Refusal refusal) {
    return new ApiException(code(refusal.kind()), refusal.message(), refusal.fix());
  }

  /**
   * {@code failure} as a door reports it: a write the journal refused is the exception its kind
   * maps to, and anything else is itself.
   */
  public static Exception translated(Exception failure) {
    return failure instanceof WriteRefused refused ? exception(refused.refusal()) : failure;
  }

  /** The answer for a room the caller may not see: the same as for one that is not there. */
  public static ApiException roomNotFound(String roomId) {
    return new ApiException(ErrorCode.ROOM_NOT_FOUND, roomMissing(roomId));
  }

  /** What {@link #roomNotFound} says, for a door whose answer is not the API envelope. */
  public static String roomMissing(String roomId) {
    return "Room '" + roomId + "' was not found.";
  }

  /** The answer for a run the caller may not see: the same as for one that is not there. */
  public static ApiException runNotFound(String runId) {
    return ApiException.of((Result.Failure<?>) noRun(runId));
  }

  /** {@link #runNotFound} as the failed result a door returns instead of throwing. */
  static <T> Result<T> noRun(String runId) {
    return Result.failure(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'.");
  }

  /** The answer for a spec the caller may not see: the same as for one that is not there. */
  static ApiException specNotFound(String specId) {
    return new ApiException(ErrorCode.SPEC_NOT_FOUND, specMissing(specId));
  }

  /** What {@link #specNotFound} says. */
  static String specMissing(String specId) {
    return "Spec '" + specId + "' was not found.";
  }

  /**
   * Throws unless {@code decision} allows: not found is {@code notFound}, a refusal its exception.
   */
  public static void require(Decision decision, Supplier<? extends RuntimeException> notFound) {
    switch (decision) {
      case Decision.Allowed allowed -> {}
      case Decision.NotFound absent -> throw notFound.get();
      case Decision.Refused refused -> throw exception(refused.refusal());
    }
  }

  /** Throws {@code refusal}'s exception, if the rule refused; a no-op when it allowed. */
  public static void enforce(Optional<Refusal> refusal) {
    if (refusal.isPresent()) {
      throw exception(refusal.get());
    }
  }

  /** {@code refusal} as the failed result a door returns instead of throwing. */
  static <T> Result<T> failure(Refusal refusal) {
    return Result.failure(code(refusal.kind()), refusal.message(), refusal.fix());
  }
}
