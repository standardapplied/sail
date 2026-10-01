/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.FileLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The node's record of main's file ceiling, learned from the welcome and read at ingest. */
class SyncLimitsTest {

  private Sqlite db;
  private SyncLimits limits;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    limits = new SyncLimits(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void anUnnamedLimitIsZeroAndCapsNothing() {
    assertEquals(0, limits.mainFileMax());
    assertEquals(100, new FileLimits(100).cappedAt(limits.mainFileMax()).effectiveMax());
  }

  @Test
  void aNamedLimitIsRecordedReplacedAndCapsTheLowerOfTheTwo() {
    limits.recordMainFileMax(4);
    assertEquals(4, limits.mainFileMax());
    assertEquals(4, new FileLimits(100).cappedAt(limits.mainFileMax()).effectiveMax());
    assertEquals(3, new FileLimits(3).cappedAt(limits.mainFileMax()).effectiveMax());

    limits.recordMainFileMax(8);
    assertEquals(8, limits.mainFileMax());

    limits.recordMainFileMax(0);
    assertEquals(8, limits.mainFileMax(), "an older main that names none leaves the last known");
  }
}
