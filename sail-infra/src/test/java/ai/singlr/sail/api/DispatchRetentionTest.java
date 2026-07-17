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

/**
 * The retention protection rule: any run that may still own a live agent keeps its run-scoped
 * directory — including a {@code stopping} run, whose pid file the stop's kill still needs. Pruning
 * it would turn the kill into a no-op that can never verify, wedging the claim.
 */
class DispatchRetentionTest {

  @Test
  void runningAndStoppingRunsAreProtectedFromRetention() {
    assertTrue(DispatchOperations.ownsLiveAgent(row("running")));
    assertTrue(
        DispatchOperations.ownsLiveAgent(row("stopping")),
        "a mid-stop run still needs its pid file for the kill and its verification");
  }

  @Test
  void terminalRunsArePrunable() {
    assertFalse(DispatchOperations.ownsLiveAgent(row("completed")));
    assertFalse(DispatchOperations.ownsLiveAgent(row("stopped")));
    assertFalse(DispatchOperations.ownsLiveAgent(row("failed")));
  }

  private static RunStore.RunRow row(String status) {
    return new RunStore.RunRow(
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "acme",
        "auth",
        "me",
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        123,
        null,
        status,
        null,
        "/home/dev/.sail/runs/r/agent.log",
        "sail-agent-r",
        null,
        null,
        List.of());
  }
}
