/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.Objects;

public record Resolution(Strategy strategy, String merged) {
  public enum Strategy { MINE, THEIRS, MERGE }

  public Resolution {
    Objects.requireNonNull(strategy, "resolution strategy");
  }
}
