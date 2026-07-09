/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.Banner;
import java.time.Duration;
import java.util.Map;
import picocli.CommandLine.Help.Ansi;

/**
 * Wires the {@link SyncScheduler} for this box. Only a node (a box pointed at a main) gets a live
 * scheduler, backed by the same {@code sail sync} round an FDE runs by hand; main and standalone
 * boxes get {@link SyncScheduler#disabled()}, so the seam is a guaranteed no-op there. The debounce
 * and the read-freshen TTL are overridable through environment variables for scripts and tests.
 */
final class NodeSync {

  static final String DEBOUNCE_ENV = "SAIL_SYNC_DEBOUNCE_MS";
  static final String FRESHEN_TTL_ENV = "SAIL_SYNC_FRESHEN_TTL_MS";

  private NodeSync() {}

  static SyncScheduler scheduler(boolean noSync) {
    return scheduler(HostSync.config(), noSync, System.getenv());
  }

  /** Whether this box propagates automatically: a node that has not opted out. Pure for testing. */
  static boolean shouldSync(SyncConfig sync, boolean noSync) {
    return !noSync && HostSync.isNode(sync);
  }

  static SyncScheduler scheduler(SyncConfig sync, boolean noSync, Map<String, String> env) {
    if (!shouldSync(sync, noSync)) {
      return SyncScheduler.disabled();
    }
    return new SyncScheduler(
        NodeSync::syncOnce,
        millis(env, DEBOUNCE_ENV, SyncScheduler.DEFAULT_DEBOUNCE),
        millis(env, FRESHEN_TTL_ENV, SyncScheduler.DEFAULT_FRESHEN_TTL));
  }

  /** One reconcile round with main; {@code sail sync} reports its own errors and returns 1. */
  static void syncOnce() throws Exception {
    if (new SyncCommand().call() != 0) {
      throw new IllegalStateException("sail sync round failed; see the log above");
    }
  }

  static Duration millis(Map<String, String> env, String name, Duration fallback) {
    var raw = env.get(name);
    if (Strings.isBlank(raw)) {
      return fallback;
    }
    try {
      var ms = Long.parseLong(raw.strip());
      if (ms > 0) {
        return Duration.ofMillis(ms);
      }
    } catch (NumberFormatException ignored) {
    }
    System.err.println(
        Banner.errorLine(
            name
                + " must be a positive number of milliseconds, got '"
                + raw
                + "'; using "
                + fallback.toMillis()
                + "ms.",
            Ansi.AUTO));
    return fallback;
  }
}
