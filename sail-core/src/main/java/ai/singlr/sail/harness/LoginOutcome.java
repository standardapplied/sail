/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Strings;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What came of the login a run gave its harness, as the harness's own report says ({@link
 * Harness#loginOutcome}): refused, limited by its account's quota, or passed over for another
 * credential. A report that says none of these has no outcome, and nothing is guessed from it.
 */
public sealed interface LoginOutcome {

  /**
   * What a harness reported in its own words, kept only when it is a plain short label: a reset
   * time or a credential source name, never a value of any credential.
   */
  Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.:,/+()-]{0,47}");

  /** The harness refused the login: expired, revoked or wrong. */
  record Refused() implements LoginOutcome {}

  /** The login's account ran out of quota; {@code resets} is when, as its harness said, or null. */
  record Limited(String resets) implements LoginOutcome {}

  /** The harness took its credential from {@code source}, not from the login it was given. */
  record Overridden(String source) implements LoginOutcome {}

  /** {@code said} when it is a plain {@link #LABEL}, else empty. */
  static Optional<String> label(String said) {
    return Optional.ofNullable(said)
        .map(String::strip)
        .filter(Strings::isNotBlank)
        .filter(text -> LABEL.matcher(text).matches());
  }
}
