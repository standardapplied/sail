/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.LiveRoom.Lines;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.LiveMemberPrompt;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.RunStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lanes a live member runs in and the machinery around it: the read-only member and its
 * per-turn guard, the room with no spec, the member whose harness has no live mode, and what a
 * restart of sail, the watcher re-armer and the missed-stop reconciler make of a live run.
 */
@ActingAs
class LiveMemberLanesTest {

  private static final String SPEC = "live-spec";
  private static final String HANDLE = LiveRoom.HANDLE;

  private static final LiveMembers.Reclaimed NOTHING = new LiveMembers.Reclaimed(0, List.of());

  @TempDir Path dir;
  private LiveRoom room;

  @BeforeEach
  void setUp() {
    room = new LiveRoom(dir);
    room.spec(SPEC);
  }

  @AfterEach
  void tearDown() {
    room.close();
  }

  private long baselines(String runId) {
    return room.db
        .queryOne("SELECT count(*) FROM room_guard WHERE run_id = ?", row -> row.integer(0), runId)
        .orElseThrow();
  }

  @Test
  void aReadOnlyMemberIsGuardedTurnByTurn() {
    var process = room.prompted(SPEC, "read-only", "what does the api do?");
    var run = room.memberRun();
    var started = room.processes.starts.getFirst();

    assertEquals(
        LiveRoom.invocation(
            "--setting-sources",
            "",
            "--strict-mcp-config",
            "--tools",
            "Bash,Read,Grep,Glob",
            "--allowedTools",
            "Bash(spec:*)",
            "Bash(cd:*)"),
        started.command());
    assertEquals("/home/dev/workspace", started.workDir());
    assertEquals("room", run.role());
    assertEquals(List.of(), run.repos(), "a read-only member holds no repo");
    assertEquals("claude/room-" + run.id(), run.principal());
    assertEquals(
        List.of(
            LiveStream.userMessage(
                LiveMemberPrompt.fresh(
                        room.specs.findById(SPEC).orElseThrow(),
                        "Live spec",
                        room.messages.list(SPEC, null, 20),
                        false)
                    .getFirst()
                    .text())),
        process.written);
    assertEquals(1, baselines(run.id()), "saved before the first prompt");

    room.says(process, Lines.init("sess-1"), Lines.delta("It serves specs."), Lines.result(""));

    assertEquals(0, baselines(run.id()), "consumed by the guard at the turn's result");
    assertEquals(
        List.of(
            HANDLE + ": what does the api do?", "claude/room-" + run.id() + ": It serves specs."),
        room.conversation(SPEC));
    assertEquals(List.of(), room.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED));

    room.says(SPEC, "and the web?");

    assertEquals(1, baselines(run.id()), "saved again before the second prompt");
    room.container.checkedOut("feat/moved-under-the-member");
    room.says(process, Lines.delta("It renders them."), Lines.result(""));

    assertEquals(0, baselines(run.id()));
    var blamed = room.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED);
    assertEquals(1, blamed.size(), "the change of turn two is caught in turn two");
    assertTrue(
        blamed.getFirst().data().get("reason").toString().startsWith("room run " + run.id()),
        blamed.getFirst().data().toString());
    assertEquals(List.of(1, 2), room.turns());
  }

  @Test
  void aTurnAQueuedMessageStartsIsGuardedFromItsFirstLine() {
    var process = room.prompted(SPEC, "read-only", "first");
    var run = room.memberRun();
    room.says(process, Lines.init("sess-1"), Lines.delta("One"));
    room.says(SPEC, "second, while the first is open");
    room.says(process, Lines.result(""));
    assertEquals(0, baselines(run.id()), "the open turn's baseline is consumed at its result");

    room.says(process, Lines.delta("Two"));

    assertEquals(
        1, baselines(run.id()), "the turn the queued message started is armed as it opens");
    room.says(process, Lines.result(""));
    assertEquals(0, baselines(run.id()));
  }

  @Test
  void aReadOnlyMemberThatEndsMidTurnIsStillLookedAt() {
    var process = room.prompted(SPEC, "read-only", "first");
    var run = room.memberRun();
    room.says(process, Lines.init("sess-1"), Lines.delta("One"));
    room.container.checkedOut("feat/moved-under-the-member");

    process.exits(1);
    room.awaitStopped(process);

    assertEquals(0, baselines(run.id()));
    assertEquals(1, room.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED).size());
  }

  @Test
  void aReadOnlyMemberWhoseGuardCannotLookAsItExitsIsStillFinishedAndGone() {
    var process = room.prompted(SPEC, "read-only", "first");
    var run = room.memberRun();
    room.says(process, Lines.init("sess-1"), Lines.delta("One"));
    room.shell.unanswered("git");

    process.exits(1);
    room.awaitStopped(process);

    assertEquals("failed", room.run(run.id()).status(), "finished whatever the guard's look met");
    assertEquals(0, baselines(run.id()), "the look consumed its baseline before it failed");
    assertEquals(List.of(), room.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED));
    room.shell.unanswered("");

    room.says(SPEC, "second");

    assertEquals(2, room.processes.starts.size(), "no member is left behind to take the message");
    assertEquals(1, room.processes.latest().written.size());
  }

  @Test
  void aRoomWithNoSpecHasALiveMemberKeyedByTheRoom() {
    room.room("lounge");
    var process = room.prompted("lounge", "full", "anyone here?");
    var run = room.memberRun();
    var started = room.processes.starts.getFirst();

    assertNull(run.specId());
    assertEquals("lounge", run.roomId());
    assertEquals("lounge", run.conversationId());
    assertEquals(List.of("api"), run.repos(), "no spec narrows it: the project's whole set");
    assertEquals("lounge", started.env().get("SAIL_SPEC_ID"));
    assertEquals(
        List.of(
            LiveStream.userMessage(
                LiveMemberPrompt.freshForRoom(
                        "lounge", "Lounge", room.messages.list("lounge", null, 20), true)
                    .getFirst()
                    .text())),
        process.written);

    room.says(process, Lines.init("sess-9"), Lines.delta("Here."), Lines.result(""));

    assertEquals("sess-9", room.run(run.id()).sessionId());
    assertEquals(
        List.of(LiveRoom.live(run, "lounge", 1, Map.of("text", "Here."))),
        room.data(Event.WellKnownTypes.AGENT_TEXT_DELTA));
    for (var type :
        List.of(
            Event.WellKnownTypes.AGENT_SESSION_STARTED,
            Event.WellKnownTypes.AGENT_TEXT_DELTA,
            Event.WellKnownTypes.AGENT_TURN_ENDED)) {
      assertEquals("lounge", room.events(type).getFirst().spec(), type);
    }
    assertEquals(
        List.of(HANDLE + ": anyone here?", "claude/" + run.id() + ": Here."),
        room.conversation("lounge"));

    process.onClose(() -> process.exits(0));
    room.later(Duration.ofMinutes(20));
    room.live.sweep();
    room.awaitStopped(process);

    assertEquals("lounge", room.stopOf(run.id()).spec(), "the stop names the room it served");
    var again = room.says("lounge", "still here?");
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-9"),
        room.processes.starts.getLast().command(),
        "resumed through the room's own runs");
    assertEquals(List.of(room.promptOf("lounge", again)), room.processes.latest().written);
  }

  @Test
  void aCodexMemberKeepsThePerTurnWake() {
    room.engage(SPEC, "codex", "full");

    room.says(SPEC, "hello");

    assertEquals(List.of(), room.processes.starts, "codex has no live mode");
    assertEquals(List.of(SPEC), room.woken);
    var turn = room.runs.listForSpec(SPEC).getFirst();
    assertEquals("room-full", turn.role());
    assertEquals(AgentUnit.forRun(turn.id()).unitName(), turn.unit());
    var launched = String.join(" ", room.launchCommands.getFirst());
    assertTrue(launched.contains("systemd-run --user --setenv"), launched);
    assertTrue(
        launched.contains("codex exec --dangerously-bypass-approvals-and-sandbox"), launched);
  }

  @Test
  void aMemberInAnotherLaneOrFromAWakeIsNotResumed() {
    var process = room.prompted(SPEC, "read-only", "first");
    room.says(process, Lines.init("sess-ro"), Lines.delta("One"), Lines.result(""));
    process.onClose(() -> process.exits(0));

    room.engage(SPEC, "claude-code", "full");
    room.awaitStopped(process);
    room.says(SPEC, "now with full access");

    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions"),
        room.processes.starts.getLast().command(),
        "a read-only conversation was told its tools were different: the full member starts fresh");
    assertEquals(
        "membership changed", room.stopOf(process.runId).data().get("reason"), "the seat changed");
  }

  @Test
  void aRestartOfSailFinishesTheMembersItLeftAndTheyResume() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result(""));
    var rearmer =
        new WatcherRearmer(
            room.runs,
            WatcherRearmer.systemdUnitActiveProbe(room.shell),
            WatcherCoverage.of(new WatcherSpawner(room.shell, null)),
            () -> HANDLE,
            room.sail::relaunchWatcher);
    assertEquals(0, rearmer.rearm(), "a live member is no unit: no watcher is armed for it");
    assertEquals(
        NOTHING, room.live.reclaim(HANDLE), "its own process is behind it: nothing to reclaim");

    room.restart();
    assertEquals(
        NOTHING, room.live.reclaim("another-box"), "another box's runs are not this one's");
    var reclaimed = room.live.reclaim(HANDLE);
    room.settle();

    assertEquals(new LiveMembers.Reclaimed(1, List.of()), reclaimed);
    assertEquals("stopped", room.run(process.runId).status());
    assertFalse(
        room.container.alive(process.runId), "its process is signalled through its pid file");
    var stop = room.stopOf(process.runId);
    assertEquals(SPEC, stop.spec());
    assertEquals(
        Map.of(
            "source", "live",
            "reason", "sail restarted",
            "run_id", process.runId,
            "run_role", "room-full"),
        stop.data());
    assertEquals(NOTHING, room.live.reclaim(HANDLE), "finished once");

    var next = room.says(SPEC, "back?");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(List.of(room.promptOf(SPEC, next)), room.processes.latest().written);
  }

  @Test
  void aRestartKeepsTheRunOfAMemberItCannotProveGone() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Still writing"));
    room.restart();
    var held = new LiveMembers.Reclaimed(0, List.of(process.runId));

    room.container.survivesKill(true);
    assertEquals(held, room.live.reclaim(HANDLE), "its process outlived its signals");
    room.container.survivesKill(false);
    room.container.unreachable(true);
    assertEquals(held, room.live.reclaim(HANDLE), "its container did not answer");
    room.container.unreachable(false);

    assertEquals(
        "running",
        room.run(process.runId).status(),
        "nothing is freed under a turn that may still be writing");
    assertTrue(room.container.alive(process.runId));
    assertNull(room.stopOf(process.runId));
    assertEquals(0, reconciler().finishDeadSessions(Set.of()), "nor while its process is there");

    room.container.exited(process.runId, "", 0);

    assertEquals(1, reconciler().finishDeadSessions(Set.of()), "its turn ended and it exited");
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals(NOTHING, room.live.reclaim(HANDLE));
  }

  @Test
  void aRunLeftWithNoProcessRecordedIsReclaimedWithoutAskingItsContainer() {
    room.engage(SPEC, "claude-code", "full");
    var reclaimed = new AtomicReference<LiveMembers.Reclaimed>();
    room.processes.onStart(starting -> starting.onClose(() -> starting.exits(0)));
    room.live.useProcesses(
        (container, workDir, env, command, pidFile, logFile) -> {
          var restarted = room.elsewhere().liveMembers();
          room.container.unreachable(true);
          reclaimed.set(restarted.reclaim(HANDLE));
          room.container.unreachable(false);
          return room.processes.start(container, workDir, env, command, pidFile, logFile);
        });

    room.says(SPEC, "first");

    var left = room.processes.latest();
    assertEquals(
        new LiveMembers.Reclaimed(1, List.of()),
        reclaimed.get(),
        "a run that records no process was never given work: there is nothing to ask about");
    assertEquals("stopped", room.run(left.runId).status());
    assertNull(room.run(left.runId).pid());
    assertEquals("sail restarted", room.stopOf(left.runId).data().get("reason"));
    assertEquals(List.of(), left.written, "and the sail that reserved it never prompts it");
  }

  @Test
  void aRunThisSailReservedIsNotOneItReclaims() {
    room.engage(SPEC, "claude-code", "full");
    var reclaimed = new AtomicReference<LiveMembers.Reclaimed>();
    room.live.useProcesses(
        (container, workDir, env, command, pidFile, logFile) -> {
          reclaimed.set(room.live.reclaim(HANDLE));
          return room.processes.start(container, workDir, env, command, pidFile, logFile);
        });

    var first = room.says(SPEC, "first");

    var process = room.processes.latest();
    assertEquals(
        NOTHING,
        reclaimed.get(),
        "a run reserved since this sail started is its own, registered or not");
    assertEquals("running", room.run(process.runId).status());
    assertEquals(1, process.written.size(), "so a message that lands as sail starts is answered");
    assertEquals(Set.of(first), room.runs.deliveredMessageIds(process.runId));
  }

  @Test
  void theMissedStopReconcilerLeavesALiveTurnAlone() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Working"));

    var finished = reconciler().finishDeadSessions(Set.of());

    assertEquals(0, finished, "its pid file answers: the run is its member's to finish");
    assertEquals(
        List.of("running"),
        room.runs.listForSpec(SPEC).stream().map(RunStore.RunRow::status).toList());
  }

  /** The sweep of runs whose process is gone, three minutes on: past its launch grace. */
  private MissedStopReconciler reconciler() {
    return new MissedStopReconciler(
        room.specs,
        room.runs,
        new EventStore(room.db),
        room.reviews,
        room.bus,
        MissedStopReconciler.systemdUnitProbe(room.shell),
        WatcherCoverage.of(new WatcherSpawner(room.shell, null)),
        () -> HANDLE,
        () -> Instant.now().plus(Duration.ofMinutes(3)));
  }
}
