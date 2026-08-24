/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pre-launch admission guards every run lane runs before any reservation or side effect. */
class LaunchAdmissionTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdeStore;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdeStore = new FdeStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static Spec spec(String assignee) {
    return new Spec(
        "auth",
        "acme",
        "Add auth",
        SpecStatus.PENDING,
        assignee,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null);
  }

  private static ShellExec shellExiting(int exitCode) {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) {
        return new ShellExec.Result(exitCode, "", "");
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout) {
        return new ShellExec.Result(exitCode, "", "");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private static ShellExec throwingShell() {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) throws IOException {
        throw new IOException("boom");
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException {
        throw new IOException("boom");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  @Test
  void requireAllowedAdmitsTheAssignee() {
    assertDoesNotThrow(
        () -> LaunchAdmission.requireAllowed(Actor.cliOperator("uday"), spec("uday"), "uday"));
  }

  @Test
  void requireAllowedRefusesAMemberActingOnAnothersSpec() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                LaunchAdmission.requireAllowed(
                    new Actor("mady", Role.MEMBER, Actor.Lane.API), spec("uday"), "uday"));
    assertEquals(ErrorCode.NOT_YOUR_SPEC, ex.failure().errorCode());
  }

  @Test
  void requireTrustedRosterSkipsWhenTheBoxKeepsNoRoster() {
    var admission = new LaunchAdmission(shellExiting(0), null);
    assertDoesNotThrow(() -> admission.requireTrustedRoster("anyone"));
  }

  @Test
  void requireTrustedRosterAdmitsARosteredHandle() {
    fdeStore.add("uday", "Uday", "uday@x");
    var admission = new LaunchAdmission(shellExiting(0), fdeStore);
    assertDoesNotThrow(() -> admission.requireTrustedRoster("uday"));
  }

  @Test
  void requireTrustedRosterRefusesAnUnrosteredHandle() {
    var admission = new LaunchAdmission(shellExiting(0), fdeStore);
    var ex = assertThrows(ApiException.class, () -> admission.requireTrustedRoster("ghost"));
    assertEquals(ErrorCode.FDE_NOT_IN_ROSTER, ex.failure().errorCode());
  }

  @Test
  void requireInstalledAdmitsWhenTheBinaryIsOnThePath() {
    var admission = new LaunchAdmission(shellExiting(0), fdeStore);
    assertDoesNotThrow(() -> admission.requireInstalled(AgentCli.CLAUDE_CODE, "acme"));
  }

  @Test
  void requireInstalledRefusesWhenTheBinaryIsMissing() {
    var admission = new LaunchAdmission(shellExiting(1), fdeStore);
    var ex =
        assertThrows(
            ApiException.class, () -> admission.requireInstalled(AgentCli.CLAUDE_CODE, "acme"));
    assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, ex.failure().errorCode());
  }

  @Test
  void requireInstalledWrapsAShellFailure() {
    var admission = new LaunchAdmission(throwingShell(), fdeStore);
    var ex =
        assertThrows(
            ApiException.class, () -> admission.requireInstalled(AgentCli.CLAUDE_CODE, "acme"));
    assertEquals(ErrorCode.COMMAND_FAILED, ex.failure().errorCode());
  }

  @Test
  void resolveAgentReturnsTheNamedAgent() {
    assertEquals(AgentCli.CLAUDE_CODE, LaunchAdmission.resolveAgent("claude-code"));
  }

  @Test
  void resolveAgentRejectsABlankName() {
    var ex = assertThrows(ApiException.class, () -> LaunchAdmission.resolveAgent("  "));
    assertEquals(ErrorCode.BAD_REQUEST, ex.failure().errorCode());
  }

  @Test
  void resolveAgentRejectsAnUnknownName() {
    var ex = assertThrows(ApiException.class, () -> LaunchAdmission.resolveAgent("not-an-agent"));
    assertEquals(ErrorCode.BAD_REQUEST, ex.failure().errorCode());
  }

  @Test
  void validateModelReturnsASafeModel() {
    assertEquals("opus-5", LaunchAdmission.validateModel("opus-5"));
  }

  @Test
  void validateModelRejectsAShellUnsafeModel() {
    var ex = assertThrows(ApiException.class, () -> LaunchAdmission.validateModel("bad model!"));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @org.junit.jupiter.api.Test
  void roomAdmissionRefusesEachRuleWithItsOwnReason() {
    var agent =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () ->
                LaunchAdmission.requireAllowedForRoom(
                    Actor.agentPrincipal("claude/x", "uday"), "chat", "uday", "uday"));
    org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.FORBIDDEN, agent.failure().errorCode());

    var noHandle =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () ->
                LaunchAdmission.requireAllowedForRoom(
                    Actor.cliOperator("uday"), "chat", "uday", " "));
    org.junit.jupiter.api.Assertions.assertEquals(
        ErrorCode.COMMAND_FAILED, noHandle.failure().errorCode());

    var foreign =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () ->
                LaunchAdmission.requireAllowedForRoom(
                    Actor.cliOperator("uday"), "chat", "ada", "uday"));
    org.junit.jupiter.api.Assertions.assertEquals(
        ErrorCode.NOT_YOUR_SPEC, foreign.failure().errorCode());

    var readOnly =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () ->
                LaunchAdmission.requireAllowedForRoom(
                    new Actor("uday", Role.VIEWER, Actor.Lane.API, null), "chat", "uday", "uday"));
    org.junit.jupiter.api.Assertions.assertEquals(
        ErrorCode.READ_ONLY_CREDENTIAL, readOnly.failure().errorCode());

    var notOwner =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () ->
                LaunchAdmission.requireAllowedForRoom(
                    new Actor("sam", Role.MEMBER, Actor.Lane.API, null), "chat", "uday", "uday"));
    org.junit.jupiter.api.Assertions.assertEquals(
        ErrorCode.NOT_YOUR_SPEC, notOwner.failure().errorCode());

    LaunchAdmission.requireAllowedForRoom(Actor.cliOperator("uday"), "chat", "uday", "uday");
  }
}
