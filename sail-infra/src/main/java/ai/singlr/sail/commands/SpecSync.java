/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.Banner;
import picocli.CommandLine.Help.Ansi;

/**
 * Automates the {@code sail sync} an FDE would otherwise run by hand, before a spec command reads
 * or writes the control-plane replica — so a node acts on main's latest state instead of a stale
 * local copy, and a dispatch's claim propagates promptly. A no-op on main and on a standalone box
 * (no peer to reconcile with) and skippable with {@code --no-sync}. Best-effort: a sync failure
 * warns and the command proceeds on the local replica, because a transient sync outage must never
 * block spec work.
 */
final class SpecSync {

  private SpecSync() {}

  /** Whether the pre-command sync should run: a node that has not opted out. Pure for testing. */
  static boolean shouldSync(SyncConfig sync, boolean noSync) {
    return !noSync && HostSync.isNode(sync);
  }

  /**
   * Runs {@code sail sync} best-effort when this box is a node and {@code --no-sync} was not set.
   */
  static void freshenIfNode(boolean noSync) {
    if (!shouldSync(HostSync.config(), noSync)) {
      return;
    }
    try {
      new SyncCommand().call();
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Sync with main failed (" + e.getMessage() + "); using the local replica.",
              Ansi.AUTO));
    }
  }
}
