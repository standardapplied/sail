/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import ai.singlr.sail.store.FdeStore;

/**
 * The roster's active-person rule ({@link FdeStore.Fde#active}) as every enrollment and passkey
 * step asks it: no ticket is minted or honoured, no registration starts or finishes, and no login
 * mints a session for a person the roster disables.
 */
final class ActivePerson {

  private ActivePerson() {}

  /** The FDE {@code handle} names, while it may act; refused when unknown or disabled. */
  static FdeStore.Fde named(FdeStore fdes, String handle) {
    return of(
        fdes.byHandle(handle)
            .orElseThrow(
                () ->
                    new PasskeyException(
                        PasskeyException.Kind.NOT_FOUND, "Unknown FDE: " + handle)));
  }

  /** {@code fde}, while it may act; refused when the roster disables it. */
  static FdeStore.Fde of(FdeStore.Fde fde) {
    if (!fde.active()) {
      throw new PasskeyException(
          PasskeyException.Kind.UNAUTHORIZED,
          "FDE '" + fde.handle() + "' is disabled. Ask an admin to re-enable it.");
    }
    return fde;
  }
}
