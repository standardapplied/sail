/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import java.util.Objects;

/**
 * One kind of static credential a harness reads from its environment: {@code kind} as a stored
 * login names it, the {@code variable} the harness reads it from, and how a person makes one. Only
 * static credentials are kinds: an API key, or a long-lived token nothing refreshes, so one stored
 * value serves any number of runs at once and nothing is ever written back.
 */
public record LoginKind(String kind, String variable, Remedy howToMake) {

  public LoginKind {
    Strings.requireNonBlank(kind, "kind");
    Strings.requireNonBlank(variable, "variable");
    Objects.requireNonNull(howToMake, "howToMake");
  }
}
