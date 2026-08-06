/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * The outcome of a resource-scoped {@code AccessPolicy} evaluation: either {@link Allowed} or a
 * {@link Refused} carrying the structured {@link ErrorCode} a GUI client renders verbatim, a
 * human-readable reason that names the resource's owner, and the concrete fixing action. Shared by
 * {@link SpecPolicy}, {@link RunPolicy}, and {@link ReviewPolicy} so every lane refuses with one
 * envelope ({@code code}, {@code message}, {@code fix}); {@link #enforce()} turns a refusal into
 * the {@link ApiException} the operations layer already propagates.
 *
 * <p>Never-silent: a refusal always names both why it happened and how to unblock it.
 */
public sealed interface AccessDecision permits AccessDecision.Allowed, AccessDecision.Refused {

  record Allowed() implements AccessDecision {}

  record Refused(ErrorCode code, String message, String fix) implements AccessDecision {}

  static AccessDecision allowed() {
    return new Allowed();
  }

  static AccessDecision refused(ErrorCode code, String message, String fix) {
    return new Refused(code, message, fix);
  }

  /**
   * Throws the structured {@link ApiException} for a {@link Refused}; a no-op when {@link Allowed}.
   */
  default void enforce() {
    if (this instanceof Refused refused) {
      throw new ApiException(refused.code(), refused.message(), refused.fix());
    }
  }
}
