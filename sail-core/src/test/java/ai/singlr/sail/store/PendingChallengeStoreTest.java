/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingChallengeStoreTest {

  private static final byte[] CHALLENGE = {1, 2, 3, 4, 5};

  @TempDir Path tempDir;
  private Sqlite db;
  private PendingChallengeStore store;
  private String fdeId;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdeId = new FdeStore(db).add("uday", null, null).id();
    store = new PendingChallengeStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void issuesAndConsumesRegisterChallenge() {
    var id = store.issue("register", CHALLENGE, fdeId, Duration.ofMinutes(5));
    assertTrue(id.startsWith("wac_"));

    var pending = store.consume(id, "register").orElseThrow();
    assertArrayEquals(CHALLENGE, pending.challenge());
    assertEquals("register", pending.ceremony());
    assertEquals(fdeId, pending.fdeId());
  }

  @Test
  void issuesAssertChallengeWithoutFde() {
    var id = store.issue("assert", CHALLENGE, null, Duration.ofMinutes(5));
    var pending = store.consume(id, "assert").orElseThrow();
    assertNull(pending.fdeId());
  }

  @Test
  void consumeIsSingleUse() {
    var id = store.issue("assert", CHALLENGE, null, Duration.ofMinutes(5));
    assertTrue(store.consume(id, "assert").isPresent());
    assertTrue(store.consume(id, "assert").isEmpty());
  }

  @Test
  void unknownHandleIsEmpty() {
    assertTrue(store.consume("wac_missing", "assert").isEmpty());
  }

  @Test
  void expiredChallengeIsRejectedAndDeleted() {
    var id = store.issue("assert", CHALLENGE, null, Duration.ofSeconds(-1));
    assertTrue(store.consume(id, "assert").isEmpty());
    assertTrue(store.consume(id, "assert").isEmpty());
  }

  @Test
  void ceremonyMismatchIsRejectedAndConsumed() {
    var id = store.issue("register", CHALLENGE, fdeId, Duration.ofMinutes(5));
    assertTrue(store.consume(id, "assert").isEmpty());
    assertTrue(store.consume(id, "register").isEmpty());
  }
}
