/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * The per-request sync opt-out, carrying the CLI's {@code --no-sync} flag to the operations seam.
 * Clients send the {@link #NO_SYNC_HEADER}; the router binds it as a {@link ScopedValue} around the
 * request so {@code SailApiOperations} can skip the read freshen and the write-triggered
 * propagation for exactly that request and act on the local replica as-is. Unbound (the Unix-socket
 * lane, direct calls in tests) means sync as usual.
 */
public final class SyncControl {

  public static final String NO_SYNC_HEADER = "X-Sail-No-Sync";

  static final ScopedValue<Boolean> NO_SYNC = ScopedValue.newInstance();

  private SyncControl() {}

  /** Whether the current request asked to skip sync with main. */
  static boolean noSync() {
    return NO_SYNC.orElse(false);
  }
}
