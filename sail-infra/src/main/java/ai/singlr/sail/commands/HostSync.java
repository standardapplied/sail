/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.NodeIdentity;

/**
 * This box's sync role, read from {@code host.yaml}, and the questions the rest of the CLI asks of
 * it: does it have a peer at all, and which suffix should a "shared/changed" message carry. Sync is
 * opt-in, so a lone box answers "standalone" — no main to configure, nothing to propagate, and the
 * commands that mention {@code sail sync} stay quiet rather than nagging about a step that does not
 * apply.
 */
final class HostSync {

  private HostSync() {}

  /** This box's configured sync role, or {@link SyncConfig#unset()} when none is set. */
  static SyncConfig config() {
    return NodeIdentity.config();
  }

  /**
   * This box's FDE handle — the assignee FDE-aware dispatch matches against — or null when unset.
   */
  static String handle() {
    return config().handle();
  }

  /** A box with a sync peer: it is the main hub, or it points at one. */
  static boolean hasPeers(SyncConfig sync) {
    return sync.isMain() || Strings.isNotBlank(sync.main());
  }

  /** A lone box — no role, no main; sync and propagation are meaningless here. */
  static boolean isStandalone(SyncConfig sync) {
    return !hasPeers(sync);
  }

  /** A box that initiates sync: a node points at a main and pushes to it. */
  static boolean isNode(SyncConfig sync) {
    return Strings.isNotBlank(sync.main());
  }

  /**
   * The trailing hint after a "shared/changed" message: a node is told to run sync; a main is told
   * peers will pull; a standalone box is told nothing.
   */
  static String propagationHint(SyncConfig sync) {
    if (isNode(sync)) {
      return "Run sail sync to propagate.";
    }
    if (sync.isMain()) {
      return "Other boxes pull it on their next sync.";
    }
    return "";
  }
}
