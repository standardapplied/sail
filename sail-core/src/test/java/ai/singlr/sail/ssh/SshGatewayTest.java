/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshGatewayTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private AuthSessionStore sessions;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    sessions = new AuthSessionStore(db);
    fdes.add("uday", null, null, "member");
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private SshGateway.Decision authorize(String command, String handle) {
    return SshGateway.authorize(command, handle, fdes, sessions);
  }

  @Test
  void permitsAllowedCommandAndMintsUsableSession() {
    var decision = authorize("sail spec list", "uday");
    var authorized = assertInstanceOf(SshGateway.Authorized.class, decision);
    assertEquals(List.of("spec", "list"), authorized.args());
    assertTrue(authorized.sessionToken().startsWith("sess_"));
    assertTrue(sessions.validate(authorized.sessionToken()).isPresent());
  }

  @Test
  void stripsLeadingSailToken() {
    var authorized = assertInstanceOf(SshGateway.Authorized.class, authorize("spec list", "uday"));
    assertEquals(List.of("spec", "list"), authorized.args());
  }

  @Test
  void rejectsFdeCommandForNonAdminRole() {
    var rejected = assertInstanceOf(SshGateway.Rejected.class, authorize("sail fde list", "uday"));
    assertTrue(rejected.reason().contains("admin role"));
    assertTrue(sessions.validate("sess_anything").isEmpty());
  }

  @Test
  void permitsFdeCommandForAdminRole() {
    fdes.add("ada", null, null, "admin");
    var authorized =
        assertInstanceOf(SshGateway.Authorized.class, authorize("sail fde list", "ada"));
    assertEquals(List.of("fde", "list"), authorized.args());
    assertTrue(sessions.validate(authorized.sessionToken()).isPresent());
  }

  @Test
  void rejectsHostPrivilegedCommandsEvenForAdmins() {
    fdes.add("ada", null, null, "admin");
    for (var handle : List.of("uday", "ada")) {
      for (var command : List.of("host status", "migrate", "server start", "project up")) {
        var rejected =
            assertInstanceOf(SshGateway.Rejected.class, authorize("sail " + command, handle));
        assertTrue(rejected.reason().contains("host privileges"));
      }
    }
  }

  @Test
  void rejectsEmptyOrShellRequest() {
    assertInstanceOf(SshGateway.Rejected.class, authorize(null, "uday"));
    assertInstanceOf(SshGateway.Rejected.class, authorize("   ", "uday"));
    assertInstanceOf(SshGateway.Rejected.class, authorize("sail", "uday"));
  }

  @Test
  void rejectsUnterminatedQuote() {
    assertInstanceOf(SshGateway.Rejected.class, authorize("sail spec create \"oops", "uday"));
  }

  @Test
  void rejectsUnknownFde() {
    assertInstanceOf(SshGateway.Rejected.class, authorize("sail spec list", "ghost"));
  }

  @Test
  void rejectsDisabledFde() {
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = ?", "uday");
    assertInstanceOf(SshGateway.Rejected.class, authorize("sail spec list", "uday"));
  }

  @Test
  void noSessionIsMintedWhenRejected() {
    authorize("sail fde list", "uday");
    authorize("sail project up acme", "uday");
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = ?", "uday");
    authorize("sail spec list", "uday");
    assertEquals(
        0L, db.queryOne("SELECT COUNT(*) FROM sessions", row -> row.integer(0)).orElse(0L));
  }

  @Test
  void admitsSyncForAnyActiveFdeIncludingViewer() {
    db.execute("UPDATE fdes SET role = 'viewer' WHERE handle = ?", "uday");
    var authorized = assertInstanceOf(SshGateway.Authorized.class, authorize("sail _sync", "uday"));
    assertEquals(List.of("_sync"), authorized.args());
    assertTrue(sessions.validate(authorized.sessionToken()).isPresent());
  }

  @Test
  void rejectsSyncForDisabledFde() {
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = ?", "uday");
    assertInstanceOf(SshGateway.Rejected.class, authorize("sail _sync", "uday"));
  }
}
