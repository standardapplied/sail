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
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.HarnessHooks;
import ai.singlr.sail.engine.LiveMemberPrompt;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A live member from its first prompt to its end: one process per room, prompted the moment a human
 * message lands, read by sail, and ended without losing its conversation.
 */
@ActingAs
class LiveMemberTest {

  private static final String SPEC = "live-spec";

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

  @Test
  void aHumanMessageStartsOneLiveProcessAndIsItsFirstPrompt() {
    room.engage(SPEC, "claude-code", "full");
    assertEquals(List.of(), room.processes.starts, "engaging alone starts nothing");

    room.says(SPEC, "what does the api do?");

    var run = room.memberRun();
    var unit = AgentUnit.live(run.id());
    var started = room.processes.starts.getFirst();
    assertEquals(1, room.processes.starts.size());
    assertEquals(LiveRoom.invocation("--dangerously-skip-permissions"), started.command());
    assertEquals(PROJECT_CONTAINER, started.container());
    assertEquals("/home/dev/workspace/api", started.workDir());
    assertEquals(
        Set.of("SAIL_SPEC_ID", "SAIL_AGENT", "SAIL_RUN_ID", "SAIL_RUN_CREDENTIAL", "SAIL_RUN_ROLE"),
        started.env().keySet());
    assertEquals(SPEC, started.env().get("SAIL_SPEC_ID"));
    assertEquals("claude-code", started.env().get("SAIL_AGENT"));
    assertEquals(run.id(), started.env().get("SAIL_RUN_ID"));
    assertEquals("room-full", started.env().get("SAIL_RUN_ROLE"));
    assertEquals(
        run.id(),
        room.runs.findByCredential(started.env().get("SAIL_RUN_CREDENTIAL")).orElseThrow().id(),
        "the process holds its run's own credential, so the spec CLI speaks for it");
    assertEquals(unit.pidPath(), started.pidFile());
    assertEquals(unit.logPath(), started.logFile());
    assertEquals("live-" + run.id(), run.unit());
    assertEquals("room-full", run.role());
    assertEquals("running", run.status());
    assertEquals(List.of("api"), run.repos());
    var fresh =
        LiveMemberPrompt.fresh(
                room.specs.findById(SPEC).orElseThrow(),
                "Live spec",
                room.messages.list(SPEC, null, 20),
                true)
            .getFirst()
            .text();
    assertEquals(List.of(LiveStream.userMessage(fresh)), room.processes.latest().written);
    assertTrue(fresh.endsWith(":\nwhat does the api do?"), fresh);
    assertEquals(
        List.of(),
        room.container.commandsContaining("systemd-run --user --setenv"),
        "a live member is no unit");
    assertEquals(List.of(), room.woken, "and no wake: nothing debounced it");
    var settings = HarnessHooks.renderLive(Harnesses.of("claude-code")).orElseThrow();
    assertFalse(settings.contains("hooks"), "the settings file names no hook: " + settings);
    assertFalse(settings.contains("/home/dev/.sail/bin/"), settings);
    assertTrue(
        room.container.file(unit.sessionPath()).contains("\"run_id\": \"" + run.id() + "\""),
        "the session file is staged as for any run");
  }

  private static final String PROJECT_CONTAINER = LiveRoom.PROJECT;

  @Test
  void sailReadsTheProcessAndPostsTheTurnsTextAsTheMember() {
    room.engage(SPEC, "claude-code", "full");
    room.says(SPEC, "what does the api do?");
    var process = room.processes.latest();
    var run = room.memberRun();
    var principal = "claude/" + run.id();

    process.says(Lines.init("sess-1"), Lines.textStart(), Lines.delta("It serves"));
    process.settle();
    room.later(Duration.ofMillis(10));
    process.says(Lines.delta(" specs"));
    process.settle();
    room.later(Duration.ofMillis(90));
    process.says(Lines.delta(" over HTTP."));
    process.says(
        Lines.toolUse("toolu_1", "Bash", "{\"command\":\"" + "x".repeat(130) + "\"}"),
        Lines.toolResult("toolu_1"),
        Lines.result("It serves specs over HTTP."));
    process.settle();
    room.await(Event.WellKnownTypes.AGENT_TURN_ENDED, 1);

    var recorded = room.memberRun();
    assertEquals("sess-1", recorded.sessionId());
    assertEquals("stream-json", recorded.sessionSource());
    assertNull(recorded.transcriptPath());
    var started = room.events(Event.WellKnownTypes.AGENT_SESSION_STARTED);
    assertEquals(1, started.size());
    assertEquals(SPEC, started.getFirst().spec());
    assertEquals(
        Map.of(
            "pid", Integer.parseInt(room.container.pidOf(run.id())),
            "run_id", run.id(),
            "run_role", "room-full"),
        started.getFirst().data());
    assertEquals(
        List.of(
            LiveRoom.live(run, SPEC, 1, Map.of("text", "It serves")),
            LiveRoom.live(run, SPEC, 1, Map.of("text", " specs over HTTP."))),
        room.data(Event.WellKnownTypes.AGENT_TEXT_DELTA),
        "the first fragment is told at once; two within the window are told as one");
    assertEquals(
        List.of(
            LiveRoom.live(
                run,
                SPEC,
                1,
                Map.of("tool", "Bash", "summary", "x".repeat(120), "status", "started")),
            LiveRoom.live(run, SPEC, 1, Map.of("tool", "Bash", "status", "finished"))),
        room.data(Event.WellKnownTypes.AGENT_TOOL_CALL));
    assertEquals(
        List.of(HANDLE + ": what does the api do?", principal + ": It serves specs over HTTP."),
        room.conversation(SPEC));
    var reply = room.messages.list(SPEC, null, 20).getLast();
    assertEquals(
        List.of(LiveRoom.live(run, SPEC, 1, Map.of("message_id", reply.id(), "is_error", false))),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));
    var announced = room.events(Event.WellKnownTypes.SPEC_MESSAGE_POSTED).getLast();
    assertEquals(principal, announced.agent(), "posted through the door that announces it");
    assertEquals(reply.id(), announced.data().get("message_id"));
    for (var type :
        List.of(
            Event.WellKnownTypes.AGENT_TEXT_DELTA,
            Event.WellKnownTypes.AGENT_TOOL_CALL,
            Event.WellKnownTypes.AGENT_TURN_ENDED)) {
      room.events(type).forEach(event -> assertEquals(SPEC, event.spec(), type));
    }
    assertEquals(1, room.processes.starts.size(), "the member's own reply prompts nothing");
    assertEquals(1, process.written.size());
  }

  @Test
  void aMessageDuringATurnIsWrittenAtOnceAndStartsTheNextTurn() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"));

    var second = room.says(SPEC, "second");

    assertEquals(2, process.written.size(), "written while the turn is still open");
    assertEquals(room.promptOf(SPEC, second), process.written.get(1));
    assertEquals(List.of(), room.turns());

    room.says(process, Lines.result("One"));
    room.says(process, Lines.delta("Two"), Lines.result("Two"));

    assertEquals(List.of(1, 2), room.turns());
    assertEquals(
        List.of(
            HANDLE + ": first",
            HANDLE + ": second",
            "claude/" + process.runId + ": One",
            "claude/" + process.runId + ": Two"),
        room.conversation(SPEC));
    assertEquals(1, room.processes.starts.size());
    assertEquals(1, room.runs.listForSpec(SPEC).size(), "one run row throughout");
  }

  @Test
  void aTurnsBeginningIsToldBeforeAnythingOfItIsAndOnceForEachTurn() {
    var process = room.prompted(SPEC, "full", "first");
    var run = room.memberRun();

    room.says(process, Lines.init("sess-1"));
    assertEquals(
        List.of(), room.data(Event.WellKnownTypes.AGENT_TURN_BEGAN), "a session is not a turn");

    room.says(process, "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}}");
    assertEquals(
        List.of(LiveRoom.live(run, SPEC, 1, Map.of())),
        room.data(Event.WellKnownTypes.AGENT_TURN_BEGAN),
        "told while the member is working and has written nothing");
    room.events(Event.WellKnownTypes.AGENT_TURN_BEGAN)
        .forEach(event -> assertEquals(SPEC, event.spec()));

    room.says(process, Lines.delta("One"), Lines.result("One"));
    room.says(SPEC, "second");
    room.says(process, Lines.delta("Two"), Lines.result("Two"));

    var turnTypes =
        Set.of(
            Event.WellKnownTypes.AGENT_TURN_BEGAN,
            Event.WellKnownTypes.AGENT_TEXT_DELTA,
            Event.WellKnownTypes.AGENT_TURN_ENDED);
    assertEquals(
        List.of(
            "agent_turn_began:1",
            "agent_text_delta:1",
            "agent_turn_ended:1",
            "agent_turn_began:2",
            "agent_text_delta:2",
            "agent_turn_ended:2"),
        room.published.stream()
            .filter(event -> turnTypes.contains(event.type()))
            .map(event -> event.type() + ":" + event.data().get("turn"))
            .toList());
  }

  @Test
  void aMessageAfterATurnGoesToTheSameProcess() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    room.later(Duration.ofMinutes(19));
    room.live.sweep();

    var second = room.says(SPEC, "second");

    assertEquals(List.of(process.written.getFirst(), room.promptOf(SPEC, second)), process.written);
    assertFalse(process.stdinClosed(), "nineteen idle minutes are inside the lane's twenty");
    assertEquals(1, room.processes.starts.size());
    assertEquals(1, room.runs.listForSpec(SPEC).size());
  }

  @Test
  void anIdleMemberEndsAndTheNextMessageResumesItsConversation() {
    var process = room.prompted(SPEC, "full", "first");
    var credential = room.processes.starts.getFirst().env().get("SAIL_RUN_CREDENTIAL");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    process.onClose(() -> process.exits(0));
    room.later(Duration.ofMinutes(20));

    room.live.sweep();
    room.awaitStopped(process);

    assertTrue(process.stdinClosed());
    assertEquals(List.of(), process.signals, "an idle member ends on its own once stdin closes");
    var ended = room.runs.findById(process.runId).orElseThrow();
    assertEquals("stopped", ended.status());
    assertEquals(0, ended.exitCode());
    assertTrue(room.runs.findByCredential(credential).isEmpty(), "its credential is revoked");
    var stop = room.stopOf(process.runId);
    assertEquals(SPEC, stop.spec());
    assertEquals("claude-code", stop.agent());
    assertEquals(
        Map.of("source", "live", "exit_code", 0, "run_id", process.runId, "run_role", "room-full"),
        stop.data());

    var second = room.says(SPEC, "second");

    var resumed = room.processes.latest();
    assertEquals(2, room.processes.starts.size());
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(List.of(room.promptOf(SPEC, second)), resumed.written, "the new message alone");
    assertEquals("running", room.runs.findById(resumed.runId).orElseThrow().status());
  }

  @Test
  void theGraceEachEndWaitsWithIsPinned() {
    assertEquals(
        new LiveMembers.Grace(
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10)),
        LiveMembers.Grace.DEFAULT);
    assertEquals(Duration.ofSeconds(80), LiveMembers.Grace.DEFAULT.whole());
    assertEquals(Duration.ofMillis(50), LiveMembers.DELTA_WINDOW);
    assertEquals(Duration.ofSeconds(30), LiveMembers.ACTIVITY_FLOOR);
    assertEquals(LiveMembers.Grace.DEFAULT, LiveMembers.Timing.real().grace());
  }

  @Test
  void aMemberDismissedMidTurnIsCutShortAndTheRoomFallsBackToItsWake() {
    room.impatient();
    var process = room.prompted(SPEC, "full", "first");
    process.onSignal(
        signal -> {
          if (signal == AgentProcess.Signal.TERM) {
            process.exits(143);
          }
        });
    room.says(process, Lines.init("sess-1"), Lines.delta("Half an"));

    room.dismiss(SPEC);
    room.awaitStopped(process);

    process.awaitStdinClosed();
    assertEquals(List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM), process.signals);
    assertEquals("stopped", room.runs.findById(process.runId).orElseThrow().status());
    var partial = room.messages.list(SPEC, null, 20).getLast();
    assertEquals("claude/" + process.runId, partial.author());
    assertEquals("Half an", partial.body());
    assertEquals(
        List.of(
            LiveRoom.live(
                room.run(process.runId),
                SPEC,
                1,
                Map.of(
                    "message_id", partial.id(), "is_error", true, "reason", "membership changed"))),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));
    assertEquals("membership changed", room.stopOf(process.runId).data().get("reason"));

    room.says(SPEC, "anyone there?");
    assertEquals(1, room.processes.starts.size(), "nobody sits in the room: no process");
    assertEquals(List.of(), room.woken, "and a spec never dispatched has nobody to wake");

    Acting.system(() -> room.rooms.updateWake(SPEC, "on"));
    room.says(SPEC, "its own agent, then");
    assertEquals(List.of(), room.woken, "a run that just ended keeps the room quiet, as ever");
    room.later(RoomWakeReactor.COOLDOWN.plusMinutes(1));
    room.says(SPEC, "and once the cooldown has passed?");

    assertEquals(List.of(SPEC), room.woken, "the wake lane decides as it always did");
    assertEquals(1, room.processes.starts.size());
    var wake = room.runs.listForSpec(SPEC).getFirst();
    assertEquals("room", wake.role());
    assertEquals(AgentUnit.forRun(wake.id()).unitName(), wake.unit());
  }

  @Test
  void aProcessThatDiesMidTurnFailsItsRunAndTheNextMessageResumes() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Half"));

    process.exits(1, "Error: out of memory\n");
    room.awaitStopped(process);

    var failed = room.runs.findById(process.runId).orElseThrow();
    assertEquals("failed", failed.status());
    assertEquals(1, failed.exitCode());
    var partial = room.messages.list(SPEC, null, 20).getLast();
    assertEquals("Half", partial.body());
    assertEquals(
        List.of(
            LiveRoom.live(
                failed,
                SPEC,
                1,
                Map.of(
                    "message_id",
                    partial.id(),
                    "is_error",
                    true,
                    "reason",
                    "Error: out of memory"))),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));
    assertEquals(
        Map.of(
            "source", "live",
            "reason", "Error: out of memory",
            "run_id", process.runId,
            "run_role", "room-full"),
        room.stopOf(process.runId).data());

    room.says(SPEC, "still there?");

    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
  }

  private static final String HANDLE = LiveRoom.HANDLE;
}
