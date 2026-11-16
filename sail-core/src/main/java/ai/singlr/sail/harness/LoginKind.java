/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One kind of static credential a harness reads from its environment: {@code kind} as a stored
 * login names it, the {@code variable} the harness reads it from, what a person calls it ({@code
 * noun}), how a person makes one, and how long one lasts from the day it is made ({@code lifetime},
 * null for a kind with no known end). Only static credentials are kinds: an API key, or a
 * long-lived token nothing refreshes, so one stored value serves any number of runs at once and
 * nothing is ever written back.
 */
public record LoginKind(
    String kind, String variable, String noun, Remedy howToMake, Duration lifetime) {

  public LoginKind {
    Strings.requireNonBlank(kind, "kind");
    Strings.requireNonBlank(variable, "variable");
    Strings.requireNonBlank(noun, "noun");
    Objects.requireNonNull(howToMake, "howToMake");
  }

  /**
   * When a login of this kind stored at {@code storedAt} ends, for a kind with a known lifetime.
   */
  public Optional<Instant> endsAt(Instant storedAt) {
    return lifetime == null ? Optional.empty() : Optional.of(storedAt.plus(lifetime));
  }
}
