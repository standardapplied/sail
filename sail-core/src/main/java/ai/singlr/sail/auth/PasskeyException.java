/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

/**
 * A passkey ceremony that cannot complete. The {@link Kind} lets the API boundary pick the HTTP
 * status without coupling the ceremony logic to the transport. Login failures are deliberately
 * collapsed to {@link Kind#UNAUTHORIZED} with a generic message so the response cannot be used as
 * an oracle to distinguish an unknown credential from a bad signature.
 */
public final class PasskeyException extends RuntimeException {

  public enum Kind {
    /** The named principal or credential does not exist. */
    NOT_FOUND,
    /** The request is malformed or the ceremony challenge is unknown/expired. */
    BAD_REQUEST,
    /** Authentication failed (any login-ceremony rejection). */
    UNAUTHORIZED
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
