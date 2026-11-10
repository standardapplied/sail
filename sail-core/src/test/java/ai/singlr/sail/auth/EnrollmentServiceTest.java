/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class EnrollmentServiceTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private EnrollmentService service;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    new FdeStore(db).add("uday", null, null);
    service = new EnrollmentService(new EnrollmentTicketStore(db), new FdeStore(db));
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void issueResolvesHandleAndAuthorizeReturnsIt() {
    var ticket = service.issue("uday");
    assertTrue(ticket.ticket().startsWith("enr_"));
    assertEquals("uday", ticket.fdeHandle());
    assertEquals("uday", service.authorize(ticket.ticket()).orElseThrow());
  }

  @Test
  void issueRejectsUnknownFde() {
    var e = assertThrows(PasskeyException.class, () -> service.issue("ghost"));
    assertEquals(PasskeyException.Kind.NOT_FOUND, e.kind());
  }

  @Test
  void authorizeIsEmptyForUnknownTicket() {
    assertTrue(service.authorize("enr_nope").isEmpty());
  }

  @Test
  void consumeIsSingleShotThenUnauthorized() {
    var ticket = service.issue("uday").ticket();
    assertTrue(service.consume(ticket));
    assertFalse(service.consume(ticket));
    assertTrue(service.authorize(ticket).isEmpty());
  }

  @Test
  void aDisabledPersonIsMintedNoTicketAndTheirTicketAuthorizesNothing() {
    var ticket = service.issue("uday").ticket();
    disable("uday");

    for (var refused :
        List.<Executable>of(() -> service.issue("uday"), () -> service.authorize(ticket))) {
      var e = assertThrows(PasskeyException.class, refused);
      assertEquals(PasskeyException.Kind.UNAUTHORIZED, e.kind());
      assertEquals("FDE 'uday' is disabled. Ask an admin to re-enable it.", e.getMessage());
    }
  }

  private void disable(String handle) {
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = ?", handle);
  }
}
