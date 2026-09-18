/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This box's sync configuration with its identity resolved: the box id {@code sail join} minted or
 * {@code sail migrate} persisted, and — until one has been persisted — the hostname, which is
 * exactly the value the migration writes, so a box keeps the identity every checkpoint on every
 * peer already carries. A box with no {@code host.yaml} at all has declared nothing and is known by
 * its hostname alone. The only place sync identity meets the hostname. A {@code host.yaml} that
 * exists but cannot be read is a loud failure, never a silent fallback: answering with the hostname
 * while the file names another id would let this box's identity flip with a permission bit and
 * orphan every peer's checkpoints.
 */
public final class BoxIdentity {

  private BoxIdentity() {}

  public static SyncConfig config() {
    return config(SailPaths.hostConfigPath(), HostInfo.hostname());
  }

  static SyncConfig config(Path hostYaml, String hostname) {
    if (!Files.exists(hostYaml)) {
      return SyncConfig.unset().withBoxId(hostname);
    }
    SyncConfig config;
    try {
      config = HostYaml.fromMap(YamlUtil.parseFile(hostYaml)).sync();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Sync identity lives in "
              + hostYaml
              + ", which could not be read: "
              + e.getMessage()
              + ". Fix the file so every peer sees this box under one id.",
          e);
    }
    return config.boxId() != null ? config : config.withBoxId(hostname);
  }
}
