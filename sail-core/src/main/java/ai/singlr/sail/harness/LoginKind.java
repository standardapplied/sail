/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * One kind of key a harness reads from its environment: {@code kind} as a stored login names it,
 * the {@code variable} the harness reads it from, what a person calls it ({@code noun}), how a
 * person makes one, the variables set beside it to values of the kind's own, or empty ({@code
 * fixed}), such as the address the key is sent to and a socket that would carry it elsewhere, so
 * nothing the container says sends it elsewhere, and whether the dollars its harness reports are
 * what the key's service charges ({@code priced}). Only a key a service issues for programs is a
 * kind: one stored value serves any number of runs at once, and nothing is ever written back. A
 * subscription's sign-in is never one: the person makes it inside the harness's own session ({@link
 * Harness#signIn}), and Sail never holds it.
 */
public record LoginKind(
    String kind,
    String variable,
    String noun,
    Remedy howToMake,
    SequencedMap<String, String> fixed,
    boolean priced) {

  public LoginKind {
    Strings.requireNonBlank(kind, "kind");
    Strings.requireNonBlank(variable, "variable");
    Strings.requireNonBlank(noun, "noun");
    Objects.requireNonNull(howToMake, "howToMake");
    if (fixed.containsKey(variable)) {
      throw new IllegalArgumentException(
          "A kind's own variable " + variable + " is its key, never a fixed value.");
    }
    fixed = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(fixed));
  }

  /**
   * {@code usage} as the key's service charges it: without its harness's dollars when the harness
   * prices a run at another service's rates ({@link #priced}).
   */
  public RunUsage charged(RunUsage usage) {
    return priced || usage == null
        ? usage
        : new RunUsage(
            usage.model(),
            usage.tokensIn(),
            usage.tokensOut(),
            usage.tokensCacheRead(),
            usage.tokensCacheWrite(),
            null,
            usage.source());
  }
}
