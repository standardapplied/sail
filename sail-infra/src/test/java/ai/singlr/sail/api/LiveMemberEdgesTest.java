/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.LiveRoom.Lines;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.LiveMemberPrompt;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
    assertEquals(MessageStore.Mark.ERROR, room.messages.list(SPEC, null, 20).getLast().mark());
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
  void aTurnItsHarnessSaysFailedIsKeptInItsOwnWordsAndMarkedAnErrorAndAnAnswerIsNot() {
    var process = room.prompted(SPEC, "full", "first");
    var run = room.memberRun();

    room.says(process, Lines.failure("success", "Not logged in · Please run /login"));

    var failed = room.messages.list(SPEC, null, 20).getLast();
    assertEquals(
        List.of("claude/" + run.id(), "Not logged in · Please run /login", MessageStore.Mark.ERROR),
        List.of(failed.author(), failed.body(), failed.mark()));
    assertEquals(
        LiveRoom.live(run, SPEC, 1, Map.of("message_id", failed.id(), "is_error", true)),
        turnEnded(),
        "no reason is told, and never 'success'");
    assertEquals(true, SpecMessageView.from(failed).toMap().get("error"), "and a reader is told");

    room.says(SPEC, "again?");
    room.says(process, Lines.delta("Half way"), Lines.failure("error_max_turns", ""));
    var cut = room.messages.list(SPEC, null, 20).getLast();
    assertEquals(List.of("Half way", MessageStore.Mark.ERROR), List.of(cut.body(), cut.mark()));
    assertEquals("error_max_turns", turnEnded().get("reason"), "a subtype naming a failure is it");

    room.says(SPEC, "and now?");
    room.says(process, Lines.delta("Fine."), Lines.result("Fine."));
    var answer = room.messages.list(SPEC, null, 20).getLast();
    assertEquals(List.of("Fine.", MessageStore.Mark.PLAIN), List.of(answer.body(), answer.mark()));
    assertFalse(SpecMessageView.from(answer).toMap().containsKey("error"));
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
        (run, roomId, text, mark) -> {
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

  private static final int SLICE = 31_930;
  private static final String CONTINUES = "\n[continues in the next prompt]";
  private static final String CONTINUED = "[continued from the previous prompt]\n";

  /** The two prompts a message of 40,000 characters and a last instruction is written as. */
  private List<String> acrossTwoPrompts(String messageId) {
    var message = room.messages.findById(messageId).orElseThrow();
    var written = message.author() + " (" + message.createdAt() + "):\n" + message.body();
    return List.of(
        LiveStream.userMessage(written.substring(0, SLICE) + CONTINUES),
        LiveStream.userMessage(CONTINUED + written.substring(SLICE)));
  }

  private Set<String> everyMessage() {
    return room.messages.list(SPEC, null, 20).stream()
        .map(MessageStore.MessageRow::id)
        .collect(Collectors.toSet());
  }

  @Test
  void aMessageLongerThanAPromptIsWrittenAcrossPromptsAndIsDeliveredOnceAllOfItIs() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result(""));

    var huge = room.says(SPEC, "a".repeat(40_000) + " FINAL_INSTRUCTION");

    assertEquals(acrossTwoPrompts(huge), process.written.subList(1, process.written.size()));
    assertEquals(
        Set.of(room.messages.list(SPEC, null, 20).getFirst().id(), huge),
        room.runs.deliveredMessageIds(process.runId));
  }

  @Test
  void aMessageAProcessTookOnlyPartOfIsHandedWholeToTheNext() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result(""));
    process.onWrite(line -> process.breaksStdin());

    var huge = room.says(SPEC, "a".repeat(40_000) + " FINAL_INSTRUCTION");

    assertEquals(
        acrossTwoPrompts(huge).subList(0, 1),
        process.written.subList(1, process.written.size()),
        "its stdin broke after the first of the two");
    assertFalse(
        room.runs.deliveredMessageIds(process.runId).contains(huge),
        "a message is not delivered by its beginning");
    assertEquals(2, room.processes.starts.size());
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(acrossTwoPrompts(huge), room.processes.latest().written);
    assertEquals(everyMessage(), room.runs.deliveredMessageIds(room.processes.latest().runId));
  }

  @Test
  void aFreshSessionIsShownARoomLongerThanAPromptOnePromptAfterAnother() {
    var first = room.says(SPEC, "a".repeat(20_000));
    var second = room.says(SPEC, "b".repeat(20_000));

    var process = room.prompted(SPEC, "full", "what do you make of those?");

    var third = room.messages.list(SPEC, null, 20).getLast().id();
    var opening =
        LiveMemberPrompt.fresh(
                room.specs.findById(SPEC).orElseThrow(),
                "Live spec",
                room.messages.list(SPEC, null, 20).subList(0, 1),
                true)
            .getFirst()
            .text();
    assertEquals(
        List.of(LiveStream.userMessage(opening), room.promptOf(SPEC, second, third)),
        process.written,
        "who it is and the oldest message, then the rest: nothing of the room is dropped");
    assertEquals(Set.of(first, second, third), room.runs.deliveredMessageIds(process.runId));
  }

  @Test
  void aMemberDismissedWhileItStartedIsEndedBeforeItIsPrompted() {
    room.engage(SPEC, "claude-code", "full");
    room.processes.onStart(starting -> starting.onClose(() -> starting.exits(0)));
    room.live.useProcesses(
        (container, workDir, env, command, pidFile, logFile) -> {
          room.vacate(SPEC);
          room.live.membershipChanged(SPEC);
          return room.processes.start(container, workDir, env, command, pidFile, logFile);
        });

    room.says(SPEC, "first");

    var process = room.processes.latest();
    assertEquals(List.of(), process.written, "nobody sits in the room: nothing is asked of it");
    assertTrue(
        process.stdinClosed(), "the change that found no member to end is seen at the start");
    assertEquals(List.of(), process.signals);
    assertEquals(List.of("stopped"), statuses());
    assertEquals("membership changed", room.stopOf(process.runId).data().get("reason"));
    assertEquals(List.of(), room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));
    assertEquals(Set.of(), room.runs.deliveredMessageIds(process.runId));
  }

  @Test
  void aMemberIsPromptedOnlyOnceItsRunRecordsThePidItsWrapperWrote() {
    room.engage(SPEC, "claude-code", "full");
    var asks = new AtomicInteger();
    var writtenUnnamed = new AtomicReference<List<String>>();
    var recordedUnnamed = new AtomicReference<Integer>(-1);
    room.processes.slowToName(true);
    room.processes.onStart(
        starting ->
            starting.onPidAsked(
                () -> {
                  if (asks.incrementAndGet() == 1) {
                    writtenUnnamed.set(List.copyOf(starting.written));
                    recordedUnnamed.set(room.run(starting.runId).pid());
                    room.windowElapses();
                  } else {
                    starting.writesPid();
                  }
                }));

    var first = room.says(SPEC, "first");

    var process = room.processes.latest();
    assertEquals(2, asks.get(), "asked again once the first ask found no pid file");
    assertEquals(List.of(), writtenUnnamed.get(), "nothing is written to a process with no pid");
    assertNull(recordedUnnamed.get(), "and its run records none: it reads as never prompted");
    assertEquals(1, process.written.size());
    assertEquals(Integer.valueOf(room.container.pidOf(process.runId)), room.memberRun().pid());
    assertEquals(Set.of(first), room.runs.deliveredMessageIds(process.runId));
  }

  @Test
  void aProcessThatNeverWritesItsPidIsEndedUnpromptedAndItsRunIsFreedAtOnce() {
    room.engage(SPEC, "claude-code", "full");
    room.impatient();
    room.processes.slowToName(true);
    room.processes.onStart(
        starting -> starting.onPidAsked(() -> room.later(LiveMembers.PID_PATIENCE)));

    var first = room.says(SPEC, "first");

    var process = room.processes.latest();
    process.awaitStdinClosed();
    assertEquals(List.of(), process.written);
    assertEquals(List.of(), process.signals, "a process with no pid cannot be signalled");
    assertEquals(
        List.of("stopped"),
        statuses(),
        "it was never prompted, so what it reserved is not held for a process that may never run");
    assertNull(room.run(process.runId).pid());
    assertEquals(
        "not started: The room's live member did not start: its process wrote no pid.",
        room.stopOf(process.runId).data().get("reason"));
    assertEquals(List.of(), room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));

    process.exits(0);
    room.grace(LiveRoom.PATIENT);
    room.processes.slowToName(false);
    room.processes.onStart(starting -> {});
    var second = room.says(SPEC, "second");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        Set.of(first, second),
        room.runs.deliveredMessageIds(room.processes.latest().runId),
        "the message it was never handed is in the next process's first prompt");
  }

  @Test
  void aProcessThatEndsBeforeItWritesItsPidFailsItsRunAndSaysWhy() {
    room.engage(SPEC, "claude-code", "full");
    room.processes.slowToName(true);
    room.processes.onStart(
        starting ->
            starting.onPidAsked(
                () -> {
                  starting.exits(255, "Error: Instance is not running\n");
                  room.heard(Event.WellKnownTypes.AGENT_SESSION_STOPPED, 1);
                }));

    room.says(SPEC, "first");

    var process = room.processes.latest();
    var failed = room.run(process.runId);
    assertEquals(List.of(), process.written);
    assertEquals("failed", failed.status(), "nobody asked it to end, and it never started");
    assertEquals(255, failed.exitCode());
    assertNull(failed.pid());
    assertEquals("Error: Instance is not running", room.stopOf(process.runId).data().get("reason"));
    assertEquals(
        List.of(),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED),
        "it was handed nothing, so it left no turn open");
  }

  @Test
  void aMemberWhoseStartFailsIsEndedAndNeverPrompted() {
    room.engage(SPEC, "claude-code", "full");
    room.processes.onStart(
        starting ->
            starting
                .onClose(() -> starting.exits(0))
                .onPidAsked(
                    () -> {
                      throw new IllegalStateException("the container could not be asked");
                    }));

    var first = room.says(SPEC, "first");

    var process = room.processes.latest();
    assertEquals(List.of(), process.written);
    assertTrue(process.stdinClosed(), "a member that could not be admitted is not left up");
    assertEquals(List.of("stopped"), statuses());
    assertEquals(
        "not started: the container could not be asked",
        room.stopOf(process.runId).data().get("reason"));

    room.processes.onStart(starting -> {});
    var second = room.says(SPEC, "second");

    assertEquals(2, room.processes.starts.size(), "nothing of it is left to hand the message to");
    assertEquals(
        Set.of(first, second), room.runs.deliveredMessageIds(room.processes.latest().runId));
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
        List.of(LiveRoom.live(failed, SPEC, 1, Map.of())),
        room.data(Event.WellKnownTypes.AGENT_TURN_BEGAN),
        "and that turn's beginning is told before its end");
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
