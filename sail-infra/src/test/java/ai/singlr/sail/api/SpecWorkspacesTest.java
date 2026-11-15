/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.commands.SailCommandLine;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestRuns;
import ai.singlr.sail.sync.SyncBox;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sweep of a box's spec workspaces through its production constructor, over real stores and a
 * box that answers only for the instances it holds, with the commands Incus and git would be given
 * read back. The clock is moved by hand and never past its horizon.
 */
class SpecWorkspacesTest {

  private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
  private static final String NODE = "eng";
  private static final String BRANCH = "agent/a";
  private static final String KEPT = BoxInstances.KEPT_KEY;
  private static final Duration IDLE_LESS_A_SECOND =
      SpecWorkspaces.IDLE.minus(Duration.ofSeconds(1));

  @TempDir Path tempDir;
  private Clock wall;
  private Sqlite db;
  private SpecStore specs;
  private RunStore runs;
  private Box box;
  private ManualClock clock;
  private final List<String> said = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    wall = DateTimeUtils.clock;
    DateTimeUtils.clock = Clock.fixed(T0, ZoneOffset.UTC);
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    HeldProjects.hold(db, "other");
    specs = new SpecStore(db);
    runs = new RunStore(db);
    box = new Box();
    clock = new ManualClock(T0, Duration.ofHours(2));
  }

  @AfterEach
  void tearDown() {
    DateTimeUtils.clock = wall;
    db.close();
    assertEquals(List.of(), box.unexpected, "the box was asked what it does not answer");
  }

  private SpecWorkspaces sweeper() {
    var locks = tempDir.resolve("locks");
    return new SpecWorkspaces(
        new Workspaces(box, box, System::nanoTime, locks, () -> 16_000L),
        box,
        locks,
        specs,
        runs,
        new ChangeLog(db),
        (specId, body) -> said.add(specId + ": " + body),
        () -> NODE,
        clock::instant);
  }

  private void spec(String id, SpecStatus status) {
    spec(id, "acme", status);
  }

  private void spec(String id, String project, SpecStatus status) {
    Acting.as("uday", () -> specs.create(row(id, project, status, "agent/" + id)));
  }

  private void status(String id, SpecStatus status) {
    Acting.as("uday", () -> specs.updateStatus(id, status));
  }

  /** A run of spec {@code specId} on this box, live until it is finished. */
  private String run(String specId) {
    return Acting.system(
        () -> {
          var id = DateTimeUtils.newId().toString();
          return runs.create(
              id,
              "acme",
              specId,
              NODE,
              "build",
              "claude-code",
              "agent/" + specId,
              "task",
              1,
              null,
              "/home/dev/.sail/runs/" + id + "/agent.log",
              "sail-agent-" + id);
        });
  }

  /** A run of {@code specId} that ended at the clock's start, its usage read. */
  private String ended(String specId, String status) {
    var id = run(specId);
    Acting.system(
        () -> {
          TestRuns.complete(runs, id, status, "failed".equals(status) ? 1 : null);
          runs.recordRead(id, RunUsage.NONE);
        });
    return id;
  }

  /** A run of {@code specId} that ended at the clock's start, its usage unread. */
  private String endedUnread(String specId) {
    var id = run(specId);
    Acting.system(() -> TestRuns.complete(runs, id, "stopped", null));
    return id;
  }

  private static String instance(String specId) {
    return RunPlace.of("acme", specId).instance();
  }

  private List<String> commandsOn(String instance) {
    return box.commands.stream()
        .filter(command -> command.contains(instance))
        .map(command -> String.join(" ", command))
        .toList();
  }

  private List<String> changesOf(String instance) {
    return box.commands.stream()
        .filter(command -> command.size() >= 3 && command.get(0).equals("incus"))
        .filter(command -> List.of("start", "stop", "delete").contains(command.get(1)))
        .filter(command -> command.get(2).equals(instance))
        .map(command -> command.get(1))
        .toList();
  }

  private String stderrOf(Runnable action) {
    var err = new ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return err.toString(StandardCharsets.UTF_8);
  }

  /** Row 1 and R13: a live run holds its workspace running through every status but erased. */
  @Test
  void aWorkspaceWithALiveRunIsNeitherStoppedNorDeletedWhateverItsSpecsStatusUnlessErased() {
    var id = "a";
    spec(id, SpecStatus.PENDING);
    box.workspace(id, "Running");
    ended(id, "stopped");
    run(id);
    clock.advance(Duration.ofMinutes(30));
    for (var status : SpecStatus.values()) {
      status(id, status);
      sweeper().sweep();
      assertEquals(List.of(), changesOf(instance(id)), "a live run under " + status);
    }

    Acting.as(
        "uday",
        () ->
            new Erasure(db)
                .erase(List.of(new Erasure.Target(Erasure.SPEC, id)), ChangeLog.Entry.LOCAL));
    sweeper().sweep();

    assertEquals(List.of("delete"), changesOf(instance(id)));
    assertFalse(box.holds(instance(id)));
    assertEquals(List.of(), said);
  }

  /**
   * Rows 2 and 4, 7, 8: a workspace whose spec is awaiting merge, cancelled, or in progress after a
   * failed run is stopped 10 minutes after its newest run ended, and not a moment before.
   */
  @Test
  void anIdleWorkspaceIsStoppedTenMinutesAfterItsNewestRunEndedAndNotBefore() {
    spec("a", SpecStatus.AWAITING_MERGE);
    spec("c", SpecStatus.CANCELLED);
    spec("f", SpecStatus.IN_PROGRESS);
    for (var id : List.of("a", "c", "f")) {
      box.workspace(id, "Running");
    }
    ended("a", "completed");
    ended("c", "stopped");
    ended("f", "failed");

    clock.advance(IDLE_LESS_A_SECOND);
    sweeper().sweep();
    for (var id : List.of("a", "c", "f")) {
      assertEquals(List.of(), changesOf(instance(id)), id + " before 10 minutes");
    }

    clock.advance(Duration.ofSeconds(1));
    sweeper().sweep();
    for (var id : List.of("a", "c", "f")) {
      assertEquals(List.of("stop"), changesOf(instance(id)), id + " at 10 minutes");
      assertEquals("Stopped", box.state(instance(id)));
    }

    sweeper().sweep();
    for (var id : List.of("a", "c", "f")) {
      assertEquals(List.of("stop"), changesOf(instance(id)), id + ": a stopped one is left");
    }
    assertEquals(List.of(), said);
  }

  /** The clock is the newest run: an older run long ended does not stop a workspace. */
  @Test
  void theIdleClockIsTheNewestRunsEnd() {
    spec("a", SpecStatus.REVIEW);
    box.workspace("a", "Running");
    ended("a", "completed");
    clock.advance(Duration.ofMinutes(20));
    DateTimeUtils.clock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
    ended("a", "completed");

    clock.advance(IDLE_LESS_A_SECOND);
    sweeper().sweep();
    assertEquals(List.of(), changesOf(instance("a")));

    clock.advance(Duration.ofSeconds(1));
    sweeper().sweep();
    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /** Row 2: an idle workspace holding a run whose usage is still unread is not stopped yet. */
  @Test
  void anIdleWorkspaceIsStoppedOnlyOnceEveryRunsUsageIsRead() {
    spec("a", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Running");
    var unread = endedUnread("a");
    clock.advance(Duration.ofMinutes(15));

    sweeper().sweep();
    assertEquals(List.of(), changesOf(instance("a")));

    Acting.system(() -> runs.recordRead(unread, RunUsage.NONE));
    sweeper().sweep();
    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /**
   * Scenarios 5, 20 and 37: a done spec's workspace, stopped with a run's usage unread, is started,
   * the usage read and recorded, the repos found pushed, and only then deleted.
   */
  @Test
  void aDoneSpecsStoppedWorkspaceIsStartedItsUsageReadAndThenDeleted() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Stopped").repo("app", 0, 0);
    var unread = endedUnread("a");

    sweeper().sweep();

    assertFalse(box.holds(instance("a")));
    assertEquals(List.of("start", "delete"), changesOf(instance("a")));
    var order =
        commandsOn(instance("a")).stream()
            .map(
                command ->
                    command.startsWith("incus start ")
                        ? "start"
                        : command.contains("tac --")
                            ? "usage"
                            : command.contains(SpecWorkspaces.UNPUSHED) ? "unpushed" : null)
            .filter(step -> step != null)
            .toList();
    assertEquals(List.of("start", "usage", "unpushed"), order);
    assertEquals("none", runs.findById(unread).orElseThrow().usage().source().wire());
    assertTrue(
        commandsOn(instance("a")).getLast().equals("incus delete " + instance("a") + " --force"));
    assertEquals(List.of(), said);
  }

  /** Scenario 41: a branch the forge merged and deleted still counts as pushed (no fetch). */
  @Test
  void aDoneSpecsRunningWorkspaceWithNothingUnpushedIsDeletedWithoutAFetch() {
    spec("a", SpecStatus.ARCHIVED);
    box.workspace("a", "Running").repo("app", 0, 0).repo("web/site", 0, 0);
    ended("a", "completed");

    sweeper().sweep();

    assertEquals(List.of("delete"), changesOf(instance("a")));
    assertTrue(
        box.commands.stream().noneMatch(command -> command.contains("fetch")), "no fetch is made");
  }

  /**
   * Scenario 6 and the said-once rule: a done spec's workspace whose repo holds a commit on another
   * branch is kept, stopped, carrying why; the room is told once over three sweeps.
   */
  @Test
  void aCommitOnAnotherBranchKeepsTheWorkspaceStoppedAndIsSaidOnceAcrossThreeSweeps() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app", 0, 1);
    ended("a", "completed");

    for (var sweep = 0; sweep < 3; sweep++) {
      sweeper().sweep();
    }

    assertTrue(box.holds(instance("a")));
    assertEquals("Stopped", box.state(instance("a")));
    assertEquals(
        "app: 1 commit on a branch other than agent/a that no remote has",
        box.key(instance("a"), KEPT));
    assertEquals(
        List.of(
            "a: Workspace a of acme is kept, stopped, though spec a is done: app: 1 commit on a"
                + " branch other than agent/a that no remote has. Push what it holds by hand (sail"
                + " project workspace start acme a, then sail project workspace shell acme a), and"
                + " Sail deletes it once nothing is left to lose; or drop it with sail project"
                + " workspace delete acme a."),
        said);
    assertTrue(
        box.commands.stream().noneMatch(command -> command.contains("push")),
        "a commit on another branch is never pushed");
  }

  /** Every command the kept notice names parses as Sail's command line, as printed. */
  @Test
  void everyCommandTheKeptNoticeNamesIsSailsAsPrinted() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app", 0, 1);
    ended("a", "completed");
    sweeper().sweep();

    var commands =
        Pattern.compile("sail ((?:project workspace) [a-z]+ acme a)").matcher(said.get(0));
    var parsed = new ArrayList<String>();
    while (commands.find()) {
      var args = commands.group(1).split(" ");
      var leaf = SailCommandLine.of(new Sail()).parseArgs(args);
      while (leaf.hasSubcommand()) {
        leaf = leaf.subcommand();
      }
      assertEquals(
          List.of("acme", "a"),
          leaf.matchedPositionals().stream()
              .map(p -> String.valueOf((Object) p.getValue()))
              .toList());
      parsed.add(leaf.commandSpec().qualifiedName(" "));
    }
    assertEquals(
        List.of(
            "sail project workspace start",
            "sail project workspace shell",
            "sail project workspace delete"),
        parsed);
  }

  /** Unpushed commits on the runs' branch alone are pushed once, and the workspace then goes. */
  @Test
  void commitsOnlyOnTheRunsBranchArePushedOnceThenTheWorkspaceGoes() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Stopped").repo("app", 2, 0);
    ended("a", "completed");

    sweeper().sweep();

    assertEquals(
        1,
        box.commands.stream()
            .filter(
                c ->
                    String.join(" ", c)
                        .endsWith("git -C /home/dev/workspace/app push origin " + BRANCH))
            .count());
    assertEquals(List.of("start", "delete"), changesOf(instance("a")));
    assertEquals(List.of(), said);
  }

  /** Scenario 6: a push that fails keeps the workspace stopped, said once with git's reason. */
  @Test
  void aPushThatFailsKeepsTheWorkspaceAndSaysWhy() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app", 1, 0).pushFails = true;
    ended("a", "completed");

    sweeper().sweep();
    sweeper().sweep();

    assertTrue(box.holds(instance("a")));
    assertEquals("Stopped", box.state(instance("a")));
    assertEquals(
        "app: the push of agent/a failed: fatal: unable to access 'https://github.com/acme/app/':"
            + " Could not connect",
        box.key(instance("a"), KEPT));
    assertEquals(1, said.size());
    assertTrue(said.get(0).contains("the push of agent/a failed"), said.get(0));
  }

  /** Scenario 38: a start that fails keeps it, said once, and is tried again each sweep. */
  @Test
  void aStartThatFailsKeepsTheWorkspaceSaidOnceAndTriedAgainEachSweep() {
    spec("a", SpecStatus.DONE);
    var held = box.workspace("a", "Stopped").repo("app", 0, 0);
    held.startFails = true;
    endedUnread("a");

    sweeper().sweep();
    sweeper().sweep();

    assertEquals(List.of("start", "start"), changesOf(instance("a")));
    assertTrue(
        box.key(instance("a"), KEPT).startsWith("it did not start: "),
        box.key(instance("a"), KEPT));
    assertEquals(1, said.size());

    held.startFails = false;
    sweeper().sweep();
    assertFalse(box.holds(instance("a")));
    assertEquals(1, said.size(), "it says nothing more as it goes");
  }

  /** A usage the started workspace still does not answer keeps it, naming the run. */
  @Test
  void aUsageTheStartedWorkspaceDoesNotAnswerKeepsItStopped() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Stopped").repo("app", 0, 0).usageAnswers = false;
    var unread = endedUnread("a");

    sweeper().sweep();

    assertEquals(List.of("start", "stop"), changesOf(instance("a")));
    assertEquals("the usage of run " + unread + " could not be read", box.key(instance("a"), KEPT));
    assertNull(runs.findById(unread).orElseThrow().usage());
  }

  /**
   * Scenario 43: a kept workspace a person started, pushed from by hand, goes on the next sweep,
   * which says nothing more; while it is theirs and still unpushed, the sweep leaves it running.
   */
  @Test
  void aKeptWorkspaceAPersonStartedIsLeftRunningUntilTheyPushThenDeletedSilently() {
    spec("a", SpecStatus.DONE);
    var held = box.workspace("a", "Running").repo("app", 0, 1);
    ended("a", "completed");
    sweeper().sweep();
    assertEquals("Stopped", box.state(instance("a")));
    assertEquals(1, said.size());

    held.state = "Running";
    sweeper().sweep();
    assertEquals("Running", box.state(instance("a")), "the sweep never stops it under them");

    held.repo("app", 0, 0);
    sweeper().sweep();
    assertFalse(box.holds(instance("a")));
    assertEquals(1, said.size());
  }

  /** The said-once rule outlives the process: a restarted sweep finds the mark and says nothing. */
  @Test
  void aRestartedSweepFindsTheKeptMarkAndSaysNothingMore() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Stopped").repo("app", 0, 1);
    ended("a", "completed");
    sweeper().sweep();
    assertEquals(1, said.size());

    sweeper().sweep();
    sweeper().sweep();

    assertEquals(1, said.size());
    assertEquals(
        List.of("start", "stop", "start", "stop", "start", "stop"), changesOf(instance("a")));
  }

  /**
   * Scenario 24: a spec erased everywhere has its workspace deleted at once, its live agent ended
   * with it, and nothing read or pushed; a project's erasure takes its specs' workspaces too.
   */
  @Test
  void anErasedSpecsOrProjectsWorkspaceIsDeletedAtOnceAndNothingIsRead() {
    spec("a", SpecStatus.IN_PROGRESS);
    spec("o", "other", SpecStatus.REVIEW);
    box.workspace("a", "Running").repo("app", 3, 3);
    box.workspace("other", "o", "Stopped");
    run("a");
    endedUnread("a");
    Acting.as(
        "uday",
        () ->
            new Erasure(db)
                .erase(
                    List.of(
                        new Erasure.Target(Erasure.SPEC, "a"),
                        new Erasure.Target(Erasure.PROJECT, "other")),
                    ChangeLog.Entry.LOCAL));

    sweeper().sweep();

    assertFalse(box.holds(instance("a")));
    assertFalse(box.holds("other-o"));
    assertEquals(List.of("delete"), changesOf(instance("a")));
    assertTrue(
        box.commands.stream()
            .noneMatch(
                c ->
                    c.contains("tac --")
                        || c.contains(SpecWorkspaces.UNPUSHED)
                        || c.contains("push")),
        "nothing is read or pushed");
    assertEquals(List.of(), said);
  }

  /**
   * Scenario 44: a spec not on this box yet, or filed under another project, is left as it is and
   * said once in the log, however many sweeps meet it.
   */
  @Test
  void aSpecNotHereOrFiledUnderAnotherProjectIsLeftAndLoggedOnce() {
    spec("moved", "other", SpecStatus.DONE);
    box.workspace("absent", "Running");
    box.workspace("moved", "Running");
    clock.advance(Duration.ofMinutes(30));

    var logged =
        stderrOf(
            () -> {
              sweeper().sweep();
            });
    var sweeper = sweeper();
    var first = stderrOf(sweeper::sweep);
    var again = stderrOf(sweeper::sweep);

    assertEquals(List.of(), changesOf(instance("absent")));
    assertEquals(List.of(), changesOf(instance("moved")));
    assertTrue(
        logged.contains("acme-absent is left as it is: spec absent is not on this box"), logged);
    assertTrue(
        logged.contains("acme-moved is left as it is: spec moved is filed under project other"),
        logged);
    assertEquals(2, first.lines().count(), first);
    assertEquals("", again);
  }

  /** Scenario 42 and R14: a person's workspace and one never made whole are never swept. */
  @Test
  void aPersonsWorkspaceAndAnIncompleteOneAreNeverSwept() {
    spec("a", SpecStatus.DONE);
    box.person("try", "Running");
    box.workspace("a", "Running").ready = false;
    clock.advance(Duration.ofHours(1));

    sweeper().sweep();

    assertTrue(box.holds("acme-try"));
    assertTrue(box.holds(instance("a")));
    assertEquals(
        List.of(List.of("incus", "list", "--format", "json")),
        box.commands,
        "nothing but the list is asked");
  }

  /** Scenario 40: a spec reopened before the sweep is read as it is now; nothing is deleted. */
  @Test
  void aSpecReopenedBeforeTheSweepKeepsItsWorkspaceAndLosesItsKeptMark() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app", 0, 1);
    ended("a", "completed");
    sweeper().sweep();
    assertEquals(
        "app: 1 commit on a branch other than agent/a that no remote has",
        box.key(instance("a"), KEPT));

    status("a", SpecStatus.IN_PROGRESS);
    sweeper().sweep();

    assertTrue(box.holds(instance("a")));
    assertEquals("", box.key(instance("a"), KEPT));
    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /**
   * Scenario 39: after a reboot the first sweep, a new process with nothing remembered, stops a
   * workspace Incus started again whose spec is in review and whose last run ended long ago.
   */
  @Test
  void afterARebootTheFirstSweepStopsAWorkspaceIdleSinceBeforeIt() {
    spec("a", SpecStatus.REVIEW);
    box.workspace("a", "Running");
    ended("a", "completed");
    clock.advance(Duration.ofMinutes(45));

    sweeper().sweep();

    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /** A workspace whose name lock another holds is left to the next sweep, nothing asked of it. */
  @Test
  void aWorkspaceAnotherHoldsTheNameLockOfIsLeftToTheNextSweep() throws Exception {
    spec("a", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Running");
    ended("a", "completed");
    clock.advance(Duration.ofMinutes(15));
    var workspaces = new Workspaces(box, box, System::nanoTime, tempDir.resolve("locks"), () -> 1L);
    var taken = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (var held = workspaces.holdIfFree(instance("a")).orElseThrow()) {
                    taken.countDown();
                    release.await(30, TimeUnit.SECONDS);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertTrue(taken.await(30, TimeUnit.SECONDS));

    sweeper().sweep();
    assertEquals(List.of(), changesOf(instance("a")));

    release.countDown();
    holder.join(TimeUnit.SECONDS.toMillis(30));
    sweeper().sweep();
    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /**
   * R8: the sweep reads the facts as they are now, not events: a status written straight to the
   * store, as a sync applies one, converges on the next sweep.
   */
  @Test
  void aStatusWrittenStraightToTheStoreConvergesOnTheNextSweep() {
    spec("a", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Stopped").repo("app", 0, 0);
    ended("a", "completed");
    sweeper().sweep();
    assertTrue(box.holds(instance("a")));

    db.execute("UPDATE specs SET status = 'done' WHERE id = 'a'");
    sweeper().sweep();

    assertFalse(box.holds(instance("a")));
  }

  /**
   * Scenario 5 and 24 with real sync rounds: a status main writes reaches the node, whose next
   * sweep deletes the done spec's workspace, and main's erasure of another spec, adopted by the
   * node, takes that spec's workspace at once.
   */
  @Test
  void aDoneThatArrivesBySyncAndAnErasureMainAuthoredEachTakeTheirWorkspaceOnTheNextSweep() {
    try (var main = new SyncBox("main");
        var node = new SyncBox(NODE)) {
      main.create(row("a", "acme", SpecStatus.AWAITING_MERGE, "agent/a"));
      main.create(row("b", "acme", SpecStatus.IN_PROGRESS, "agent/b"));
      SyncBox.quiesce(main, node);
      var nodeRuns = new RunStore(node.db);
      for (var id : List.of("a", "b")) {
        var run = DateTimeUtils.newId().toString();
        Acting.system(
            () -> {
              nodeRuns.create(
                  run,
                  "acme",
                  id,
                  NODE,
                  "build",
                  "claude-code",
                  "agent/" + id,
                  "task",
                  1,
                  null,
                  "/home/dev/.sail/runs/" + run + "/agent.log",
                  "sail-agent-" + run);
              TestRuns.complete(nodeRuns, run, "completed", null);
              nodeRuns.recordRead(run, RunUsage.NONE);
            });
      }
      box.workspace("a", "Running").repo("app", 0, 0);
      box.workspace("b", "Running");
      var locks = tempDir.resolve("locks");
      var sweeper =
          new SpecWorkspaces(
              new Workspaces(box, box, System::nanoTime, locks, () -> 16_000L),
              box,
              locks,
              node.specs,
              nodeRuns,
              new ChangeLog(node.db),
              (specId, body) -> said.add(specId + ": " + body),
              () -> NODE,
              clock::instant);
      sweeper.sweep();
      assertTrue(box.holds(instance("a")) && box.holds(instance("b")));

      Acting.as("main", () -> main.specs.updateStatus("a", SpecStatus.DONE));
      Acting.as(
          "main",
          () ->
              new Erasure(main.db)
                  .erase(List.of(new Erasure.Target(Erasure.SPEC, "b")), ChangeLog.Entry.LOCAL));
      SyncBox.round(main, node);
      assertEquals(SpecStatus.DONE, node.specs.findById("a").orElseThrow().status());
      sweeper.sweep();

      assertFalse(box.holds(instance("a")));
      assertFalse(box.holds(instance("b")));
      assertEquals(List.of(), said);
    }
  }

  private static SpecStore.SpecRow row(
      String id, String project, SpecStatus status, String branch) {
    return new SpecStore.SpecRow(
        id,
        project,
        "Spec " + id,
        status,
        NODE,
        "claude-code",
        null,
        null,
        branch,
        0,
        "uday",
        null,
        null,
        "uday",
        List.of(),
        List.of("app"));
  }

  /**
   * The box's sweep, as its server builds it, says a kept workspace in the spec's room as Sail,
   * through the door every lane tells a room through.
   */
  @Test
  void theBoxsOwnSweepSaysAKeptWorkspaceInTheSpecsRoomAsSail() throws Exception {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app", 0, 1);
    ended("a", "completed");
    var messages = new MessageStore(db);
    try (var operations =
        OperationsFactory.create(db, box, null, SyncScheduler.disabled(), SessionYield.NONE)
            .useControlPlane(
                db,
                tempDir,
                new SyncOperations(
                    db,
                    NODE,
                    tempDir,
                    SyncConfig::unset,
                    target -> {
                      throw new IOException("main unavailable");
                    }))
            .useMessages(messages)) {
      operations.specWorkspaces(() -> NODE).sweep();
    }

    var posted = messages.list("a", null, 10);
    assertEquals(
        List.of(
            MessageStore.SAIL_AUTHOR
                + ": Workspace a of acme is kept, stopped, though spec a is done: app: 1 commit"
                + " on a branch other than agent/a that no remote has. Push what it holds by hand"
                + " (sail project workspace start acme a, then sail project workspace shell acme"
                + " a), and Sail deletes it once nothing is left to lose; or drop it with sail"
                + " project workspace delete acme a."),
        posted.stream().map(m -> m.author() + ": " + m.body()).toList());
  }

  /**
   * Scenario 41 and the count itself, by git: commits only on the runs' branch, commits off it in
   * another repo, a detached commit, and a pushed branch the forge deleted after its merge, which
   * still counts as pushed because nothing is fetched.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void theCountByGitTellsTheRunsBranchFromTheRestAndTakesAMergedAndDeletedBranchAsPushed()
      throws Exception {
    var remote = tempDir.resolve("remote.git");
    run(List.of("git", "init", "--quiet", "--bare", "-b", "main", remote.toString()));
    var seed = tempDir.resolve("seed");
    run(List.of("git", "init", "--quiet", "-b", "main", seed.toString()));
    git(seed, "commit", "--quiet", "--allow-empty", "-m", "seed");
    git(seed, "push", "--quiet", remote.toString(), "main");
    var workspace = tempDir.resolve("workspace");
    var app = workspace.resolve("app");
    var site = workspace.resolve("web/site");
    run(List.of("git", "clone", "--quiet", remote.toString(), app.toString()));
    run(List.of("git", "clone", "--quiet", remote.toString(), site.toString()));
    assertEquals("0 0 app\n0 0 web/site\n", unpushed(workspace, BRANCH));

    git(app, "switch", "--quiet", "-c", BRANCH);
    git(app, "commit", "--quiet", "--allow-empty", "-m", "built");
    git(site, "switch", "--quiet", "-c", "other");
    git(site, "commit", "--quiet", "--allow-empty", "-m", "by hand");
    assertEquals("1 0 app\n1 1 web/site\n", unpushed(workspace, BRANCH));

    git(app, "push", "--quiet", "origin", BRANCH);
    git(remote, "branch", "--quiet", "-D", BRANCH);
    assertEquals("0 0 app\n1 1 web/site\n", unpushed(workspace, BRANCH));

    git(app, "switch", "--quiet", "--detach");
    git(app, "commit", "--quiet", "--allow-empty", "-m", "detached");
    assertEquals("1 1 app\n1 1 web/site\n", unpushed(workspace, BRANCH));
    assertEquals("1 1 app\n1 1 web/site\n", unpushed(workspace, ""));
  }

  private static String unpushed(Path workspace, String branch) throws Exception {
    return run(
        List.of(
            "bash", "-c", SpecWorkspaces.UNPUSHED, "sail-unpushed", workspace.toString(), branch));
  }

  private static String git(Path repo, String... args) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    return run(command);
  }

  /** {@code command} run here with git told who commits and nothing of this account's config. */
  private static String run(List<String> command) throws Exception {
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder
        .environment()
        .putAll(
            Map.of(
                "GIT_AUTHOR_NAME", "it",
                "GIT_AUTHOR_EMAIL", "it@sail.invalid",
                "GIT_COMMITTER_NAME", "it",
                "GIT_COMMITTER_EMAIL", "it@sail.invalid",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_CONFIG_NOSYSTEM", "1",
                "LC_ALL", "C"));
    var process = builder.start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), String.join(" ", command));
    assertEquals(0, process.exitValue(), String.join(" ", command) + ": " + output);
    return output;
  }

  /**
   * A box whose Incus does not list is left to the next sweep, said in the log; an interrupt, at
   * the list or inside a workspace's decision, ends the pass with the interrupt kept.
   */
  @Test
  void aListThatFailsIsLoggedAndAnInterruptEndsThePassKeepingIt() {
    spec("a", SpecStatus.AWAITING_MERGE);
    spec("b", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Running");
    box.workspace("b", "Running");
    ended("a", "completed");
    ended("b", "completed");
    clock.advance(Duration.ofMinutes(15));

    box.listFails = true;
    var logged = stderrOf(() -> sweeper().sweep());
    assertEquals(
        "  [workspaces] could not list this box's instances: incus list failed: Error: Get"
            + " \"http://unix.socket/1.0\": dial unix: connect: no such file or directory\n",
        logged);

    box.listFails = false;
    box.interruptOn = "incus list --format json";
    sweeper().sweep();
    assertTrue(Thread.interrupted(), "the interrupt at the list is kept");

    box.interruptOn = "incus list ^" + instance("a") + "$";
    sweeper().sweep();
    assertTrue(Thread.interrupted(), "the interrupt inside a decision is kept");
    assertEquals(List.of(), changesOf(instance("b")), "the pass ended there");
    box.interruptOn = null;
  }

  /** A workspace that cannot be decided is logged, and the pass goes on to the next. */
  @Test
  void aWorkspaceThatCannotBeDecidedIsLoggedAndTheRestGoOn() {
    spec("a", SpecStatus.AWAITING_MERGE);
    spec("b", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Running").stopFails = true;
    box.workspace("b", "Running");
    ended("a", "completed");
    ended("b", "completed");
    clock.advance(Duration.ofMinutes(15));

    var logged = stderrOf(() -> sweeper().sweep());

    assertEquals("Running", box.state(instance("a")));
    assertEquals("Stopped", box.state(instance("b")));
    assertTrue(
        logged.contains(
            "  [workspaces] could not tend acme-a: Failed to stop container 'acme-a': Error: The"
                + " instance is busy\n"),
        logged);
  }

  /**
   * A kept mark that cannot be written is said nowhere, since the mark is what remembers it was
   * said; the next sweep that writes it says it, once.
   */
  @Test
  void aKeptMarkThatCannotBeWrittenSaysNothingUntilASweepWritesIt() {
    spec("a", SpecStatus.DONE);
    var held = box.workspace("a", "Running").repo("app", 0, 1);
    held.configFails = true;
    ended("a", "completed");

    var logged = stderrOf(() -> sweeper().sweep());
    assertEquals(List.of(), said);
    assertTrue(
        logged.contains(
            "  [workspaces] could not mark acme-a kept (app: 1 commit on a branch other than"
                + " agent/a that no remote has): Error: config is locked"),
        logged);

    held.configFails = false;
    sweeper().sweep();
    sweeper().sweep();
    assertEquals(1, said.size());
  }

  /**
   * Repos that cannot be counted, by git's failure or a line the count never prints, keep a done
   * spec's workspace, saying why.
   */
  @Test
  void reposThatCannotBeCountedKeepTheWorkspace() {
    spec("a", SpecStatus.DONE);
    spec("b", SpecStatus.DONE);
    box.workspace("a", "Running").countSays =
        new ShellExec.Result(128, "", "fatal: bad object HEAD\n");
    box.workspace("b", "Running").countSays =
        new ShellExec.Result(0, "warning: refname is ambiguous\n", "");
    ended("a", "completed");
    ended("b", "completed");

    sweeper().sweep();

    assertEquals(
        "its repos could not be counted: fatal: bad object HEAD", box.key(instance("a"), KEPT));
    assertEquals(
        "its repos could not be counted: warning: refname is ambiguous",
        box.key(instance("b"), KEPT));
  }

  /**
   * A workspace no run of its spec here ever ended in is idle from when it was made whole, and a
   * spec that names no branch has every unpushed commit said as such.
   */
  @Test
  void aWorkspaceWithNoRunHereIsIdleFromItsReadyMarkAndNoBranchMeansNoPush() {
    spec("a", SpecStatus.CANCELLED);
    Acting.as("uday", () -> specs.create(row("n", "acme", SpecStatus.DONE, null)));
    box.workspace("a", "Running");
    box.workspace("n", "Running").repo("app", 0, 2);

    sweeper().sweep();

    assertEquals(List.of("stop"), changesOf(instance("a")));
    assertEquals("app: 2 commits that no remote has", box.key(instance("n"), KEPT));
    assertTrue(box.commands.stream().noneMatch(c -> c.contains("push")));
  }

  /**
   * A box that answers only for the instances it holds: Incus's list, start, stop, delete and
   * config writes, the readiness and machinery a start waits on, and inside a running workspace the
   * usage read, the unpushed count and the push. Anything else, or any command to an instance it
   * does not hold, is recorded as unexpected and fails, as on a box.
   */
  static final class Box implements ShellExec {

    static final class Held {
      final String project;
      final String workspace;
      volatile String state;
      final Map<String, String> keys = new LinkedHashMap<>();
      final Map<String, int[]> repos = new TreeMap<>();
      volatile boolean ready = true;
      volatile boolean startFails;
      volatile boolean pushFails;
      volatile boolean usageAnswers = true;
      volatile boolean stopFails;
      volatile boolean configFails;
      volatile ShellExec.Result countSays;

      Held(String project, String workspace, String state) {
        this.project = project;
        this.workspace = workspace;
        this.state = state;
      }

      /**
       * Repo {@code path} holds {@code onBranch} unpushed commits on the runs' branch, and {@code
       * elsewhere} off it.
       */
      Held repo(String path, int onBranch, int elsewhere) {
        repos.put(path, new int[] {onBranch, elsewhere});
        return this;
      }
    }

    final Map<String, Held> instances = Collections.synchronizedMap(new TreeMap<>());
    final List<List<String>> commands = new CopyOnWriteArrayList<>();
    final List<String> unexpected = new CopyOnWriteArrayList<>();
    volatile boolean listFails;
    volatile String interruptOn;

    Held workspace(String specId, String state) {
      return workspace("acme", specId, state);
    }

    Held workspace(String project, String specId, String state) {
      var place = (RunPlace.SpecWorkspace) RunPlace.of(project, specId);
      var held = new Held(project, place.workspace(), state);
      held.keys.put(BoxInstances.SPEC_KEY, specId);
      instances.put(place.instance(), held);
      return held;
    }

    Held person(String name, String state) {
      var held = new Held("acme", name, state);
      instances.put("acme-" + name, held);
      return held;
    }

    boolean holds(String instance) {
      return instances.containsKey(instance);
    }

    String state(String instance) {
      return instances.get(instance).state;
    }

    String key(String instance, String key) {
      return instances.get(instance).keys.getOrDefault(key, null);
    }

    @Override
    public Result exec(List<String> command) throws InterruptedException {
      return exec(command, null, Duration.ZERO);
    }

    @Override
    public synchronized Result exec(List<String> command, Path workDir, Duration timeout)
        throws InterruptedException {
      commands.add(List.copyOf(command));
      var joined = String.join(" ", command);
      if (joined.startsWith(Objects.requireNonNullElse(interruptOn, "\0"))) {
        throw new InterruptedException("shutting down");
      }
      if (listFails && joined.startsWith("incus list ")) {
        return fail(
            "Error: Get \"http://unix.socket/1.0\": dial unix: connect: no such file or directory");
      }
      if (joined.equals("incus list --format json")) {
        return ok(listed(instances.keySet().stream().toList()));
      }
      if (command.size() == 5 && joined.startsWith("incus list ^")) {
        var name = command.get(2).substring(1, command.get(2).length() - 1);
        return ok(listed(holds(name) ? List.of(name) : List.of()));
      }
      if (joined.equals("incus storage list --format json")) {
        return ok(
            "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\":"
                + " \"/var/lib/incus/disks/tank.img\", \"zfs.pool_name\": \"tank\"}}]");
      }
      if (command.get(0).equals("incus") && command.size() >= 3) {
        var held = instances.get(command.get(nameAt(command)));
        if (held == null) {
          return fail("Error: Instance not found");
        }
        switch (command.get(1)) {
          case "start" -> {
            if (held.startFails) {
              return fail("Error: Failed to start device \"eth0\": no free address");
            }
            held.state = "Running";
            return ok("");
          }
          case "stop" -> {
            if (held.stopFails) {
              return fail("Error: The instance is busy");
            }
            held.state = "Stopped";
            return ok("");
          }
          case "delete" -> {
            instances.values().remove(held);
            return ok("");
          }
          case "config" -> {
            return config(held, command);
          }
          case "exec" -> {
            return inside(held, command.subList(command.indexOf("--") + 1, command.size()));
          }
          default -> {}
        }
      }
      return unexpected(joined);
    }

    /**
     * Where an Incus command names its instance: after {@code config set}, {@code config device
     * get}, or its verb.
     */
    private static int nameAt(List<String> command) {
      if (!command.get(1).equals("config")) {
        return 2;
      }
      return command.get(2).equals("device") ? 4 : 3;
    }

    private Result config(Held held, List<String> command) {
      if (command.get(2).equals("set") && held.configFails) {
        return fail("Error: config is locked");
      }
      if (command.get(2).equals("set")) {
        for (var assignment : command.subList(4, command.size())) {
          var at = assignment.indexOf('=');
          held.keys.put(assignment.substring(0, at), assignment.substring(at + 1));
        }
        return ok("");
      }
      if (command.get(2).equals("device") && command.get(3).equals("get")) {
        return ok(SailPaths.apiSocketHostDir().toString());
      }
      return unexpected(String.join(" ", command));
    }

    private Result inside(Held held, List<String> inner) {
      if (!held.state.equals("Running")) {
        return fail("Error: Instance is not running");
      }
      var joined = String.join(" ", inner);
      if (inner.get(0).equals("bash") && inner.get(2).contains("/run/user/1000/bus")) {
        return ok("");
      }
      if (inner.size() > 4 && inner.get(4).equals(ContainerSailSetup.STAMP_PATH)) {
        return ok("");
      }
      if (inner.get(0).equals("sh") && inner.get(2).contains("tac --")) {
        return held.usageAnswers ? ok("") : fail("Error: websocket: close 1006");
      }
      if (inner.get(0).equals("bash") && inner.get(2).equals(SpecWorkspaces.UNPUSHED)) {
        if (held.countSays != null) {
          return held.countSays;
        }
        var branch = inner.get(5);
        var lines = new StringBuilder();
        held.repos.forEach(
            (path, counts) -> {
              var total = counts[0] + counts[1];
              var elsewhere = branch.isEmpty() ? total : counts[1];
              lines
                  .append(total)
                  .append(' ')
                  .append(elsewhere)
                  .append(' ')
                  .append(path)
                  .append('\n');
            });
        return ok(lines.toString());
      }
      if (inner.size() == 6 && inner.get(0).equals("git") && inner.get(3).equals("push")) {
        var path = inner.get(2).substring("/home/dev/workspace/".length());
        if (held.pushFails) {
          return fail(
              "fatal: unable to access 'https://github.com/acme/app/': Could not connect\n");
        }
        held.repos.get(path)[0] = 0;
        return ok("");
      }
      return unexpected(joined);
    }

    private Result unexpected(String joined) {
      unexpected.add(joined);
      return fail("unexpected: " + joined);
    }

    private String listed(List<String> names) {
      var entries = new ArrayList<String>();
      for (var name : names) {
        var held = instances.get(name);
        var config = new LinkedHashMap<String, String>();
        config.put(BoxInstances.PROJECT_KEY, held.project);
        config.put(BoxInstances.WORKSPACE_KEY, held.workspace);
        if (held.ready) {
          config.put(BoxInstances.READY_KEY, "1763200000000");
        }
        config.putAll(held.keys);
        var keys =
            String.join(
                ", ",
                config.entrySet().stream()
                    .map(
                        e ->
                            "\""
                                + e.getKey()
                                + "\": \""
                                + e.getValue().replace("\"", "\\\"")
                                + "\"")
                    .toList());
        entries.add(
            "{\"name\": \"%s\", \"status\": \"%s\", \"type\": \"container\", \"state\": {}, \"config\": {%s}}"
                .formatted(name, held.state, keys));
      }
      return "[" + String.join(", ", entries) + "]";
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    private static Result ok(String stdout) {
      return new Result(0, stdout, "");
    }

    private static Result fail(String stderr) {
      return new Result(1, "", stderr);
    }
  }
}
