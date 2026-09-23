/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.RetentionConfig;
import ai.singlr.sail.store.BlobStore;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Main's daily housekeeping of what it keeps. With a {@code retention} block in {@code host.yaml}
 * it turns the policy into erasures ({@link SpecPruner#retain}): archived specs past {@code
 * prune_archived_after}, room messages past {@code messages}, finished runs past {@code
 * runs_after_finished}. Without the block it erases nothing — no default is compiled in. Either way
 * it then compacts every entity's history to what every box keeps and collects the content nothing
 * references any more, so main's disk is not a ratchet; a policy that fails does not stop that.
 * Only an authoritative box sweeps: a node applies main's erasures and compacts after each of its
 * rounds.
 */
public final class RetentionSweeper implements AutoCloseable {

  public static final Duration INTERVAL = Duration.ofDays(1);

  private final SpecPruner pruner;
  private final Supplier<RetentionConfig> retention;
  private final BlobStore blobs;
  private final BooleanSupplier authoritative;
  private final ScheduledExecutorService scheduler;

  RetentionSweeper(
      SpecPruner pruner,
      Supplier<RetentionConfig> retention,
      BlobStore blobs,
      BooleanSupplier authoritative) {
    this(
        pruner,
        retention,
        blobs,
        authoritative,
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sail-retention-", 0).factory()));
  }

  RetentionSweeper(
      SpecPruner pruner,
      Supplier<RetentionConfig> retention,
      BlobStore blobs,
      BooleanSupplier authoritative,
      ScheduledExecutorService scheduler) {
    this.pruner = Objects.requireNonNull(pruner, "pruner");
    this.retention = Objects.requireNonNull(retention, "retention");
    this.blobs = Objects.requireNonNull(blobs, "blobs");
    this.authoritative = Objects.requireNonNull(authoritative, "authoritative");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** Sweeps an hour after the server starts and daily from then on. */
  public void start() {
    scheduler.scheduleAtFixedRate(
        this::sweepQuietly,
        Duration.ofHours(1).toMinutes(),
        INTERVAL.toMinutes(),
        TimeUnit.MINUTES);
  }

  /**
   * One sweep on an authoritative box: the policy, then the collection, each attempted whatever the
   * other did, each failure reported and left to the next sweep.
   */
  void sweepQuietly() {
    if (!authoritative.getAsBoolean()) {
      return;
    }
    try {
      var pruned = retain();
      if (pruned != null && !pruned.entries().isEmpty()) {
        System.out.println("sail retention: erased " + pruned.summary());
      }
    } catch (RuntimeException e) {
      failed("retention", e);
    }
    try {
      var collected = collect();
      if (collected.compacted() > 0 || collected.freed() > 0) {
        System.out.println(
            "sail retention: compacted "
                + collected.compacted()
                + " history entries, freed "
                + collected.freed()
                + " bytes");
      }
    } catch (RuntimeException e) {
      failed("compaction", e);
    }
  }

  /** What the {@code retention} block erases now; null when there is no block. */
  PruneReport retain() {
    var policy = retention.get();
    return policy.isEmpty() ? null : pruner.retain(policy);
  }

  /** Compacts every entity's history and frees the content nothing references. */
  BlobStore.Collected collect() {
    return blobs.gc(BlobStore.Compaction.ALL, true);
  }

  private static void failed(String step, RuntimeException e) {
    System.err.println("sail retention: " + step + " failed and the next sweep retries: " + e);
    e.printStackTrace(System.err);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
