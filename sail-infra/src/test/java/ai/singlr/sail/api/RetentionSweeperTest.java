/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.RetentionConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Main's daily sweep, with an injected clock: no retention block erases nothing, a block turns its
 * ages into erasures through the one prune method, and a node sweeps nothing at all.
 */
class RetentionSweeperTest {

  private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

  @TempDir Path dir;
  private Sqlite db;
  private SpecStore specs;
  private final AtomicBoolean main = new AtomicBoolean(true);
  private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
  private final AtomicReference<RetentionConfig> policy =
      new AtomicReference<>(RetentionConfig.none());
  private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
  private RetentionSweeper sweeper;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("main.db"));
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    sweeper =
        new RetentionSweeper(
            new SpecPruner(db, null, main::get, clock::get),
            policy::get,
            new BlobStore(db),
            main::get,
            scheduler);
  }

  @AfterEach
  void tearDown() {
    sweeper.close();
    db.close();
  }

  @Test
  void withNoRetentionBlockNothingIsErasedAndHistoryIsStillCompacted() {
    archived("old", NOW.minus(Duration.ofDays(400)));
    busyHistory();

    assertNull(sweeper.retain(), "no policy, no prune");
    var collected = sweeper.collect();

    assertTrue(specs.findById("old").isPresent());
    assertEquals(6, collected.compacted(), "compaction is a constant, not a policy");
    assertEquals(ChangeLog.HISTORY_REVISIONS, new ChangeLog(db).history("spec", "busy").size());
  }

  @Test
  void aBlockTurnsArchivedPastItsAgeIntoErasuresOnMainAsRetention() {
    archived("old", NOW.minus(Duration.ofDays(200)));
    archived("recent", NOW.minus(Duration.ofDays(10)));
    policy.set(new RetentionConfig(Duration.ofDays(90), null, null));

    assertEquals(1, sweeper.retain().specs());
    assertTrue(specs.findById("old").isEmpty());
    assertTrue(specs.findById("recent").isPresent(), "archived ten days ago is inside the age");
    assertEquals(
        "sail-retention",
        new ChangeLog(db).head("spec", "old").orElseThrow().actor(),
        "the erasure row names retention as who pruned");

    clock.set(NOW.plus(Duration.ofDays(400)));
    assertEquals(1, sweeper.retain().specs(), "the same policy, later, takes the rest");
  }

  @Test
  void aBlockWithoutAnArchivedAgePrunesNoSpecs() {
    archived("old", NOW.minus(Duration.ofDays(400)));
    policy.set(new RetentionConfig(null, Duration.ofDays(30), Duration.ofDays(30)));

    assertEquals(0, sweeper.retain().specs());
    assertTrue(specs.findById("old").isPresent(), "only prune_archived_after erases specs");
  }

  @Test
  void nothingRunsOnANode() {
    archived("old", NOW.minus(Duration.ofDays(400)));
    busyHistory();
    policy.set(new RetentionConfig(Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1)));
    main.set(false);

    var out = capture(false, sweeper::sweepQuietly);

    assertEquals("", out);
    assertTrue(specs.findById("old").isPresent());
    assertEquals(26, new ChangeLog(db).history("spec", "busy").size(), "nor any compaction");
  }

  @Test
  void aQuietSweepReportsWhatItDidAndAFailedPolicyStillCompacts() {
    archived("old", NOW.minus(Duration.ofDays(400)));
    busyHistory();
    policy.set(new RetentionConfig(Duration.ofDays(90), null, null));

    var out = capture(false, sweeper::sweepQuietly);
    busyHistory();
    var failing =
        new RetentionSweeper(
            new SpecPruner(db, null, main::get, clock::get),
            () -> {
              throw new IllegalStateException("host.yaml: retention.messages is not an age");
            },
            new BlobStore(db),
            main::get,
            scheduler);
    var err = capture(true, () -> capture(false, failing::sweepQuietly));

    assertTrue(out.contains("sail retention: erased 1 specs"), out);
    assertTrue(out.contains("sail retention: compacted 6 history entries"), out);
    assertTrue(err.contains("retention failed and the next sweep retries"), err);
    assertTrue(err.contains("retention.messages is not an age"), err);
    assertEquals(
        ChangeLog.HISTORY_REVISIONS,
        new ChangeLog(db).history("spec", "busy").size(),
        "the failed policy did not skip the compaction");
  }

  @Test
  void aSweepWithNothingToDoSaysNothingAndAFailedCollectionIsReportedNotThrown() {
    assertEquals("", capture(false, sweeper::sweepQuietly));

    var err = capture(true, () -> db.transaction(() -> capture(false, sweeper::sweepQuietly)));

    assertTrue(err.contains("compaction failed and the next sweep retries"), err);
  }

  @Test
  void startingSchedulesOneDailySweepAndClosingStopsIt() {
    sweeper.start();

    assertEquals(1, scheduler.getQueue().size());
    sweeper.close();
    assertTrue(scheduler.isShutdown());
  }

  private void archived(String id, Instant since) {
    specs.create(row(id, id, SpecStatus.ARCHIVED));
    db.execute("UPDATE specs SET archived_at = ? WHERE id = ?", since.toString(), id);
  }

  private void busyHistory() {
    if (specs.findById("busy").isEmpty()) {
      specs.create(row("busy", "busy", SpecStatus.PENDING));
    }
    for (var i = 0; i < 25; i++) {
      specs.update(row("busy", "edit " + i + " " + System.nanoTime(), SpecStatus.PENDING));
    }
  }

  private static SpecStore.SpecRow row(String id, String title, SpecStatus status) {
    return new SpecStore.SpecRow(
        id, "proj", title, status, "uday", null, null, null, null, 0, "uday", "", "", "uday",
        List.of(), List.of());
  }

  private static String capture(boolean stderr, Runnable work) {
    var buffer = new ByteArrayOutputStream();
    var original = stderr ? System.err : System.out;
    try (var stream = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      if (stderr) System.setErr(stream);
      else System.setOut(stream);
      work.run();
    } finally {
      if (stderr) System.setErr(original);
      else System.setOut(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
