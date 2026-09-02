/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyRooms;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AgentAttachCommandTest {

  @Test
  void anUnreadableRunStateRefusesTheAttachInsteadOfForkingFresh() {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.orRefuse(
                    "acme",
                    () -> {
                      throw new IllegalStateException("database disk image is malformed");
                    }));
    assertTrue(
        thrown.getMessage().contains("refusing to attach"),
        "unknown is never absent: an unreadable database must refuse, because treating it as"
            + " 'no run' bypasses the live-run refusal and forks a second agent");
    assertTrue(thrown.getCause().getMessage().contains("malformed"), "the cause travels");
  }

  @Test
  void aSuccessfulEmptyQueryAllowsTheFreshFallback() {
    assertTrue(
        AgentAttachCommand.orRefuse("acme", java.util.Optional::empty).isEmpty(),
        "only a successful empty query means no run — the fresh-attach lane stays open");
  }

  @Test
  void aRecordedSessionResumesExactlyByIdForClaudeCode() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, "abc-123");
    assertEquals(List.of("bash", "-lc", "cd ~/workspace && claude --resume abc-123"), cmd);
  }

  @Test
  void aRecordedSessionResumesExactlyByIdForCodex() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CODEX, "abc-123");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && codex resume abc-123"),
        cmd,
        "codex resumes by id via 'codex resume <SESSION_ID>' — verified against current docs");
  }

  @Test
  void aNullSessionAttachesFreshNeverAnInteractivePicker() {
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && claude"),
        AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, null),
        "no recorded session means a fresh conversation, not '--resume' picker roulette");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && codex"),
        AgentAttachCommand.buildResumeCommand(AgentCli.CODEX, null));
  }

  @Test
  void ordinarySessionIdShapesAreSafe() {
    assertTrue(AgentAttachCommand.isSafeSessionId("0198f00d-1234-7000-8000-abcdefabcdef"));
    assertTrue(AgentAttachCommand.isSafeSessionId("abc-123"));
    assertTrue(AgentAttachCommand.isSafeSessionId("a"));
    assertTrue(AgentAttachCommand.isSafeSessionId("9session.name_x"));
  }

  @Test
  void sessionIdStartingWithDashIsRejectedAsOptionInjection() {
    assertFalse(
        AgentAttachCommand.isSafeSessionId("--dangerously-bypass-approvals-and-sandbox"),
        "a leading '-' would be parsed by the agent CLI as an option, not a session id");
    assertFalse(AgentAttachCommand.isSafeSessionId("-r"));
    assertFalse(AgentAttachCommand.isSafeSessionId(".hidden"));
    assertFalse(AgentAttachCommand.isSafeSessionId("_x"));
  }

  @Test
  void sessionIdWithShellMetacharactersOrOversizeIsRejected() {
    assertFalse(AgentAttachCommand.isSafeSessionId("abc; rm -rf /"));
    assertFalse(AgentAttachCommand.isSafeSessionId("abc$(id)"));
    assertFalse(AgentAttachCommand.isSafeSessionId(""));
    assertFalse(AgentAttachCommand.isSafeSessionId("a".repeat(129)));
    assertTrue(AgentAttachCommand.isSafeSessionId("a".repeat(128)));
  }

  @Test
  void buildIncusExecWithTtyIncludesTtyFlag() {
    var cmd =
        AgentAttachCommand.buildIncusExecWithTty(
            "myproject", List.of("bash", "-lc", "claude --resume abc"));
    assertTrue(cmd.contains("-t"));
    assertTrue(cmd.contains("myproject"));
    assertTrue(cmd.contains("--user"));
    assertTrue(cmd.contains("1000"));
    assertEquals("claude --resume abc", cmd.getLast());
  }

  @Test
  void buildIncusExecSetsHomeEnv() {
    var cmd = AgentAttachCommand.buildIncusExecWithTty("proj", List.of("echo", "test"));
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("HOME=/home/dev"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void openOrJoinOpensOnceThenJoinsTheLiveSessionInsteadOfForking(@TempDir Path dir)
      throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            token -> new PtyIdentity("uday", true),
            PtyRooms.NONE,
            PtyEvents.NONE)) {
      var plan =
          new AgentAttachCommand.ResumePlan("resume-1", List.of("sh", "-c", "read a"), "", "");
      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        assertTrue(AgentAttachCommand.openOrJoin(client, plan, 80, 24), "the first attach opens");
        assertFalse(
            AgentAttachCommand.openOrJoin(client, plan, 80, 24),
            "a second attach joins the live session, never a second agent");
        assertEquals(1, host.sessionCount());

        var unbound =
            new AgentAttachCommand.ResumePlan("resume-2", List.of("sh"), "", "no-such-room");
        assertThrows(
            IOException.class,
            () -> AgentAttachCommand.openOrJoin(client, unbound, 80, 24),
            "a refusal with nothing live behind it is the error it was");
      }
    }
  }

  @Test
  void aCompletedLatestRunIsStillResumableAndAnythingElseRefuses() {
    var completed = row("r1", "completed");
    assertDoesNotThrow(
        () -> AgentAttachCommand.requireStillLatestAndIdle(completed, latest(completed), "acme"));

    var live =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.requireStillLatestAndIdle(
                    completed, latest(row("r2", "running")), "acme"));
    assertTrue(live.getMessage().startsWith("Run r2 is live"), live.getMessage());

    var newer =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.requireStillLatestAndIdle(
                    completed, latest(row("r2", "completed")), "acme"));
    assertTrue(newer.getMessage().contains("changed while opening"), newer.getMessage());
    assertThrows(
        IllegalStateException.class,
        () ->
            AgentAttachCommand.requireStillLatestAndIdle(
                completed, AgentAttachCommand.Latest.NONE, "acme"));
  }

  /**
   * The gate read from the conversation's side: a live full turn over the planned run's repo
   * refuses the resume through the dispatch gate — the same rule a reservation applies to yield a
   * live resume session — while the read-only lane, which reserves nothing, never does.
   */
  @Test
  void aLiveLaneStillRefusesThroughTheGate() {
    var completed = row("r1", "completed", List.of("app"));
    var wholeContainer = running("adhoc", "adhoc", List.of());
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    AgentAttachCommand.requireStillLatestAndIdle(
                        completed, latest(completed, wholeContainer), "acme"))
            .getMessage()
            .contains("live in container 'acme'"));

    var fullTurn = running("turn", DispatchGate.ROOM_FULL_ROLE, List.of("app"));
    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.requireStillLatestAndIdle(
                    completed, latest(completed, fullTurn), "acme"));
    assertTrue(
        refused.getMessage().startsWith("Run turn (room-full) is live in repo(s) [app]"),
        refused.getMessage());

    var elsewhere = running("other", "build", List.of("web"));
    var wake = running("wake", DispatchGate.ROOM_ROLE, List.of());
    assertDoesNotThrow(
        () ->
            AgentAttachCommand.requireStillLatestAndIdle(
                completed, latest(completed, elsewhere, wake), "acme"),
        "a disjoint repo and the read-only lane hold nothing the resume would take");
  }

  private static AgentAttachCommand.Latest latest(
      RunStore.RunRow run, DispatchGate.RunningRun... running) {
    return new AgentAttachCommand.Latest(run, "", List.of(running));
  }

  private static DispatchGate.RunningRun running(String id, String role, List<String> repos) {
    return new DispatchGate.RunningRun(id, "spec-" + id, role, repos);
  }

  /**
   * The reviewer's interleaving: attach reads a completed run, a dispatch claims the project and
   * scans for sessions to yield (finding none), and only then does the attach reach the host. With
   * the claim lock shared by both, the attach waits behind the dispatch, rereads, sees the live
   * claim, and refuses — no resumed agent opens over repos the dispatch owns. (The other order — a
   * session opened under the lock is found and yielded by the claim that follows — runs against a
   * real container in {@code AgentResumeSessionIT}.)
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void anAttachWaitingOnADispatchRereadsTheClaimAndRefuses(@TempDir Path dir) throws Exception {
    var socket = dir.resolve("h.sock");
    var hostYield = new PtyHostYield(socket, dir.resolve("locks"));
    try (var db = Sqlite.open(dir.resolve("t.db"));
        var host =
            PtyHostCommand.startHost(
                socket,
                dir.resolve("s"),
                token -> new PtyIdentity("uday", true),
                PtyRooms.NONE,
                PtyEvents.NONE)) {
      new SchemaManager(db).migrate();
      var runs = new RunStore(db);
      var refused =
          attachParkedBehindClaim(
              socket, host, hostYield, runs, () -> reserve(runs, "r2", "build", List.of()));
      assertTrue(refused.getMessage().startsWith("Run r2 is live"), refused.getMessage());
    }
  }

  /**
   * Runs the reviewer's interleaving: an attach planned on completed run {@code r1} parks on the
   * project's claim lock, {@code claim} lands while it waits (finding nothing to yield), and the
   * attach then rereads under the lock. Returns the attach's refusal; the host must hold no session
   * on either side of it.
   */
  private static IllegalStateException attachParkedBehindClaim(
      Path socket, PtySessionHost host, PtyHostYield hostYield, RunStore runs, Runnable claim)
      throws Exception {
    var planned = completedRun(runs, "r1", List.of("app"));
    Supplier<AgentAttachCommand.Latest> latest =
        () ->
            new AgentAttachCommand.Latest(
                runs.latestForProjectOnNode("acme", "it").orElse(null),
                "",
                runs.runningOnNode("acme", "it"));
    var plan =
        new AgentAttachCommand.ResumePlan("resume-r1", List.of("sh", "-c", "read a"), "acme", "");
    var dispatchClaiming = hostYield.lock("acme");
    var refused = new AtomicReference<Throwable>();
    var done = new CountDownLatch(1);
    try (var client = SessionClient.connect(socket, "")) {
      var attach =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      AgentAttachCommand.openLocked(
                          hostYield, latest, planned, client, plan, 80, 24);
                    } catch (Throwable t) {
                      refused.set(t);
                    } finally {
                      done.countDown();
                    }
                  });
      SessionDispatchLockTest.awaitParked(attach);
      claim.run();
      assertEquals(0, host.sessionCount(), "the claim's yield scan finds nothing live");
      dispatchClaiming.close();
      done.await();
    }
    assertEquals(0, host.sessionCount(), "the stale attach never opened over the claimed repos");
    return assertInstanceOf(IllegalStateException.class, refused.get());
  }

  private static RunStore.RunRow completedRun(RunStore runs, String id, List<String> repos) {
    reserve(runs, id, "build", repos);
    runs.transition(id, "running", "completed", 0);
    return runs.findById(id).orElseThrow();
  }

  private static void reserve(RunStore runs, String id, String role, List<String> repos) {
    runs.reserveDispatch(
        id, "acme", "spec-" + id, "it", "it", role, repos, "codex", null, "t", "l", "u");
  }

  private static RunStore.RunRow row(String id, String status) {
    return row(id, status, List.of());
  }

  private static RunStore.RunRow row(String id, String status, List<String> repos) {
    return new RunStore.RunRow(
        id,
        "acme",
        "spec-" + id,
        "it",
        "build",
        "codex",
        null,
        "t",
        null,
        null,
        status,
        null,
        "l",
        "u",
        "t0",
        null,
        repos,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void theDryRunPlanIsTheSessionVerbsTheAttachIsMadeOf() {
    var plan =
        new AgentAttachCommand.ResumePlan(
            "resume-1",
            AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, "abc"),
            "acme",
            "spec-x");
    assertEquals(
        "sail session new resume-1 --project acme --room spec-x --command bash -lc"
            + " 'cd ~/workspace && claude --resume abc'\nsail session attach resume-1",
        plan.asSessionCommands());
    var unbound = new AgentAttachCommand.ResumePlan("resume-1", List.of("codex"), "acme", "");
    assertFalse(unbound.asSessionCommands().contains("--room"));
  }

  @Test
  void knownRoomIsTheRunsConversationOnlyWhenThatRoomExistsHere(@TempDir Path dir) {
    try (var db = Sqlite.open(dir.resolve("t.db"))) {
      new SchemaManager(db).migrate();
      var rooms = new RoomStore(db);
      rooms.create(
          new RoomStore.RoomRow("spec-x", "acme", "X", "it", "on", null, "it", null, null, "it"));
      var runs = new RunStore(db);
      runs.create(
          "r1", "acme", "spec-x", "it", "it", "build", "codex", null, "t", null, null, "l", "u");
      runs.create(
          "r2", "acme", "legacy", "it", "it", "build", "codex", null, "t", null, null, "l", "u");
      runs.create(
          "r3", "acme", null, "it", "it", "adhoc", "codex", null, "t", null, null, "l", "u");
      assertEquals(
          "spec-x", AgentAttachCommand.knownRoom(rooms, runs.findById("r1").orElseThrow()));
      assertEquals(
          "",
          AgentAttachCommand.knownRoom(rooms, runs.findById("r2").orElseThrow()),
          "a spec without a room here opens unbound rather than unreachable");
      assertEquals("", AgentAttachCommand.knownRoom(rooms, runs.findById("r3").orElseThrow()));
    }
  }
}
