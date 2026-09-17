/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.api;

import ai.singlr.sail.sync.SyncEngine;
import java.time.Instant;

public record SyncStatus(
    String role,
    String main,
    SyncEngine.Report lastReport,
    String state,
    Instant lastAttemptAt,
    Instant lastSuccessAt,
    int consecutiveFailures,
    String lastErrorKind,
    String lastError,
    Instant staleSince) {
  /** A node that has never completed a round, or a box with no main: nothing to report yet. */
  public static SyncStatus unattempted(String role, String main) {
    return new SyncStatus(role, main, null, null, null, null, 0, null, null, null);
  }
}
