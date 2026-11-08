/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.OperatorCommand;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import picocli.CommandLine.Help.Ansi;

/**
 * Wires the {@link SyncScheduler} for this box. A command gets a live scheduler only on a node (a
 * box pointed at a main), backed by the same {@code sail sync} round an FDE runs by hand, and
 * {@link SyncScheduler#disabled()} on main and standalone; sail-api's ({@link #serverScheduler})
 * follows the box's role as it changes. The debounce and the read-freshen TTL are overridable
 * through environment variables for scripts and tests.
 */
final class NodeSync {

  private static final System.Logger LOG = System.getLogger(NodeSync.class.getName());

  static final String DEBOUNCE_ENV = "SAIL_SYNC_DEBOUNCE_MS";
  static final String FRESHEN_TTL_ENV = "SAIL_SYNC_FRESHEN_TTL_MS";

  private NodeSync() {}

  static SyncScheduler scheduler(boolean noSync, OperatorCommand operator) {
    return scheduler(HostSync.config(), noSync, System.getenv(), operator);
  }

  /** Whether this box propagates automatically: a node that has not opted out. Pure for testing. */
  static boolean shouldSync(SyncConfig sync, boolean noSync) {
    return !noSync && HostSync.isNode(sync);
  }

  static SyncScheduler scheduler(
      SyncConfig sync, boolean noSync, Map<String, String> env, OperatorCommand operator) {
    var scheduler = scheduler(sync, noSync, env, () -> syncOnce(operator));
    if (shouldSync(sync, noSync)) {
      var operations = OperationsFactory.open();
      scheduler.useHealth(operations::syncStatus, operations);
    }
    return scheduler;
  }

  /**
   * The scheduler sail-api runs for as long as it runs: live on every box, and active only while
   * {@code sync}, read afresh on each call, says the box is a node, so a box that joins a main
   * after sail-api started, or stops being a node, is followed with no restart. On main and
   * standalone each call asks once and returns. A config that cannot be read answers not now, said
   * in the log: a file caught mid-write costs one skipped round, and the next one or the idle
   * check-in catches up.
   */
  static SyncScheduler serverScheduler(
      Supplier<SyncConfig> sync, Map<String, String> env, SyncScheduler.Reconcile reconcile) {
    var unreadable = new AtomicBoolean();
    return new SyncScheduler(
            reconcile,
            millis(env, DEBOUNCE_ENV, SyncScheduler.DEFAULT_DEBOUNCE),
            millis(env, FRESHEN_TTL_ENV, SyncScheduler.DEFAULT_FRESHEN_TTL))
        .onlyWhile(
            () -> {
              try {
                var node = HostSync.isNode(sync.get());
                if (unreadable.getAndSet(false)) {
                  LOG.log(System.Logger.Level.INFO, "host.yaml reads whole again; sync resumes");
                }
                return node;
              } catch (RuntimeException failure) {
                if (!unreadable.getAndSet(true)) {
                  LOG.log(
                      System.Logger.Level.WARNING,
                      "host.yaml could not be read ("
                          + failure.getMessage()
                          + "); sync waits until it reads whole");
                }
                return false;
              }
            });
  }

  static SyncScheduler scheduler(
      SyncConfig sync, boolean noSync, Map<String, String> env, SyncScheduler.Reconcile reconcile) {
    if (!shouldSync(sync, noSync)) {
      return SyncScheduler.disabled();
    }
    return new SyncScheduler(
        reconcile,
        millis(env, DEBOUNCE_ENV, SyncScheduler.DEFAULT_DEBOUNCE),
        millis(env, FRESHEN_TTL_ENV, SyncScheduler.DEFAULT_FRESHEN_TTL));
  }

  /**
   * One reconcile round with main, worded for the operator who started the command; {@code sail
   * sync} reports its own errors and returns 1.
   */
  static void syncOnce(OperatorCommand operator) throws Exception {
    if (new SyncCommand().call(operator) != 0) {
      throw new IllegalStateException("The sync round failed; see the log above.");
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
