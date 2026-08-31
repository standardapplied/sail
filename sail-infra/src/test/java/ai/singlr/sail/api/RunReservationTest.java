/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared reservation seam and its best-effort cleanup: a claim returns a credential and prunes,
 * a prune or status probe that fails never fails the launch, and the run-store bookkeeping is a
 * silent no-op without a store.
 */
class RunReservationTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runStore;
  private static final String RUN_ID = DateTimeUtils.newId().toString();
  private SessionYield sessionYield = SessionYield.NONE;

  private static final SailYaml CONFIG =
      SailYaml.fromMap(
          Map.of(
              "name",
              "acme",
              "ssh",
              Map.of("user", "dev"),
              "repos",
              List.of(Map.of("url", "https://example.com/app.git", "path", "app"))));

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static ShellExec quietShell() {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) {
        return new ShellExec.Result(1, "", "");
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout) {
        return new ShellExec.Result(1, "", "");
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
        throw new IOException("container gone");
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException {
        throw new IOException("container gone");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private RunReservation reservation(ShellExec shell, RunStore store) {
    return new RunReservation(store, shell, DispatchOperations.Listener.NONE, () -> sessionYield);
  }

  private String reserve(RunReservation r) {
    return r.reserve(
        RUN_ID,
        "acme",
        "spec",
        "node",
        "node",
        "adhoc",
        List.of(),
        "codex",
        null,
        "task",
        AgentUnit.forRun(RUN_ID),
        CONFIG);
  }

  @Test
  void reserveClaimsTheRunAndReturnsItsCredential() {
    var credential = reserve(reservation(quietShell(), runStore));
    assertNotNull(credential);
    assertEquals("running", runStore.findById(RUN_ID).orElseThrow().status());
  }

  @Test
  void anOverlappingClaimIsRefused() {
    var r = reservation(quietShell(), runStore);
    reserve(r);
    var second = reservation(quietShell(), runStore);
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                second.reserve(
                    DateTimeUtils.newId().toString(),
                    "acme",
                    "spec2",
                    "node",
                    "node",
                    "adhoc",
                    List.of(),
                    "codex",
                    null,
                    "task",
                    AgentUnit.forRun(DateTimeUtils.newId().toString()),
                    CONFIG));
    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, ex.failure().errorCode());
  }

  private String completedRun(String specId, List<String> repos) {
    var id = DateTimeUtils.newId().toString();
    runStore.reserveDispatch(
        id, "acme", specId, "node", "node", "build", repos, "codex", null, "task", "log", "unit");
    runStore.transition(id, "running", "completed", 0);
    return id;
  }

  private String reserveBuild(String specId, String role, List<String> repos) {
    return reservation(quietShell(), runStore)
        .reserve(
            RUN_ID,
            "acme",
            specId,
            "node",
            "node",
            role,
            repos,
            "codex",
            null,
            "task",
            AgentUnit.forRun(RUN_ID),
            CONFIG);
  }

  @Test
  void aClaimEndsTheResumeSessionsItDisplacesAndSparesDisjointOnes() {
    var overlapping = completedRun("old-spec", List.of("app"));
    completedRun("other-spec", List.of("web"));
    var ended = new ArrayList<String>();
    var reasons = new ArrayList<String>();
    sessionYield =
        (sessions, reason) -> {
          ended.addAll(sessions);
          reasons.add(reason);
        };

    reserveBuild("spec", "build", List.of("app"));

    assertEquals(
        List.of(SessionYield.resumeSession(overlapping)),
        ended,
        "only the conversation over the reserved repo yields; the disjoint one lives on");
    assertEquals(List.of("yielded to dispatch " + RUN_ID + " of spec spec"), reasons);
  }

  @Test
  void aReadOnlyRoomWakeDisplacesNoConversation() {
    completedRun("old-spec", List.of("app"));
    sessionYield = (sessions, reason) -> fail("a read-only wake reserves nothing and ends nothing");

    assertNotNull(reserveBuild("old-spec", DispatchGate.ROOM_ROLE, List.of()));
  }

  @Test
  void aWholeContainerClaimDisplacesEveryConversationAndAHostFailureNeverFailsTheClaim() {
    completedRun("old-spec", List.of("app"));
    sessionYield =
        (sessions, reason) -> {
          throw new IOException("Session 'resume-x' belongs to mady.");
        };

    var credential = reserve(reservation(quietShell(), runStore));

    assertNotNull(credential, "a host that refuses is a warning, never a failed launch");
    assertEquals("running", runStore.findById(RUN_ID).orElseThrow().status());
  }

  @Test
  void theDefaultSeamEndsNothingAndTheClaimStands() {
    completedRun("old-spec", List.of("app"));
    assertNotNull(reserve(reservation(quietShell(), runStore)));
  }

  @Test
  void aConversationYieldsByTheDispatchGateRulesHoldingItsRunsRepos() {
    var row = runStore.findById(completedRun("spec", List.of("web"))).orElseThrow();
    assertTrue(
        RunReservation.displaces("spec", "build", List.of("app"), row),
        "the same spec displaces its own conversation even over disjoint repos");
    assertFalse(
        RunReservation.displaces("other", "build", List.of("app"), row),
        "another spec over a disjoint repo leaves it alone");
    assertTrue(
        RunReservation.displaces("other", DispatchGate.ROOM_FULL_ROLE, List.of("web"), row),
        "a full chat turn claims its repos like a build");
    assertTrue(
        RunReservation.displaces("other", "build", List.of(), row),
        "a whole-container claim displaces everything");
  }

  @Test
  void aPruneFailureNeverFailsTheReservation() {
    var credential = reserve(reservation(throwingShell(), runStore));
    assertNotNull(credential, "a best-effort prune that throws must not fail the claim");
  }

  @Test
  void anUnprobeableAgentIsTreatedAsLiveSoTheRunIsNotReleased() {
    reserve(reservation(quietShell(), runStore));

    reservation(throwingShell(), runStore)
        .releaseIfAbsent(RUN_ID, "acme", AgentUnit.forRun(RUN_ID));

    assertEquals(
        "running",
        runStore.findById(RUN_ID).orElseThrow().status(),
        "an unprobeable agent is assumed live, so the reservation is kept");
  }

  @Test
  void anAbsentAgentReleasesTheReservation() {
    reserve(reservation(quietShell(), runStore));

    reservation(quietShell(), runStore).releaseIfAbsent(RUN_ID, "acme", AgentUnit.forRun(RUN_ID));

    assertEquals(
        "failed",
        runStore.findById(RUN_ID).orElseThrow().status(),
        "a probe that finds no live agent frees the run");
  }

  @Test
  void releaseWithoutARunStoreIsASilentNoOp() {
    assertDoesNotThrow(
        () ->
            new RunReservation(
                    null, quietShell(), DispatchOperations.Listener.NONE, () -> SessionYield.NONE)
                .releaseIfAbsent(RUN_ID, "acme", AgentUnit.forRun(RUN_ID)));
  }

  @Test
  void aStoreFailureDuringCleanupIsSwallowed() {
    reserve(reservation(quietShell(), runStore));
    db.close();

    assertDoesNotThrow(
        () ->
            reservation(quietShell(), runStore)
                .releaseIfAbsent(RUN_ID, "acme", AgentUnit.forRun(RUN_ID)),
        "bookkeeping must never propagate a store error out of the launch cleanup");
    assertFalse(db == null);
  }
}
