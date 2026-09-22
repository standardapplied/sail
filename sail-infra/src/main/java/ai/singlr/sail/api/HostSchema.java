/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/** The database's own lifecycle: its version, its migration, and readiness to sync. */
public interface HostSchema {
  int version();

  Migration initialize();

  void prepareSync();

  long collectContent();

  record Migration(int before, int after) {}
}
