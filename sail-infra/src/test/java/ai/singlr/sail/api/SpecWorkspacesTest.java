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
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ScriptedShellExecutor;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sweep of a box's spec workspaces through its production constructor, over real stores and a
 * box that answers only for the instances it holds, with the commands Incus and git would be given
 * read back. The clock is moved by hand and never past its horizon; the sweep's process starts at
 * {@link #T0}, and runs end when the store's clock, moved with it, says.
 */
class SpecWorkspacesTest {

  private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
  private static final String NODE = "eng";
  private static final String KEPT = BoxInstances.KEPT_KEY;
  private static final Duration IDLE = SpecWorkspaces.IDLE;
  private static final Duration A_SECOND = Duration.ofSeconds(1);

  @TempDir Path tempDir;
  private Clock wall;
  private Sqlite db;
  private SpecStore specs;
  private RunStore runs;
  private Box box;
  private ManualClock clock;
  private SpecWorkspaces sweeper;
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
    clock = new ManualClock(T0, Duration.ofHours(3));
    box = new Box();
    sweeper = sweeper(specs, runs, db, project -> Optional.of(definition(project)));
  }

  @AfterEach
  void tearDown() {
    DateTimeUtils.clock = wall;
    db.close();
    assertEquals(List.of(), box.unexpected, "the box was asked what it does not answer");
  }

  /** This box's sweep, its process started at {@link #T0}. */
  private SpecWorkspaces sweeper() {
    return sweeper;
  }

  private SpecWorkspaces sweeper(
      SpecStore specs, RunStore runs, Sqlite db, ProjectReader definitions) {
    var locks = tempDir.resolve("locks");
    return new SpecWorkspaces(
        new Workspaces(box, box, System::nanoTime, locks, () -> 16_000L),
        box,
        locks,
        specs,
        runs,
        new ChangeLog(db),
        definitions,
        (specId, body) -> said.add(specId + ": " + body),
        () -> NODE,
        clock::instant);
  }

  /** A project of two repos, one at a nested path. */
  private static ProjectDefinition definition(String project) {
    return new ProjectDefinition(
        project,
        "",
        null,
        null,
        null,
        null,
        List.of(
            new ProjectDefinition.Repo("https://github.com/acme/app.git", "app", null),
            new ProjectDefinition.Repo("https://github.com/acme/site.git", "web/site", null)),
        null,
        null,
        null,
        null);
  }

  private void spec(String id, SpecStatus status) {
    spec(id, "acme", status);
  }

  private void spec(String id, String project, SpecStatus status) {
    Acting.as("uday", () -> specs.create(row(id, project, status)));
  }

  private static SpecStore.SpecRow row(String id, String project, SpecStatus status) {
    return new SpecStore.SpecRow(
        id,
        project,
        "Spec " + id,
        status,
        NODE,
        "claude-code",
        null,
        null,
        "agent/" + id,
        0,
        "uday",
        null,
        null,
        "uday",
        List.of(),
        List.of("app"));
  }

  private void status(String id, SpecStatus status) {
    Acting.as("uday", () -> specs.updateStatus(id, status));
  }

  /** A run of spec {@code specId} on this box, live until it is finished. */
  private String run(String specId) {
    return run(runs, specId);
  }

  private static String run(RunStore runs, String specId) {
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

  /** A run of {@code specId} that ends now, its usage read. */
  private String ended(String specId) {
    var id = run(specId);
    finish(runs, id);
    return id;
  }

  private static void finish(RunStore runs, String id) {
    Acting.system(
        () -> {
          TestRuns.complete(runs, id, "completed", null);
          runs.recordRead(id, RunUsage.NONE);
        });
  }

  /** A run of {@code specId} that ends now, its usage unread. */
  private String endedUnread(String specId) {
    var id = run(specId);
    Acting.system(() -> TestRuns.complete(runs, id, "stopped", null));
    return id;
  }

  /** Moves the sweep's clock by {@code by}, and the store's with it. */
  private void later(Duration by) {
    clock.advance(by);
    DateTimeUtils.clock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
  }

  private static String instance(String specId) {
    return RunPlace.of("acme", specId).instance();
  }

  private List<String> changesOf(String instance) {
    return box.commands.stream()
        .filter(command -> command.size() >= 3 && command.get(0).equals("incus"))
        .filter(command -> List.of("start", "stop", "delete").contains(command.get(1)))
        .filter(command -> command.get(2).equals(instance))
        .map(command -> command.get(1))
        .toList();
  }

  /** Every command run inside {@code instance}, each as {@link Box#what} names it. */
  private List<String> askedOf(String instance) {
    return box.commands.stream()
        .filter(command -> command.size() > 3)
        .filter(command -> command.subList(0, 3).equals(List.of("incus", "exec", instance)))
        .map(command -> Box.what(command.subList(command.indexOf("--") + 1, command.size())))
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

  /**
   * The room's notice for spec {@code specId} of acme, kept while {@code status} for {@code why}.
   */
  private static String notice(String specId, String status, String why) {
    return "Workspace "
        + specId
        + " of acme is kept, stopped, though spec "
        + specId
        + " is "
        + status
        + ": "
        + why
        + ". Sail will not delete it. Take what it holds by hand (sail project workspace start"
        + " acme "
        + specId
        + ", then sail project workspace shell acme "
        + specId
        + "), then drop it with sail project workspace delete acme "
        + specId
        + ".";
  }

  /**
   * R13, row 1: a live run holds its workspace through every status, nothing asked inside it, and
   * only an erasure takes it.
   */
  @Test
  void aWorkspaceWithALiveRunIsNeitherStoppedNorReadNorDeletedWhateverItsStatusUnlessErased() {
    spec("a", SpecStatus.PENDING);
    box.workspace("a", "Running").repo("app").repo("web/site").ahead("app", 1);
    ended("a");
    run("a");
    later(Duration.ofHours(1));
    for (var status : SpecStatus.values()) {
      status("a", status);
      sweeper().sweep();
    }
    assertEquals(List.of(), changesOf(instance("a")));
    assertEquals(List.of(), askedOf(instance("a")), "nothing is read or pushed under a live run");

    Acting.as(
        "uday",
        () ->
            new Erasure(db)
                .erase(List.of(new Erasure.Target(Erasure.SPEC, "a")), ChangeLog.Entry.LOCAL));
    sweeper().sweep();

    assertEquals(List.of("delete"), changesOf(instance("a")));
    assertEquals(List.of(), said);
  }

  /**
   * Rows 2, 4, 7, 8: whatever the status, an idle workspace is stopped 10 minutes after its spec's
   * last run here ended, and not a second before; a stopped one is left stopped.
   */
  @Test
  void anIdleWorkspaceIsStoppedTenMinutesAfterTheLastRunEndedAndNotBefore() {
    spec("a", SpecStatus.AWAITING_MERGE);
    spec("c", SpecStatus.CANCELLED);
    spec("f", SpecStatus.IN_PROGRESS);
    var ids = List.of("a", "c", "f");
    ids.forEach(id -> box.workspace(id, "Running"));
    later(Duration.ofMinutes(5));
    ids.forEach(this::ended);

    later(IDLE.minus(A_SECOND));
    sweeper().sweep();
    for (var id : ids) {
      assertEquals(List.of(), changesOf(instance(id)), id + " before 10 minutes");
    }

    later(A_SECOND);
    sweeper().sweep();
    sweeper().sweep();
    for (var id : ids) {
      assertEquals(List.of("stop"), changesOf(instance(id)), id + " at 10 minutes, once");
      assertEquals("Stopped", box.state(instance(id)));
    }
    assertEquals(List.of(), said);
  }

  /**
   * Minor 1: the clock is the latest end of any run here, not the end of the newest-started run: a
   * room turn that started after the build but ended before it does not stop the workspace early.
   */
  @Test
  void theIdleClockIsTheLatestEndOfAnyRun() {
    spec("a", SpecStatus.REVIEW);
    box.workspace("a", "Running");
    later(Duration.ofMinutes(15));
    var build = run("a");
    later(Duration.ofMinutes(40));
    ended("a");
    later(Duration.ofMinutes(10));
    finish(runs, build);

    later(IDLE.minus(A_SECOND));
    sweeper().sweep();
    assertEquals(List.of(), changesOf(instance("a")));

    later(A_SECOND);
    sweeper().sweep();
    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /**
   * Rule D and Major 4, in the reviewer's order: a reviewer's run ended long before sail-api came
   * back; the first pass after the start, while the loop the replayed stop woke reads the
   * workspace, does not stop it, nor does any pass while the loop's next run is live; it stops 10
   * minutes after that run ends.
   */
  @Test
  void aRestartNeverStopsAWorkspaceBeforeTheLoopItReplaysHasHadIt() {
    spec("a", SpecStatus.REVIEW);
    box.workspace("a", "Running");
    ended("a");
    later(Duration.ofMinutes(30));
    var restarted = sweeper(specs, runs, db, project -> Optional.of(definition(project)));

    restarted.sweep();
    assertEquals(List.of(), changesOf(instance("a")), "the pass right after the start");

    later(Duration.ofMinutes(5));
    var fix = run("a");
    later(Duration.ofMinutes(20));
    restarted.sweep();
    assertEquals(List.of(), changesOf(instance("a")), "the loop's run is live");

    finish(runs, fix);
    later(IDLE.minus(A_SECOND));
    restarted.sweep();
    assertEquals(List.of(), changesOf(instance("a")));
    later(A_SECOND);
    restarted.sweep();
    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /**
   * Rule C and Major 3: a terminal open in a workspace keeps it, as one that does not say whether
   * it is open does; once it is closed, the workspace stops.
   */
  @Test
  void aTerminalOpenInTheWorkspaceOrOneThatCannotBeAskedKeepsItRunning() {
    spec("a", SpecStatus.AWAITING_MERGE);
    var held = box.workspace("a", "Running");
    ended("a");
    later(Duration.ofHours(1));

    held.terminal = Box.Terminal.OPEN;
    sweeper().sweep();
    held.terminal = Box.Terminal.UNANSWERED;
    sweeper().sweep();
    assertEquals(List.of(), changesOf(instance("a")));

    held.terminal = Box.Terminal.THROWS;
    sweeper().sweep();
    assertEquals(List.of(), changesOf(instance("a")));

    held.terminal = Box.Terminal.CLOSED;
    sweeper().sweep();
    assertEquals(List.of("stop"), changesOf(instance("a")));
  }

  /** The sweep runs on a pass of its own, which its close stops. */
  @Test
  void theSweepStartsItsOwnPassAndCloseStopsIt() {
    try (var own = sweeper(specs, runs, db, project -> Optional.empty())) {
      own.start();
    }
    assertEquals(List.of(), box.commands, "the first pass is a minute away");
  }

  /**
   * Scenarios 5, 20, 37: a done spec's idle workspace, stopped with a run's usage unread, is
   * started, its usage read and recorded, its configured repos read, and only then deleted.
   */
  @Test
  void aDoneSpecsStoppedWorkspaceIsStartedItsUsageAndReposReadAndThenDeleted() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Stopped").repo("app").repo("web/site");
    var unread = endedUnread("a");
    later(IDLE);

    sweeper().sweep();

    assertFalse(box.holds(instance("a")));
    assertEquals(List.of("start", "delete"), changesOf(instance("a")));
    assertEquals(List.of("bus", "machinery", "usage", "left app web/site"), askedOf(instance("a")));
    assertEquals("none", runs.findById(unread).orElseThrow().usage().source().wire());
    assertEquals(List.of(), said);
  }

  /** A done spec's workspace is decided only once it is idle, as any other is stopped. */
  @Test
  void aDoneSpecsWorkspaceIsDecidedOnlyOnceIdle() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app").repo("web/site");
    ended("a");

    later(IDLE.minus(A_SECOND));
    sweeper().sweep();
    assertTrue(box.holds(instance("a")));
    assertEquals(List.of(), askedOf(instance("a")));

    later(A_SECOND);
    sweeper().sweep();
    assertFalse(box.holds(instance("a")));
  }

  /**
   * Rules A and B: each thing a repo holds that no remote has keeps a done spec's workspace,
   * stopped, said once in one sentence naming each repo and what it holds; a configured repo that
   * is missing keeps it too. Kept is final: later passes neither start, read nor say it again.
   */
  @Test
  void anythingARepoHoldsKeepsTheWorkspaceSaidOnceAndKeptIsFinal() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app", "changes", "stash").repo("web/site", "commits");
    spec("m", SpecStatus.ARCHIVED);
    box.workspace("m", "Running").repo("app");
    ended("a");
    ended("m");
    later(IDLE);

    sweeper().sweep();
    later(Duration.ofHours(1));
    sweeper().sweep();
    sweeper().sweep();

    var why = "app holds uncommitted changes and a stash; web/site holds commits no remote has";
    assertEquals(why, box.key(instance("a"), KEPT));
    assertEquals("web/site is not in the workspace", box.key(instance("m"), KEPT));
    assertEquals(
        List.of(
            "a: " + notice("a", "done", why),
            "m: " + notice("m", "archived", "web/site is not in the workspace")),
        said);
    assertEquals(List.of("stop"), changesOf(instance("a")));
    assertEquals(List.of("stop"), changesOf(instance("m")));
    assertEquals(
        List.of("presence", "left app web/site", "presence"),
        askedOf(instance("a")).stream().filter(step -> !step.equals("cat")).toList(),
        "a kept workspace is read once");
  }

  /**
   * Rules B and C: a kept workspace a person starts is never read or deleted by the sweep, and is
   * stopped again once idle, as any other is; a terminal they open keeps it running meanwhile.
   */
  @Test
  void aKeptWorkspaceAPersonStartsIsStoppedWhenIdleAndNeverReadAgain() {
    spec("a", SpecStatus.DONE);
    var held = box.workspace("a", "Running").repo("app", "commits").repo("web/site");
    ended("a");
    later(IDLE);
    sweeper().sweep();
    assertEquals("Stopped", box.state(instance("a")));

    held.startedByHand();
    held.terminal = Box.Terminal.OPEN;
    sweeper().sweep();
    assertEquals("Running", box.state(instance("a")));

    held.terminal = Box.Terminal.CLOSED;
    held.repo("app");
    sweeper().sweep();
    assertEquals("Stopped", box.state(instance("a")));
    assertTrue(box.holds(instance("a")), "its person deletes it, never the sweep");
    assertEquals(1, said.size());
    assertEquals(
        1, askedOf(instance("a")).stream().filter(step -> step.startsWith("left")).count());
  }

  /** Every command the kept notice names parses as Sail's command line, as printed. */
  @Test
  void everyCommandTheKeptNoticeNamesIsSailsAsPrinted() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app", "commits").repo("web/site");
    ended("a");
    later(IDLE);
    sweeper().sweep();

    var commands =
        Pattern.compile("sail ((?:project workspace) [a-z]+ acme a)").matcher(said.getFirst());
    var parsed = new ArrayList<String>();
    while (commands.find()) {
      var leaf = SailCommandLine.of(new Sail()).parseArgs(commands.group(1).split(" "));
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

  /**
   * Rule A's push: commits the runs' branch alone holds are pushed once in the read, and the
   * workspace goes; a push that fails leaves them, which keeps it.
   */
  @Test
  void theRunsBranchIsPushedOnceInTheReadAndAFailedPushKeepsTheWorkspace() {
    spec("a", SpecStatus.DONE);
    spec("b", SpecStatus.DONE);
    box.workspace("a", "Stopped").repo("app").repo("web/site").ahead("app", 2);
    box.workspace("b", "Stopped").repo("app").repo("web/site").ahead("app", 1).pushFails = true;
    ended("a");
    ended("b");
    later(IDLE);

    sweeper().sweep();

    assertEquals(
        List.of(instance("a") + " app agent/a", instance("b") + " app agent/b"), box.pushes);
    assertFalse(box.holds(instance("a")));
    assertEquals("app holds commits no remote has", box.key(instance("b"), KEPT));
    assertEquals(List.of("b: " + notice("b", "done", "app holds commits no remote has")), said);
  }

  /**
   * Rule F and Major 1: a read that times out, fails, or says what it cannot mean keeps the
   * workspace, stopped and said once, never left running unmarked.
   */
  @Test
  void aReadThatTimesOutFailsOrSaysNonsenseKeepsTheWorkspaceStopped() {
    var ids = List.of("t", "f", "n");
    ids.forEach(id -> spec(id, SpecStatus.DONE));
    box.workspace("t", "Stopped").repo("app").repo("web/site").leftTimesOut = true;
    box.workspace("f", "Stopped").leftSays = new ShellExec.Result(128, "", "fatal: bad object\n");
    box.workspace("n", "Stopped").leftSays = new ShellExec.Result(0, "warning: ambiguous\n", "");
    ids.forEach(this::ended);
    later(IDLE);

    sweeper().sweep();
    sweeper().sweep();

    assertEquals(
        "its repos could not be read: Command timed out after 180s", box.key(instance("t"), KEPT));
    assertEquals("its repos could not be read: fatal: bad object", box.key(instance("f"), KEPT));
    assertEquals("its repos could not be read: warning: ambiguous", box.key(instance("n"), KEPT));
    for (var id : ids) {
      assertEquals("Stopped", box.state(instance(id)), id);
      assertEquals(List.of("start", "stop"), changesOf(instance(id)), id);
    }
    assertEquals(3, said.size());
  }

  /** Scenario 38: a start that fails keeps the workspace, said once, and is never started again. */
  @Test
  void aStartThatFailsKeepsTheWorkspaceSaidOnceAndItIsNeverStartedAgain() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Stopped").startFails = true;
    ended("a");
    later(IDLE);

    sweeper().sweep();
    later(Duration.ofHours(1));
    sweeper().sweep();

    assertEquals(List.of("start"), changesOf(instance("a")));
    assertEquals(
        "it did not start: Failed to start container 'acme-a': Error: Failed to start device"
            + " \"eth0\": no free address",
        box.key(instance("a"), KEPT));
    assertEquals(1, said.size());
  }

  /** A usage the started workspace does not answer keeps it, naming the run, before any git. */
  @Test
  void aUsageTheWorkspaceDoesNotAnswerKeepsItNamingTheRun() {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Stopped").repo("app").repo("web/site").usageAnswers = false;
    var unread = endedUnread("a");
    later(IDLE);

    sweeper().sweep();

    assertEquals("the usage of run " + unread + " could not be read", box.key(instance("a"), KEPT));
    assertNull(runs.findById(unread).orElseThrow().usage());
    assertTrue(askedOf(instance("a")).stream().noneMatch(step -> step.startsWith("left")));
  }

  /** A project whose definition this box cannot read keeps the done spec's workspace. */
  @Test
  void aProjectWhoseDefinitionCannotBeReadOrIsNotHereKeepsTheWorkspace() {
    spec("a", SpecStatus.DONE);
    spec("b", SpecStatus.DONE);
    box.workspace("a", "Running");
    box.workspace("b", "Running");
    ended("a");
    ended("b");
    var unreadable =
        sweeper(
            specs,
            runs,
            db,
            project -> {
              throw new IllegalStateException("unreadable");
            });
    var absent = sweeper(specs, runs, db, project -> Optional.empty());
    later(IDLE);

    unreadable.sweep();
    box.instances.get(instance("b")).keys.remove(KEPT);
    box.instances.get(instance("b")).startedByHand();
    absent.sweep();

    var why = "the definition of project acme is not readable here, so neither are its repos";
    assertEquals(why, box.key(instance("a"), KEPT));
    assertEquals(why, box.key(instance("b"), KEPT));
  }

  /**
   * Rule E and Major 7: the delete acts on the spec and its runs read again just before it: a spec
   * reopened, or a run started, during the reads leaves the workspace, neither deleted nor kept.
   */
  @Test
  void aSpecReopenedOrARunStartedDuringTheReadsLeavesTheWorkspaceUnkept() {
    spec("a", SpecStatus.DONE);
    spec("b", SpecStatus.DONE);
    box.workspace("a", "Running").repo("app").repo("web/site");
    box.workspace("b", "Running").repo("app").repo("web/site");
    ended("a");
    ended("b");
    later(IDLE);
    box.duringLeft.put(instance("a"), () -> status("a", SpecStatus.IN_PROGRESS));
    box.duringLeft.put(instance("b"), () -> run("b"));

    sweeper().sweep();

    for (var id : List.of("a", "b")) {
      assertTrue(box.holds(instance(id)), id);
      assertNull(box.key(instance(id), KEPT), id);
      assertEquals(List.of(), changesOf(instance(id)), id);
    }
    assertEquals(List.of(), said);
  }

  /**
   * The decision reads the workspace again under its lock, never the list it began from: one
   * stopped between the list and the lock is started for its reads, then deleted.
   */
  @Test
  void theDecisionReadsTheWorkspaceAgainUnderItsLock() {
    spec("a", SpecStatus.DONE);
    var held = box.workspace("a", "Running").repo("app").repo("web/site");
    ended("a");
    later(IDLE);
    box.beforeLookup = () -> held.state = "Stopped";

    sweeper().sweep();

    assertEquals(List.of("start", "delete"), changesOf(instance("a")));
  }

  /**
   * Scenario 24: a spec or project erased everywhere has its workspace deleted at once, live agent
   * and all, and nothing read or pushed.
   */
  @Test
  void anErasedSpecsOrProjectsWorkspaceIsDeletedAtOnceAndNothingIsRead() {
    spec("a", SpecStatus.IN_PROGRESS);
    spec("o", "other", SpecStatus.REVIEW);
    box.workspace("a", "Running").repo("app", "commits");
    box.workspace("other", "o", "Stopped");
    run("a");
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
    assertEquals(List.of(), askedOf(instance("a")));
    assertEquals(List.of(), said);
  }

  /**
   * Ruling 3, Minor 10 and scenario 44: the workspace of a spec not on this box, or filed under
   * another project now, stops when idle and is never deleted, said once in the log.
   */
  @Test
  void aSpecNotHereOrUnderAnotherProjectStopsWhenIdleNeverDeletedLoggedOnce() {
    spec("moved", "other", SpecStatus.DONE);
    box.workspace("absent", "Running");
    box.workspace("moved", "Running");
    later(IDLE);
    var sweeper = sweeper();

    var first = stderrOf(sweeper::sweep);
    box.instances.get(instance("absent")).startedByHand();
    var again = stderrOf(sweeper::sweep);

    assertEquals(List.of("stop", "stop"), changesOf(instance("absent")));
    assertEquals(List.of("stop"), changesOf(instance("moved")));
    assertEquals(
        "  [workspaces] acme-absent: spec absent is not one of acme's on this box, so its"
            + " workspace is stopped when idle and never deleted\n"
            + "  [workspaces] stopped acme-absent: idle for 10 min\n"
            + "  [workspaces] acme-moved: spec moved is not one of acme's on this box, so its"
            + " workspace is stopped when idle and never deleted\n"
            + "  [workspaces] stopped acme-moved: idle for 10 min\n",
        first);
    assertEquals("  [workspaces] stopped acme-absent: idle for 10 min\n", again);
    assertTrue(box.holds(instance("absent")) && box.holds(instance("moved")));
  }

  /** Scenario 42 and R14: a person's workspace and one never made whole are never swept. */
  @Test
  void aPersonsWorkspaceAndAnIncompleteOneAreNeverSwept() {
    spec("a", SpecStatus.DONE);
    box.person("try", "Running");
    box.workspace("a", "Running").ready = false;
    later(Duration.ofHours(1));

    sweeper().sweep();

    assertEquals(
        List.of(List.of("incus", "list", "--format", "json")),
        box.commands,
        "nothing but the list is asked");
  }

  /** A workspace whose name lock another holds is left to the next sweep, nothing asked of it. */
  @Test
  void aWorkspaceAnotherHoldsTheNameLockOfIsLeftToTheNextSweep() throws Exception {
    spec("a", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Running");
    ended("a");
    later(IDLE);
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

  /** R8: a status written straight to the store, as a sync applies one, is met on the next pass. */
  @Test
  void aStatusWrittenStraightToTheStoreConvergesOnTheNextSweep() {
    spec("a", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Stopped").repo("app").repo("web/site");
    ended("a");
    later(IDLE);
    sweeper().sweep();
    assertTrue(box.holds(instance("a")));

    db.execute("UPDATE specs SET status = 'done' WHERE id = 'a'");
    sweeper().sweep();

    assertFalse(box.holds(instance("a")));
  }

  /**
   * Scenarios 5 and 24 with real sync rounds: a done status main writes reaches the node, whose
   * next pass deletes the workspace, and main's erasure of another spec takes that one at once.
   */
  @Test
  void aDoneThatArrivesBySyncAndAnErasureMainAuthoredEachTakeTheirWorkspace() {
    try (var main = new SyncBox("main");
        var node = new SyncBox(NODE)) {
      main.create(row("a", "acme", SpecStatus.AWAITING_MERGE));
      main.create(row("b", "acme", SpecStatus.IN_PROGRESS));
      SyncBox.quiesce(main, node);
      var nodeRuns = new RunStore(node.db);
      List.of("a", "b").forEach(id -> finish(nodeRuns, run(nodeRuns, id)));
      box.workspace("a", "Running").repo("app").repo("web/site");
      box.workspace("b", "Running");
      var sweeper =
          sweeper(node.specs, nodeRuns, node.db, project -> Optional.of(definition(project)));
      later(IDLE);
      sweeper.sweep();
      assertTrue(box.holds(instance("a")) && box.holds(instance("b")));

      Acting.as("main", () -> main.specs.updateStatus("a", SpecStatus.DONE));
      Acting.as(
          "main",
          () ->
              new Erasure(main.db)
                  .erase(List.of(new Erasure.Target(Erasure.SPEC, "b")), ChangeLog.Entry.LOCAL));
      SyncBox.round(main, node);
      sweeper.sweep();

      assertFalse(box.holds(instance("a")));
      assertFalse(box.holds(instance("b")));
      assertEquals(List.of(), said);
    }
  }

  /**
   * The box's sweep, as its server builds it, says a kept workspace in the spec's room as Sail,
   * through the door every lane tells a room through.
   */
  @Test
  void theBoxsOwnSweepSaysAKeptWorkspaceInTheSpecsRoomAsSail() throws Exception {
    spec("a", SpecStatus.DONE);
    box.workspace("a", "Running").usageAnswers = false;
    var unread = endedUnread("a");
    later(IDLE);
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
                .useMessages(messages);
        var sweeper = operations.specWorkspaces(() -> NODE)) {
      later(IDLE);
      sweeper.sweep();
    }

    assertEquals(
        List.of(
            MessageStore.SAIL_AUTHOR
                + ": "
                + notice("a", "done", "the usage of run " + unread + " could not be read")),
        messages.list("a", null, 10).stream().map(m -> m.author() + ": " + m.body()).toList());
  }

  /**
   * A box whose Incus does not list is left to the next pass, said in the log; an interrupt, at the
   * list or inside a workspace's decision, ends the pass with the interrupt kept.
   */
  @Test
  void aListThatFailsIsLoggedAndAnInterruptEndsThePassKeepingIt() {
    spec("a", SpecStatus.AWAITING_MERGE);
    spec("b", SpecStatus.AWAITING_MERGE);
    box.workspace("a", "Running");
    box.workspace("b", "Running");
    ended("a");
    ended("b");
    later(IDLE);

    box.listFails = true;
    assertEquals(
        "  [workspaces] could not list this box's instances: incus list failed: Error: Get"
            + " \"http://unix.socket/1.0\": dial unix: connect: no such file or directory\n",
        stderrOf(() -> sweeper().sweep()));

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

  /**
   * Rule C: a stop that fails is tried again each pass and logged once until it succeeds, and the
   * pass goes on to the next workspace meanwhile.
   */
  @Test
  void aStopThatFailsIsTriedAgainEachPassLoggedOnceUntilItSucceeds() {
    spec("a", SpecStatus.AWAITING_MERGE);
    spec("b", SpecStatus.AWAITING_MERGE);
    var held = box.workspace("a", "Running");
    held.stopFails = true;
    box.workspace("b", "Running");
    ended("a");
    ended("b");
    later(IDLE);
    var sweeper = sweeper();

    var first = stderrOf(sweeper::sweep);
    var second = stderrOf(sweeper::sweep);
    held.stopFails = false;
    sweeper.sweep();
    held.startedByHand();
    held.stopFails = true;
    var after = stderrOf(sweeper::sweep);

    var failed =
        "  [workspaces] could not tend acme-a: Failed to stop container 'acme-a': Error: The"
            + " instance is busy\n";
    assertTrue(first.startsWith(failed), first);
    assertEquals("Stopped", box.state(instance("b")), "the pass went on");
    assertEquals("", second);
    assertEquals(List.of("stop", "stop", "stop", "stop"), changesOf(instance("a")));
    assertEquals(failed, after, "a failure after a success is said again");
  }

  /**
   * Minor 4: the room is told before the mark is written, so a mark that cannot be written leaves
   * the workspace to be decided again, saying it again, rather than never.
   */
  @Test
  void aMarkThatCannotBeWrittenIsDecidedAgainAndSaidAgain() {
    spec("a", SpecStatus.DONE);
    var held = box.workspace("a", "Running").repo("app", "commits").repo("web/site");
    held.configFails = true;
    ended("a");
    later(IDLE);

    var logged = stderrOf(() -> sweeper().sweep());
    held.configFails = false;
    sweeper().sweep();
    sweeper().sweep();

    assertTrue(
        logged.contains(
            "  [workspaces] could not mark acme-a kept (app holds commits no remote has): Error:"
                + " config is locked"),
        logged);
    assertEquals(2, said.size());
    assertEquals("app holds commits no remote has", box.key(instance("a"), KEPT));
  }

  /**
   * A box that answers only for the instances it holds: Incus's list, start, stop, delete and
   * config writes, the readiness and machinery a start waits on, and inside a running workspace the
   * terminal's presence, the usage read and {@link WorkspaceGit#LEFT}, answered from what each repo
   * is said to hold. Anything else, or any command to an instance it does not hold, is recorded as
   * unexpected and fails, as on a box.
   */
  static final class Box implements ShellExec {

    enum Terminal {
      CLOSED,
      OPEN,
      UNANSWERED,
      THROWS
    }

    static final class Held {
      final String project;
      final String workspace;
      volatile String state;
      final Map<String, String> keys = new LinkedHashMap<>();
      final Map<String, Set<String>> repos = new TreeMap<>();
      final Map<String, Integer> ahead = new TreeMap<>();
      volatile Terminal terminal = Terminal.CLOSED;
      volatile boolean ready = true;
      volatile boolean startFails;
      volatile boolean stopFails;
      volatile boolean configFails;
      volatile boolean pushFails;
      volatile boolean usageAnswers = true;
      volatile boolean leftTimesOut;
      volatile ShellExec.Result leftSays;

      Held(String project, String workspace, String state) {
        this.project = project;
        this.workspace = workspace;
        this.state = state;
      }

      /**
       * Repo {@code path} is there, holding {@code kinds} as {@link WorkspaceGit#LEFT} names them.
       */
      Held repo(String path, String... kinds) {
        repos.put(path, new TreeSet<>(List.of(kinds)));
        return this;
      }

      /** The runs' branch in repo {@code path} holds {@code commits} that no remote has. */
      Held ahead(String path, int commits) {
        ahead.put(path, commits);
        return this;
      }

      void startedByHand() {
        state = "Running";
      }
    }

    final Map<String, Held> instances = Collections.synchronizedMap(new TreeMap<>());
    final List<List<String>> commands = new CopyOnWriteArrayList<>();
    final List<String> unexpected = new CopyOnWriteArrayList<>();
    final List<String> pushes = new CopyOnWriteArrayList<>();
    final Map<String, Runnable> duringLeft = new LinkedHashMap<>();
    volatile Runnable beforeLookup = () -> {};
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
      return instances.get(instance).keys.get(key);
    }

    /** What a command inside a workspace is, in a word or two, for a test to read the order by. */
    static String what(List<String> inner) {
      if (inner.get(0).equals("bash") && inner.get(2).equals(WorkspaceGit.LEFT)) {
        return "left " + String.join(" ", inner.subList(6, inner.size()));
      }
      if (inner.get(0).equals("bash") && inner.get(2).contains("/run/user/1000/bus")) {
        return "bus";
      }
      if (inner.size() > 4 && inner.get(4).equals(ContainerSailSetup.STAMP_PATH)) {
        return "machinery";
      }
      if (inner.get(0).equals("sh") && inner.get(2).contains("tac --")) {
        return "usage";
      }
      if (ScriptedShellExecutor.reachableContainer(String.join(" ", inner)).isPresent()) {
        return "presence";
      }
      return inner.get(0);
    }

    @Override
    public Result exec(List<String> command) throws InterruptedException, TimeoutException {
      return exec(command, null, Duration.ZERO);
    }

    @Override
    public synchronized Result exec(List<String> command, Path workDir, Duration timeout)
        throws InterruptedException, TimeoutException {
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
        beforeLookup.run();
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
        return switch (command.get(1)) {
          case "start" -> start(held);
          case "stop" -> stop(held);
          case "delete" -> {
            instances.values().remove(held);
            yield ok("");
          }
          case "config" -> config(held, command);
          case "exec" ->
              inside(
                  command.get(2), held, command.subList(command.indexOf("--") + 1, command.size()));
          default -> unexpected(joined);
        };
      }
      return unexpected(joined);
    }

    private static Result start(Held held) {
      if (held.startFails) {
        return fail("Error: Failed to start device \"eth0\": no free address");
      }
      held.startedByHand();
      return ok("");
    }

    private static Result stop(Held held) {
      if (held.stopFails) {
        return fail("Error: The instance is busy");
      }
      held.state = "Stopped";
      return ok("");
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

    private Result inside(String name, Held held, List<String> inner) throws TimeoutException {
      if (!held.state.equals("Running")) {
        return fail("Error: Instance is not running");
      }
      var what = what(inner);
      if (held.terminal == Terminal.THROWS && !what.startsWith("left ")) {
        throw new TimeoutException("Command timed out after 120s");
      }
      if (what.startsWith("left ")) {
        return left(name, held, inner);
      }
      return switch (what) {
        case "bus", "machinery" -> ok("");
        case "usage" -> held.usageAnswers ? ok("") : fail("Error: websocket: close 1006");
        case "presence" ->
            held.terminal == Terminal.UNANSWERED
                ? fail("Error: websocket: close 1006")
                : ok(held.terminal == Terminal.OPEN ? "alive" : "gone");
        case "cat" ->
            inner.get(1).equals(AgentUnit.TERMINAL.pidPath()) && held.terminal == Terminal.OPEN
                ? ok("4242")
                : fail("cat: " + inner.get(1) + ": No such file or directory");
        case "kill" -> held.terminal == Terminal.OPEN ? ok("") : fail("no such process");
        default -> unexpected(String.join(" ", inner));
      };
    }

    private Result left(String name, Held held, List<String> inner) throws TimeoutException {
      Optional.ofNullable(duringLeft.get(name)).ifPresent(Runnable::run);
      if (held.leftTimesOut) {
        throw new TimeoutException("Command timed out after 180s");
      }
      if (held.leftSays != null) {
        return held.leftSays;
      }
      var branch = inner.get(5);
      var lines = new StringBuilder();
      for (var path : inner.subList(6, inner.size())) {
        var kinds = held.repos.get(path);
        if (kinds == null) {
          lines.append("missing ").append(path).append('\n');
          continue;
        }
        if (held.ahead.getOrDefault(path, 0) > 0) {
          pushes.add(name + " " + path + " " + branch);
          if (!held.pushFails) {
            held.ahead.put(path, 0);
          }
        }
        for (var kind : List.of("changes", "stash", "commits")) {
          var aheadStill = kind.equals("commits") && held.ahead.getOrDefault(path, 0) > 0;
          if (kinds.contains(kind) || aheadStill) {
            lines.append(kind).append(' ').append(path).append('\n');
          }
        }
      }
      return ok(lines.toString());
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
            "{\"name\": \"%s\", \"status\": \"%s\", \"type\": \"container\", \"state\": {},"
                    .formatted(name, held.state)
                + " \"config\": {"
                + keys
                + "}}");
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
