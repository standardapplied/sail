/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.RetentionConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.BlobStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Main's daily housekeeping of what it keeps. With a {@code retention} block in {@code host.yaml}
 * it turns the policy into erasures through the one prune method, as an admin: archived specs past
 * {@code prune_archived_after}, room messages past {@code messages}, finished runs past {@code
 * runs_after_finished}. Without the block it erases nothing — no default is compiled in. Either way
 * it then compacts every entity's history to what every box keeps and collects the content nothing
 * references any more, so main's disk is not a ratchet. Only an authoritative box sweeps: a node
 * applies main's erasures and compacts after each of its rounds.
 */
public final class RetentionSweeper implements AutoCloseable {

  public static final Duration INTERVAL = Duration.ofDays(1);

  /** Who a policy's erasures are attributed to in the erasure rows every box keeps. */
  static final String ACTOR = "sail-retention";

  /** One sweep: what retention pruned, if a policy is set, and what the collection did. */
  record Swept(PruneReport pruned, BlobStore.Collected collected) {
    static final Swept NOTHING = new Swept(null, BlobStore.Collected.NONE);
  }

  private final GlobalSpecOperations specs;
  private final Supplier<RetentionConfig> retention;
  private final BlobStore blobs;
  private final BooleanSupplier authoritative;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofVirtual().name("sail-retention-", 0).factory());

  RetentionSweeper(
      GlobalSpecOperations specs,
      Supplier<RetentionConfig> retention,
      BlobStore blobs,
      BooleanSupplier authoritative) {
    this.specs = Objects.requireNonNull(specs, "specs");
    this.retention = Objects.requireNonNull(retention, "retention");
    this.blobs = Objects.requireNonNull(blobs, "blobs");
    this.authoritative = Objects.requireNonNull(authoritative, "authoritative");
  }

  /** Sweeps an hour after the server starts and daily from then on. */
  public void start() {
    scheduler.scheduleAtFixedRate(
        this::sweepQuietly,
        Duration.ofHours(1).toMinutes(),
        INTERVAL.toMinutes(),
        TimeUnit.MINUTES);
  }

  Swept sweep() {
    if (!authoritative.getAsBoolean()) {
      return Swept.NOTHING;
    }
    var policy = retention.get();
    var pruned =
        policy.isEmpty()
            ? null
            : specs.prune(request(policy), new Actor(ACTOR, Role.ADMIN, Actor.Lane.CLI));
    return new Swept(pruned, blobs.gc(BlobStore.Compaction.ALL, true));
  }

  private static PruneRequest request(RetentionConfig policy) {
    return new PruneRequest(
        List.of(),
        policy.pruneArchivedAfter() == null
            ? null
            : new PruneRequest.Policy(
                List.of(SpecStatus.ARCHIVED), policy.pruneArchivedAfter(), null),
        null,
        policy.messages(),
        policy.runsAfterFinished(),
        false);
  }

  void sweepQuietly() {
    try {
      var swept = sweep();
      if (swept.pruned() != null && !swept.pruned().entries().isEmpty()) {
        System.out.println(
            "sail retention: pruned "
                + swept.pruned().specs()
                + " specs, "
                + swept.pruned().messages()
                + " messages and "
                + swept.pruned().runs()
                + " runs");
      }
      if (swept.collected().compacted() > 0 || swept.collected().freed() > 0) {
        System.out.println(
            "sail retention: compacted "
                + swept.collected().compacted()
                + " history entries, freed "
                + swept.collected().freed()
                + " bytes");
      }
    } catch (RuntimeException e) {
      System.err.println(
          "sail retention: this sweep failed and the next one retries (" + e.getMessage() + ").");
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
