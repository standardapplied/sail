/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SyncConfig;

/**
 * This box's sync configuration with its identity resolved: the box id {@code sail join} minted or
 * {@code sail migrate} persisted, and — until one has been persisted — the hostname, which is
 * exactly the value the migration writes, so a box keeps the identity every checkpoint on every
 * peer already carries. The only place sync identity meets the hostname.
 */
public final class BoxIdentity {

  private BoxIdentity() {}

  public static SyncConfig config() {
    var config = NodeIdentity.config();
    return config.boxId() != null ? config : config.withBoxId(HostInfo.hostname());
  }
}
