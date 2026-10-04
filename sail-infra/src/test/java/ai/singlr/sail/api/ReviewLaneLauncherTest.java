/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The review and fix lanes' launcher over a {@link FakeContainer}: what it records, what it leaves
 * behind when a launch fails, what it reads back, and which repos it rescues.
 */
class ReviewLaneLauncherTest {

  private static final String PROJECT = ReviewLoop.PROJECT;

  @TempDir Path tempDir;
  private ReviewLoop loop;
  private ReviewLanes lanes;

  @BeforeEach
  void setUp() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    lanes = loop.operations.reviewLanes();
  }

  @AfterEach
  void tearDown() {
    loop.close();
  }

  private static ReviewLanes.Invocation invocation(Lane lane, List<String> shown) {
    return new ReviewLanes.Invocation(
        lane,
        DateTimeUtils.newId().toString(),
        PROJECT,
        "auth",
        "codex",
        "review it",
        "feat/test",
        List.of("api"),
        null,
        "high",
        shown);
  }

  private String launch(ReviewLanes.Invocation invocation) {
    var launched = Acting.system(() -> lanes.launch(invocation, ReviewLoop.HANDLE));
    return assertInstanceOf(ReviewLanes.Launch.Started.class, launched).runId();
  }

  @Test
  void aLaunchRecordsARunThatNamesItsReviewAndStagesItsTaskAndSessionUnderTheRunsOwnDirectory() {
    var invocation = invocation(Lane.FIX, List.of());

    var runId = launch(invocation);

    var run = loop.runs.findById(runId).orElseThrow();
    var unit = AgentUnit.forRun(runId);
    assertEquals("running", run.status());
    assertEquals("fix", run.role());
    assertEquals(invocation.reviewId(), run.reviewId());
    assertEquals(unit.unitName(), run.unit());
    assertEquals(unit.logPath(), run.logPath());
    assertEquals("review it", loop.container.file(unit.taskPath()));
    var session = YamlUtil.parseMap(loop.container.file(unit.sessionPath()));
    assertEquals("fix", session.get("role"), "the stop gate reads the lane from here");
    assertEquals("auth", session.get("spec_id"));
    assertEquals(runId, session.get("run_id"));
    assertEquals(List.of("api"), session.get("repos"));
    assertTrue(loop.container.alive(runId));
  }

  @Test
  void aLaunchTheContainerRefusesThrowsAndLeavesNoRunRunning() {
    loop.container.stopped();

    assertThrows(ApiException.class, () -> launch(invocation(Lane.REVIEW, List.of())));

    assertTrue(loop.runs.running().isEmpty(), "nothing is left for the loop to wait on");
  }

  @Test
  void aLaunchThatFailsAfterItsRunWasRecordedFailsTheRunSoNothingWaitsOnIt() {
    loop.refuseLaunches();
    var invocation = invocation(Lane.REVIEW, List.of());

    assertThrows(ApiException.class, () -> launch(invocation));

    var recorded = loop.runs.forReview(invocation.reviewId());
    assertEquals(List.of("failed"), recorded.stream().map(RunStore.RunRow::status).toList());
    assertTrue(loop.runs.running().isEmpty());
  }

  @Test
  void aLaunchPassesTheDispatchGateAndIsDeferredBesideARunOverTheSameRepos() {
    var first = launch(invocation(Lane.REVIEW, List.of()));

    var second =
        Acting.system(() -> lanes.launch(invocation(Lane.FIX, List.of()), ReviewLoop.HANDLE));

    var deferred = assertInstanceOf(ReviewLanes.Launch.Deferred.class, second);
    assertTrue(deferred.why().contains(first), "the refusal names the run in the way: " + deferred);
    assertEquals(
        List.of(first),
        loop.container.launched(),
        "nothing is started, and no run recorded, for a claim the gate refused");
    assertEquals(List.of(first), loop.runs.running().stream().map(RunStore.RunRow::id).toList());
    assertEquals(List.of("api"), loop.runs.findById(first).orElseThrow().repos());
  }

  @Test
  void aRunsOutputIsItsOwnLogsFinalAnswerAndBlankWhenItWroteNone() throws Exception {
    var runId = launch(invocation(Lane.REVIEW, List.of()));
    var run = loop.runs.findById(runId).orElseThrow();

    assertEquals("", lanes.output(run), "no log yet");

    loop.container.wrote(runId, "{\"type\":\"result\",\"result\":\"the verdict\"}");

    assertEquals("the verdict", lanes.output(run));
  }

  @Test
  void aRescueCommitsOnlyReposOnTheSpecsBranchThatHoldUncommittedWork() throws Exception {
    loop.container.dirty("api", " M src/A.java\nR  old.txt -> new.txt\n");

    var rescued = lanes.ensureCommitted(PROJECT, List.of("api", "web"), "feat/test", "fix: it");

    assertEquals(
        List.of(new ReviewLanes.Rescue("api", List.of("src/A.java", "new.txt"))),
        rescued,
        "a clean repo is left alone, and a rename is named by its new path");
    assertEquals(List.of("api: fix: it"), loop.container.commits());
    assertEquals(
        1,
        loop.container.commandsContaining("git -C /home/dev/workspace/api push").size(),
        "the rescue is pushed");
  }

  @Test
  void aRepoParkedOnAnotherBranchIsNeverCommitted() throws Exception {
    loop.container.dirty("api", " M src/A.java\n");
    loop.container.checkedOut("main");

    assertEquals(List.of(), lanes.ensureCommitted(PROJECT, List.of("api"), "feat/test", "fix"));
    assertEquals(
        List.of(),
        lanes.ensureCommitted(PROJECT, List.of("api"), " ", "fix"),
        "no branch, no rescue");
    assertTrue(loop.container.commits().isEmpty(), "auto-committing there contaminates the clone");
  }

  @Test
  void aRescueWhoseGitFailsSaysWhichCommandFailed() {
    loop.container.dirty("api", " M src/A.java\n");
    loop.container.gitFails("fatal: index.lock exists", "status");

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> lanes.ensureCommitted(PROJECT, List.of("api"), "feat/test", "fix"));

    assertTrue(failure.getMessage().contains("git status failed"), failure.getMessage());
    assertTrue(failure.getMessage().contains("index.lock"), failure.getMessage());
  }

  @Test
  void aPushThatFailsStillCountsTheRescue() throws Exception {
    loop.container.dirty("api", " M src/A.java\n");
    loop.container.gitFails("remote hung up", "push");

    var rescued = lanes.ensureCommitted(PROJECT, List.of("api"), "feat/test", "fix");

    assertEquals(1, rescued.size(), "the commit is the rescue; the push is best-effort");
    assertEquals(1, loop.container.commits().size());
  }

  @Test
  void theMessagesATaskShowedAreMarkedDeliveredToItsRun() {
    var shown =
        Acting.as("uday", () -> loop.messages.append("auth", "uday", "check the lock", null));

    var runId = launch(invocation(Lane.REVIEW, List.of(shown.id())));

    assertEquals(
        List.of(shown.id()), List.copyOf(loop.runs.deliveredMessageIds(runId)), "its own ledger");
  }

  @Test
  void onlyReviewersAndFixAgentsLaunchThroughTheseLanes() {
    var refused = assertThrows(ApiException.class, () -> launch(invocation(Lane.BUILD, List.of())));

    assertTrue(refused.getCause().getMessage().contains("build"), refused.getCause().getMessage());
    assertEquals(
        List.of(), loop.runs.listForSpec("auth").stream().map(RunStore.RunRow::id).toList());
  }
}
