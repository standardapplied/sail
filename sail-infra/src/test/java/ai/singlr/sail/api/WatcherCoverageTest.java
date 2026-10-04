/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.RunStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class WatcherCoverageTest {

  private static final String RUN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  private static RunStore.RunRow run(Integer watcherPid) {
    return new RunStore.RunRow(
        RUN,
        "acme",
        "auth",
        "node-a",
        "fix",
        "claude-code",
        "feat/auth",
        "fix it",
        4242,
        watcherPid,
        "running",
        null,
        "/home/dev/.sail/runs/" + RUN + "/agent.log",
        "sail-agent-" + RUN,
        "2026-10-03T12:00:00Z",
        null,
        List.of("api"),
        null,
        "claude/fix-" + RUN,
        "node-a",
        null,
        null,
        null,
        null,
        null,
        "review-1",
        null);
  }

  @Test
  void aWatcherProcessForTheRunCoversItWhateverPidWasRecorded() {
    var coverage = new WatcherCoverage(RUN::equals, pid -> false);

    assertTrue(coverage.covers(run(null)));
    assertTrue(coverage.watching(run(null)));
  }

  @Test
  void aRecordedPidThatIsAliveCoversARunForTheRearmerButIsNoWatcherTheReconcilerWaitsOn() {
    var coverage = new WatcherCoverage(runId -> false, pid -> pid == 777);

    assertTrue(coverage.covers(run(777)), "the re-armer never doubles a watcher it recorded");
    assertFalse(
        coverage.watching(run(777)),
        "a pid the host may have handed to another process is not a watcher: a run that is gone"
            + " must not wait forever on one that is not there");
  }

  @Test
  void aRunWithNoWatcherProcessAndNoLivePidIsUncovered() {
    var coverage = new WatcherCoverage(runId -> false, pid -> false);

    assertFalse(coverage.covers(run(777)));
    assertFalse(coverage.covers(run(null)));
    assertFalse(coverage.watching(run(777)));
  }
}
