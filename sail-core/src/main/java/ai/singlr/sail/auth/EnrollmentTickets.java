/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Mints and resolves one-time passkey enrollment tickets. An admin issues a ticket for an FDE; the
 * enrollment page presents it to the register endpoints in place of an operator credential, since a
 * browser cannot carry one. The API handler depends on this abstraction rather than the store so
 * its authorization path can be tested without a database.
 */
public interface EnrollmentTickets {

  /** A minted ticket: the plaintext value (shown once), the FDE it enrolls, and its expiry. */
  record Ticket(String ticket, String fdeHandle, Instant expiresAt) {}

  /**
   * Issues a ticket authorizing one enrollment for the FDE with {@code fdeHandle}. Throws {@link
   * PasskeyException.Kind#NOT_FOUND} when no such FDE exists.
   */
  Ticket issue(String fdeHandle);

  /** Resolves a live ticket to the handle of the FDE it enrolls, or empty if invalid/expired. */
  Optional<String> authorize(String ticket);

  /** Marks a ticket used; returns true only if it was still live (single-shot). */
  boolean consume(String ticket);
}
