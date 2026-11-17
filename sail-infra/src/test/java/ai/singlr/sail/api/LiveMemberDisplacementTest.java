/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.LiveRoom.Lines;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.TestRuns;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What ends a live member before its time: a working lane's claim on the repos it holds, a terminal
 * on its conversation, and the limits of its lane. Nothing else does, and none of them loses the
 * conversation.
 */
@ActingAs
class LiveMemberDisplacementTest {

  private static final String SPEC = "live-spec";

  /**
   * A room with no spec: its member lives in the project's own container, as an ad-hoc session
   * does, so the two claim the same tree. A spec's member lives in the spec's workspace, where a
   * run of that spec is what claims its repos.
   */
  private static final String LOUNGE = "lounge";

  private static final String PROJECT = LiveRoom.PROJECT;
  private static final String HANDLE = LiveRoom.HANDLE;

  private static final String LIMITED =
      """
      name: test-project
      repos:
        - url: https://github.com/acme/api.git
          path: api
      agent:
        type: claude-code
        install: [claude-code, codex]
        guardrails:
          max_duration: 1h
          max_idle: 20m
      """;

  @TempDir Path dir;
  private LiveRoom room;

  @BeforeEach
  void setUp() {
    room = new LiveRoom(dir);
    room.spec(SPEC);
    room.room(LOUNGE);
  }

  @AfterEach
  void tearDown() {
    room.close();
  }

  private DispatchOperations.AdhocSession adhoc() {
    return Acting.system(
        () ->
            room.sail
                .dispatching()
                .startAdhoc(
                    PROJECT,
                    new DispatchOperations.AdhocRequest("tidy the repo", null, null, true, false),
                    HANDLE));
  }

  private static void diesOn(FakeAgentProcess.Fake process, AgentProcess.Signal fatal) {
    process.onSignal(
        signal -> {
          if (signal == fatal) {
            process.exits(128 + (fatal == AgentProcess.Signal.INT ? 2 : 15));
          }
        });
  }

  private FakeAgentProcess.Fake idleMember() {
    return idleMember(SPEC);
  }

  private FakeAgentProcess.Fake idleMember(String conversation) {
    var process = room.prompted(conversation, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    return process;
  }

  @Test
  void aClaimOnAnIdleMembersReposEndsItAndTheMemberResumesOnceTheyAreFree() {
    var process = idleMember(LOUNGE);
    process.onClose(() -> process.exits(0));

    var session = adhoc();
    room.awaitStopped(process);

    assertTrue(process.stdinClosed());
    assertEquals(List.of(), process.signals, "an idle member is ended by closing its stdin alone");
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals(
        "displaced by run " + session.runId() + " claiming its repos",
        room.stopOf(process.runId).data().get("reason"));
    var dispatched = room.run(session.runId());
    assertEquals("running", dispatched.status());
    assertEquals("adhoc", dispatched.role());

    var waiting = room.says(LOUNGE, "while that runs");
    assertEquals(1, room.processes.starts.size(), "its repos are held: the member stays away");

    Acting.system(() -> TestRuns.complete(room.runs, session.runId(), "completed", 0));
    var next = room.says(LOUNGE, "and now?");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(
        List.of(room.promptOf(LOUNGE, waiting, next)),
        room.processes.latest().written,
        "everything it missed, in one prompt");
  }

  @Test
  void aClaimDuringATurnWaitsForItsResult() {
    var process = room.prompted(LOUNGE, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Nearly"));
    process.onClose(() -> process.says(Lines.delta(" done."), Lines.result("Nearly done.")));
    diesOn(process, AgentProcess.Signal.INT);

    var session = adhoc();
    room.awaitStopped(process);

    assertEquals(
        List.of(AgentProcess.Signal.INT),
        process.signals,
        "the turn answered, so the process is told to go and goes");
    assertEquals("claude/" + process.runId + ": Nearly done.", room.conversation(LOUNGE).getLast());
    assertEquals(
        false, room.data(Event.WellKnownTypes.AGENT_TURN_ENDED).getFirst().get("is_error"));
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals("running", room.run(session.runId()).status());
  }

  @Test
  void aClaimDuringATurnThatNeverAnswersForcesIt() {
    room.impatient();
    var process = room.prompted(LOUNGE, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Half"));
    diesOn(process, AgentProcess.Signal.INT);

    var session = adhoc();
    room.awaitStopped(process);

    var why = "displaced by run " + session.runId() + " claiming its repos";
    process.awaitStdinClosed();
    assertEquals(List.of(AgentProcess.Signal.INT), process.signals);
    var partial = room.messages.list(LOUNGE, null, 20).getLast();
    assertEquals("Half", partial.body());
    assertEquals(
        List.of(
            LiveRoom.live(
                room.run(process.runId),
                LOUNGE,
                1,
                Map.of("message_id", partial.id(), "is_error", true, "reason", why))),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));
    assertEquals("running", room.run(session.runId()).status());
  }

  @Test
  void aMemberThatWillNotEndRefusesTheClaimAsAnyHolderDoes() {
    room.impatient();
    var process = idleMember(LOUNGE);

    var refused = assertThrows(ApiException.class, this::adhoc);

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refused.failure().errorCode());
    assertEquals(
        List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM),
        process.signals,
        "it was told every way there is");
    assertEquals(
        "running", room.run(process.runId).status(), "and its claim stands while it lives");
  }

  private String build(String specId) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            room.runs.create(
                id,
                PROJECT,
                specId,
                HANDLE,
                "build",
                "claude-code",
                "feat/other",
                "work",
                null,
                null,
                AgentUnit.forRun(id).logPath(),
                AgentUnit.forRun(id).unitName()));
    room.container.started(id);
    return id;
  }

  @Test
  void aClaimABuildHoldsIsRefusedAndNoMemberIsTouched() {
    var process = room.prompted(LOUNGE, "read-only", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    var build = build(null);

    var refused = assertThrows(ApiException.class, this::adhoc);

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refused.failure().errorCode());
    assertTrue(refused.getMessage().contains(build), refused.getMessage());
    assertFalse(process.stdinClosed(), "a read-only member holds nothing a claim could want");
    assertEquals(List.of(), process.signals);
    assertEquals("running", room.run(process.runId).status());
  }

  @Test
  void anotherRoomsMemberNeverDisplacesThisOne() {
    var process = idleMember(LOUNGE);
    room.room("second-room");
    room.engage("second-room", "claude-code", "full");

    room.says("second-room", "hello over there");

    assertEquals(1, room.processes.starts.size(), "the repos are held: the second waits");
    assertFalse(process.stdinClosed(), "a conversation never ends another");
    assertEquals("running", room.run(process.runId).status());
  }

  @Test
  void aFixAgentsClaimEndsTheMemberToo() {
    var process = idleMember();
    process.onClose(() -> process.exits(0));
    var invocation =
        new ReviewLanes.Invocation(
            Lane.FIX,
            DateTimeUtils.newId().toString(),
            PROJECT,
            SPEC,
            "claude-code",
            "fix it",
            "feat/test",
            List.of("api"),
            null,
            null,
            List.of());

    var launched =
        Acting.system(() -> room.sail.reviewLanes().launch(invocation, HANDLE, () -> {}));
    room.awaitStopped(process);

    assertInstanceOf(ReviewLanes.Launch.Started.class, launched);
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals(
        List.of("running"),
        room.runs.forReview(invocation.reviewId()).stream().map(RunStore.RunRow::status).toList());
  }

  /** A session host on which exactly the sessions named in {@code open} are live. */
  private static SessionYield host(List<String> open, List<String> ended) {
    return new SessionYield() {
      @Override
      public Hold lock(String project) {
        return NO_HOLD;
      }

      @Override
      public void end(List<String> sessions, String reason) {
        ended.addAll(sessions);
      }

      @Override
      public boolean live(String session) {
        return open.contains(session);
      }
    };
  }

  @Test
  void aTerminalOnTheMembersConversationEndsItAndHoldsItUntilTheTerminalCloses() {
    var open = new ArrayList<String>();
    var ended = new ArrayList<String>();
    room.terminals(host(open, ended));
    var process = idleMember(LOUNGE);
    process.onClose(() -> process.exits(0));

    assertTrue(
        room.sail.dispatching().endLiveMember(process.runId, "displaced by a terminal"),
        "the attach ends the member before it opens the conversation");
    room.awaitStopped(process);
    open.add(SessionYield.resumeSession(process.runId));

    assertEquals("stopped", room.run(process.runId).status());
    assertEquals("displaced by a terminal", room.stopOf(process.runId).data().get("reason"));
    assertEquals("sess-1", room.run(process.runId).sessionId(), "the session the terminal resumes");

    var during = room.says(LOUNGE, "is the terminal still open?");
    assertEquals(1, room.processes.starts.size(), "one process on a conversation, never two");
    assertEquals(List.of(), ended, "and the terminal is left alone");

    open.clear();
    var after = room.says(LOUNGE, "and now?");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(List.of(room.promptOf(LOUNGE, during, after)), room.processes.latest().written);
  }

  /**
   * B1: a terminal resuming a member's conversation in the spec's workspace holds it as a host
   * session holds one in the project's container: the room's next message starts no second process
   * on that session, and once the terminal ends the member resumes it.
   */
  @Test
  void aTerminalInTheSpecsWorkspaceOnTheMembersSessionStartsNoSecondProcessUntilItEnds() {
    var process = room.prompted(SPEC, "read-only", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    process.onClose(() -> process.exits(0));
    room.sail.dispatching().endLiveMember(process.runId, "displaced by a terminal");
    room.awaitStopped(process);
    room.container.terminalOpened();

    room.says(SPEC, "is the terminal still open?");

    assertEquals(1, room.processes.starts.size(), "one process on a conversation, never two");
    assertTrue(room.container.terminalOpen(), "and the terminal is left alone");

    room.container.terminalClosed();
    room.says(SPEC, "and now?");

    assertEquals(2, room.processes.starts.size());
    assertTrue(
        room.processes.starts.getLast().command().contains("sess-1"),
        String.valueOf(room.processes.starts.getLast().command()));
  }

  /**
   * As on main (DispatchGate.decide: a read-only chat turn is never in a build's way), a read-only
   * member's own turn writes no tree, so it neither ends a terminal open on the spec's conversation
   * nor waits on it: the two run beside each other, and the tree keeps one writer.
   */
  @Test
  void aReadOnlyMembersTurnRunsBesideATerminalOnTheSpecsConversationAndLeavesItOpen() {
    room.container.holds(SPEC);
    room.container.terminalOpened();

    room.prompted(SPEC, "read-only", "first");

    assertEquals(1, room.processes.starts.size(), "the member's own turn starts");
    assertTrue(room.container.terminalOpen(), "and the terminal is left as it is");
  }

  @Test
  void aTerminalThatOpensWhileTheMemberClaimsStillFindsNoSecondProcess() {
    var open = new ArrayList<String>();
    var asked = new AtomicReference<>(0);
    var process = idleMember(LOUNGE);
    process.onClose(() -> process.exits(0));
    room.sail.dispatching().endLiveMember(process.runId, "displaced by a terminal");
    room.awaitStopped(process);
    var session = SessionYield.resumeSession(process.runId);
    room.terminals(
        new SessionYield() {
          @Override
          public Hold lock(String project) {
            return NO_HOLD;
          }

          @Override
          public void end(List<String> sessions, String reason) {}

          @Override
          public boolean live(String name) {
            return asked.updateAndGet(count -> count + 1) > 1 && session.equals(name);
          }
        });

    room.says(LOUNGE, "the attach lands between the member's two looks");

    assertEquals(2, asked.get(), "once before the claim and once after it landed");
    assertEquals(1, room.processes.starts.size());
    assertEquals(
        List.of("failed", "stopped"),
        room.runs.listForRoom(LOUNGE).stream().map(RunStore.RunRow::status).toList(),
        "the claim it had made is given back");
    assertNoLaunchFileOfTheFailedRun();
  }

  @Test
  void aTerminalThatOpensWhileTheMemberStagesLeavesNoLaunchFileBehind() {
    var asked = new AtomicReference<>(0);
    var process = idleMember(LOUNGE);
    process.onClose(() -> process.exits(0));
    room.sail.dispatching().endLiveMember(process.runId, "displaced by a terminal");
    room.awaitStopped(process);
    var session = SessionYield.resumeSession(process.runId);
    room.terminals(
        new SessionYield() {
          @Override
          public Hold lock(String project) {
            return NO_HOLD;
          }

          @Override
          public void end(List<String> sessions, String reason) {}

          @Override
          public boolean live(String name) {
            return asked.updateAndGet(count -> count + 1) > 2 && session.equals(name);
          }
        });

    room.says(LOUNGE, "the attach lands while the launch file is pushed");

    assertEquals(3, asked.get(), "before the claim, before staging, and after staging");
    assertEquals(1, room.processes.starts.size());
    assertNoLaunchFileOfTheFailedRun();
  }

  private void assertNoLaunchFileOfTheFailedRun() {
    var failed =
        room.runs.listForRoom(LOUNGE).stream()
            .filter(run -> "failed".equals(run.status()))
            .findFirst()
            .orElseThrow();
    assertNull(
        room.container.file(AgentUnit.live(failed.id()).launchEnvPath()),
        "the secrets left with the claim");
  }

  @Test
  void aSessionHostThatCannotBeAskedHasNotSaidTheConversationIsFree() {
    var process = idleMember(LOUNGE);
    process.onClose(() -> process.exits(0));
    room.sail.dispatching().endLiveMember(process.runId, "displaced by a terminal");
    room.awaitStopped(process);
    var asked = new AtomicReference<>(0);
    var refusedAt = new AtomicReference<>(1);
    room.terminals(
        new SessionYield() {
          @Override
          public Hold lock(String project) {
            return NO_HOLD;
          }

          @Override
          public void end(List<String> sessions, String reason) {}

          @Override
          public boolean live(String name) throws IOException {
            if (asked.updateAndGet(count -> count + 1).equals(refusedAt.get())) {
              throw new IOException("the host refused the dispatch credential");
            }
            return false;
          }
        });

    var unanswered = room.says(LOUNGE, "anyone?");

    assertEquals(1, room.processes.starts.size(), "no answer is not a no: nothing is started");
    assertEquals(
        List.of("stopped"),
        room.runs.listForRoom(LOUNGE).stream().map(RunStore.RunRow::status).toList(),
        "and nothing was claimed for it");
    asked.set(0);
    var refused = assertThrows(ApiException.class, () -> room.live.deliver(LOUNGE, HANDLE));
    assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
    assertEquals(
        "Could not ask the session host whether a terminal holds the conversation of run "
            + process.runId
            + ", so no process was started over it.",
        refused.getMessage());
    assertEquals(
        "Check the sail-pty-host service on this box; the room's next message tries again.",
        refused.failure().remedy().plain());
    assertEquals("the host refused the dispatch credential", refused.getCause().getMessage());

    asked.set(0);
    refusedAt.set(2);
    var between = room.says(LOUNGE, "the host goes quiet between the member's two looks");

    assertEquals(1, room.processes.starts.size());
    assertEquals(
        List.of("failed", "stopped"),
        room.runs.listForRoom(LOUNGE).stream().map(RunStore.RunRow::status).toList(),
        "the claim made between the two looks is given back");

    asked.set(0);
    refusedAt.set(0);
    var answered = room.says(LOUNGE, "and now?");

    assertEquals(2, room.processes.starts.size(), "a host that answers no frees the conversation");
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(
        List.of(room.promptOf(LOUNGE, unanswered, between, answered)),
        room.processes.latest().written,
        "what was posted while the host could not be asked was never lost");
  }

  @Test
  void aMemberWhoseRunAClaimEndedWhileItStartedIsNeverPrompted() {
    room.engage(SPEC, "claude-code", "full");
    var cli = room.elsewhere().dispatching();
    room.processes.onStart(starting -> starting.onClose(() -> starting.exits(0)));
    room.live.useProcesses(
        (container, workDir, env, command, pidFile, logFile, launchEnvFile) -> {
          assertTrue(
              cli.endLiveMember(env.get("SAIL_RUN_ID"), "displaced by a build"),
              "a run that records no process was never prompted, so another process may end it");
          return room.processes.start(
              container, workDir, env, command, pidFile, logFile, launchEnvFile);
        });

    var first = room.says(SPEC, "first");

    var lost = room.processes.latest();
    assertEquals(List.of(), lost.written, "a run that holds nothing is given no work");
    assertTrue(lost.stdinClosed(), "and its process is ended");
    assertEquals(List.of(), lost.signals);
    assertEquals("stopped", room.run(lost.runId).status());
    assertNull(room.run(lost.runId).pid(), "an ended run takes no process");
    assertEquals("displaced by a build", room.stopOf(lost.runId).data().get("reason"));
    assertEquals(1, stops(lost.runId));
    assertEquals(Set.of(), room.runs.deliveredMessageIds(lost.runId));

    room.live.useProcesses(room.processes);
    var second = room.says(SPEC, "second");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        Set.of(first, second),
        room.runs.deliveredMessageIds(room.processes.latest().runId),
        "the message it was never handed is in the next process's first prompt");
    assertEquals(
        Integer.valueOf(room.processes.latest().pid().getAsInt()),
        room.memberRun().pid(),
        "a run that is still its member's records the process it is");
  }

  @Test
  void aMemberDisplacedBeforeItsPidIsWrittenIsNeverPrompted() {
    room.engage(SPEC, "claude-code", "full");
    var cli = room.elsewhere().dispatching();
    var freed = new AtomicReference<Boolean>();
    room.processes.slowToName(true);
    room.processes.onStart(
        starting ->
            starting
                .onClose(() -> starting.exits(0))
                .onPidAsked(
                    () -> {
                      freed.set(cli.endLiveMember(starting.runId, "displaced by a build"));
                      starting.writesPid();
                    }));

    var first = room.says(SPEC, "first");

    var lost = room.processes.latest();
    assertEquals(
        Boolean.TRUE, freed.get(), "a run that records no process is ended as never started");
    assertEquals(List.of(), lost.written, "so the process that then names itself gets no work");
    assertTrue(lost.stdinClosed());
    assertFalse(room.container.alive(lost.runId), "and nothing works what the run gave back");
    assertEquals("stopped", room.run(lost.runId).status());
    assertNull(room.run(lost.runId).pid(), "the stamp lost the compare the end won");
    assertEquals("displaced by a build", room.stopOf(lost.runId).data().get("reason"));
    assertEquals(1, stops(lost.runId));
    assertEquals(Set.of(), room.runs.deliveredMessageIds(lost.runId));

    room.processes.slowToName(false);
    room.processes.onStart(starting -> {});
    var second = room.says(SPEC, "second");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        Set.of(first, second), room.runs.deliveredMessageIds(room.processes.latest().runId));
  }

  @Test
  void aMemberDisplacedAsItIsFirstPromptedIsSignalledBeforeItsRunIsFreed() {
    room.engage(SPEC, "claude-code", "full");
    var cli = room.elsewhere().dispatching();
    var asks = new AtomicInteger();
    var freed = new AtomicReference<Boolean>();
    var aliveOnceFreed = new AtomicReference<Boolean>();
    var displaced = new AtomicReference<FakeAgentProcess.Fake>();
    room.processes.slowToName(true);
    room.processes.onStart(
        starting -> {
          if (!displaced.compareAndSet(null, starting)) {
            starting.onPidAsked(() -> named(starting));
            return;
          }
          starting
              .onPidAsked(
                  () -> {
                    if (asks.incrementAndGet() == 1) {
                      room.windowElapses();
                    } else {
                      named(starting);
                    }
                  })
              .onWrite(
                  line -> {
                    freed.set(cli.endLiveMember(starting.runId, "displaced by a build"));
                    named(starting);
                    aliveOnceFreed.set(room.container.alive(starting.runId));
                    starting.exits(143);
                  });
        });

    var first = room.says(SPEC, "first");

    var process = displaced.get();
    process.settle();
    room.settle();
    assertEquals(Boolean.TRUE, freed.get());
    assertEquals(
        Boolean.FALSE,
        aliveOnceFreed.get(),
        "a prompted run records its process, so it is ended through its pid file before it is"
            + " freed: a wrapper slow to write its pid is waited for, never read as gone");
    assertEquals(1, process.written.size(), "it was prompted while its run still held its repos");
    assertEquals(
        Set.of(),
        room.runs.deliveredMessageIds(process.runId),
        "it ended before it began to answer, so the message is not its");
    assertEquals(Set.of(first), room.runs.deliveredMessageIds(room.processes.latest().runId));
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals(
        Integer.valueOf(room.container.pidOf(process.runId)), room.run(process.runId).pid());
    assertEquals("displaced by a build", room.stopOf(process.runId).data().get("reason"));
    assertEquals(1, stops(process.runId));
  }

  /** The wrapper of {@code process} writes its pid file, unless it already has. */
  private void named(FakeAgentProcess.Fake process) {
    if (!room.container.launched().contains(process.runId)) {
      process.writesPid();
    }
  }

  @Test
  void aMemberThatStoppedReadingItsStdinIsStillReadAndIsStillEnded() {
    var process = idleMember();
    process.stopsReading();
    diesOn(process, AgentProcess.Signal.TERM);

    var second = room.posts(SPEC, "second");
    process.awaitBlockedWrite();
    room.later(Duration.ofSeconds(1));
    process.says(Lines.delta("Two"));
    process.settle();
    room.heard(Event.WellKnownTypes.AGENT_TEXT_DELTA, 2);

    room.grace(
        new LiveMembers.Grace(
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofSeconds(60)));
    var ended =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () -> room.sail.dispatching().endLiveMember(process.runId, "displaced by a build"));
    room.settle();

    assertTrue(ended, "a write the process never reads holds up neither its lines nor its end");
    assertEquals(
        List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM),
        process.signals,
        "its stdin could not be closed behind that write, and the signals did not wait for it");
    assertEquals(
        List.of("One", "Two"),
        room.data(Event.WellKnownTypes.AGENT_TEXT_DELTA).stream()
            .map(data -> data.get("text"))
            .toList());
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals("displaced by a build", room.stopOf(process.runId).data().get("reason"));
    assertEquals(2, room.processes.starts.size(), "the message nobody took starts the next one");
    assertEquals(List.of(room.promptOf(SPEC, second)), room.processes.latest().written);
  }

  private long stops(String runId) {
    return room.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
        .filter(event -> runId.equals(event.data().get(Event.WellKnownData.RUN_ID)))
        .count();
  }

  @Test
  void anotherProcessEndsAMemberBySignalAndWhoeverSeesItGoneFinishesItsRun() {
    var process = idleMember();
    var cli = room.elsewhere().dispatching();

    assertTrue(cli.endLiveMember(process.runId, AgentAttachWhy.TERMINAL));
    room.settle();

    assertFalse(process.stdinClosed(), "only the process that started it holds its stdin");
    assertFalse(room.container.alive(process.runId), "so it is signalled through the container");
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals(AgentAttachWhy.TERMINAL, room.stopOf(process.runId).data().get("reason"));

    process.exits(143);
    process.settle();
    room.settle();

    assertEquals(1, stops(process.runId), "the owner sees it gone and finds its run finished");
    assertTrue(cli.endLiveMember(process.runId, "asked again"), "a run that is over is over");
    assertEquals(1, stops(process.runId));

    room.says(SPEC, "back?");
    assertEquals(2, room.processes.starts.size(), "and the room's next message resumes it");
  }

  /**
   * A message meeting a member whose process has ended, before its reader has seen the end, finds
   * the pipe gone: the message waits for the member to finish and starts the next one.
   */
  @Test
  void aMessageMeetingAnEndedProcessWaitsForItsMemberToFinishAndStartsTheNext() throws Exception {
    var process = idleMember();
    process.holdsItsEnd();
    process.exits(143);
    process.awaitHeldEnd();

    var said = Thread.ofVirtual().start(() -> room.says(SPEC, "back?"));
    process.awaitRefusedWrite();
    assertEquals(1, room.processes.starts.size(), "it waits for the member to finish");
    process.releasesItsEnd();
    said.join();

    assertEquals(
        List.of(), room.runs.givenBack(process.runId), "a write that failed handed nothing");
    assertEquals(2, room.processes.starts.size(), "and starts the next one");
    assertTrue(room.processes.latest().written.stream().anyMatch(line -> line.contains("back?")));
  }

  /**
   * A run another process finished is handed nothing, even while its member's process still reads:
   * a message meeting it is not written to that process and stays owed, so the room's next message
   * hands it to the next member.
   */
  @Test
  void aRunFinishedElsewhereIsHandedNothing() {
    var process = idleMember();
    assertTrue(
        room.elsewhere().dispatching().endLiveMember(process.runId, AgentAttachWhy.TERMINAL));
    room.settle();
    room.grace(
        new LiveMembers.Grace(
            Duration.ofMillis(1),
            Duration.ofMillis(1),
            Duration.ofMillis(1),
            Duration.ofMillis(1)));

    var back = room.says(SPEC, "back?");

    assertTrue(process.written.stream().noneMatch(line -> line.contains("back?")));
    assertFalse(room.runs.deliveredMessageIds(process.runId).contains(back));
    process.exits(143);
    process.settle();
    room.settle();
    room.says(SPEC, "again");
    assertEquals(2, room.processes.starts.size());
    assertTrue(room.processes.latest().written.stream().anyMatch(line -> line.contains("back?")));
  }

  /**
   * A message the client of an ended process took, before the member's reader saw the end, was
   * never read: it is the next member's to answer, not lost with the old one. Here the run is
   * finished elsewhere while the message is being written, after it was handed: that finish gives
   * it back, so the next member starts at once for it.
   */
  @Test
  void aMessageTheClientOfAnEndedProcessTookIsGivenToTheNextMember() {
    var process = idleMember();
    process.onWrite(
        line ->
            assertTrue(
                room.elsewhere()
                    .dispatching()
                    .endLiveMember(process.runId, AgentAttachWhy.TERMINAL)));
    process.outlivedByItsClient().holdsItsEnd();
    process.exits(143);
    process.awaitHeldEnd();

    room.says(SPEC, "back?");
    assertTrue(
        process.written.stream().anyMatch(line -> line.contains("back?")), "the client took it");
    process.releasesItsEnd();
    process.settle();
    room.settle();

    assertEquals(2, room.processes.starts.size(), "the next member is started for it");
    assertTrue(room.processes.latest().written.stream().anyMatch(line -> line.contains("back?")));
    assertTrue(
        room.conversation(SPEC).stream()
            .noneMatch(line -> line.startsWith(MessageStore.SAIL_AUTHOR + ": ")),
        "nothing is said when the next member starts at once");
  }

  /**
   * Claude Code answers a message written while it is answering in its next turn, never in the
   * running one (LiveStreamTest's recording). A member that dies before that turn begins never
   * answered it: it is given back, the room says it waits, and the next member is handed it.
   */
  @Test
  void aFollowUpHandedWhileTheMemberAnswersIsGivenBackWhenItDiesBeforeAnsweringIt() {
    var process = room.prompted(LOUNGE, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Working on it"));
    var followUp = room.says(LOUNGE, "and the tests please");
    assertTrue(process.written.stream().anyMatch(line -> line.contains("and the tests please")));

    process.exits(1);
    process.settle();
    room.settle();

    assertFalse(room.runs.deliveredMessageIds(process.runId).contains(followUp));
    assertEquals(1, room.processes.starts.size(), "a crash starts nothing at once");
    assertTrue(
        room.conversation(LOUNGE).contains(waitingLine(LOUNGE, followUp)),
        "the room says it waits");

    room.says(LOUNGE, "anyone?");

    assertEquals(2, room.processes.starts.size());
    var prompt = String.join("\n", room.processes.latest().written);
    assertTrue(prompt.contains("and the tests please"), "the next member is handed the follow-up");
    assertFalse(
        prompt.contains("Your last process ended"),
        "no turn took it, so the session does not hold it");
  }

  /**
   * A message written after a turn's {@code init} but before its first answer waits for the next
   * turn (the turn took what was waiting at its {@code init}): its answering does not make it
   * answered, and a member that ends before the next turn gives it back.
   */
  @Test
  void aMessageWrittenBetweenATurnsInitAndItsFirstAnswerIsTheNextTurns() {
    var process = room.prompted(LOUNGE, "full", "first");
    room.says(process, Lines.init("sess-1"));
    var late = room.says(LOUNGE, "one more thing");
    room.says(process, Lines.delta("answering first"));

    process.exits(1);
    process.settle();
    room.settle();

    assertFalse(room.runs.deliveredMessageIds(process.runId).contains(late));
    room.says(LOUNGE, "anyone?");
    var prompt = String.join("\n", room.processes.latest().written);
    assertTrue(prompt.contains("one more thing"), "the next member is handed it");
    assertFalse(prompt.contains("Your last process ended"), "no turn took it");
  }

  /**
   * Claude Code reads a message and prints its turn's {@code init} at once, so Sail's reader can
   * see that {@code init} before the write that handed the message returns: the message is recorded
   * handed before it is written, so that turn takes it, and its answer answers it. The member's
   * idle end then gives nothing back, says nothing, and the next member is not handed it again.
   */
  @Test
  void aTurnsInitReadBeforeItsWriteReturnsTakesTheMessageItAnswers() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    var once = new AtomicBoolean();
    process.onWrite(
        line -> {
          if (once.compareAndSet(false, true)) {
            process.says(Lines.init("sess-1"));
            process.settle();
          }
        });
    var second = room.says(SPEC, "second");
    room.says(process, Lines.delta("Two"), Lines.result("Two"));

    process.exits(0);
    process.settle();
    room.settle();
    var answered = room.conversation(SPEC);
    room.says(SPEC, "third");

    assertTrue(room.runs.deliveredMessageIds(process.runId).contains(second));
    assertEquals(List.of(), room.runs.givenBack(process.runId));
    assertFalse(answered.stream().anyMatch(line -> line.startsWith(MessageStore.SAIL_AUTHOR)));
    assertEquals(2, room.processes.starts.size());
    assertFalse(
        room.processes.latest().written.stream().anyMatch(line -> line.contains("second")),
        "'Two' answered it");
  }

  /**
   * A message a turn took at its {@code init}, by a member that ended before answering it, may be
   * in the session the next member resumes: it is handed again with a line saying so, even after
   * sail restarts in between, since the give-back records it on the run.
   */
  @Test
  void aMessageATurnTookAndNeverAnsweredIsHandedAgainWithItsLineAcrossARestart() {
    var process = idleMember(LOUNGE);
    var second = room.says(LOUNGE, "second");
    room.says(process, Lines.init("sess-1"));

    process.exits(1);
    process.settle();
    room.settle();
    room.restart();
    room.says(LOUNGE, "anyone?");

    assertEquals(Set.of(second), room.runs.unansweredMessageIds(process.runId));
    var prompt = String.join("\n", room.processes.latest().written);
    assertTrue(
        prompt.contains(
            "Your last process ended before it answered the message below from node-a; your"
                + " session may already show it, so answer each once."),
        prompt);
    assertTrue(prompt.contains("second"));
  }

  /**
   * A member dismissed while it holds a message it never answered: nothing starts it again, so the
   * room is told the message is unanswered and how to seat an agent, not that the next message
   * answers it (a dismissal itself says nothing in the room).
   */
  @Test
  void aMessageHandedToAMemberThatIsDismissedIsSaidUnanswered() {
    var process = idleMember(SPEC);
    var second = room.says(SPEC, "second");
    process.onClose(() -> process.exits(0));

    room.dismiss(SPEC);
    process.settle();
    room.settle();

    var rows = rows(SPEC, second);
    assertTrue(
        room.conversation(SPEC)
            .contains(
                MessageStore.SAIL_AUTHOR
                    + ": "
                    + LiveMembers.unseated(
                        rows, room.specs.findById(SPEC).orElseThrow(), "claude-code")),
        room.conversation(SPEC).toString());
    assertFalse(room.conversation(SPEC).contains(waitingLine(SPEC, second)));
    room.says(SPEC, "anyone?");
    assertEquals(1, room.processes.starts.size(), "no agent is seated");
  }

  /**
   * Stopping fits inside the unit's stop timeout and leaves what closes after it a share of its
   * own, so systemd never kills sail before its database is closed.
   */
  @Test
  void stoppingLeavesWhatClosesAfterItsShareOfTheUnitsStopTimeout() {
    assertEquals(
        SystemdServiceInstaller.STOP_TIMEOUT,
        LiveMembers.STOPPING.plus(LiveMembers.AFTER_STOPPING));
    assertTrue(LiveMembers.AFTER_STOPPING.isPositive());
  }

  /**
   * Stopping waits for every reader still running under one deadline, not one after another, and
   * names in the log each that was not done by it.
   */
  @Test
  void stoppingWaitsForEveryReaderUnderOneDeadlineAndNamesTheOnesNotDone() {
    room.impatient();
    room.room("second-room");
    var first = idleMember(LOUNGE);
    var second = room.prompted("second-room", "read-only", "hello");
    assertNotEquals(first.runId, second.runId, "two members, each with a reader");
    for (var process : List.of(first, second)) {
      process.holdsItsEnd();
      process.onSignal(signal -> process.exitsIfRunning());
    }
    first.onSignal(
        signal -> {
          room.later(LiveMembers.STOPPING);
          first.exitsIfRunning();
        });
    var err = System.err;
    var said = new ByteArrayOutputStream();
    System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          room.live::close,
          "the deadline is the injected clock's, not real time");
    } finally {
      System.setErr(err);
      first.releasesItsEnd();
      second.releasesItsEnd();
    }

    var log = said.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("sail stopped before run " + first.runId + " of " + LOUNGE), log);
    assertTrue(log.contains("sail stopped before run " + second.runId + " of second-room"), log);
  }

  /**
   * An end this box made (an operator's, a terminal's, a claim's) is not undone by starting the
   * member again at once, even when another process finished its run first: what it was handed and
   * never answered waits for the room's next message.
   */
  @Test
  void aMessageHandedAsThisBoxEndsTheMemberWaitsForTheNextMessage() {
    var process = idleMember(LOUNGE);
    var second = room.says(LOUNGE, "second");
    assertTrue(process.written.stream().anyMatch(line -> line.contains("second")));
    process.onClose(() -> process.exits(0));
    assertTrue(
        room.elsewhere().dispatching().endLiveMember(process.runId, "stopped by an operator"));
    room.settle();

    room.sail.dispatching().endLiveMember(process.runId, "displaced by a terminal");
    process.settle();
    room.settle();

    assertFalse(room.runs.deliveredMessageIds(process.runId).contains(second));
    assertEquals(1, room.processes.starts.size(), "an end this box made starts nothing at once");
    assertTrue(room.conversation(LOUNGE).contains(waitingLine(LOUNGE, second)));

    room.says(LOUNGE, "anyone?");

    assertEquals(2, room.processes.starts.size());
    assertTrue(room.processes.latest().written.stream().anyMatch(line -> line.contains("second")));
  }

  /** A next member that cannot start at once (its repos held, its host refusing) is said. */
  @Test
  void aMessageWhoseNextMemberCannotStartAtOnceWaitsAndTheRoomSaysSo() {
    var process = idleMember(LOUNGE);
    var second = room.says(LOUNGE, "second");
    assertTrue(
        room.elsewhere().dispatching().endLiveMember(process.runId, AgentAttachWhy.TERMINAL));
    room.settle();
    room.processes.refusing(new IOException("no incus"));

    process.exits(143);
    process.settle();
    room.settle();

    assertEquals(1, room.processes.starts.size());
    assertFalse(room.runs.deliveredMessageIds(process.runId).contains(second));
    assertEquals(
        1,
        room.conversation(LOUNGE).stream().filter(waitingLine(LOUNGE, second)::equals).count(),
        "said once");
  }

  /**
   * The finish and the give-back are one transaction: a give-back that fails leaves the run
   * unfinished, never finished with its message still marked delivered.
   */
  @Test
  void aGiveBackThatFailsLeavesTheRunUnfinished() {
    var process = idleMember(LOUNGE);
    var second = room.says(LOUNGE, "second");
    room.db.execute(
        "CREATE TRIGGER no_give_back BEFORE UPDATE OF state ON run_delivered_messages"
            + " WHEN NEW.state = 'given' BEGIN SELECT RAISE(ABORT, 'refused'); END");

    process.exits(1);
    process.settle();
    room.settle();

    assertEquals("running", room.run(process.runId).status(), "the finish rolled back with it");
    assertTrue(room.runs.deliveredMessageIds(process.runId).contains(second));
  }

  private String waitingLine(String roomId, String... messageIds) {
    return MessageStore.SAIL_AUTHOR + ": " + LiveMembers.waiting(rows(roomId, messageIds));
  }

  private List<MessageStore.MessageRow> rows(String roomId, String... messageIds) {
    var ids = List.of(messageIds);
    return room.messages.list(roomId, null, 100).stream()
        .filter(message -> ids.contains(message.id()))
        .toList();
  }

  @Test
  void aRunThatIsNoLiveMembersIsNeverEndedAsOne() {
    var cli = room.elsewhere().dispatching();
    var build = build("other-spec");

    assertFalse(cli.endLiveMember(build, "not a member"));
    assertFalse(cli.endLiveMember(DateTimeUtils.newId().toString(), "no such run"));

    assertTrue(room.container.alive(build));
    assertEquals("running", room.run(build).status());
  }

  @Test
  void aMemberAnotherProcessCannotProveGoneKeepsItsRun() {
    var process = idleMember();
    var cli = room.elsewhere().dispatching();

    room.container.survivesKill(true);
    assertFalse(cli.endLiveMember(process.runId, AgentAttachWhy.TERMINAL));
    room.container.survivesKill(false);
    room.shell.unanswered("kill");
    assertFalse(cli.endLiveMember(process.runId, AgentAttachWhy.TERMINAL));

    assertEquals(
        "running", room.run(process.runId).status(), "nothing is freed under a live agent");
  }

  /** Why the attach ends a member, as the command says it. */
  private static final class AgentAttachWhy {
    static final String TERMINAL = "displaced by a terminal on its conversation";
  }

  @Test
  void aMemberPastItsLifetimeIsCutShortAndResumes() {
    room.close();
    room = new LiveRoom(dir, LIMITED);
    room.spec(SPEC);
    room.impatient();
    var process = room.prompted(SPEC, "full", "first");
    diesOn(process, AgentProcess.Signal.TERM);
    room.says(process, Lines.init("sess-1"), Lines.delta("Half"));
    room.later(Duration.ofMinutes(59));
    room.says(process, Lines.delta(" way"));
    room.live.sweep();
    assertFalse(process.stdinClosed(), "inside its hour, and working");
    room.later(Duration.ofMinutes(1));

    room.live.sweep();
    room.awaitStopped(process);

    process.awaitStdinClosed();
    assertEquals(List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM), process.signals);
    var partial = room.messages.list(SPEC, null, 20).getLast();
    assertEquals("Half way", partial.body());
    assertEquals(
        List.of(
            LiveRoom.live(
                room.run(process.runId),
                SPEC,
                1,
                Map.of("message_id", partial.id(), "is_error", true, "reason", "time limit (1h)"))),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals("time limit (1h)", room.stopOf(process.runId).data().get("reason"));

    room.says(SPEC, "carry on");

    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
  }

  @Test
  void aTurnSilentForTheIdleWindowIsAStall() {
    room.impatient();
    var process = room.prompted(SPEC, "full", "first");
    diesOn(process, AgentProcess.Signal.INT);
    room.says(process, Lines.init("sess-1"), Lines.delta("Thinking"));
    room.later(Duration.ofMinutes(20));

    room.live.sweep();
    room.awaitStopped(process);

    assertEquals("stall (20m)", room.stopOf(process.runId).data().get("reason"));
    assertEquals(
        "stall (20m)", room.data(Event.WellKnownTypes.AGENT_TURN_ENDED).getFirst().get("reason"));
  }
}
