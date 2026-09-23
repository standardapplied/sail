/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

/**
 * The authenticated FDE behind one sync session: the handle the SSH-key gateway resolved, whether
 * that FDE's role may push, and whether it is an admin. The handle is what binds single-writer
 * aggregates (runs) to their executing node — a session may only commit runs stamped with its own
 * handle — and what ownership of an erased spec is checked against. A {@code null} handle
 * (unauthenticated or machine session) can never own a run or a spec, so it fails closed.
 */
public record SyncPrincipal(String handle, boolean canWrite, boolean admin) {

  public SyncPrincipal(String handle, boolean canWrite) {
    this(handle, canWrite, false);
  }

  public static SyncPrincipal readOnly() {
    return new SyncPrincipal(null, false);
  }
}
