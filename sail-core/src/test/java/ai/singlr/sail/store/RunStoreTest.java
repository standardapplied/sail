/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private String newRun(String project, String specId) {
    var id = DateTimeUtils.newId().toString();
    return store.create(
        id,
        project,
        specId,
        "node-a",
        "node-a",
        "build",
        "claude-code",
        "feat/x",
        "do it",
        1234,
        5678,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
  }

  @Test
  void createAndFindRun() {
    var id = newRun("backend", "auth");

    var run = store.findById(id).orElseThrow();
    assertEquals("backend", run.project());
    assertEquals("auth", run.specId());
    assertEquals("node-a", run.node());
    assertEquals("build", run.role());
    assertEquals("claude-code", run.agent());
    assertEquals("feat/x", run.branch());
    assertEquals("do it", run.task());
    assertEquals(1234, run.pid());
    assertEquals(5678, run.watcherPid());
    assertEquals("running", run.status());
    assertEquals("/home/dev/.sail/runs/" + id + "/agent.log", run.logPath());
    assertNotNull(run.startedAt());
    assertNull(run.completedAt());
  }

  @Test
  void updateProcessRefusesToStampARunTheStopAlreadyClaimed() {
    var id = newRun("backend", "auth");
    assertTrue(store.transition(id, "running", "stopped"));

    assertFalse(store.updateProcess(id, 4321, 987654321L, 8765));

    var run = store.findById(id).orElseThrow();
    assertEquals("stopped", run.status());
    assertNotEquals(4321, run.pid(), "a refused stamp must leave the row's identity untouched");
  }

  @Test
  void updateProcessPersistsThePidFingerprintAndItReplicates() {
    var id = newRun("backend", "auth");

    assertTrue(store.updateProcess(id, 4321, 987654321L, 8765));

    var run = store.findById(id).orElseThrow();
    assertEquals(4321, run.pid());
    assertEquals(987654321L, run.pidTicks());
    assertEquals(8765, run.watcherPid());
    var snapshot = store.comparableSnapshot(id);
    assertEquals(987654321L, snapshot.get("pid_ticks"), "the fingerprint replicates with the run");
    var adopted = DateTimeUtils.newId().toString();
    store.applyRevision(adopted, snapshot, "1-remote");
    assertEquals(987654321L, store.findById(adopted).orElseThrow().pidTicks());
  }

  @Test
  void transitionCommitsOnlyFromTheExpectedStatus() {
    var id = newRun("backend", "auth");

    assertFalse(store.transition(id, "stopping", "stopped"));
    assertEquals("running", store.findById(id).orElseThrow().status());

    assertTrue(store.transition(id, "running", "stopping"));
    var claimed = store.findById(id).orElseThrow();
    assertEquals("stopping", claimed.status());
    assertNull(claimed.completedAt(), "a claim is not terminal, so nothing is stamped complete");

    assertTrue(store.transition(id, "stopping", "stopped"));
    var stopped = store.findById(id).orElseThrow();
    assertEquals("stopped", stopped.status());
    assertNotNull(stopped.completedAt());
  }

  @Test
  void transitionBackToRunningClearsCompletedAt() {
    var id = newRun("backend", "auth");
    store.transition(id, "running", "stopping");

    assertTrue(store.transition(id, "stopping", "running"));

    var restored = store.findById(id).orElseThrow();
    assertEquals("running", restored.status());
    assertNull(restored.completedAt());
  }

  @Test
  void transitionRunsAlongsideOnlyWhenItWinsAndRollsBackWithIt() {
    var id = newRun("backend", "auth");
    var ran = new AtomicBoolean();

    assertFalse(store.transition(id, "stopping", "stopped", () -> ran.set(true)));
    assertFalse(ran.get(), "a lost transition must never run its alongside work");

    assertThrows(
        IllegalStateException.class,
        () ->
            store.transition(
                id,
                "running",
                "stopping",
                () -> {
                  throw new IllegalStateException("conflict");
                }));
    assertEquals(
        "running",
        store.findById(id).orElseThrow().status(),
        "an alongside failure rolls the transition back with it");
  }

  @Test
  void transitionJournalsARevisionSoTheClaimReplicates() {
    var id = newRun("backend", "auth");
    var before = store.latestRev(id);

    store.transition(id, "running", "stopping");

    assertNotEquals(before, store.latestRev(id));
  }

  @Test
  void runIfLatestAttemptRunsTheWorkForTheSpecsNewestRun() {
    var id = newRun("backend", "auth");
    var ran = new AtomicBoolean();

    assertTrue(store.runIfLatestAttempt(id, "auth", () -> ran.set(true)));
    assertTrue(ran.get());
  }

  @Test
  void runIfLatestAttemptRefusesOnceANewerAttemptExists() {
    var older = newRun("backend", "auth");
    newRun("backend", "auth");
    var ran = new AtomicBoolean();

    assertFalse(store.runIfLatestAttempt(older, "auth", () -> ran.set(true)));
    assertFalse(ran.get(), "a superseded run must never act on its spec");
  }

  @Test
  void runIfLatestAttemptIgnoresReviewRowsWhenPickingTheLatestAttempt() {
    var build = newRun("backend", "auth");
    store.createReview(
        DateTimeUtils.newId().toString(),
        "backend",
        "auth",
        "node-a",
        "node-a",
        "claude-code",
        "feat/x",
        "review",
        "/home/dev/.sail/runs/r/agent.log",
        "sail-review-r");
    var ran = new AtomicBoolean();

    assertTrue(
        store.runIfLatestAttempt(build, "auth", () -> ran.set(true)),
        "a review-lane row is pipeline negotiation, not a newer attempt");
    assertTrue(ran.get());
  }

  @Test
  void transitionWithExitCodeStampsStatusAndCodeInOneWrite() {
    var id = newRun("backend", "auth");

    assertTrue(store.transition(id, "running", "failed", 2));

    var run = store.findById(id).orElseThrow();
    assertEquals("failed", run.status());
    assertEquals(2, run.exitCode());
    assertNotNull(run.completedAt());

    assertFalse(store.transition(id, "running", "completed", 0));
    assertEquals(2, store.findById(id).orElseThrow().exitCode());
  }

  @Test
  void runIfLatestAttemptRollsBackWorkThatFails() {
    var id = newRun("backend", "auth");

    assertThrows(
        IllegalStateException.class,
        () ->
            store.runIfLatestAttempt(
                id,
                "auth",
                () -> {
                  store.complete(id, "stopped", null);
                  throw new IllegalStateException("conflict");
                }));
    assertEquals(
        "running",
        store.findById(id).orElseThrow().status(),
        "failing work rolls back everything it wrote inside the guard");
  }

  @Test
  void stoppingListsOnlyBuildRunsHoldingAClaim() {
    var claimed = newRun("backend", "auth");
    store.transition(claimed, "running", "stopping");
    newRun("backend", "other");
    var review =
        store.createReview(
            DateTimeUtils.newId().toString(),
            "backend",
            "auth",
            "node-a",
            "node-a",
            "codex",
            "feat/x",
            "review",
            "/home/dev/.sail/runs/rev/review.log",
            "sail-review-rev");
    db.execute("UPDATE runs SET status = 'stopping' WHERE id = ?", review);

    var claims = store.stopping();

    assertEquals(1, claims.size());
    assertEquals(claimed, claims.getFirst().id());
  }

  @Test
  void aStoppingRunStaysTheActiveRunForItsProjectAndNode() {
    var id = newRun("backend", "auth");
    store.transition(id, "running", "stopping");

    var active = store.runningForProjectOnNode("backend", "node-a").orElseThrow();

    assertEquals(id, active.id());
  }

  @Test
  void createAndCompleteReviewRun() {
    var id = DateTimeUtils.newId().toString();
    var logPath = "/home/dev/.sail/runs/" + id + "/review.log";

    store.createReview(
        id,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "codex",
        "feat/auth",
        "review it",
        logPath,
        "sail-review-" + id);

    var running = store.findById(id).orElseThrow();
    assertEquals("review", running.role());
    assertEquals("running", running.status());
    assertEquals("node-a", running.node());
    assertEquals("codex", running.agent());
    assertEquals("feat/auth", running.branch());
    assertEquals("review it", running.task());
    assertEquals(logPath, running.logPath());
    assertEquals("sail-review-" + id, running.unit());
    assertEquals("review", store.comparableSnapshot(id).get("role"));

    store.complete(id, "completed", 0);

    var completed = store.findById(id).orElseThrow();
    assertEquals("completed", completed.status());
    assertEquals(0, completed.exitCode());
    assertNotNull(completed.completedAt());
  }

  @Test
  void createRecordsTheLaunchedUnitAndItSurvivesReplication() {
    var id = DateTimeUtils.newId().toString();
    store.create(
        id,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "build",
        "claude-code",
        "feat/x",
        "do it",
        1234,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);

    var run = store.findById(id).orElseThrow();
    assertEquals("sail-agent-" + id, run.unit());
    var snapshot = store.comparableSnapshot(id);
    assertEquals("sail-agent-" + id, snapshot.get("unit"), "the unit replicates with the run");
    var adopted = DateTimeUtils.newId().toString();
    store.applyRevision(adopted, snapshot, "1-remote");
    assertEquals("sail-agent-" + id, store.findById(adopted).orElseThrow().unit());
  }

  @Test
  void createWithNullOptionalFields() {
    var id = DateTimeUtils.newId().toString();
    store.create(
        id,
        "backend",
        null,
        "node-a",
        "node-a",
        "build",
        "codex",
        null,
        null,
        null,
        null,
        null,
        "sail-agent-" + id);

    var run = store.findById(id).orElseThrow();
    assertNull(run.specId());
    assertNull(run.branch());
    assertNull(run.task());
    assertNull(run.pid());
    assertNull(run.watcherPid());
    assertNull(run.exitCode());
    assertNull(run.logPath());
  }

  @Test
  void completeRun() {
    var id = newRun("backend", "auth");
    store.complete(id, "completed", 0);

    var run = store.findById(id).orElseThrow();
    assertEquals("completed", run.status());
    assertNotNull(run.completedAt());
    assertEquals(0, run.exitCode());
  }

  @Test
  void completeRecordsANonZeroExitCode() {
    var id = newRun("backend", "auth");
    store.complete(id, "stopped", 137);

    assertEquals(137, store.findById(id).orElseThrow().exitCode());
  }

  @Test
  void completeRunsAlongsideWorkInTheSameTransaction() {
    var id = newRun("backend", "auth");
    var other = newRun("backend", "auth");

    store.complete(id, "stopped", null, () -> store.complete(other, "stopped", null));

    assertEquals("stopped", store.findById(id).orElseThrow().status());
    assertEquals("stopped", store.findById(other).orElseThrow().status());
  }

  @Test
  void aFailureAfterAlongsideWorkRollsBackBothWrites() {
    var id = newRun("backend", "auth");
    var other = newRun("backend", "auth");

    assertThrows(
        IllegalStateException.class,
        () ->
            store.complete(
                id,
                "stopped",
                null,
                () -> {
                  store.complete(other, "stopped", null);
                  throw new IllegalStateException("boom");
                }));

    assertEquals("running", store.findById(id).orElseThrow().status());
    assertEquals("running", store.findById(other).orElseThrow().status());
  }

  @Test
  void recordExitCodeStampsAnAlreadyFinishedRun() {
    var id = newRun("backend", "auth");
    store.complete(id, "stopped", null);
    store.recordExitCode(id, 42);

    assertEquals(42, store.findById(id).orElseThrow().exitCode());
  }

  private String newRunOn(String project, String specId, String node) {
    var id = DateTimeUtils.newId().toString();
    return store.create(
        id,
        project,
        specId,
        node,
        node,
        "build",
        "claude-code",
        "feat/x",
        "do it",
        1234,
        5678,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
  }

  @Test
  void latestForProjectOnNodeReturnsNewest() {
    newRun("backend", "spec-1");
    newRun("backend", "spec-2");
    newRun("other", "spec-3");

    assertEquals(
        "spec-2", store.latestForProjectOnNode("backend", "node-a").orElseThrow().specId());
    assertTrue(store.latestForProjectOnNode("nonexistent", "node-a").isEmpty());
  }

  @Test
  void buildSessionQueriesIgnoreRunningReviewRows() {
    var buildId = newRun("backend", "auth");
    var reviewId = DateTimeUtils.newId().toString();
    store.createReview(
        reviewId,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "codex",
        "feat/x",
        "review it",
        "/home/dev/.sail/runs/" + reviewId + "/review.log",
        "sail-review-" + reviewId);

    assertEquals(buildId, store.latestForProjectOnNode("backend", "node-a").orElseThrow().id());
    assertEquals(buildId, store.runningForProjectOnNode("backend", "node-a").orElseThrow().id());
    assertEquals(List.of(buildId), store.running().stream().map(RunStore.RunRow::id).toList());
    assertEquals(2, store.listForProject("backend").size(), "the aggregate still lists both roles");
  }

  @Test
  void startupFailsOnlyLocalRunningReviewRows() {
    var local = DateTimeUtils.newId().toString();
    var foreign = DateTimeUtils.newId().toString();
    store.createReview(
        local,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "codex",
        "b",
        "t",
        "/runs/" + local,
        "sail-review-l");
    store.createReview(
        foreign,
        "backend",
        "auth",
        "node-b",
        "node-b",
        "codex",
        "b",
        "t",
        "/runs/" + foreign,
        "sail-review-f");

    assertEquals(1, store.failRunningReviewsOnNode("node-a"));
    assertEquals("failed", store.findById(local).orElseThrow().status());
    assertNotNull(store.findById(local).orElseThrow().completedAt());
    assertEquals("running", store.findById(foreign).orElseThrow().status());
  }

  @Test
  void latestForProjectOnNodeExcludesForeignRuns() {
    newRunOn("backend", "mine", "node-a");
    newRunOn("backend", "theirs", "node-b");

    assertEquals("mine", store.latestForProjectOnNode("backend", "node-a").orElseThrow().specId());
    assertEquals(
        "theirs", store.latestForProjectOnNode("backend", "node-b").orElseThrow().specId());
  }

  @Test
  void latestForProjectOnNodeWithNoHandleOwnsOnlyBlankNodeRuns() {
    newRunOn("backend", "legacy", "");
    newRunOn("backend", "theirs", "node-b");

    assertEquals("legacy", store.latestForProjectOnNode("backend", null).orElseThrow().specId());
    assertEquals("legacy", store.latestForProjectOnNode("backend", "").orElseThrow().specId());
  }

  @Test
  void runningForProjectOnNodeFindsActiveRun() {
    var id1 = newRun("backend", "spec-1");
    store.complete(id1, "completed", 0);
    newRun("backend", "spec-2");

    var running = store.runningForProjectOnNode("backend", "node-a").orElseThrow();
    assertEquals("spec-2", running.specId());
    assertEquals("running", running.status());
  }

  @Test
  void runningForProjectOnNodeIgnoresAForeignRunningRun() {
    newRunOn("backend", "theirs", "node-b");

    assertTrue(store.runningForProjectOnNode("backend", "node-a").isEmpty());
    assertEquals(
        "theirs", store.runningForProjectOnNode("backend", "node-b").orElseThrow().specId());
  }

  @Test
  void runningForProjectOnNodeReturnsEmptyWhenAllCompleted() {
    var id = newRun("backend", "spec-1");
    store.complete(id, "completed", 0);

    assertTrue(store.runningForProjectOnNode("backend", "node-a").isEmpty());
  }

  @Test
  void listForProjectReturnsNewestFirst() {
    newRun("backend", "spec-1");
    newRun("backend", "spec-2");
    newRun("other", "spec-3");

    var runs = store.listForProject("backend");
    assertEquals(2, runs.size());
    assertEquals("spec-2", runs.get(0).specId());
    assertEquals("spec-1", runs.get(1).specId());
  }

  @Test
  void listForSpecReturnsRunsAcrossProjects() {
    newRun("backend", "auth");
    newRun("frontend", "auth");
    newRun("backend", "payment");

    assertEquals(2, store.listForSpec("auth").size());
  }

  @Test
  void listNarrowsByProjectAndSpec() {
    newRun("backend", "auth");
    newRun("backend", "payment");
    newRun("frontend", "auth");

    assertEquals(3, store.list(null, null).size());
    assertEquals(2, store.list("backend", null).size());
    assertEquals(2, store.list(null, "auth").size());
    assertEquals(1, store.list("backend", "auth").size());
  }

  @Test
  void findByIdReturnsEmptyForUnknown() {
    assertTrue(store.findById("nonexistent").isEmpty());
  }

  @Test
  void mutationsJournalComparableRevisions() {
    var id = newRun("backend", "auth");

    assertTrue(store.syncEntityIds().contains(id));
    var running = store.comparableSnapshot(id);
    assertEquals("running", running.get("status"));
    assertNull(running.get("id"), "the surrogate id is excluded from the comparable snapshot");
    var rev1 = store.latestRev(id);

    store.complete(id, "completed", 0);

    assertNotEquals(rev1, store.latestRev(id));
    assertEquals("completed", store.comparableSnapshot(id).get("status"));
    assertEquals("running", store.comparableAtRev(id, rev1).get("status"));
  }

  @Test
  void applyRevisionAdoptsMainsStateAtItsExactRevAsTheBase() {
    var id = newRun("backend", "auth");
    var snapshot = store.comparableSnapshot(id);
    var rev = store.latestRev(id);

    var other = freshStore("adopt.db");
    other.applyRevision(id, snapshot, rev);

    assertEquals(rev, other.latestRev(id));
    assertEquals(rev, other.baseRevOf(id));
    assertEquals("auth", other.findById(id).orElseThrow().specId());
    assertEquals("node-a", other.findById(id).orElseThrow().node());
  }

  @Test
  void commitAcceptsWhenExpectedRevMatchesAndRejectsWhenStale() {
    var other = freshStore("commit.db");
    other.applyRevision("00000000-0000-7000-8000-000000000001", base(), "rev-base");

    var accepted =
        other.commitRevision("00000000-0000-7000-8000-000000000001", moved(), "rev-base");
    assertInstanceOf(PushOutcome.Accepted.class, accepted);

    var stale = other.commitRevision("00000000-0000-7000-8000-000000000001", moved(), "rev-base");
    assertInstanceOf(PushOutcome.Stale.class, stale);
  }

  @Test
  void applyRevisionNullAdoptsADeletion() {
    var id = newRun("backend", "auth");
    var rev = store.latestRev(id);

    store.applyRevision(id, null, rev + "-del");

    assertTrue(store.findById(id).isEmpty());
    assertNull(store.comparableSnapshot(id));
  }

  @Test
  void resolveConflictTakeTheirsAdoptsRemote() {
    var other = freshStore("resolve.db");
    other.applyRevision("00000000-0000-7000-8000-000000000001", base(), "rev-base");

    other.resolveConflict("00000000-0000-7000-8000-000000000001", theirs(), theirs());

    assertEquals(
        "completed", other.findById("00000000-0000-7000-8000-000000000001").orElseThrow().status());
  }

  private RunStore freshStore(String file) {
    var freshDb = Sqlite.open(tempDir.resolve(file));
    new SchemaManager(freshDb).migrate();
    return new RunStore(freshDb);
  }

  private static java.util.Map<String, Object> base() {
    return java.util.Map.of(
        "project",
        "backend",
        "spec_id",
        "auth",
        "node",
        "node-a",
        "role",
        "build",
        "agent",
        "claude-code",
        "status",
        "running");
  }

  private static java.util.Map<String, Object> moved() {
    return java.util.Map.of(
        "project",
        "backend",
        "spec_id",
        "auth",
        "node",
        "node-a",
        "role",
        "build",
        "agent",
        "claude-code",
        "status",
        "completed");
  }

  private static java.util.Map<String, Object> theirs() {
    return moved();
  }

  private java.util.Optional<DispatchGate.Conflict> reserve(
      RunStore target, String id, String specId, String node, java.util.List<String> repos) {
    return conflictOf(
        target.reserveDispatch(
            id,
            "backend",
            specId,
            node,
            node,
            "build",
            repos,
            "claude-code",
            "feat/x",
            "do it",
            "/home/dev/.sail/runs/" + id + "/agent.log",
            "sail-agent-" + id));
  }

  private static java.util.Optional<DispatchGate.Conflict> conflictOf(
      RunStore.Reservation reservation) {
    return reservation instanceof RunStore.Reservation.Conflicted conflicted
        ? java.util.Optional.of(conflicted.conflict())
        : java.util.Optional.empty();
  }

  private java.util.Optional<DispatchGate.Conflict> reserveAdhoc(String id, String node) {
    return conflictOf(
        store.reserveDispatch(
            id,
            "backend",
            "",
            node,
            node,
            "adhoc",
            java.util.List.of(),
            "claude-code",
            null,
            "do it",
            "/home/dev/.sail/runs/" + id + "/agent.log",
            "sail-agent-" + id));
  }

  @Test
  void anAdhocReservationRecordsItsRoleAndEmptyRepoSet() {
    var id = DateTimeUtils.newId().toString();

    var conflict = reserveAdhoc(id, "node-a");

    assertTrue(conflict.isEmpty());
    var run = store.findById(id).orElseThrow();
    assertEquals("adhoc", run.role());
    assertEquals("", run.specId());
    assertEquals(java.util.List.of(), run.repos());
    assertEquals("running", run.status());
    assertEquals("sail-agent-" + id, run.unit());
  }

  @Test
  void anAdhocReservationBlocksAnyDispatch() {
    reserveAdhoc(DateTimeUtils.newId().toString(), "node-a");

    var conflict =
        reserve(
            store, DateTimeUtils.newId().toString(), "auth", "node-a", java.util.List.of("app"));

    assertTrue(conflict.isPresent(), "an ad-hoc session reserves the whole container");
  }

  @Test
  void aRunningDispatchBlocksAnAdhocReservation() {
    reserve(store, DateTimeUtils.newId().toString(), "auth", "node-a", java.util.List.of("app"));

    var conflict = reserveAdhoc(DateTimeUtils.newId().toString(), "node-a");

    assertTrue(conflict.isPresent(), "an ad-hoc target overlaps every reserved repo set");
  }

  @Test
  void anAdhocReservationBlocksASecondAdhocReservation() {
    reserveAdhoc(DateTimeUtils.newId().toString(), "node-a");

    var conflict = reserveAdhoc(DateTimeUtils.newId().toString(), "node-a");

    assertTrue(conflict.isPresent());
  }

  @Test
  void aFinishedAdhocRunFreesTheContainerForTheNextReservation() {
    var first = DateTimeUtils.newId().toString();
    reserveAdhoc(first, "node-a");
    store.complete(first, "stopped", 0);

    assertTrue(reserveAdhoc(DateTimeUtils.newId().toString(), "node-a").isEmpty());
  }

  @Test
  void aLegacyNullSpecRunningRunStillGatesReservationsWithoutBlowingUp() {
    var id = DateTimeUtils.newId().toString();
    store.create(
        id,
        "backend",
        null,
        "node-a",
        "node-a",
        "build",
        "codex",
        null,
        null,
        null,
        null,
        null,
        "sail-agent-" + id);

    var conflict = reserveAdhoc(DateTimeUtils.newId().toString(), "node-a");

    assertTrue(conflict.isPresent());
  }

  @Test
  void sessionQueriesIncludeAdhocRuns() {
    var id = DateTimeUtils.newId().toString();
    reserveAdhoc(id, "node-a");

    assertEquals(id, store.runningForProjectOnNode("backend", "node-a").orElseThrow().id());
    assertEquals(id, store.latestForProjectOnNode("backend", "node-a").orElseThrow().id());
    assertEquals(java.util.List.of(id), store.running().stream().map(RunStore.RunRow::id).toList());
    assertTrue(store.transition(id, "running", "stopping"));
    assertEquals(
        java.util.List.of(id), store.stopping().stream().map(RunStore.RunRow::id).toList());
  }

  @Test
  void sessionRoleCoversBuildAndAdhocButNeverReview() {
    var adhoc = DateTimeUtils.newId().toString();
    reserveAdhoc(adhoc, "node-a");
    var build = newRun("backend", "auth");
    var review = DateTimeUtils.newId().toString();
    store.createReview(
        review,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "codex",
        "feat/x",
        "review",
        "/home/dev/.sail/runs/rev/review.log",
        "sail-review-rev");

    assertTrue(store.findById(adhoc).orElseThrow().sessionRole());
    assertTrue(store.findById(build).orElseThrow().sessionRole());
    assertFalse(store.findById(build).orElseThrow().adhocRole());
    assertTrue(store.findById(adhoc).orElseThrow().adhocRole());
    assertFalse(store.findById(review).orElseThrow().sessionRole());
    assertFalse(store.findById(adhoc).orElseThrow().buildRole());
  }

  @Test
  void adhocRunsNeverSurfaceAsSpecAttempts() {
    var adhoc = DateTimeUtils.newId().toString();
    reserveAdhoc(adhoc, "node-a");

    assertTrue(store.listForSpec("").stream().noneMatch(RunStore.RunRow::buildRole));
    assertFalse(store.runIfLatestAttempt(adhoc, "", () -> {}));
  }

  @Test
  void createReviewRecordsItsUnit() {
    var id = DateTimeUtils.newId().toString();

    store.createReview(
        id,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "codex",
        "feat/auth",
        "review it",
        "/home/dev/.sail/runs/" + id + "/review.log",
        "sail-review-" + id);

    assertEquals("sail-review-" + id, store.findById(id).orElseThrow().unit());
  }

  @Test
  void reserveDispatchPersistsTheReservedReposOnTheRunningRun() {
    var id = DateTimeUtils.newId().toString();

    var conflict = reserve(store, id, "auth", "node-a", java.util.List.of("app", "web"));

    assertTrue(conflict.isEmpty());
    var run = store.findById(id).orElseThrow();
    assertEquals(java.util.List.of("app", "web"), run.repos());
    assertEquals("running", run.status());
    assertEquals("build", run.role());
    assertEquals("sail-agent-" + id, run.unit());
  }

  @Test
  void reservedReposSurviveReplication() {
    var id = DateTimeUtils.newId().toString();
    reserve(store, id, "auth", "node-a", java.util.List.of("app"));

    var snapshot = store.comparableSnapshot(id);
    assertEquals(java.util.List.of("app"), snapshot.get("repos"));
    var adopted = DateTimeUtils.newId().toString();
    store.applyRevision(adopted, snapshot, "1-remote");
    assertEquals(java.util.List.of("app"), store.findById(adopted).orElseThrow().repos());
  }

  @Test
  void reserveDispatchRefusesAnOverlapAndInsertsNothing() {
    var first = DateTimeUtils.newId().toString();
    reserve(store, first, "auth", "node-a", java.util.List.of("app", "web"));

    var second = DateTimeUtils.newId().toString();
    var conflict = reserve(store, second, "billing", "node-a", java.util.List.of("web", "docs"));

    var blocked = conflict.orElseThrow();
    assertEquals(first, blocked.run().runId());
    assertEquals("auth", blocked.run().specId());
    assertEquals(java.util.List.of("web"), blocked.overlap());
    assertTrue(store.findById(second).isEmpty(), "a refused reservation must not insert a row");
  }

  @Test
  void reserveDispatchAdmitsDisjointRepos() {
    reserve(store, DateTimeUtils.newId().toString(), "auth", "node-a", java.util.List.of("app"));

    var second = DateTimeUtils.newId().toString();
    var conflict = reserve(store, second, "web-work", "node-a", java.util.List.of("web"));

    assertTrue(conflict.isEmpty());
    assertEquals("running", store.findById(second).orElseThrow().status());
  }

  @Test
  void anEmptyRepoSetReservesTheWholeContainer() {
    reserve(store, DateTimeUtils.newId().toString(), "auth", "node-a", java.util.List.of());

    var conflict =
        reserve(
            store, DateTimeUtils.newId().toString(), "billing", "node-a", java.util.List.of("web"));

    assertTrue(conflict.isPresent());
    assertEquals(java.util.List.of(), conflict.orElseThrow().overlap());
  }

  @Test
  void aLegacyRunningRunWithoutReposBlocksEveryReservation() {
    newRun("backend", "auth");

    var conflict =
        reserve(
            store, DateTimeUtils.newId().toString(), "billing", "node-a", java.util.List.of("web"));

    assertTrue(conflict.isPresent(), "a row the gate cannot scope reads as whole-container");
  }

  @Test
  void aLiveRunOfTheSameSpecBlocksItsOwnReReservationEvenOnDisjointRepos() {
    var first = DateTimeUtils.newId().toString();
    reserve(store, first, "auth", "node-a", java.util.List.of("app"));

    var conflict =
        reserve(
            store, DateTimeUtils.newId().toString(), "auth", "node-a", java.util.List.of("web"));

    assertEquals(first, conflict.orElseThrow().run().runId());
  }

  @Test
  void aFinishedRunNeverBlocksAReservation() {
    var first = DateTimeUtils.newId().toString();
    reserve(store, first, "auth", "node-a", java.util.List.of("app"));
    store.complete(first, "stopped", 0);

    var conflict =
        reserve(
            store, DateTimeUtils.newId().toString(), "billing", "node-a", java.util.List.of("app"));

    assertTrue(conflict.isEmpty());
  }

  @Test
  void aForeignNodesRunNeverBlocksAReservation() {
    reserve(store, DateTimeUtils.newId().toString(), "auth", "raj", java.util.List.of("app"));

    var conflict =
        reserve(
            store, DateTimeUtils.newId().toString(), "billing", "node-a", java.util.List.of("app"));

    assertTrue(conflict.isEmpty());
  }

  @Test
  void concurrentReservationsAcrossConnectionsAdmitExactlyOne() throws Exception {
    var path = tempDir.resolve("test.db");
    var contenders = 4;
    var start = new java.util.concurrent.CountDownLatch(1);
    var admitted = new java.util.concurrent.atomic.AtomicInteger();
    var threads = new java.util.ArrayList<Thread>();
    for (var i = 0; i < contenders; i++) {
      var spec = "spec-" + i;
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var connection = Sqlite.open(path)) {
                      var contender = new RunStore(connection);
                      start.await();
                      var id = DateTimeUtils.newId().toString();
                      if (reserve(contender, id, spec, "node-a", java.util.List.of("app"))
                          .isEmpty()) {
                        admitted.incrementAndGet();
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
    }
    start.countDown();
    for (var thread : threads) {
      thread.join();
    }

    assertEquals(
        1,
        admitted.get(),
        "the BEGIN IMMEDIATE reservation must serialize dispatches across connections");
  }

  private String reservedCredential(String id, String specId, java.util.List<String> repos) {
    var reservation =
        store.reserveDispatch(
            id,
            "backend",
            specId,
            "node-a",
            "uday",
            "build",
            repos,
            "claude-code",
            "feat/x",
            "do it",
            "/home/dev/.sail/runs/" + id + "/agent.log",
            "sail-agent-" + id);
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  private long credentialRows(String id) {
    return db.queryOne(
            "SELECT COUNT(*) FROM run_credentials WHERE run_id = ?", row -> row.integer(0), id)
        .orElseThrow();
  }

  @Test
  void reserveDispatchMintsThePrincipalAndCredentialWithTheRow() {
    var id = DateTimeUtils.newId().toString();

    var credential = reservedCredential(id, "auth", java.util.List.of("app"));

    var run = store.findById(id).orElseThrow();
    assertEquals("claude/" + id, run.principal());
    assertEquals("uday", run.owner());
    assertTrue(credential.startsWith("sailrun_"));
    assertEquals(id, store.findByCredential(credential).orElseThrow().id());
    assertTrue(
        db.queryOne(
                    "SELECT credential_hash FROM run_credentials WHERE run_id = ?",
                    row -> row.text(0),
                    id)
                .orElseThrow()
                .length()
            == 64,
        "only the SHA-256 hash is at rest, never the plaintext");
  }

  @Test
  void aRefusedReservationMintsNothing() {
    reservedCredential(DateTimeUtils.newId().toString(), "auth", java.util.List.of("app"));
    var second = DateTimeUtils.newId().toString();

    var conflict = reserve(store, second, "billing", "node-a", java.util.List.of("app"));

    assertTrue(conflict.isPresent());
    assertEquals(0, credentialRows(second));
  }

  @Test
  void anAdhocReservationMintsAPrincipalOwnedByTheLaunchingNode() {
    var id = DateTimeUtils.newId().toString();

    reserveAdhoc(id, "node-a");

    var run = store.findById(id).orElseThrow();
    assertEquals("claude/" + id, run.principal());
    assertEquals("node-a", run.owner());
    assertEquals(1, credentialRows(id));
  }

  @Test
  void createReviewMintsAReviewMarkedPrincipalOwnedByTheAssignee() {
    var id = DateTimeUtils.newId().toString();

    store.createReview(
        id,
        "backend",
        "auth",
        "node-a",
        "uday",
        "codex",
        "feat/x",
        "review it",
        "/home/dev/.sail/runs/" + id + "/review.log",
        "sail-review-" + id);

    var run = store.findById(id).orElseThrow();
    assertEquals("codex/review-" + id, run.principal());
    assertEquals("uday", run.owner());
    assertEquals(1, credentialRows(id));
  }

  @Test
  void createReviewReturnsTheLiveCredentialOfItsRun() {
    var id = DateTimeUtils.newId().toString();

    var credential = createReview(id);

    assertTrue(credential.startsWith("sailrun_"));
    assertEquals(id, store.findByCredential(credential).orElseThrow().id());
  }

  @Test
  void rotateCredentialRetiresTheOldPlaintextAndMintsAFreshOne() {
    var id = DateTimeUtils.newId().toString();
    var original = createReview(id);

    var rotated = store.rotateCredential(id);

    assertEquals(id, store.findByCredential(rotated).orElseThrow().id());
    assertTrue(
        store.findByCredential(original).isEmpty(),
        "the previous invocation's credential is retired by the rotation");
    assertEquals(1, credentialRows(id), "a run holds exactly one credential at a time");

    assertTrue(store.transition(id, "running", "completed", 0));

    assertTrue(store.findByCredential(rotated).isEmpty(), "the credential dies with the run");
    assertEquals(0, credentialRows(id));
  }

  @Test
  void rotateCredentialRefusesAMissingOrFinishedRun() {
    assertThrows(IllegalStateException.class, () -> store.rotateCredential("ghost"));

    var id = DateTimeUtils.newId().toString();
    createReview(id);
    assertTrue(store.transition(id, "running", "completed", 0));

    assertThrows(
        IllegalStateException.class,
        () -> store.rotateCredential(id),
        "a dead run's identity is never resurrected");
  }

  private String createReview(String id) {
    return store.createReview(
        id,
        "backend",
        "auth",
        "node-a",
        "uday",
        "codex",
        "feat/x",
        "review it",
        "/home/dev/.sail/runs/" + id + "/review.log",
        "sail-review-" + id);
  }

  @Test
  void findByCredentialRejectsBlankUnknownAndRevoked() {
    var id = DateTimeUtils.newId().toString();
    var credential = reservedCredential(id, "auth", java.util.List.of("app"));

    assertTrue(store.findByCredential(null).isEmpty());
    assertTrue(store.findByCredential("").isEmpty());
    assertTrue(store.findByCredential("sailrun_wrong").isEmpty());

    store.transition(id, "running", "completed", 0);

    assertTrue(store.findByCredential(credential).isEmpty(), "a finished run's credential is dead");
  }

  @Test
  void anExpiredCredentialIsRejectedAndPrunedOnLookup() {
    var id = DateTimeUtils.newId().toString();
    var credential = reservedCredential(id, "auth", java.util.List.of("app"));
    db.execute(
        "UPDATE run_credentials SET expires_at = ? WHERE run_id = ?", "2000-01-01T00:00:00Z", id);

    assertTrue(store.findByCredential(credential).isEmpty());
    assertEquals(0, credentialRows(id), "the expired row is pruned on lookup");
  }

  @Test
  void everyTerminalTransitionRevokesTheCredential() {
    for (var terminal : java.util.List.of("completed", "stopped", "failed")) {
      var id = DateTimeUtils.newId().toString();
      var credential = reservedCredential(id, "spec-" + terminal, java.util.List.of(terminal));

      assertTrue(store.transition(id, "running", terminal));

      assertTrue(store.findByCredential(credential).isEmpty(), terminal);
      assertEquals(0, credentialRows(id), terminal);
    }
  }

  @Test
  void aStopClaimKeepsTheCredentialUntilTheVerifiedFinish() {
    var id = DateTimeUtils.newId().toString();
    var credential = reservedCredential(id, "auth", java.util.List.of("app"));

    assertTrue(store.transition(id, "running", "stopping"));
    assertTrue(
        store.findByCredential(credential).isPresent(),
        "mid-stop the agent may still be alive; only the verified finish revokes");

    assertTrue(store.transition(id, "stopping", "stopped"));
    assertTrue(store.findByCredential(credential).isEmpty());
  }

  @Test
  void aLostTransitionRevokesNothing() {
    var id = DateTimeUtils.newId().toString();
    var credential = reservedCredential(id, "auth", java.util.List.of("app"));

    assertFalse(store.transition(id, "stopping", "stopped"));

    assertTrue(store.findByCredential(credential).isPresent());
  }

  @Test
  void completeRevokesTheCredential() {
    var id = DateTimeUtils.newId().toString();
    var credential = reservedCredential(id, "auth", java.util.List.of("app"));

    store.complete(id, "failed", 1);

    assertTrue(store.findByCredential(credential).isEmpty());
  }

  @Test
  void principalAndOwnerReplicateInTheComparableSnapshot() {
    var id = DateTimeUtils.newId().toString();
    reservedCredential(id, "auth", java.util.List.of("app"));

    var snapshot = store.comparableSnapshot(id);

    assertEquals("claude/" + id, snapshot.get("principal"));
    assertEquals("uday", snapshot.get("owner"));
    assertFalse(
        snapshot.containsKey("credential_hash"), "secrets never join a replicated snapshot");
  }
}
