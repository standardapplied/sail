/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.util.List;

/** One round's summed counts, an optional reason nothing ran, and the per-type detail. */
public record SyncReport(
    SyncEngine.Report report, String message, List<SyncSession.TypeReport> types) {
  public SyncReport(SyncEngine.Report report, String message) {
    this(report, message, List.of());
  }
}
