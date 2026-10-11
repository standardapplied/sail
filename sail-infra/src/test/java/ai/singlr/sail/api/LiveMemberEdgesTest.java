/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.LiveRoom.Lines;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edges of a live member: what the agent writes that sail must bound or ignore, a start that
 * fails, pipes that break, a room that refuses the reply, and waits that are interrupted. None of
 * them may strand a run or stop the reader.
 */
@ActingAs
class LiveMemberEdgesTest {

  private static final String SPEC = "live-spec";
  private static final String HANDLE = LiveRoom.HANDLE;

  private static final String NO_AGENT =
      """
      name: test-project
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/api.git
          path: api
      """;

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

  private List<String> statuses() {
    return room.runs.listForSpec(SPEC).stream().map(RunStore.RunRow::status).toList();
  }

  private Map<String, Object> turnEnded() {
    return room.data(Event.WellKnownTypes.AGENT_TURN_ENDED).getLast();
  }

  @Test
  void textBlocksAreParagraphsAndLinesThatSayNothingAreIgnored() {
    var process = room.prompted(SPEC, "full", "first");

    room.says(
        process,
        "{\"type\":\"rate_limit_event\"}",
        "not json at all",
        Lines.init("sess-1"),
        Lines.init("sess-1"),
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}}",
        Lines.textStart(),
        Lines.delta("Let me look."),
        Lines.toolResult("toolu_never_seen"),
        Lines.textStart(),
        Lines.delta("Found it."),
        Lines.init("sess-2"),
        Lines.result("ignored: the stream already said it"));

    assertEquals(
        "claude/" + process.runId + ": Let me look.\n\nFound it.",
        room.conversation(SPEC).getLast());
    assertEquals("sess-2", room.run(process.runId).sessionId(), "the newest session is the run's");
    assertEquals(
        1,
        room.events(Event.WellKnownTypes.AGENT_SESSION_STARTED).size(),
        "a session is announced once");
    assertEquals(List.of(), room.data(Event.WellKnownTypes.AGENT_TOOL_CALL));
    assertEquals(List.of(1), room.turns());
  }

  @Test
  void aTurnThatStreamedNothingPostsItsResultAndAnEmptyOnePostsNothing() {
    var process = room.prompted(SPEC, "full", "first");

    room.says(process, Lines.failure("error_during_execution", "API Error: overloaded"));

    assertEquals(
        "claude/" + process.runId + ": API Error: overloaded", room.conversation(SPEC).getLast());
    assertEquals(true, turnEnded().get("is_error"));
    assertEquals("error_during_execution", turnEnded().get("reason"));

    room.says(SPEC, "again?");
    room.says(process, Lines.result(""));

    assertEquals(HANDLE + ": again?", room.conversation(SPEC).getLast(), "nothing to post");
    assertFalse(turnEnded().containsKey("message_id"));
    assertEquals(false, turnEnded().get("is_error"));
    assertEquals(List.of(1, 2), room.turns());
  }

  @Test
  void whatTheAgentWritesIsBoundedWhereItEnters() {
    var process = room.prompted(SPEC, "full", "first");
    var flood = "x".repeat(MessageStore.MAX_BODY_BYTES + 10);

    room.says(process, Lines.delta(flood), Lines.delta("y".repeat(100)), Lines.result(""));

    var reply = room.messages.list(SPEC, null, 20).getLast().body();
    assertEquals(MessageStore.MAX_BODY_BYTES, reply.length(), "the reply is cut to fit a message");
    assertTrue(reply.endsWith("[cut: longer than a room message can be]"), "and says it was cut");
    assertEquals(
        8_000,
        room.data(Event.WellKnownTypes.AGENT_TEXT_DELTA).getFirst().get("text").toString().length(),
        "and a fragment is clipped before it is told");
  }

  @Test
  void aFragmentHeldBackIsToldWhenItsWindowElapses() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.delta("One"));
    room.later(Duration.ofMillis(10));
    room.says(process, Lines.delta(" two"));
    assertEquals(1, room.events(Event.WellKnownTypes.AGENT_TEXT_DELTA).size(), "held back");

    room.windowElapses();
    room.await(Event.WellKnownTypes.AGENT_TEXT_DELTA, 2);

    assertEquals(
        List.of("One", " two"),
        room.data(Event.WellKnownTypes.AGENT_TEXT_DELTA).stream()
            .map(data -> data.get("text"))
            .toList());
  }

  @Test
  void aRoomThatRefusesTheReplyEndsTheTurnInError() {
    var process = room.prompted(SPEC, "full", "first");
    room.live.useRoom(
        (run, roomId, text) -> {
          throw new ApiException(ErrorCode.FORBIDDEN, "no voice in this room");
        });

    room.says(process, Lines.delta("Unheard"), Lines.result(""));

    assertEquals(HANDLE + ": first", room.conversation(SPEC).getLast());
    assertEquals(true, turnEnded().get("is_error"));
    assertEquals("reply not posted: no voice in this room", turnEnded().get("reason"));
    assertFalse(turnEnded().containsKey("message_id"));
  }

  @Test
  void aRoomThatIsGoneRefusesTheReplyThroughItsOwnDoor() {
    room.room("lounge");
    var process = room.prompted("lounge", "full", "first");
    room.says(process, Lines.delta("Too late"));
    room.db.execute("DELETE FROM rooms WHERE id = ?", "lounge");

    room.says(process, Lines.result(""));

    assertEquals(true, turnEnded().get("is_error"));
    assertEquals("reply not posted: Room 'lounge' was not found.", turnEnded().get("reason"));
  }

  @Test
  void aRunWhoseFdeLeftTheBoxSpeaksForNobody() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.delta("Unheard"));
    room.db.execute("DELETE FROM fdes WHERE handle = ?", HANDLE);

    room.says(process, Lines.result(""));

    assertEquals(true, turnEnded().get("is_error"));
    assertEquals(
        "reply not posted: Run "
            + process.runId
            + " acts for nobody this box knows, so it cannot post.",
        turnEnded().get("reason"));
    assertEquals(HANDLE + ": first", room.conversation(SPEC).getLast());
  }

  @Test
  void aLineThatFailsIsItsOwnFailureAndTheReaderGoesOn() {
    var process = room.prompted(SPEC, "full", "first");
    var working = LiveMembers.Timing.real();
    room.live.useTiming(
        new LiveMembers.Timing(
            () -> {
              throw new IllegalStateException("the clock stopped");
            },
            working.delay(),
            LiveRoom.PATIENT));

    room.says(process, Lines.delta("lost to the clock"));
    room.live.useTiming(working);
    room.says(process, Lines.delta("Heard."), Lines.result(""));

    assertEquals("claude/" + process.runId + ": Heard.", room.conversation(SPEC).getLast());
  }

  @Test
  void anAnnouncementOfNothingNewPromptsNothing() {
    var process = room.prompted(SPEC, "full", "first");

    room.live.deliver(SPEC, HANDLE);
    room.live.deliver("no-such-room", HANDLE);
    room.spec("empty-seat");
    room.live.deliver("empty-seat", HANDLE);

    assertEquals(1, process.written.size());
    assertEquals(1, room.processes.starts.size());
  }

  @Test
  void aMemberWhoseStdinBrokeIsReplacedOnTheSameMessage() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result(""));
    process.breaksStdin();

    var second = room.says(SPEC, "second");

    assertEquals(2, room.processes.starts.size(), "the message is for whoever comes next");
    assertEquals(List.of(room.promptOf(SPEC, second)), room.processes.latest().written);
    assertEquals(List.of("running", "stopped"), statuses());
  }

  @Test
  void aMemberThatWillNotEndTakesNoPromptAndNothingStartsBesideIt() {
    room.impatient();
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result(""));
    room.later(Duration.ofMinutes(20));

    room.live.sweep();
    room.says(SPEC, "second");

    assertEquals(List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM), process.signals);
    assertEquals(1, process.written.size(), "its stdin is closed");
    assertEquals(1, room.processes.starts.size(), "and its run still holds the room");
    assertEquals(List.of("running"), statuses());

    room.live.sweep();
    assertEquals(4, process.signals.size(), "it is told again on the next pass");
  }

  @Test
  void aStdoutThatBreaksEndsTheRunAsADeathDoes() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.delta("Half"));
    room.shell.unanswered("tail");

    process.breaks(new IOException("Stream closed"));
    room.awaitStopped(process);

    assertEquals("failed", room.run(process.runId).status());
    assertEquals(
        "exited with code 1", turnEnded().get("reason"), "its log could not be read: the code");
  }

  @Test
  void aProcessThatVanishesWithNoExitCodeIsStillFinished() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.delta("Half"));

    process.vanishes();
    room.awaitStopped(process);

    assertEquals("failed", room.run(process.runId).status());
    assertEquals("ended without a result", turnEnded().get("reason"));
  }

  @Test
  void anInterruptedWaitForTheExitCodeFinishesTheRunWithoutOne() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.delta("One"), Lines.result(""));
    process.interruptsWait();

    process.exits(0);
    room.awaitStopped(process);

    var ended = room.run(process.runId);
    assertEquals("stopped", ended.status());
    assertEquals(null, ended.exitCode());
  }

  @Test
  void anInterruptedEndStillTellsTheProcessToGo() throws Exception {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.delta("One"), Lines.result(""));
    process.onSignal(
        signal -> {
          if (signal == AgentProcess.Signal.TERM) {
            process.exits(143);
          }
        });
    room.later(Duration.ofMinutes(20));

    var ender =
        Thread.ofVirtual()
            .start(
                () -> {
                  Thread.currentThread().interrupt();
                  room.live.sweep();
                });
    ender.join();
    room.awaitStopped(process);

    assertEquals(List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM), process.signals);
    assertEquals("stopped", room.run(process.runId).status());
  }

  private void posted(String conversation, String body) {
    Acting.system(() -> room.messages.append(conversation, HANDLE, body, null));
  }

  @Test
  void aProcessThatCannotBeStartedGivesItsClaimBack() {
    room.engage(SPEC, "claude-code", "full");
    posted(SPEC, "first");
    room.processes.refusing(new IOException("incus: command not found"));

    var failed = assertThrows(ApiException.class, () -> room.live.deliver(SPEC, HANDLE));

    assertEquals(ErrorCode.AGENT_LAUNCH_FAILED, failed.failure().errorCode());
    assertEquals("Failed to start the room's live member.", failed.getMessage());
    assertEquals(List.of("failed"), statuses(), "the claim is released: the room is free");
  }

  @Test
  void aRunThatCannotBeStagedGivesItsClaimBack() {
    room.engage(SPEC, "claude-code", "full");
    posted(SPEC, "first");
    room.container.failing("agent-session.json", "No space left on device");

    var failed = assertThrows(ApiException.class, () -> room.live.deliver(SPEC, HANDLE));

    assertEquals(ErrorCode.AGENT_LAUNCH_FAILED, failed.failure().errorCode());
    assertEquals(List.of(), room.processes.starts, "nothing is started over a run half staged");
    assertEquals(List.of("failed"), statuses());
  }

  @Test
  void aMemberIsStartedOnlyByTheBoxThatOwnsItsRoom() {
    room.engage(SPEC, "claude-code", "full");
    posted(SPEC, "first");
    room.room("lounge");
    room.engage("lounge", "claude-code", "full");
    posted("lounge", "first");

    var spec = assertThrows(ApiException.class, () -> room.live.deliver(SPEC, "another-box"));
    var lounge = assertThrows(ApiException.class, () -> room.live.deliver("lounge", "another-box"));

    assertEquals(ErrorCode.NOT_YOUR_SPEC, spec.failure().errorCode());
    assertEquals(
        "Spec 'live-spec' belongs to 'node-a', not this box's FDE 'another-box', so no room run"
            + " was reserved.",
        spec.getMessage());
    assertEquals(
        "Room 'lounge' belongs to 'node-a', not this box's FDE 'another-box', so no room run was"
            + " reserved.",
        lounge.getMessage());
    assertEquals(List.of(), room.processes.starts);
    assertEquals(List.of(), statuses());
  }

  @Test
  void aProjectWithNoAgentBlockStartsNoMember() {
    room.close();
    room = new LiveRoom(dir, NO_AGENT);
    room.spec(SPEC);
    var member = Engagement.of("claude-code", "full", null, room.now.get().toString());
    Acting.system(() -> room.rooms.updateRoster(SPEC, Roster.solo(member).toJson()));
    posted(SPEC, "first");

    var refused = assertThrows(ApiException.class, () -> room.live.deliver(SPEC, HANDLE));

    assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, refused.failure().errorCode());
    assertEquals(List.of(), statuses());
  }

  @Test
  void aProcessThatExitsBeforeItAnswersFailsItsRunAndSaysWhy() {
    var process = room.prompted(SPEC, "full", "first");

    process.exits(2, "error: unknown option '--permission-prompts'\n");
    room.awaitStopped(process);

    var failed = room.run(process.runId);
    assertEquals("failed", failed.status());
    assertEquals(
        List.of(
            LiveRoom.live(
                failed,
                SPEC,
                1,
                Map.of(
                    "is_error", true, "reason", "error: unknown option '--permission-prompts'"))),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED),
        "the prompt it never began to answer is a turn it left open");
    assertEquals(
        "error: unknown option '--permission-prompts'",
        room.stopOf(process.runId).data().get("reason"));

    room.says(SPEC, "second");

    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions"),
        room.processes.starts.getLast().command(),
        "it recorded no session, so the next process starts fresh");
    var again = room.processes.latest().written.getFirst();
    assertTrue(again.contains("first") && again.contains("second"), again);
  }

  @Test
  void aPromptNobodyAnswersForTheIdleWindowIsAStall() {
    room.impatient();
    var process = room.prompted(SPEC, "full", "first");
    process.onSignal(signal -> process.exitsIfRunning());
    room.later(Duration.ofMinutes(20));

    room.live.sweep();
    room.awaitStopped(process);

    assertEquals("stall (20m)", room.stopOf(process.runId).data().get("reason"));
    assertEquals("stall (20m)", turnEnded().get("reason"));
    assertEquals("stopped", room.run(process.runId).status());
  }

  @Test
  void aMemberWhoseHarnessHasNoLiveModeIsNeverStartedAsOne() {
    room.engage(SPEC, "codex", "full");
    posted(SPEC, "first");

    room.live.deliver(SPEC, HANDLE);

    assertEquals(List.of(), room.processes.starts);
    assertEquals(List.of(), statuses());
  }

  @Test
  void theSweepRunsOnItsOwnOnceStarted() {
    room.live.startSweep(Duration.ofHours(1));
    assertTrue(room.live.serves("claude-code"));
    assertFalse(room.live.serves("codex"));
  }
}
