/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;

/**
 * This box's identity in the db-sync star, read from {@code host.yaml}: its sync role and, above
 * all, its FDE {@code handle} — the {@code assignee} a spec must carry to execute here. A single
 * reader shared by the CLI dispatch lane and the API server, so both agree on which specs this node
 * owns. An unconfigured or unreadable {@code host.yaml} yields {@link SyncConfig#unset()} — fails
 * closed to a null handle that matches no assignee.
 */
public final class NodeIdentity {

  private NodeIdentity() {}

  /** This box's declared sync configuration, or {@link SyncConfig#unset()} when none is set. */
  public static SyncConfig config() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return SyncConfig.unset();
    }
    try {
      return HostYaml.fromMap(YamlUtil.parseFile(path)).sync();
    } catch (IOException e) {
      return SyncConfig.unset();
    }
  }

  /** This box's FDE handle — the assignee dispatch matches a spec against — or null when unset. */
  public static String handle() {
    return config().handle();
  }
}
