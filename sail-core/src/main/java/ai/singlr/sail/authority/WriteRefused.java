/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import java.util.Objects;

/**
 * A local write the journal refused: the type's {@link WriteAuthority} decided against the bound
 * actor as the revision was recorded, so the transaction the write ran in is rolled back whole.
 * Carries the {@link Refusal}, which the API layer's one translator turns into the client's error.
 */
public final class WriteRefused extends RuntimeException {

  private final transient Refusal refusal;

  /** The refusal of a write, {@code refusal}, as the exception its transaction ends with. */
  public WriteRefused(Refusal refusal) {
    super(Objects.requireNonNull(refusal, "refusal").message());
    this.refusal = refusal;
  }

  /** Why the write was refused. */
  public Refusal refusal() {
    return refusal;
  }
}
