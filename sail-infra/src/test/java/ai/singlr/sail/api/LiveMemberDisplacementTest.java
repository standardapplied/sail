/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.LiveRoom.Lines;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final String PROJECT = LiveRoom.PROJECT;
  private static final String HANDLE = LiveRoom.HANDLE;

  private static final String LIMITED =
      """
      name: test-project
      ssh:
        user: dev
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
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    return process;
  }

  @Test
  void aClaimOnAnIdleMembersReposEndsItAndTheMemberResumesOnceTheyAreFree() {
    var process = idleMember();
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

    var waiting = room.says(SPEC, "while that runs");
    assertEquals(1, room.processes.starts.size(), "its repos are held: the member stays away");

    Acting.system(() -> room.runs.complete(session.runId(), "completed", 0));
    var next = room.says(SPEC, "and now?");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(
        List.of(room.promptOf(SPEC, waiting, next)),
        room.processes.latest().written,
        "everything it missed, in one prompt");
  }

  @Test
  void aClaimDuringATurnWaitsForItsResult() {
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Nearly"));
    process.onClose(() -> process.says(Lines.delta(" done."), Lines.result("Nearly done.")));
    diesOn(process, AgentProcess.Signal.INT);

    var session = adhoc();
    room.awaitStopped(process);

    assertEquals(
        List.of(AgentProcess.Signal.INT),
        process.signals,
        "the turn answered, so the process is told to go and goes");
    assertEquals("claude/" + process.runId + ": Nearly done.", room.conversation(SPEC).getLast());
    assertEquals(
        false, room.data(Event.WellKnownTypes.AGENT_TURN_ENDED).getFirst().get("is_error"));
    assertEquals("stopped", room.run(process.runId).status());
    assertEquals("running", room.run(session.runId()).status());
  }

  @Test
  void aClaimDuringATurnThatNeverAnswersForcesIt() {
    room.impatient();
    var process = room.prompted(SPEC, "full", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("Half"));
    diesOn(process, AgentProcess.Signal.INT);

    var session = adhoc();
    room.awaitStopped(process);

    var why = "displaced by run " + session.runId() + " claiming its repos";
    assertTrue(process.stdinClosed());
    assertEquals(List.of(AgentProcess.Signal.INT), process.signals);
    var partial = room.messages.list(SPEC, null, 20).getLast();
    assertEquals("Half", partial.body());
    assertEquals(
        List.of(
            LiveRoom.live(
                room.run(process.runId),
                SPEC,
                1,
                Map.of("message_id", partial.id(), "is_error", true, "reason", why))),
        room.data(Event.WellKnownTypes.AGENT_TURN_ENDED));
    assertEquals("running", room.run(session.runId()).status());
  }

  @Test
  void aMemberThatWillNotEndRefusesTheClaimAsAnyHolderDoes() {
    room.impatient();
    var process = idleMember();

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
    var process = room.prompted(SPEC, "read-only", "first");
    room.says(process, Lines.init("sess-1"), Lines.delta("One"), Lines.result("One"));
    var build = build("other-spec");

    var refused = assertThrows(ApiException.class, this::adhoc);

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refused.failure().errorCode());
    assertTrue(refused.getMessage().contains(build), refused.getMessage());
    assertFalse(process.stdinClosed(), "a read-only member holds nothing a claim could want");
    assertEquals(List.of(), process.signals);
    assertEquals("running", room.run(process.runId).status());
  }

  @Test
  void anotherRoomsMemberNeverDisplacesThisOne() {
    var process = idleMember();
    room.spec("second-spec");
    room.engage("second-spec", "claude-code", "full");

    room.says("second-spec", "hello over there");

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
            "reviewed-spec",
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
    var process = idleMember();
    process.onClose(() -> process.exits(0));

    assertTrue(
        room.sail.dispatching().endLiveMember(process.runId, "displaced by a terminal"),
        "the attach ends the member before it opens the conversation");
    room.awaitStopped(process);
    open.add(SessionYield.resumeSession(process.runId));

    assertEquals("stopped", room.run(process.runId).status());
    assertEquals("displaced by a terminal", room.stopOf(process.runId).data().get("reason"));
    assertEquals("sess-1", room.run(process.runId).sessionId(), "the session the terminal resumes");

    var during = room.says(SPEC, "is the terminal still open?");
    assertEquals(1, room.processes.starts.size(), "one process on a conversation, never two");
    assertEquals(List.of(), ended, "and the terminal is left alone");

    open.clear();
    var after = room.says(SPEC, "and now?");

    assertEquals(2, room.processes.starts.size());
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(List.of(room.promptOf(SPEC, during, after)), room.processes.latest().written);
  }

  @Test
  void aTerminalThatOpensWhileTheMemberClaimsStillFindsNoSecondProcess() {
    var open = new ArrayList<String>();
    var asked = new AtomicReference<>(0);
    var process = idleMember();
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

    room.says(SPEC, "the attach lands between the member's two looks");

    assertEquals(2, asked.get(), "once before the claim and once after it landed");
    assertEquals(1, room.processes.starts.size());
    assertEquals(
        List.of("failed", "stopped"),
        room.runs.listForSpec(SPEC).stream().map(RunStore.RunRow::status).toList(),
        "the claim it had made is given back");
  }

  @Test
  void aSessionHostThatCannotBeAskedHasNotSaidTheConversationIsFree() {
    var process = idleMember();
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

    var unanswered = room.says(SPEC, "anyone?");

    assertEquals(1, room.processes.starts.size(), "no answer is not a no: nothing is started");
    assertEquals(
        List.of("stopped"),
        room.runs.listForSpec(SPEC).stream().map(RunStore.RunRow::status).toList(),
        "and nothing was claimed for it");
    asked.set(0);
    var refused = assertThrows(ApiException.class, () -> room.live.deliver(SPEC, HANDLE));
    assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
    assertEquals(
        "Could not ask the session host whether a terminal holds the conversation of run "
            + process.runId
            + ", so no process was started over it.",
        refused.getMessage());
    assertEquals(
        "Check the sail-pty-host service on this box; the room's next message tries again.",
        refused.failure().action());
    assertEquals("the host refused the dispatch credential", refused.getCause().getMessage());

    asked.set(0);
    refusedAt.set(2);
    var between = room.says(SPEC, "the host goes quiet between the member's two looks");

    assertEquals(1, room.processes.starts.size());
    assertEquals(
        List.of("failed", "stopped"),
        room.runs.listForSpec(SPEC).stream().map(RunStore.RunRow::status).toList(),
        "the claim made between the two looks is given back");

    asked.set(0);
    refusedAt.set(0);
    var answered = room.says(SPEC, "and now?");

    assertEquals(2, room.processes.starts.size(), "a host that answers no frees the conversation");
    assertEquals(
        LiveRoom.invocation("--dangerously-skip-permissions", "--resume", "sess-1"),
        room.processes.starts.getLast().command());
    assertEquals(
        List.of(room.promptOf(SPEC, unanswered, between, answered)),
        room.processes.latest().written,
        "what was posted while the host could not be asked was never lost");
  }

  @Test
  void aMemberWhoseRunAClaimEndedWhileItStartedIsNeverPrompted() {
    room.engage(SPEC, "claude-code", "full");
    var cli = room.elsewhere().dispatching();
    room.processes.onStart(starting -> starting.onClose(() -> starting.exits(0)));
    room.live.useProcesses(
        (container, workDir, env, command, pidFile, logFile) -> {
          assertTrue(
              cli.endLiveMember(env.get("SAIL_RUN_ID"), "displaced by a build"),
              "nothing runs yet, so another process finds the run's agent gone and frees it");
          return room.processes.start(container, workDir, env, command, pidFile, logFile);
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

    assertTrue(process.stdinClosed());
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
