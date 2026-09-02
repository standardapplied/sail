/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure repo-overlap and role matrix behind the concurrent-dispatch refusal. */
class DispatchGateTest {

  private static final String RUN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  private static DispatchGate.RunningRun running(String specId, List<String> repos) {
    return running(specId, "build", repos);
  }

  private static DispatchGate.RunningRun running(String specId, String role, List<String> repos) {
    return new DispatchGate.RunningRun(RUN, specId, role, repos);
  }

  @Test
  void noRunningRunsAllows() {
    assertTrue(DispatchGate.decide("target", "build", List.of("web"), List.of()).isEmpty());
  }

  @Test
  void disjointRepoSetsAllow() {
    var running = List.of(running("auth", List.of("app")));

    assertTrue(DispatchGate.decide("target", "build", List.of("web"), running).isEmpty());
  }

  @Test
  void intersectingRepoSetsRefuseNamingTheBlockingRunAndOverlap() {
    var running = List.of(running("auth", List.of("app", "web")));

    var conflict = DispatchGate.decide("target", "build", List.of("web"), running).orElseThrow();

    assertEquals(RUN, conflict.run().runId());
    assertEquals("auth", conflict.run().specId());
    assertEquals(List.of("web"), conflict.overlap());
  }

  @Test
  void multiRepoTargetsRefuseOnASingleSharedRepo() {
    var running = List.of(running("auth", List.of("app")));

    var conflict =
        DispatchGate.decide("target", "build", List.of("app", "web"), running).orElseThrow();

    assertEquals(List.of("app"), conflict.overlap());
  }

  @Test
  void anEmptyTargetRepoSetOverlapsEverything() {
    var running = List.of(running("auth", List.of("app")));

    var conflict = DispatchGate.decide("target", "build", List.of(), running).orElseThrow();

    assertEquals(List.of(), conflict.overlap(), "empty means the whole container");
  }

  @Test
  void aRunningRunWithNoReposOverlapsEverything() {
    var running = List.of(running("auth", List.of()));

    assertTrue(DispatchGate.decide("target", "build", List.of("web"), running).isPresent());
  }

  @Test
  void aLiveRunOfTheTargetSpecItselfAlwaysBlocksEvenOnDisjointRepos() {
    var running = List.of(running("auth", List.of("app")));

    var conflict = DispatchGate.decide("auth", "build", List.of("web"), running).orElseThrow();

    assertEquals(RUN, conflict.run().runId());
    assertEquals(List.of(), conflict.overlap(), "one spec, one lifecycle: no second execution");
  }

  @Test
  void theFirstConflictingRunWins() {
    var running =
        List.of(
            new DispatchGate.RunningRun(RUN, "clear", "build", List.of("docs")),
            new DispatchGate.RunningRun(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "auth", "build", List.of("web")));

    var conflict = DispatchGate.decide("target", "build", List.of("web"), running).orElseThrow();

    assertEquals("auth", conflict.run().specId());
  }

  @Test
  void aRoomTargetNeverConflictsWithAnotherSpecsBuildEvenWholeContainer() {
    var running = List.of(running("auth", "build", List.of()));

    assertTrue(DispatchGate.decide("target", "room", List.of(), running).isEmpty());
  }

  @Test
  void aRoomTargetNeverConflictsWithAnotherSpecsAdhocOrReviewRun() {
    var running =
        List.of(
            running("", "adhoc", List.of()),
            new DispatchGate.RunningRun(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "auth", "review", List.of("web")));

    assertTrue(DispatchGate.decide("target", "room", List.of(), running).isEmpty());
  }

  @Test
  void aRoomTargetRunsAlongsideItsOwnSpecsLiveBuild() {
    var running = List.of(running("auth", "build", List.of("app")));

    assertTrue(
        DispatchGate.decide("auth", "room", List.of(), running).isEmpty(),
        "a read-only chat turn answers the room while the build works");
  }

  @Test
  void chatTurnsOfOneSpecSerializeAcrossModes() {
    assertTrue(
        DispatchGate.decide(
                "auth", "room", List.of(), List.of(running("auth", "room-full", List.of("app"))))
            .isPresent());
    assertTrue(
        DispatchGate.decide(
                "auth", "room-full", List.of("app"), List.of(running("auth", "room", List.of())))
            .isPresent());
  }

  @Test
  void aFullChatTurnReservesLikeABuild() {
    var running = List.of(running("auth", "build", List.of("app")));

    var deferred = DispatchGate.decide("chat", "room-full", List.of("app"), running).orElseThrow();
    assertEquals(List.of("app"), deferred.overlap(), "one writer per repo, chat included");
    assertTrue(
        DispatchGate.decide("chat", "room-full", List.of("web"), running).isEmpty(),
        "disjoint repos share the container");
  }

  @Test
  void aFullChatTurnWithNoReposClaimsTheWholeContainer() {
    var running = List.of(running("auth", "build", List.of("app")));

    assertTrue(DispatchGate.decide("chat", "room-full", List.of(), running).isPresent());
  }

  @Test
  void aFullChatTurnDefersOnItsOwnSpecsBuildViaTheRepoRule() {
    var running = List.of(running("auth", "build", List.of("app")));

    assertTrue(DispatchGate.decide("auth", "room-full", List.of("app"), running).isPresent());
  }

  @Test
  void aLiveFullChatTurnBlocksAnOverlappingDispatch() {
    var running = List.of(running("auth", "room-full", List.of("app")));

    assertTrue(DispatchGate.decide("other", "build", List.of("app"), running).isPresent());
    assertTrue(DispatchGate.decide("other", "build", List.of("web"), running).isEmpty());
  }

  @Test
  void aRoomTargetIsBlockedByALiveRoomRunOfItsOwnSpec() {
    var running = List.of(running("auth", "room", List.of()));

    assertTrue(DispatchGate.decide("auth", "room", List.of(), running).isPresent());
  }

  @Test
  void aLiveRoomRunNeverBlocksADisjointSpecsBuildDespiteItsEmptyRepoSet() {
    var running = List.of(running("auth", "room", List.of()));

    assertTrue(DispatchGate.decide("target", "build", List.of("web"), running).isEmpty());
    assertTrue(DispatchGate.decide("target", "build", List.of(), running).isEmpty());
  }

  @Test
  void aLiveRoomRunNeverBlocksItsOwnSpecsDispatch() {
    var running = List.of(running("auth", "room", List.of()));

    assertTrue(
        DispatchGate.decide("auth", "build", List.of("web"), running).isEmpty(),
        "dispatch proceeds during a read-only chat turn");
  }

  @Test
  void aLiveRoomRunNeverBlocksAnAdhocSessionsWholeContainerClaim() {
    var running = List.of(running("auth", "room", List.of()));

    assertTrue(DispatchGate.decide("", "adhoc", List.of(), running).isEmpty());
  }
}
