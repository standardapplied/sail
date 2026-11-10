/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.identity.Actor;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one place a {@link Decision} becomes what a door answers. A refusal is the error envelope API
 * clients get: its kind picks the {@link ErrorCode}, and its message and fix are carried verbatim.
 * A write the journal refused ({@link WriteRefused}) is translated here too, so every door answers
 * a kind with one code. A resource the caller may not read is the door's own answer for an id it
 * does not hold, so a door can never tell a caller that something it may not see exists.
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
  static ApiException roomNotFound(String roomId) {
    return new ApiException(ErrorCode.ROOM_NOT_FOUND, "Room '" + roomId + "' was not found.");
  }

  /** The answer for a spec the caller may not see: the same as for one that is not there. */
  static ApiException specNotFound(String specId) {
    return new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found.");
  }

  /**
   * Throws unless {@code actor} may take {@code action} on {@code resource}: one it may not read is
   * {@code notFound}, the answer the door gives an id it does not hold, and a refusal is its
   * exception.
   */
  static void require(
      Actor actor,
      Action action,
      Resource resource,
      Supplier<? extends RuntimeException> notFound) {
    require(Access.decide(actor, action, resource), notFound);
  }

  /**
   * Refuses {@code actor} {@code action} on {@code project}, its creator's ({@code createdBy}): the
   * one gate of a door that acts on a project. One the actor may not read is one the catalog does
   * not hold ({@link ProjectReader#absent}), and a refusal is its exception.
   */
  static void requireProject(Actor actor, Action action, String project, String createdBy) {
    Refusals.require(
        actor, action, Resources.project(project, createdBy), () -> ProjectReader.absent(project));
  }

  /**
   * Throws unless {@code decision} allows: not found is {@code notFound}, a refusal its exception.
   */
  static void require(Decision decision, Supplier<? extends RuntimeException> notFound) {
    switch (decision) {
      case Decision.Allowed allowed -> {}
      case Decision.NotFound absent -> throw notFound.get();
      case Decision.Refused refused -> throw exception(refused.refusal());
    }
  }

  /** Throws {@code refusal}'s exception, if the rule refused; a no-op when it allowed. */
  static void enforce(Optional<Refusal> refusal) {
    if (refusal.isPresent()) {
      throw exception(refusal.get());
    }
  }

  /** {@code refusal} as the failed result a door returns instead of throwing. */
  static <T> Result<T> failure(Refusal refusal) {
    return Result.failure(code(refusal.kind()), refusal.message(), refusal.fix());
  }
}
