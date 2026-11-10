/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import java.util.Objects;

/**
 * The answer {@link Access#decide} gives: allowed, not found, or refused with a reason and a fix.
 */
public sealed interface Decision {

  /** The principal may take the action. */
  record Allowed() implements Decision {}

  /**
   * The principal cannot read the resource, so it is not told the resource exists: a door answers
   * exactly as it does for an id it does not hold, and a list leaves the row out. It carries no
   * {@link Refusal} on purpose: only the door knows its answer for an absent id ("Spec 'x' was not
   * found.", "No run 'x'.", a project's catalog miss with how to get it, "already exists" for a
   * create whose id is taken outside the caller's scope), and any other wording would itself say
   * the resource is there.
   */
  record NotFound() implements Decision {}

  /**
   * The principal may read the resource but not take the action: {@code refusal} says why and what
   * to do, so a refusal with no fix is refused itself.
   */
  record Refused(Refusal refusal) implements Decision {
    public Refused {
      Objects.requireNonNull(refusal, "refusal");
      if (refusal.fix().isEmpty()) {
        throw new IllegalArgumentException("A refusal names its fix: " + refusal.message());
      }
    }
  }
}
