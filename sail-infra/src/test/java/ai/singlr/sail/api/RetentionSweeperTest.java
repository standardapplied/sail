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
import ai.singlr.sail.store.RoomStore;
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

  @TempDir Path dir;
  private Sqlite db;
  private SpecStore specs;
  private final AtomicBoolean main = new AtomicBoolean(true);
  private final AtomicReference<Instant> clock =
      new AtomicReference<>(Instant.parse("2026-09-23T00:00:00Z"));
  private final AtomicReference<RetentionConfig> policy =
      new AtomicReference<>(RetentionConfig.none());
  private RetentionSweeper sweeper;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("main.db"));
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    var ops =
        new GlobalSpecOperations(
            specs,
            null,
            null,
            null,
            () -> new RoomStore(db),
            new GlobalSpecOperations.Pruning(db, main::get, clock::get));
    sweeper = new RetentionSweeper(ops, policy::get, new BlobStore(db), main::get);
  }

  @AfterEach
  void tearDown() {
    sweeper.close();
    db.close();
  }

  @Test
  void withNoRetentionBlockNothingIsErasedAndHistoryIsStillCompacted() {
    archivedLongAgo("old");
    for (var i = 0; i < 25; i++) {
      specs.update(row("busy", "edit " + i, SpecStatus.PENDING));
    }

    var swept = sweeper.sweep();

    assertNull(swept.pruned(), "no policy, no prune");
    assertTrue(specs.findById("old").isPresent());
    assertTrue(swept.collected().compacted() > 0, "compaction is a constant, not a policy");
    assertEquals(ChangeLog.HISTORY_REVISIONS, new ChangeLog(db).history("spec", "busy").size());
  }

  @Test
  void aBlockTurnsArchivedPastItsAgeIntoErasuresOnMainAsRetention() {
    archivedLongAgo("old");
    specs.create(row("recent", "Recent", SpecStatus.ARCHIVED));
    policy.set(new RetentionConfig(Duration.ofDays(90), null, null));

    var swept = sweeper.sweep();

    assertEquals(1, swept.pruned().specs());
    assertTrue(specs.findById("old").isEmpty());
    assertTrue(specs.findById("recent").isPresent(), "archived today is inside the age");
    assertEquals(
        "sail-retention",
        new ChangeLog(db).head("spec", "old").orElseThrow().actor(),
        "the erasure row names retention as who pruned");

    clock.set(clock.get().plus(Duration.ofDays(400)));
    assertEquals(1, sweeper.sweep().pruned().specs(), "the same policy, later, takes the rest");
  }

  @Test
  void aBlockWithoutAnArchivedAgePrunesNoSpecs() {
    archivedLongAgo("old");
    policy.set(new RetentionConfig(null, Duration.ofDays(30), Duration.ofDays(30)));

    var swept = sweeper.sweep();

    assertEquals(0, swept.pruned().specs());
    assertTrue(specs.findById("old").isPresent(), "only prune_archived_after erases specs");
  }

  @Test
  void nothingRunsOnANode() {
    archivedLongAgo("old");
    policy.set(new RetentionConfig(Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1)));
    main.set(false);

    var swept = sweeper.sweep();

    assertEquals(RetentionSweeper.Swept.NOTHING, swept);
    assertTrue(specs.findById("old").isPresent());
  }

  @Test
  void aQuietSweepReportsWhatItDidAndSurvivesAFailure() {
    archivedLongAgo("old");
    for (var i = 0; i < 25; i++) {
      specs.update(row("busy", "edit " + i, SpecStatus.PENDING));
    }
    policy.set(new RetentionConfig(Duration.ofDays(90), null, null));

    var out = capture(false, sweeper::sweepQuietly);
    policy.set(null);
    var err = capture(true, sweeper::sweepQuietly);

    assertTrue(out.contains("sail retention: pruned 1 specs, 0 messages and 0 runs"), out);
    assertTrue(out.contains("sail retention: compacted"), out);
    assertTrue(err.contains("this sweep failed and the next one retries"), err);
    sweeper.start();
  }

  private void archivedLongAgo(String id) {
    specs.create(row(id, id, SpecStatus.ARCHIVED));
    db.execute("UPDATE specs SET archived_at = '2026-01-01T00:00:00Z' WHERE id = ?", id);
    if (specs.findById("busy").isEmpty()) {
      specs.create(row("busy", "busy", SpecStatus.PENDING));
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
