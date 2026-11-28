/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.TestWorkspaces;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.Child;
import ai.singlr.sail.engine.FileMutexTest;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.TestOwners;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmissions;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AgentAttachCommandTest {

  @TempDir Path dir;

  @Test
  void theHarnessIsTheCatalogRowsAgentAndTheDefaultForAProjectWithNoRow() {
    var dbPath = seeded("acme", "name: acme\nagent:\n  type: codex\n");
    var acme = new AgentAttachCommand(operator -> OperationsFactory.open(dbPath));
    new CommandLine(acme).parseArgs("acme");
    var absent = new AgentAttachCommand(operator -> OperationsFactory.open(dbPath));
    new CommandLine(absent).parseArgs("absent");

    assertEquals("codex", acme.resolveHarness(OperatorCommand.PLAIN).yamlName());
    assertSame(Harnesses.DEFAULT, absent.resolveHarness(OperatorCommand.PLAIN));
  }

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
    var cmd = AgentAttachCommand.buildResumeCommand(Harnesses.of("claude-code"), "abc-123");
    assertEquals(List.of("bash", "-lc", "cd ~/workspace && exec claude --resume abc-123"), cmd);
  }

  @Test
  void aRecordedSessionResumesExactlyByIdForCodex() {
    var cmd = AgentAttachCommand.buildResumeCommand(Harnesses.of("codex"), "abc-123");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && exec codex resume abc-123"),
        cmd,
        "codex resumes by id via 'codex resume <SESSION_ID>' — verified against current docs");
  }

  @Test
  void aNullSessionAttachesFreshNeverAnInteractivePicker() {
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && exec claude"),
        AgentAttachCommand.buildResumeCommand(Harnesses.of("claude-code"), null),
        "no recorded session means a fresh conversation, not '--resume' picker roulette");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && exec codex"),
        AgentAttachCommand.buildResumeCommand(Harnesses.of("codex"), null));
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
            token -> new PtyIdentity.Person(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
            PtyAdmissions.NONE,
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
        () ->
            AgentAttachCommand.requireStillLatestAndIdle(
                completed, latest(completed), "acme", OperatorCommand.PLAIN));

    var live =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.requireStillLatestAndIdle(
                    completed, latest(row("r2", "running")), "acme", OperatorCommand.PLAIN));
    assertTrue(live.getMessage().startsWith("Run r2 is live"), live.getMessage());

    var newer =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.requireStillLatestAndIdle(
                    completed, latest(row("r2", "completed")), "acme", OperatorCommand.PLAIN));
    assertTrue(newer.getMessage().contains("changed while opening"), newer.getMessage());
    assertThrows(
        IllegalStateException.class,
        () ->
            AgentAttachCommand.requireStillLatestAndIdle(
                completed, AgentAttachCommand.Latest.NONE, "acme", OperatorCommand.PLAIN));
  }

  /**
   * The gate read from the conversation's side: a live full turn over the planned run's repo
   * refuses the resume through the dispatch gate — the same rule a reservation applies to yield a
   * live resume session — while the read-only lane, which reserves nothing, never does.
   */
  @Test
  void aLiveLaneStillRefusesThroughTheGate() {
    var completed = row("r1", "completed", List.of("app"));
    var wholeContainer = runningIn("adhoc", "adhoc", List.of());
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    AgentAttachCommand.requireStillLatestAndIdle(
                        completed,
                        latest(completed, wholeContainer),
                        "acme",
                        OperatorCommand.PLAIN))
            .getMessage()
            .contains("live in container 'acme-spec-r1'"));

    var fullTurn = runningIn("turn", DispatchGate.ROOM_FULL_ROLE, List.of("app"));
    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.requireStillLatestAndIdle(
                    completed, latest(completed, fullTurn), "acme", OperatorCommand.PLAIN));
    assertTrue(
        refused.getMessage().startsWith("Run turn (room-full) is live in repo(s) [app]"),
        refused.getMessage());

    var elsewhere = runningIn("other", "build", List.of("web"));
    var wake = runningIn("wake", DispatchGate.ROOM_ROLE, List.of());
    assertDoesNotThrow(
        () ->
            AgentAttachCommand.requireStillLatestAndIdle(
                completed, latest(completed, elsewhere, wake), "acme", OperatorCommand.PLAIN),
        "a disjoint repo and the read-only lane hold nothing the resume would take");
  }

  /**
   * The room's live member is the one live run an attach does not refuse: its conversation is taken
   * by ending it first, so its own claim is not what stands in the way of resuming it.
   */
  @Test
  void aLiveMembersRunIsTakenOverNotRefused(@TempDir Path dir) {
    try (var db = Sqlite.open(dir.resolve("t.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      var runs = new RunStore(db);
      var id = DateTimeUtils.newId().toString();
      Acting.system(
          () ->
              runs.reserveDispatch(
                  id,
                  "acme",
                  "chat",
                  "it",
                  DispatchGate.ROOM_FULL_ROLE,
                  List.of("app"),
                  "claude-code",
                  null,
                  "t",
                  "l",
                  AgentUnit.live(id).unitName()));
      var member = runs.findById(id).orElseThrow();
      var itself =
          new DispatchGate.RunningRun(
              id, "chat", DispatchGate.ROOM_FULL_ROLE, List.of("app"), RunPlace.of("acme", null));
      var build = running("build", "build", List.of("app"));

      assertTrue(AgentAttachCommand.takesFromMember(member, "sess-1"));
      assertFalse(
          AgentAttachCommand.takesFromMember(member, null),
          "a member that recorded no session has no conversation to take");
      assertFalse(AgentAttachCommand.takesFromMember(null, "sess-1"));
      assertFalse(AgentAttachCommand.takesFromMember(row("r1", "running"), "sess-1"));
      assertEquals(List.of(build), AgentAttachCommand.besides(member, List.of(itself, build)));
      assertDoesNotThrow(
          () ->
              AgentAttachCommand.requireNoLiveConflict(
                  member,
                  AgentAttachCommand.besides(member, List.of(itself)),
                  RunPlace.of("acme", null)));
      assertThrows(
          IllegalStateException.class,
          () ->
              AgentAttachCommand.requireNoLiveConflict(
                  member,
                  AgentAttachCommand.besides(member, List.of(itself, build)),
                  RunPlace.of("acme", null)),
          "any other run over its repos still refuses");

      Acting.system(() -> runs.finish(id, "running", "stopped", 0, null));
      assertFalse(
          AgentAttachCommand.takesFromMember(runs.findById(id).orElseThrow(), "sess-1"),
          "a member already over is resumed as any finished run is");
    }
    assertEquals(
        "displaced by a terminal on its conversation", AgentAttachCommand.MEMBER_DISPLACED);
  }

  private static AgentAttachCommand.Latest latest(
      RunStore.RunRow run, DispatchGate.RunningRun... running) {
    return new AgentAttachCommand.Latest(run, "", List.of(running));
  }

  /** A run live in the workspace of spec {@code spec-r1}, the planned run's. */
  private static DispatchGate.RunningRun runningIn(String id, String role, List<String> repos) {
    return new DispatchGate.RunningRun(
        id, "spec-" + id, role, repos, RunPlace.of("acme", "spec-r1"));
  }

  private static DispatchGate.RunningRun running(String id, String role, List<String> repos) {
    return new DispatchGate.RunningRun(id, "spec-" + id, role, repos, RunPlace.of("acme", null));
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
                token -> new PtyIdentity.Person(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
                PtyAdmissions.NONE,
                PtyEvents.NONE)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
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
                          hostYield, latest, planned, client, plan, 80, 24, OperatorCommand.PLAIN);
                    } catch (Throwable t) {
                      refused.set(t);
                    } finally {
                      done.countDown();
                    }
                  });
      FileMutexTest.awaitParked(attach);
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
    Acting.system(() -> runs.finish(id, "running", "completed", 0, null));
    return runs.findById(id).orElseThrow();
  }

  private static void reserve(RunStore runs, String id, String role, List<String> repos) {
    Acting.system(
        () -> {
          runs.reserveDispatch(
              id, "acme", "spec-" + id, "it", role, repos, "codex", null, "t", "l", "u");
        });
  }

  /**
   * Medium 1: what {@code --dry-run} prints is the attach itself: the line, read back by a shell,
   * is the very argv a real attach executes, its script one word.
   */
  @Test
  void theDryRunsLineReadBackByAShellIsTheAttachsArgv() throws Exception {
    var exec =
        AgentAttachCommand.direct(
                row("r1", "spec-a", "build", "completed", List.of()),
                "acme",
                "7027ad75-06d5-48ef-9a62-31085b8eca43",
                AgentAttachCommand.buildResumeCommand(
                    Harnesses.of("claude-code"), "7027ad75-06d5-48ef-9a62-31085b8eca43"))
            .orElseThrow();
    var line = AgentAttachCommand.printed(exec);

    var shell = Child.run(new ProcessBuilder("bash", "-c", "printf '%s\\0' " + line));
    var argv = shell.stdout();
    assertEquals(0, shell.exit(), shell.stderr());

    assertEquals(exec, List.of(argv.substring(0, argv.length() - 1).split("\0", -1)));
  }

  /**
   * The terminal's half of the order against a claim: it asks for claims only once its process is
   * named, or once it has ended; a terminal never named in its wait is reported, so it can be
   * ended.
   */
  @Test
  void aTerminalWaitsUntilItsProcessIsNamedOrItEndsAndNoLonger() throws Exception {
    var asked = new AtomicInteger();
    var pauses = new AtomicInteger();
    var now = new AtomicLong();
    AgentAttachCommand.Pause pause = pauses::incrementAndGet;

    assertTrue(
        AgentAttachCommand.awaitNamed(
            () -> asked.incrementAndGet() > 2, () -> true, now::get, Duration.ofSeconds(10), pause),
        "named on the third ask");
    assertEquals(2, pauses.get(), "a pause between asks, none after");
    assertTrue(
        AgentAttachCommand.awaitNamed(
            () -> false, () -> false, now::get, Duration.ofSeconds(10), pause),
        "a terminal that ended has nothing to yield");
    assertFalse(
        AgentAttachCommand.awaitNamed(
            () -> false,
            () -> true,
            () -> now.getAndAdd(Duration.ofSeconds(11).toNanos()),
            Duration.ofSeconds(10),
            pause),
        "never named in its wait");
  }

  /**
   * The claims a terminal yields to are the runs of other lanes than the read-only room's live in
   * its own workspace, and what it says names the run and the command that resumes it.
   */
  @Test
  void aTerminalYieldsOnlyToAClaimOfItsWorkspaceAndSaysHowToResume() {
    var planned = row("r0", "spec-a", "build", "completed", List.of());
    var workspace = (RunPlace.SpecWorkspace) RunPlace.of("acme", "spec-a");
    var running =
        List.of(
            new DispatchGate.RunningRun("r0", "spec-a", "build", List.of(), workspace),
            new DispatchGate.RunningRun("r1", "spec-a", "room", List.of(), workspace),
            new DispatchGate.RunningRun(
                "r2", "spec-b", "build", List.of(), RunPlace.of("acme", "spec-b")),
            new DispatchGate.RunningRun("r3", "spec-a", "review", List.of(), workspace));

    assertEquals(
        List.of("r3"),
        AgentAttachCommand.claimsOf(planned, workspace, running),
        "other runs live beside a claim never stand in the way of naming it");
    assertEquals(
        "  ⚠ This terminal ended: yielded to run r3 of spec spec-a. Its conversation is"
            + " resumable once that run ends: sail agent attach acme --spec spec-a",
        AgentAttachCommand.yielded(planned, "r3", "acme", OperatorCommand.PLAIN)
            .replaceAll("\u001B\\[[;\\d]*m", ""));
  }

  private static RunStore.RunRow row(String id, String status) {
    return row(id, status, List.of());
  }

  private static RunStore.RunRow row(String id, String status, List<String> repos) {
    return row(id, "spec-" + id, "build", status, repos);
  }

  /** An ad hoc run, of no spec: one of the project's own container. */
  private static RunStore.RunRow adhocRow(String id) {
    return row(id, null, "adhoc", "completed", List.of());
  }

  private static RunStore.RunRow row(
      String id, String specId, String role, String status, List<String> repos) {
    return new RunStore.RunRow(
        id,
        "acme",
        specId,
        "it",
        role,
        "codex",
        null,
        "t",
        null,
        null,
        status,
        null,
        "l",
        "u",
        Instant.EPOCH,
        null,
        repos,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * A spec's run lived in its own workspace, so its conversation is resumed there, over a raw tty;
   * a fresh attach goes where the run lived; only a recorded conversation of the project's own
   * container is a host session's.
   */
  @Test
  void aSpecsConversationIsAttachedInItsWorkspaceAndOnlyTheProjectContainersInAHostSession() {
    var command = AgentAttachCommand.buildResumeCommand(Harnesses.of("claude-code"), "abc");
    var specRun = row("r1", "completed");
    var adhoc = adhocRow("r2");

    assertEquals(
        Optional.of(
            AgentAttachCommand.buildIncusExecWithTty(
                "acme-spec-r1", AgentAttachCommand.inTheWorkspace(command))),
        AgentAttachCommand.direct(specRun, "acme", "abc", command));
    assertEquals(
        Optional.of(
            AgentAttachCommand.buildIncusExecWithTty(
                "acme-spec-r1", AgentAttachCommand.inTheWorkspace(command))),
        AgentAttachCommand.direct(specRun, "acme", null, command));
    assertEquals(
        Optional.of(AgentAttachCommand.buildIncusExecWithTty("acme", command)),
        AgentAttachCommand.direct(null, "acme", null, command));
    assertEquals(Optional.empty(), AgentAttachCommand.direct(adhoc, "acme", "abc", command));
  }

  /**
   * A terminal resuming a spec's conversation records its start and then writes its process into
   * its workspace's terminal pid file before it execs the harness, so a claim of the workspace ends
   * it by that file and a live member sees it; the dry run prints this very command.
   */
  @Test
  void aSpecsTerminalNamesItsProcessForTheWorkspaceThenExecsTheConversation() {
    var command = AgentAttachCommand.buildResumeCommand(Harnesses.of("claude-code"), "abc");

    assertEquals(
        List.of(
            "bash",
            "-lc",
            "cat /proc/$$/stat > /home/dev/.sail/terminal.pid.start && echo $$ >"
                + " /home/dev/.sail/terminal.pid && cd ~/workspace && exec claude --resume abc"),
        AgentAttachCommand.inTheWorkspace(command));
    assertEquals(
        Optional.of(
            AgentAttachCommand.buildIncusExecWithTty(
                "acme-spec-r1", AgentAttachCommand.inTheWorkspace(command))),
        AgentAttachCommand.direct(row("r1", "completed"), "acme", "abc", command));
  }

  /**
   * Run here for real, its files under the test's folder: the terminal records the start of the
   * process its pid names, the one that execs the conversation, so a claim can tell it from a later
   * process the box gives its pid.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aSpecsTerminalRecordsTheStartOfTheProcessItsPidNames() throws Exception {
    var pidFile = dir.resolve("terminal.pid");
    var line =
        AgentAttachCommand.inTheWorkspace(List.of("bash", "-lc", "echo ready; read -r line"))
            .stream()
            .map(arg -> arg.replace(AgentUnit.TERMINAL.pidPath(), pidFile.toString()))
            .toList();
    var builder = new ProcessBuilder(line).redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("HOME", dir.toString());
    var terminal = builder.start();
    try {
      assertEquals("ready", terminal.inputReader().readLine());
      var pid = Files.readString(pidFile).strip();
      var recorded = Files.readString(Path.of(AgentUnit.startPath(pidFile.toString())));

      assertEquals(String.valueOf(terminal.pid()), pid);
      assertEquals(
          startTicks(Files.readString(Path.of("/proc", pid, "stat"))), startTicks(recorded));
    } finally {
      Child.end(terminal);
    }
  }

  /** Field 22 of a {@code /proc/<pid>/stat} line: when the process started. */
  private static String startTicks(String stat) {
    return stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19];
  }

  /** A run of a spec's workspace is in the way of resuming another of that spec, and no other. */
  @Test
  void aResumeInASpecsWorkspaceIsRefusedOnlyByARunLiveInIt() {
    var completed = row("r1", "completed", List.of("app"));
    var place = AgentAttachCommand.placeOf(completed, "acme");
    var inIt = new DispatchGate.RunningRun("b", "spec-r1", "build", List.of("app"), place);
    var inTheContainer = running("adhoc", "adhoc", List.of());

    assertDoesNotThrow(
        () -> AgentAttachCommand.requireNoLiveConflict(completed, List.of(inTheContainer), place));
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> AgentAttachCommand.requireNoLiveConflict(completed, List.of(inIt), place))
            .getMessage()
            .startsWith("Run b (build) is live in container 'acme-spec-r1'"));
  }

  @Test
  void theDryRunPlanIsTheSessionVerbsTheAttachIsMadeOf() {
    var plan =
        new AgentAttachCommand.ResumePlan(
            "resume-1",
            AgentAttachCommand.buildResumeCommand(Harnesses.of("claude-code"), "abc"),
            "acme",
            "spec-x");
    assertEquals(
        "sail session new resume-1 --project acme --room spec-x --command bash -lc"
            + " 'cd ~/workspace && exec claude --resume abc'\nsail session attach resume-1",
        plan.asSessionCommands());
    var unbound = new AgentAttachCommand.ResumePlan("resume-1", List.of("codex"), "acme", "");
    assertFalse(unbound.asSessionCommands().contains("--room"));
  }

  @Test
  void knownRoomIsTheRunsConversationOnlyWhenThatRoomExistsHere(@TempDir Path dir) {
    try (var db = Sqlite.open(dir.resolve("t.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      var rooms = new RoomStore(db);
      Acting.as(
          "it",
          () ->
              rooms.create(
                  new RoomStore.RoomRow(
                      "spec-x", "acme", "X", "it", "on", null, "it", null, null, "it")));
      var runs = new RunStore(db);
      Acting.system(
          () ->
              runs.create(
                  "r1", "acme", "spec-x", "it", "build", "codex", null, "t", null, null, "l", "u"));
      Acting.system(
          () ->
              runs.create(
                  "r2", "acme", "legacy", "it", "build", "codex", null, "t", null, null, "l", "u"));
      Acting.system(
          () ->
              runs.create(
                  "r3", "acme", null, "it", "adhoc", "codex", null, "t", null, null, "l", "u"));
      assertEquals(
          "spec-x", AgentAttachCommand.knownRoom(rooms, runs.findById("r1").orElseThrow()));
      assertEquals(
          "",
          AgentAttachCommand.knownRoom(rooms, runs.findById("r2").orElseThrow()),
          "a spec without a room here opens unbound rather than unreachable");
      assertEquals("", AgentAttachCommand.knownRoom(rooms, runs.findById("r3").orElseThrow()));
    }
  }

  private Path seeded(String project, String definition) {
    var dbPath = dir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.yaml(definition)));
    }
    return dbPath;
  }

  /**
   * Major F-M2 of the final look: an attach reaches a spec's workspace through its own door, so one
   * the idle sweep stopped is started and the attach goes on, and one that is gone is refused
   * naming the dispatch that makes it.
   */
  @Test
  void anAttachStartsItsSpecsIdleStoppedWorkspaceAndRefusesAMissingOne() throws Exception {
    var place = RunPlace.of("acme", "u2");
    var stopped =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list --format json",
                "[" + TestWorkspaces.specWorkspace("acme", "u2", "Stopped", "uday") + "]");
    var gone =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", "[]");

    var said = new Said(stopped);
    var announced = new Said(gone);
    AgentAttachCommand.reach(
        place,
        TestWorkspaces.over(stopped),
        stopped,
        TestDefinitions::named,
        TestOwners::of,
        null,
        said);
    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.reach(
                    place,
                    TestWorkspaces.over(gone),
                    gone,
                    TestDefinitions::named,
                    TestOwners::of,
                    null,
                    announced));

    assertTrue(
        stopped.invocations().contains("incus start acme-u2"), stopped.invocations().toString());
    assertTrue(refused.getMessage().endsWith("sail spec dispatch -p acme --spec u2 --restart"));
    assertTrue(gone.invocations().stream().noneMatch(c -> c.startsWith("incus start")));
    assertEquals(List.of(), announced.lines(), "a refused attach announces nothing");
    assertEquals(
        List.of("starting acme-u2", "reached"),
        said.lines(),
        "a cold start is said before the start, and the attach once reached");
  }

  /**
   * #500's m1: on a node, an attach of a spec's workspace labelled for another person of the node's
   * handle (an old id) is refused in the door's words: running, no raw terminal opens on that
   * person's credentials; stopped, it is neither started nor stripped, which is the pass's job. One
   * of the node's person by id is reached.
   */
  @Test
  void onANodeAnAttachIsRefusedASpecsWorkspaceLabelledForAnotherPersonOfItsHandle()
      throws Exception {
    var place = RunPlace.of("acme", "u2");
    var said = new ArrayList<String>();
    for (var state : List.of("Running", "Stopped")) {
      var listed =
          "["
              + TestWorkspaces.specWorkspace("acme", "u2", state, "uday")
                  .replace("fde_uday", "fde_old_uday")
              + "]";
      var box =
          new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
              .onOk("incus list ^acme-u2$", listed)
              .onOk("incus list --format json", listed);
      var refused =
          assertThrows(
              IllegalStateException.class,
              () ->
                  AgentAttachCommand.reach(
                      place,
                      TestWorkspaces.over(box),
                      box,
                      TestDefinitions::named,
                      TestOwners::of,
                      "uday",
                      new Said(box) {
                        @Override
                        public void reached() {
                          said.add("announced");
                        }

                        @Override
                        public void starting(String instance) {
                          said.add("starting");
                        }
                      }));
      said.add(refused.getMessage());
      assertTrue(
          box.invocations().stream().allMatch(c -> c.startsWith("incus list")),
          box.invocations().toString());
    }
    var mine =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list ^acme-u2$",
                "[" + TestWorkspaces.specWorkspace("acme", "u2", "Running", "uday") + "]")
            .onOk(
                "incus list --format json",
                "[" + TestWorkspaces.specWorkspace("acme", "u2", "Running", "uday") + "]");

    var announced = new Said(mine);
    AgentAttachCommand.reach(
        place,
        TestWorkspaces.over(mine),
        mine,
        TestDefinitions::named,
        TestOwners::of,
        "uday",
        announced);

    var workspace = ((RunPlace.SpecWorkspace) place).workspace();
    assertEquals(
        Collections.nCopies(
            2,
            workspace
                + " is labelled for a person this box does not know as uday, so no terminal opens"
                + " in it until the box's pass leaves it nobody's."),
        said);
    assertEquals(
        List.of("reached"),
        announced.lines(),
        "a running workspace is not said to start; the attach is said once reached");
  }

  /**
   * #503's last look: an attach to the project's own container, not a spec's workspace, says what
   * it attaches only once the container is found running, and nothing when it is refused.
   */
  @Test
  void anAttachToTheProjectsContainerIsAnnouncedOnlyOnceItRuns() throws Exception {
    var place = RunPlace.of("acme", null);
    var running =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list ^acme$",
                "[{\"name\": \"acme\", \"status\": \"Running\", \"type\": \"container\","
                    + " \"config\": {}}]");
    var stopped =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list ^acme$",
                "[{\"name\": \"acme\", \"status\": \"Stopped\", \"type\": \"container\","
                    + " \"config\": {}}]");
    var said = new Said(running);
    var refusedSaid = new Said(stopped);

    AgentAttachCommand.reach(
        place,
        TestWorkspaces.over(running),
        running,
        TestDefinitions::named,
        TestOwners::of,
        null,
        said);
    assertThrows(
        Exception.class,
        () ->
            AgentAttachCommand.reach(
                place,
                TestWorkspaces.over(stopped),
                stopped,
                TestDefinitions::named,
                TestOwners::of,
                null,
                refusedSaid));

    assertEquals(List.of("reached"), said.lines());
    assertEquals(List.of(), refusedSaid.lines(), "a refused attach announces nothing");
  }

  /**
   * #507's nit: a resumed host session is refused when its place is not running before it says it
   * resumes, and says so once it is.
   */
  @Test
  void aResumedHostSessionSaysItResumesOnlyOnceItsPlaceRuns() throws Exception {
    var place = RunPlace.of("acme", null);
    var said = new ArrayList<String>();
    var stopped =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list ^acme$",
                "[{\"name\": \"acme\", \"status\": \"Stopped\", \"type\": \"container\","
                    + " \"config\": {}}]");
    var running =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list ^acme$",
                "[{\"name\": \"acme\", \"status\": \"Running\", \"type\": \"container\","
                    + " \"config\": {}}]");

    assertThrows(
        Exception.class,
        () -> AgentAttachCommand.resumeIn(place, stopped, () -> said.add("stopped")));
    AgentAttachCommand.resumeIn(place, running, () -> said.add("running"));

    assertEquals(List.of("running"), said);
  }

  /** What an attach says while it reaches its place, in order, told against the box it reaches. */
  private static class Said implements AgentAttachCommand.Reaching {
    private final List<String> lines = new ArrayList<>();
    private final ScriptedShellExecutor box;

    Said(ScriptedShellExecutor box) {
      this.box = box;
    }

    List<String> lines() {
      return lines;
    }

    @Override
    public void starting(String instance) {
      var started = box.invocations().stream().anyMatch(c -> c.startsWith("incus start"));
      lines.add("starting " + instance + (started ? " after its start" : ""));
    }

    @Override
    public void reached() {
      lines.add("reached");
    }
  }
}
