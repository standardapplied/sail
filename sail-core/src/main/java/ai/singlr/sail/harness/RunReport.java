/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import java.util.Objects;
import java.util.Optional;

/**
 * What one read of a finished run's report says: what it cost, and what came of the login it was
 * given ({@code login}, null when the report says nothing of it or the run was given none).
 */
public record RunReport(RunUsage usage, LoginOutcome login) {

  /** A run whose harness reported nothing. */
  public static final RunReport NONE = new RunReport(RunUsage.NONE, null);

  public RunReport {
    Objects.requireNonNull(usage, "usage");
  }

  /** What came of the run's login, if its report says. */
  public Optional<LoginOutcome> outcome() {
    return Optional.ofNullable(login);
  }

  /**
   * Whether its harness refused the run's own login: what only a stored login mends. A limit resets
   * by itself.
   */
  public boolean refusedItsLogin() {
    return login instanceof LoginOutcome.Refused;
  }
}
