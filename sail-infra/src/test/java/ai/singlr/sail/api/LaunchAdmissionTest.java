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
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
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
        () ->
            Actor.run(
                Actor.cliOperator("uday"),
                () -> LaunchAdmission.requireAllowed(spec("uday"), "uday")));
  }

  @Test
  void requireAllowedRefusesAMemberActingOnAnothersSpecBeforeAskingWhereItRuns() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Actor.run(
                    new Actor("mady", Role.MEMBER, Actor.Lane.API),
                    () -> LaunchAdmission.requireAllowed(spec("uday"), "mady")));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, ex.failure().errorCode());
    assertEquals("Spec 'auth' is assigned to 'uday', not you.", ex.getMessage());
  }

  @Test
  void aViewerIsRefusedReadOnlyBeforeWhereTheSpecRunsIsAsked() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Actor.run(
                    new Actor("uday", Role.VIEWER, Actor.Lane.API),
                    () -> LaunchAdmission.requireAllowed(spec("raj"), "uday")));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, ex.failure().errorCode());
    assertEquals("Your credential is read-only and cannot dispatch specs.", ex.getMessage());
  }

  @Test
  void anAgentLaneIsRefusedOutrightEvenForItsOwnersSpec() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Actor.run(
                    Actor.agentPrincipal("claude/a1b2c3", "uday"),
                    () -> LaunchAdmission.requireAllowed(spec("uday"), "uday")));
    assertEquals(ErrorCode.AGENT_LANE_FORBIDDEN, ex.failure().errorCode());
    assertEquals("Agent principals cannot dispatch specs.", ex.getMessage());
  }

  @Test
  void anAdminMayDispatchAnySpecButOnlyOnTheBoxItRunsOn() {
    var admin = new Actor("ops", Role.ADMIN, Actor.Lane.API);
    assertDoesNotThrow(
        () -> Actor.run(admin, () -> LaunchAdmission.requireAllowed(spec("uday"), "uday")));

    var ex =
        assertThrows(
            ApiException.class,
            () -> Actor.run(admin, () -> LaunchAdmission.requireAllowed(spec("raj"), "uday")));
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, ex.failure().errorCode());
  }

  @Test
  void requireAllowedLeavesTheAudienceToTheReadThatFoundTheSpec() {
    assertDoesNotThrow(
        () ->
            Actor.run(
                new Actor("uday", Role.MEMBER, Actor.Lane.API)
                    .withScope(ProjectScope.of(List.of("beta"))),
                () -> LaunchAdmission.requireAllowed(spec("uday"), "uday")));
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
  void requireTrustedRosterRefusesADisabledHandle() {
    fdeStore.add("uday", "Uday", "uday@x");
    fdeStore.disableUnlisted(List.of());
    var admission = new LaunchAdmission(shellExiting(0), fdeStore);

    var ex = assertThrows(ApiException.class, () -> admission.requireTrustedRoster("uday"));

    assertEquals(ErrorCode.FDE_NOT_IN_ROSTER, ex.failure().errorCode());
  }

  @Test
  void requireInstalledAdmitsWhenTheBinaryIsOnThePath() {
    var admission = new LaunchAdmission(shellExiting(0), fdeStore);
    assertDoesNotThrow(() -> admission.requireInstalled(Harnesses.of("claude-code"), "acme"));
  }

  @Test
  void requireInstalledRefusesWhenTheBinaryIsMissing() {
    var admission = new LaunchAdmission(shellExiting(1), fdeStore);
    var ex =
        assertThrows(
            ApiException.class,
            () -> admission.requireInstalled(Harnesses.of("claude-code"), "acme"));
    assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, ex.failure().errorCode());
  }

  @Test
  void requireInstalledWrapsAShellFailure() {
    var admission = new LaunchAdmission(throwingShell(), fdeStore);
    var ex =
        assertThrows(
            ApiException.class,
            () -> admission.requireInstalled(Harnesses.of("claude-code"), "acme"));
    assertEquals(ErrorCode.COMMAND_FAILED, ex.failure().errorCode());
  }

  @Test
  void resolveAgentReturnsTheNamedAgent() {
    assertEquals(Harnesses.of("claude-code"), LaunchAdmission.resolveAgent("claude-code"));
  }

  @Test
  void resolveAgentRejectsABlankName() {
    var ex = assertThrows(ApiException.class, () -> LaunchAdmission.resolveAgent("  "));
    assertEquals(ErrorCode.BAD_REQUEST, ex.failure().errorCode());
    assertEquals("Pass agent: claude-code or codex.", ex.failure().remedy().plain());
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

  @Test
  void roomAdmissionDecidesTheActorThenTheBox() {
    var agent =
        assertThrows(
            ApiException.class,
            () ->
                Actor.run(
                    Actor.agentPrincipal("claude/x", "uday"),
                    () -> LaunchAdmission.requireAllowedForRoom("chat", "acme", "uday", "uday")));
    assertEquals(ErrorCode.AGENT_LANE_FORBIDDEN, agent.failure().errorCode());
    assertEquals("Agent principals cannot dispatch rooms.", agent.getMessage());

    var viewer =
        assertThrows(
            ApiException.class,
            () ->
                Actor.run(
                    new Actor("uday", Role.VIEWER, Actor.Lane.API),
                    () -> LaunchAdmission.requireAllowedForRoom("chat", "acme", "uday", " ")));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, viewer.failure().errorCode());
    assertEquals("Your credential is read-only and cannot dispatch rooms.", viewer.getMessage());

    Actor.run(
        new Actor("uday", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("beta"))),
        () -> LaunchAdmission.requireAllowedForRoom("chat", "acme", "uday", "uday"));

    var noHandle =
        assertThrows(
            ApiException.class,
            () ->
                Actor.run(
                    Actor.cliOperator("uday"),
                    () -> LaunchAdmission.requireAllowedForRoom("chat", "acme", "uday", " ")));
    assertEquals(ErrorCode.COMMAND_FAILED, noHandle.failure().errorCode());

    var foreign =
        assertThrows(
            ApiException.class,
            () ->
                Actor.run(
                    Actor.cliOperator("uday"),
                    () -> LaunchAdmission.requireAllowedForRoom("chat", "acme", "ada", "uday")));
    assertEquals(ErrorCode.NOT_YOUR_SPEC, foreign.failure().errorCode());

    Actor.run(
        Actor.cliOperator("uday"),
        () -> LaunchAdmission.requireAllowedForRoom("chat", "acme", "uday", "uday"));
  }
}
