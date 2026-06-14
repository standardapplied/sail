/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnrollmentTicketStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private EnrollmentTicketStore store;
  private String fdeId;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdeId = new FdeStore(db).add("uday", "Uday", null).id();
    store = new EnrollmentTicketStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void issuesAndValidatesResolvingHandle() {
    var created = store.issue(fdeId, Duration.ofMinutes(15));
    assertTrue(created.ticket().startsWith("enr_"));
    assertEquals(fdeId, created.fdeId());

    var info = store.validate(created.ticket()).orElseThrow();
    assertEquals(fdeId, info.fdeId());
    assertEquals("uday", info.fdeHandle());
    assertEquals(created.expiresAt(), info.expiresAt());
  }

  @Test
  void validateReturnsEmptyForUnknownTicket() {
    assertTrue(store.validate("enr_bogus").isEmpty());
  }

  @Test
  void expiredTicketIsRejectedAndPruned() {
    var created = store.issue(fdeId, Duration.ofSeconds(-1));
    assertTrue(store.validate(created.ticket()).isEmpty());
    assertFalse(store.consume(created.ticket()));
  }

  @Test
  void consumeIsSingleShot() {
    var created = store.issue(fdeId, Duration.ofMinutes(15));
    assertTrue(store.consume(created.ticket()));
    assertFalse(store.consume(created.ticket()));
    assertTrue(store.validate(created.ticket()).isEmpty());
  }

  @Test
  void consumeUnknownTicketIsFalse() {
    assertFalse(store.consume("enr_missing"));
  }
}
