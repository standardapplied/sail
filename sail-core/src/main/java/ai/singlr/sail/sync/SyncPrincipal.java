/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

/**
 * The authenticated FDE behind one sync session: the handle the SSH-key gateway resolved and
 * whether that FDE's role may push. The handle is what binds single-writer aggregates (runs) to
 * their executing node — a session may only commit runs stamped with its own handle. A {@code null}
 * handle (unauthenticated or machine session) can never own a run, so it fails closed.
 */
public record SyncPrincipal(String handle, boolean canWrite) {

  public static SyncPrincipal readOnly() {
    return new SyncPrincipal(null, false);
  }
}
