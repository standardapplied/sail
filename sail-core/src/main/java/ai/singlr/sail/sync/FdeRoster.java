/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.List;
import java.util.Map;

/**
 * Supplies main's FDE roster — one map of identity fields per FDE — to a {@link SyncRpcServer} so a
 * node can mirror it. Kept behind this seam (rather than a direct store dependency) so the core
 * sync layer stays free of the identity store; the {@code _sync} server on main provides the real
 * roster.
 */
public interface FdeRoster {

  List<Map<String, Object>> entries();

  /** A roster with no identities — for sessions, like the test harness, that only sync specs. */
  FdeRoster EMPTY = List::of;
}
