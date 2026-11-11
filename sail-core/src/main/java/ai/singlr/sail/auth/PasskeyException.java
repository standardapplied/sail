/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

/**
 * A passkey ceremony that cannot complete. The {@link Kind} lets the API boundary pick its answer
 * without coupling the ceremony logic to the transport. A login whose assertion fails is collapsed
 * to {@link Kind#UNAUTHORIZED} with a generic message, so the answer cannot tell an unknown
 * credential from a bad signature; one whose assertion verifies for a disabled FDE is {@link
 * Kind#DISABLED}, since only the holder of that FDE's passkey can reach it.
 */
public final class PasskeyException extends RuntimeException {

  public enum Kind {
    /** The named principal or credential does not exist. */
    NOT_FOUND,
    /** The request is malformed or the ceremony challenge is unknown/expired. */
    BAD_REQUEST,
    /** A login assertion that does not verify, said generically. */
    UNAUTHORIZED,
    /** The FDE the ceremony is for is disabled in the roster. */
    DISABLED
  }

  private final transient Kind kind;

  public PasskeyException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
