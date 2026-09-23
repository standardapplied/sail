/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.BlobStore;

/** The database's own lifecycle: its version, its migration, and readiness to sync. */
public interface HostSchema {
  int version();

  Migration initialize();

  void prepareSync();

  /**
   * Compacts every entity's history to what it keeps and frees the content nothing references any
   * longer, answering both counts.
   */
  BlobStore.Collected collectContent();

  record Migration(int before, int after) {}
}
