/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure repo-overlap matrix behind the concurrent-dispatch refusal. */
class DispatchGateTest {

  private static final String RUN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  private static DispatchGate.RunningRun running(String specId, List<String> repos) {
    return new DispatchGate.RunningRun(RUN, specId, repos);
  }

  @Test
  void noRunningRunsAllows() {
    assertTrue(DispatchGate.decide("target", List.of("web"), List.of()).isEmpty());
  }

  @Test
  void disjointRepoSetsAllow() {
    var running = List.of(running("auth", List.of("app")));

    assertTrue(DispatchGate.decide("target", List.of("web"), running).isEmpty());
  }

  @Test
  void intersectingRepoSetsRefuseNamingTheBlockingRunAndOverlap() {
    var running = List.of(running("auth", List.of("app", "web")));

    var conflict = DispatchGate.decide("target", List.of("web"), running).orElseThrow();

    assertEquals(RUN, conflict.run().runId());
    assertEquals("auth", conflict.run().specId());
    assertEquals(List.of("web"), conflict.overlap());
  }

  @Test
  void multiRepoTargetsRefuseOnASingleSharedRepo() {
    var running = List.of(running("auth", List.of("app")));

    var conflict = DispatchGate.decide("target", List.of("app", "web"), running).orElseThrow();

    assertEquals(List.of("app"), conflict.overlap());
  }

  @Test
  void anEmptyTargetRepoSetOverlapsEverything() {
    var running = List.of(running("auth", List.of("app")));

    var conflict = DispatchGate.decide("target", List.of(), running).orElseThrow();

    assertEquals(List.of(), conflict.overlap(), "empty means the whole container");
  }

  @Test
  void aRunningRunWithNoReposOverlapsEverything() {
    var running = List.of(running("auth", List.of()));

    assertTrue(DispatchGate.decide("target", List.of("web"), running).isPresent());
  }

  @Test
  void aLiveRunOfTheTargetSpecItselfAlwaysBlocksEvenOnDisjointRepos() {
    var running = List.of(running("auth", List.of("app")));

    var conflict = DispatchGate.decide("auth", List.of("web"), running).orElseThrow();

    assertEquals(RUN, conflict.run().runId());
    assertEquals(List.of(), conflict.overlap(), "one spec, one lifecycle: no second execution");
  }

  @Test
  void theFirstConflictingRunWins() {
    var running =
        List.of(
            new DispatchGate.RunningRun(RUN, "clear", List.of("docs")),
            new DispatchGate.RunningRun(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "auth", List.of("web")));

    var conflict = DispatchGate.decide("target", List.of("web"), running).orElseThrow();

    assertEquals("auth", conflict.run().specId());
  }
}
