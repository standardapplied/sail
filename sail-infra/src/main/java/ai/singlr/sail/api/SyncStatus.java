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
  public SyncStatus(String role, String main, SyncEngine.Report lastReport) {
    this(
        role,
        main,
        lastReport,
        main == null ? "in_sync" : "syncing",
        null,
        null,
        0,
        null,
        null,
        null);
  }
}
