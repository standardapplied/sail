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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
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
    assertEquals(
        Set.of(buildId, reviewId),
        store.runningForPresence().stream().map(RunStore.RunRow::id).collect(Collectors.toSet()),
        "presence covers the review execution too — it is an agent at work");
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
  void stampActivityCoalescesToOneWritePerFloorWindow() {
    var id = newRun("backend", "auth");

    assertTrue(store.stampActivity(id, Duration.ofSeconds(30)));
    var first = store.findById(id).orElseThrow().lastActivityAt();
    assertNotNull(first);
    assertFalse(
        store.stampActivity(id, Duration.ofSeconds(30)),
        "a second stamp inside the floor window is skipped");
    assertEquals(first, store.findById(id).orElseThrow().lastActivityAt());
  }

  @Test
  void stampActivityRefreshesOncePastTheFloor() {
    var id = newRun("backend", "auth");
    assertTrue(store.stampActivity(id, Duration.ZERO));

    assertTrue(
        store.stampActivity(id, Duration.ZERO),
        "a zero floor coalesces nothing — every stamp writes");
  }

  @Test
  void stampActivityJournalsNoRevisionAndRidesTheNextRealOne() {
    var id = newRun("backend", "auth");
    var rev = store.latestRev(id);

    store.stampActivity(id, Duration.ofSeconds(30));

    assertEquals(rev, store.latestRev(id), "presence stamping never mints revisions of its own");
    assertNull(store.comparableAtRev(id, rev).get("last_activity_at"));

    store.complete(id, "completed", 0);

    assertNotNull(
        store.comparableSnapshot(id).get("last_activity_at"),
        "the stamp rides along on the run's next real revision");
  }

  @Test
  void stampActivityRefusesANonRunningRun() {
    var id = newRun("backend", "auth");
    store.complete(id, "completed", 0);

    assertFalse(store.stampActivity(id, Duration.ofSeconds(30)));
    assertNull(store.findById(id).orElseThrow().lastActivityAt());
  }

  @Test
  void anOldShapeSnapshotDerivesANullActivityStamp() {
    var id = newRun("backend", "auth");
    var snapshot = store.comparableSnapshot(id);
    snapshot.remove("last_activity_at");
    var rev = store.latestRev(id);

    var other = freshStore("old-shape.db");
    other.applyRevision(id, snapshot, rev);

    assertNull(
        other.findById(id).orElseThrow().lastActivityAt(),
        "a pre-upgrade snapshot must read as unknown activity, never as quiet");
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

  private java.util.Optional<DispatchGate.Conflict> reserveRoom(
      String id, String specId, String node) {
    return conflictOf(
        store.reserveDispatch(
            id,
            "backend",
            specId,
            node,
            node,
            "room",
            java.util.List.of(),
            "claude-code",
            null,
            "answer in the room",
            "/home/dev/.sail/runs/" + id + "/agent.log",
            "sail-agent-" + id));
  }

  @Test
  void aRoomReservationMintsItsMarkedPrincipalAndSessionRole() {
    var id = DateTimeUtils.newId().toString();

    var conflict = reserveRoom(id, "auth", "node-a");

    assertTrue(conflict.isEmpty());
    var run = store.findById(id).orElseThrow();
    assertEquals("room", run.role());
    assertEquals("claude/room-" + id, run.principal());
    assertTrue(run.roomRole());
    assertTrue(run.sessionRole(), "room runs join the stop/status/reaper lanes");
    assertEquals(java.util.List.of("claude/room-" + id), store.principals(id));
  }

  @Test
  void inviteRolesMintTheInvitePrincipalAndJoinTheLanePredicates() {
    var readOnly = DateTimeUtils.newId().toString();
    var full = DateTimeUtils.newId().toString();
    store.create(
        readOnly,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "invite",
        "claude-code",
        null,
        "consult",
        null,
        null,
        null,
        "sail-agent-" + readOnly);
    store.create(
        full,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "invite-full",
        "codex",
        null,
        "pitch in",
        null,
        null,
        null,
        "sail-agent-" + full);

    var readOnlyRun = store.findById(readOnly).orElseThrow();
    var fullRun = store.findById(full).orElseThrow();
    assertEquals("claude/invite-" + readOnly, readOnlyRun.principal());
    assertEquals("codex/invite-" + full, fullRun.principal());
    assertTrue(readOnlyRun.inviteRole());
    assertTrue(fullRun.inviteRole());
    assertTrue(readOnlyRun.readOnlyLane(), "a read-only invite carries the room contract");
    assertFalse(fullRun.readOnlyLane(), "a full invite is member-tier, never viewer");
    assertTrue(readOnlyRun.sessionRole(), "invites join the stop/status/reaper lanes");
    assertTrue(fullRun.sessionRole());
    assertFalse(readOnlyRun.buildRole());
    assertTrue(store.running().stream().map(RunStore.RunRow::id).toList().contains(readOnly));
    assertTrue(store.running().stream().map(RunStore.RunRow::id).toList().contains(full));
  }

  @Test
  void aRoomReservationNeverBlocksADisjointSpecsDispatchAndViceVersa() {
    reserveRoom(DateTimeUtils.newId().toString(), "auth", "node-a");

    assertTrue(
        reserve(
                store,
                DateTimeUtils.newId().toString(),
                "other",
                "node-a",
                java.util.List.of("app"))
            .isEmpty(),
        "a chat never blocks another spec's build");

    assertTrue(reserveRoom(DateTimeUtils.newId().toString(), "third", "node-a").isEmpty());
  }

  @Test
  void aRoomReservationSerializesWithItsOwnSpecsRuns() {
    reserveRoom(DateTimeUtils.newId().toString(), "auth", "node-a");

    assertTrue(
        reserve(store, DateTimeUtils.newId().toString(), "auth", "node-a", java.util.List.of("app"))
            .isPresent(),
        "a wake and a dispatch on one spec serialize");
    assertTrue(reserveRoom(DateTimeUtils.newId().toString(), "auth", "node-a").isPresent());
  }

  @Test
  void aLiveBuildBlocksItsOwnSpecsRoomReservation() {
    reserve(store, DateTimeUtils.newId().toString(), "auth", "node-a", java.util.List.of("app"));

    assertTrue(reserveRoom(DateTimeUtils.newId().toString(), "auth", "node-a").isPresent());
    assertTrue(reserveRoom(DateTimeUtils.newId().toString(), "other", "node-a").isEmpty());
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

    var rotated = store.rotateCredential(id, "claude-code", "fix");

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
  void rotateCredentialStampsTheRejoiningInvocationsIdentityAndJournalsIt() {
    var id = DateTimeUtils.newId().toString();
    createReview(id);

    store.rotateCredential(id, "claude-code", "fix");

    var asFix = store.findById(id).orElseThrow();
    assertEquals("claude-code", asFix.agent());
    assertEquals("claude/fix-" + id, asFix.principal());
    assertEquals("review", asFix.role(), "the row stays a review run; only the identity changes");
    assertEquals(
        "claude/fix-" + id,
        store.comparableSnapshot(id).get("principal"),
        "the honest attribution joins the journaled snapshot and replicates");

    store.rotateCredential(id, "codex", "review");

    var asReviewer = store.findById(id).orElseThrow();
    assertEquals("codex", asReviewer.agent());
    assertEquals("codex/review-" + id, asReviewer.principal());
  }

  @Test
  void rotateCredentialRefusesAMissingOrFinishedRun() {
    assertThrows(
        IllegalStateException.class, () -> store.rotateCredential("ghost", "codex", "review"));

    var id = DateTimeUtils.newId().toString();
    createReview(id);
    assertTrue(store.transition(id, "running", "completed", 0));

    assertThrows(
        IllegalStateException.class,
        () -> store.rotateCredential(id, "codex", "review"),
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
  void aCredentialWithoutAConfiguredHardStopNeverExpires() {
    var id = DateTimeUtils.newId().toString();
    var credential = reservedCredential(id, "auth", java.util.List.of("app"));

    var nullExpiryRows =
        db.queryOne(
                "SELECT COUNT(*) FROM run_credentials WHERE run_id = ? AND expires_at IS NULL",
                row -> row.integer(0),
                id)
            .orElseThrow();

    assertEquals(1L, nullExpiryRows, "no expiry at rest");
    assertEquals(id, store.findByCredential(credential).orElseThrow().id());
  }

  @Test
  void credentialExpiryCoversTheConfiguredHardStopPlusGrace() {
    var id = DateTimeUtils.newId().toString();
    var maxDuration = Duration.ofHours(169);

    var reservation =
        store.reserveDispatch(
            id,
            "backend",
            "auth",
            "node-a",
            "uday",
            "build",
            java.util.List.of("app"),
            "claude-code",
            "feat/x",
            "do it",
            "/home/dev/.sail/runs/" + id + "/agent.log",
            "sail-agent-" + id,
            maxDuration);
    var credential = ((RunStore.Reservation.Reserved) reservation).credential();

    var row =
        db.queryOne(
                "SELECT created_at, expires_at FROM run_credentials WHERE run_id = ?",
                r -> new String[] {r.text(0), r.text(1)},
                id)
            .orElseThrow();
    var expected = Instant.parse(row[0]).plus(maxDuration).plus(RunStore.CREDENTIAL_GRACE);
    assertEquals(
        expected,
        Instant.parse(row[1]),
        "the credential outlives the run's configured hard stop by exactly the grace window");
    assertEquals(id, store.findByCredential(credential).orElseThrow().id());
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

  @Test
  void deliveryLedgerTracksExactIdsIdempotentlyWithoutChurningRevisions() {
    var id = newRun("backend", "auth");
    var revBefore = store.latestRev(id);
    var messages = new MessageStore(db);
    var first = messages.append("auth", "ada", "one", null).id();
    var second = messages.append("auth", "ada", "two", null).id();

    store.markDelivered(id, List.of(first, second));
    store.markDelivered(id, List.of(first));

    assertEquals(
        java.util.Set.of(first, second),
        store.deliveredMessageIds(id),
        "a replayed acknowledgement is a no-op, never an error");
    assertEquals(revBefore, store.latestRev(id), "delivery bookkeeping never journals a revision");
  }

  @Test
  void deletingARunCascadesAwayItsDeliveryLedger() {
    var id = newRun("backend", "auth");
    var messages = new MessageStore(db);
    var delivered = messages.append("auth", "ada", "seen", null).id();
    store.markDelivered(id, List.of(delivered));

    store.applyRevision(id, null, "2-gone");

    assertTrue(store.findById(id).isEmpty());
    assertTrue(
        store.deliveredMessageIds(id).isEmpty(),
        "the ledger is local bookkeeping for a run that no longer exists — the foreign key"
            + " cascade leaves no orphaned rows behind");
  }

  @Test
  void deliveryLedgerIsEmptyForUnknownOrUndeliveredRunsAndValidatesIds() {
    assertTrue(store.deliveredMessageIds("nope").isEmpty());
    var id = newRun("backend", "auth");
    assertTrue(store.deliveredMessageIds(id).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> store.markDelivered(id, List.of("not-a-uuid")));
  }

  @Test
  void rotationAppendsToTheReplicatedPrincipalHistory() {
    var id = newRun("backend", "auth");
    var first = store.findById(id).orElseThrow().principal();

    store.rotateCredential(id, "claude-code", "fix");

    var rotated = store.findById(id).orElseThrow().principal();
    assertTrue(rotated.contains("fix"), "the live row shows the current lane's identity");
    assertEquals(
        List.of(first, rotated).stream().sorted().toList(),
        store.principals(id),
        "history is append-only: rotation never erases the identity earlier messages were"
            + " authored under");
    var snapshot = store.comparableSnapshot(id);
    assertEquals(
        store.principals(id),
        snapshot.get("principals"),
        "the history replicates with the run, so main can authenticate late-syncing messages");
  }

  @Test
  void adoptingAnOldShapeSnapshotDerivesTheHistoryFromItsPrincipal() {
    var id = newRun("backend", "auth");
    var snapshot = new java.util.LinkedHashMap<>(store.comparableSnapshot(id));
    var principal = (String) snapshot.get("principal");
    snapshot.remove("principals");

    store.applyRevision(id, snapshot, "2-remote");

    assertTrue(
        store.principals(id).contains(principal),
        "a snapshot without a history list still records its current principal as a member");
  }

  @Test
  void recordSessionPersistsTheConversationIdentityAndLastWriteWins() {
    var id = newRun("backend", "auth");
    var beforeReport = store.latestRev(id);

    store.recordSession(id, "abc-123", "startup", "/home/dev/.claude/projects/p/abc-123.jsonl");

    var run = store.findById(id).orElseThrow();
    assertEquals("abc-123", run.sessionId());
    assertEquals("startup", run.sessionSource());
    assertEquals("/home/dev/.claude/projects/p/abc-123.jsonl", run.transcriptPath());
    assertNotEquals(beforeReport, store.latestRev(id), "a session report journals a revision");

    store.recordSession(id, "def-456", "compact", null);

    var reReported = store.findById(id).orElseThrow();
    assertEquals("def-456", reReported.sessionId(), "last write wins: the newest conversation");
    assertEquals("compact", reReported.sessionSource());
    assertNull(reReported.transcriptPath());
  }

  @Test
  void sessionFieldsJoinTheSnapshotAndSurviveReplication() {
    var id = newRun("backend", "auth");
    store.recordSession(id, "abc-123", "startup", "/t/abc.jsonl");
    var snapshot = store.comparableSnapshot(id);

    assertEquals("abc-123", snapshot.get("session_id"));
    assertEquals("startup", snapshot.get("session_source"));
    assertEquals("/t/abc.jsonl", snapshot.get("transcript_path"));

    var other = freshStore("session.db");
    other.applyRevision(id, snapshot, store.latestRev(id));

    var adopted = other.findById(id).orElseThrow();
    assertEquals("abc-123", adopted.sessionId());
    assertEquals("startup", adopted.sessionSource());
    assertEquals("/t/abc.jsonl", adopted.transcriptPath());
  }

  @Test
  void adoptingAnOldShapeSnapshotDerivesNullSessionFields() {
    var id = newRun("backend", "auth");
    var snapshot = new java.util.LinkedHashMap<>(store.comparableSnapshot(id));
    snapshot.remove("session_id");
    snapshot.remove("session_source");
    snapshot.remove("transcript_path");

    store.applyRevision(id, snapshot, "2-remote");

    var adopted = store.findById(id).orElseThrow();
    assertNull(adopted.sessionId(), "an old-shape snapshot derives nulls instead of failing");
    assertNull(adopted.sessionSource());
    assertNull(adopted.transcriptPath());
  }

  @Test
  void completionRevokesTheCredentialSoAFinishedRunCannotReport() {
    var id = DateTimeUtils.newId().toString();
    var reservation =
        (RunStore.Reservation.Reserved)
            store.reserveDispatch(
                id,
                "backend",
                "auth",
                "node-a",
                "node-a",
                "build",
                List.of(),
                "claude-code",
                "b",
                "t",
                "l",
                "u");
    assertTrue(store.findByCredential(reservation.credential()).isPresent());

    store.complete(id, "completed", 0);

    assertTrue(
        store.findByCredential(reservation.credential()).isEmpty(),
        "credential revocation at completion is the session write gate — no status check needed");
  }

  @Test
  void seedingByExactIdentityNeverSweepsALateSyncingOlderMessage() {
    var id = newRun("backend", "auth");
    var messages = new MessageStore(db);
    var lateId = DateTimeUtils.newId().toString();
    var rendered = messages.append("auth", "ada", "rendered", null);

    store.markDelivered(id, List.of(rendered.id()));
    messages.applyRevision(
        lateId,
        java.util.Map.of("spec_id", "auth", "author", "ada", "body", "late", "created_at", "now"),
        "1-abc");

    assertEquals(
        java.util.Set.of(rendered.id()),
        store.deliveredMessageIds(id),
        "the seed is the exact ids the prompt rendered — a message syncing in later is never"
            + " swept, even though its id sorts before the rendered one");
  }

  @Test
  void theRoomGuardBaselineIsConsumedExactlyOnce() {
    var id = newRun("backend", "auth");

    store.saveRoomGuardBaseline(id, "{\"app\": {\"head\": \"aaa\"}}");
    store.saveRoomGuardBaseline(id, "{\"app\": {\"head\": \"bbb\"}}");

    assertEquals(
        "{\"app\": {\"head\": \"bbb\"}}",
        store.consumeRoomGuardBaseline(id).orElseThrow(),
        "the latest recorded baseline wins");
    assertTrue(
        store.consumeRoomGuardBaseline(id).isEmpty(),
        "consumed on first read — a replayed stop checks nothing twice");
  }
}
