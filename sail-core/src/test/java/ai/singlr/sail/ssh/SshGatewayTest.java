/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.RoleRule;
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

  /** Row 25's refusal, the same at the gateway as on the host CLI. */
  static final String MEMBER_MAY_NOT_MANAGE_PEOPLE =
      "Only an admin may manage people. Ask an admin to do it.";

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
    return SshGateway.authorize(
        command,
        handle,
        fdes,
        new RoleRule(() -> new SyncConfig("main", null, "main-op", "box"), fdes),
        sessions);
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
  void anAgentVerbThatReadsTheControlPlaneDirectlyIsRefusedAndOneThatAsksTheApiRuns() {
    for (var verb :
        List.of("stop", "logs", "status", "attach", "report", "sweep", "run", "watch")) {
      var refused =
          assertInstanceOf(
              SshGateway.Rejected.class,
              authorize("sail agent " + verb + " beta --dry-run", "uday"));
      assertEquals(
          "'agent "
              + verb
              + "' reads this box's control plane directly, as the box's own person, so it is not"
              + " available over an SSH-key session. Follow the work with 'sail spec' and the"
              + " board, or SSH to the host directly to run it.",
          refused.reason());
    }
    for (var verb : List.of("stream", "sessions", "review")) {
      assertEquals(
          List.of("agent", verb, "r1"),
          assertInstanceOf(
                  SshGateway.Authorized.class, authorize("sail agent " + verb + " r1", "uday"))
              .args());
    }
    assertInstanceOf(SshGateway.Rejected.class, authorize("sail agent", "uday"));
  }

  @Test
  void stripsLeadingSailToken() {
    var authorized = assertInstanceOf(SshGateway.Authorized.class, authorize("spec list", "uday"));
    assertEquals(List.of("spec", "list"), authorized.args());
  }

  @Test
  void aMemberMayNotManagePeopleAndIsToldWhoMay() {
    for (var command : List.of("sail fde list", "sail fde add zed --name Zed")) {
      var rejected = assertInstanceOf(SshGateway.Rejected.class, authorize(command, "uday"));
      assertEquals(MEMBER_MAY_NOT_MANAGE_PEOPLE, rejected.reason());
    }
  }

  @Test
  void aViewerMayNotManagePeopleAndIsToldWhatCredentialMay() {
    fdes.add("obs", null, null, "viewer");

    var rejected =
        assertInstanceOf(SshGateway.Rejected.class, authorize("sail fde add zed", "obs"));

    assertEquals(
        "Your credential is read-only and cannot manage people. Ask an admin for a member or"
            + " admin credential.",
        rejected.reason());
  }

  @Test
  void mainsOperatorActsAsAnAdminWhateverItsRosterRole() {
    fdes.add("main-op", null, null, "member");

    assertInstanceOf(SshGateway.Authorized.class, authorize("sail fde list", "main-op"));
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
  void memberManagesItsOwnPasskeys() {
    for (var command :
        List.of(
            "sail fde passkey list uday",
            "sail fde passkey rm uday --credential abc123 --revoke-sessions")) {
      var authorized = assertInstanceOf(SshGateway.Authorized.class, authorize(command, "uday"));
      assertEquals("fde", authorized.args().getFirst());
      assertTrue(sessions.validate(authorized.sessionToken()).isPresent());
    }
  }

  @Test
  void memberCannotManageAnotherFdesPasskeys() {
    fdes.add("ada", null, null, "member");
    for (var command :
        List.of("sail fde passkey list ada", "sail fde passkey rm ada --credential abc123")) {
      var rejected = assertInstanceOf(SshGateway.Rejected.class, authorize(command, "uday"));
      assertEquals(
          "Managing another FDE's passkeys requires the admin role. You may manage your own:"
              + " sail fde passkey list uday",
          rejected.reason());
    }
  }

  @Test
  void aMemberMintingAnotherFdesTicketIsToldTheThinClientsOwnEnrollment() {
    fdes.add("ada", null, null, "member");
    var rejected =
        assertInstanceOf(SshGateway.Rejected.class, authorize("sail fde enroll ada", "uday"));
    assertEquals(
        "Minting an enrollment ticket for another FDE requires the admin role. Enroll yourself"
            + " with: sail enroll",
        rejected.reason());
  }

  @Test
  void memberPasskeyCarveOutDoesNotLeakIntoOtherFdeSubcommands() {
    for (var command :
        List.of(
            "sail fde passkey",
            "sail fde passkey list",
            "sail fde passkey add uday",
            "sail fde passkey rm --credential abc123 uday")) {
      var rejected = assertInstanceOf(SshGateway.Rejected.class, authorize(command, "uday"));
      assertEquals(
          "Managing another FDE's passkeys requires the admin role. You may manage your own:"
              + " sail fde passkey list uday",
          rejected.reason());
    }
    var rejected =
        assertInstanceOf(SshGateway.Rejected.class, authorize("sail fde rm uday", "uday"));
    assertEquals(MEMBER_MAY_NOT_MANAGE_PEOPLE, rejected.reason());
  }

  @Test
  void memberMintsItsOwnEnrollmentTicket() {
    var authorized =
        assertInstanceOf(SshGateway.Authorized.class, authorize("sail fde enroll uday", "uday"));
    assertEquals(List.of("fde", "enroll", "uday"), authorized.args());
    assertTrue(sessions.validate(authorized.sessionToken()).isPresent());
  }

  @Test
  void bareEnrollIsPinnedToTheCallersHandle() {
    var bare = assertInstanceOf(SshGateway.Authorized.class, authorize("sail fde enroll", "uday"));
    assertEquals(List.of("fde", "enroll", "uday"), bare.args());
    var withFlag =
        assertInstanceOf(SshGateway.Authorized.class, authorize("sail fde enroll --json", "uday"));
    assertEquals(List.of("fde", "enroll", "uday", "--json"), withFlag.args());
  }

  @Test
  void memberCannotMintAnEnrollmentTicketForAnotherFde() {
    fdes.add("ada", null, null, "member");
    var rejected =
        assertInstanceOf(SshGateway.Rejected.class, authorize("sail fde enroll ada", "uday"));
    assertTrue(rejected.reason().contains("admin role"));
    assertTrue(rejected.reason().contains("sail enroll"));
  }

  @Test
  void adminMintsEnrollmentTicketsForAnyFde() {
    fdes.add("ada", null, null, "admin");
    var authorized =
        assertInstanceOf(SshGateway.Authorized.class, authorize("sail fde enroll uday", "ada"));
    assertEquals(List.of("fde", "enroll", "uday"), authorized.args());
  }

  @Test
  void adminManagesAnyFdesPasskeys() {
    fdes.add("ada", null, null, "admin");
    var authorized =
        assertInstanceOf(
            SshGateway.Authorized.class, authorize("sail fde passkey list uday", "ada"));
    assertEquals(List.of("fde", "passkey", "list", "uday"), authorized.args());
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
