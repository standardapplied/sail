/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.authority.Refusal;
import java.util.Optional;

/**
 * The one place a rule's {@link Refusal} becomes the error envelope API clients get: its kind picks
 * the {@link ErrorCode}, and its message and fix are carried verbatim.
 */
final class Refusals {

  private Refusals() {}

  /** The code a client sees for a refusal of {@code kind}. */
  static ErrorCode code(Refusal.Kind kind) {
    return switch (kind) {
      case READ_ONLY -> ErrorCode.READ_ONLY_CREDENTIAL;
      case NOT_OWNER -> ErrorCode.FORBIDDEN_NOT_ASSIGNEE;
      case ADMIN_ONLY -> ErrorCode.FORBIDDEN_ADMIN_ONLY;
      case NOT_AUTHOR -> ErrorCode.FORBIDDEN_NOT_AUTHOR;
      case FIXED -> ErrorCode.INVALID_REQUEST;
      case NOT_PRUNABLE -> ErrorCode.SPEC_NOT_PRUNABLE;
      case NOT_PUBLISHABLE -> ErrorCode.FORBIDDEN;
    };
  }

  /** The exception a door throws for {@code refusal}. */
  static ApiException exception(Refusal refusal) {
    return new ApiException(code(refusal.kind()), refusal.message(), refusal.fix());
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
