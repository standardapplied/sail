/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.OperationHooks;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectSkillInstaller;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production-wiring freeze for the CLI lane (#142's regression pattern applied to the second
 * entry point): operations built exactly as {@code sail spec dispatch} builds them must record the
 * run, stamp the branch, enforce the roster guard, and leave rows the server's {@code /v1/runs}
 * route can answer from — no store silently dropped.
 */
class DispatchCommandWiringTest {

  private static final String HANDLE = "me";

  private static final String RUNNING_JSON =
      """
      [
        {
          "name": "acme",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  private static final String YAML =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
        auto_branch: true
        branch_prefix: sail/
      """;

  @TempDir Path tempDir;

  private Sqlite db;

  private SailOperations cliOperations(ShellExec shell, List<Event> events) throws Exception {
    seed();
    return DispatchCommand.operations(
        db,
        shell,
        events::add,
        new WatcherSpawner(shell, (command, logPath) -> 4242L),
        (project, config) -> "",
        command -> 0,
        DispatchOperations.Listener.NONE,
        SessionYield.NONE,
        OperatorCommand.PLAIN);
  }

  /** The control plane holding project {@code acme} and its pending spec {@code auth}. */
  private void seed() throws Exception {
    Acting.system(
        () -> {
          db = Sqlite.open(tempDir.resolve("control-plane.db"));
          new SchemaManager(db).migrate();
          new ProjectStore(db).upsert("acme", YAML);
          var specStore = new SpecStore(db);
          Acting.as(
              "me",
              () ->
                  specStore.create(
                      new SpecStore.SpecRow(
                          "auth",
                          "acme",
                          "Add auth",
                          SpecStatus.PENDING,
                          HANDLE,
                          null,
                          null,
                          null,
                          null,
                          0,
                          "me",
                          null,
                          null,
                          "me",
                          List.of(),
                          List.of())));
          specStore.setContent("auth", "Do auth", "");
          return null;
        });
  }

  /**
   * {@code sail spec dispatch <args>} for {@code acme} as this box's admin runs it, its agent
   * exiting {@code agent}: the door's own hooks, its events kept here and its watcher a pid.
   */
  /** {@code spec dispatch -p acme --spec auth} with {@code args}, over the seeded control plane. */
  private SailCli.Ran dispatch(ShellExec shell, int agent, String... args) throws Exception {
    if (db == null) {
      seed();
    }
    new FdeStore(db).add(HANDLE, null, null, "admin");
    var command =
        new DispatchCommand(
            dryRun -> shell,
            (host, hooks) ->
                OperationsFactory.create(
                    db,
                    host,
                    new OperationHooks(
                        event -> {},
                        new WatcherSpawner(host, (spawned, logPath) -> 4242L),
                        hooks.snapshotter(),
                        launched -> agent,
                        hooks.dispatchListener(),
                        hooks.stopListener()),
                    SessionYield.NONE),
            () -> HANDLE);
    var all =
        new ArrayList<>(List.of("-p", "acme", "--spec", "auth", "--no-sync", "--no-snapshot"));
    all.addAll(List.of(args));
    return SailCli.run(command, all.toArray(String[]::new));
  }

  @Test
  void backgroundRunsTheAgentInTheBackgroundAsItsHelpSays() throws Exception {
    var ran = dispatch(shell(), 0, "--background");

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(ran.out().contains("Launching agent in background..."), ran.out());
    assertTrue(ran.out().contains("Guardrail watcher started (pid 4242"), ran.out());
  }

  @Test
  void aDispatchNamingNoModeRunsTheAgentInTheBackground() throws Exception {
    var ran = dispatch(shell(), 0);

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(ran.out().contains("Launching agent in background..."), ran.out());
    assertTrue(ran.out().contains("Guardrail watcher started (pid 4242"), ran.out());
  }

  @Test
  void noBackgroundRunsTheAgentInThisTerminal() throws Exception {
    var ran = dispatch(shell(), 0, "--no-background");

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(ran.out().contains("Launching agent with spec..."), ran.out());
    assertFalse(ran.out().contains("Guardrail watcher started (pid 4242"), ran.out());
  }

  static final String NOT_LOGGED_IN =
      "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
          + "\"result\":\"Not logged in · Please run /login\"}\n";

  @Test
  void aForegroundAgentThatExitsNonZeroFailsTheDispatchSayingWhy() throws Exception {
    var ran = dispatch(shell().on("tail -n 20", NOT_LOGGED_IN), 1, "--no-background");

    assertEquals(1, ran.exit(), ran.out());
    assertEquals(
        "  ✗ The agent exited 1: Not logged in · Please run /login\n"
            + "  Full log: "
            + OperatorCommand.here().sail("agent log acme")
            + "\n",
        ran.err());
  }

  @Test
  void aBackgroundAgentsExitIsTheWatchersToReport() throws Exception {
    var shell = shell().on("tail -n 20", NOT_LOGGED_IN);

    var ran = dispatch(shell, 0);

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(ran.out().contains("Guardrail watcher started (pid 4242"), ran.out());
    assertFalse(ran.err().contains("✗"), ran.err());
    assertTrue(
        shell.invocations.stream().noneMatch(command -> command.contains("tail -n 20")),
        "a background launch reads no log: " + shell.invocations);
  }

  static StubShell shell() {
    return new StubShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("printf '%s'", "")
        .on("test -d /home/dev/workspace/app/.git", "")
        .on("git -C /home/dev/workspace/app checkout -b sail/auth", "");
  }

  private static DispatchOperations.Request request() {
    return new DispatchOperations.Request("auth", "background", false, null, false);
  }

  @Test
  void aViewerNodesCliDispatchAndStopAreRefusedWithTheSyncedRole() throws Exception {
    var operations = cliOperations(shell(), new ArrayList<>());
    var fdes = new FdeStore(db);
    fdes.add(HANDLE, null, null, "member");
    operations.useControlPlane(
        db,
        tempDir,
        new SyncOperations(
            db,
            "box",
            tempDir,
            () -> new SyncConfig("node", "sail@main", HANDLE, "box"),
            target -> {
              throw new IOException("main unavailable");
            },
            SyncOperations.PLAIN_NOTICES));
    assertInstanceOf(
        DispatchOperations.Dispatched.class,
        DispatchCommand.dispatchAsOperator(operations, "acme", request(), HANDLE));

    fdes.update(HANDLE, null, null, "viewer");

    var stop =
        assertThrows(
            ApiException.class,
            () -> AgentStopCommand.stopAsOperator(operations, "acme", HANDLE, false));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, stop.failure().errorCode());
    var dispatch =
        assertThrows(
            IllegalStateException.class,
            () -> DispatchCommand.dispatchAsOperator(operations, "acme", request(), HANDLE));
    assertTrue(dispatch.getMessage().contains("read-only"), dispatch.getMessage());
  }

  @Test
  void theControlPlaneWiresTheProjectsOwnSkillsIntoEveryLaunchOnThisBox() throws Exception {
    var shell =
        shell()
            .on("-mmin", "")
            .on("incus file push", "")
            .on("mkdir -p /home/dev/.claude", "")
            .on("flock /home/dev/.claude/skills", "");
    var operations = cliOperations(shell, new ArrayList<>());
    new FdeStore(db).add(HANDLE, null, null, "admin");
    Acting.system(
        () -> {
          new ProjectStore(db).upsert("acme", YAML);
          new FileStore(db)
              .put(
                  "acme",
                  ".sail/skills/e2e/SKILL.md",
                  new ByteArrayInputStream("Run the e2e suite.\n".getBytes(UTF_8)),
                  0644);
        });
    operations.useControlPlane(
        db,
        tempDir,
        new SyncOperations(
            db,
            "box",
            tempDir,
            () -> new SyncConfig("node", "sail@main", HANDLE, "box"),
            target -> {
              throw new IOException("main unavailable");
            },
            SyncOperations.PLAIN_NOTICES));

    assertEquals(
        List.of("e2e"),
        operations.projectSkills().held("acme").stream().map(StageSkill::name).toList(),
        "the launcher reads the project's skills through the control plane's instance");
    assertInstanceOf(
        DispatchOperations.Dispatched.class,
        DispatchCommand.dispatchAsOperator(operations, "acme", request(), HANDLE));
    var run = new RunStore(db).listForProject("acme").getFirst();
    assertTrue(run.task().contains("\n## How this run works\n"), run.task());
    assertFalse(run.task().contains("e2e"), "no prompt names a skill: " + run.task());
    assertTrue(
        shell.invocations.stream()
            .anyMatch(
                command ->
                    command.startsWith("incus file push")
                        && command.contains("acme/home/dev/.claude/.sail-stage-build-e2e.")
                        && command.endsWith("/SKILL.md")),
        "the project's skill is installed from its files: " + shell.invocations);
  }

  @Test
  void cliConstructionPathRecordsTheRunStampsTheBranchAndAnswersRuns() throws Exception {
    var events = new ArrayList<Event>();
    var operations = cliOperations(shell(), events);
    new FdeStore(db).add(HANDLE, null, null, "admin");

    var outcome = DispatchCommand.dispatchAsOperator(operations, "acme", request(), HANDLE);

    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    assertEquals("sail/auth", dispatched.branch());

    var spec = new SpecStore(db).findById("auth").orElseThrow();
    assertEquals(SpecStatus.IN_PROGRESS, spec.status());
    assertEquals("sail/auth", spec.branch(), "the claim stamps the dispatch branch on the spec");

    var runs = new RunStore(db).listForProject("acme");
    assertEquals(1, runs.size(), "an in-process CLI dispatch records its run");
    assertEquals(HANDLE, runs.getFirst().node(), "the run carries this box's handle as provenance");
    assertEquals("sail/auth", runs.getFirst().branch());
    assertEquals("running", runs.getFirst().status());

    assertEquals(
        List.of(Event.WellKnownTypes.SPEC_DISPATCHED),
        events.stream().map(Event::type).toList(),
        "the CLI lane publishes the same lifecycle events as the server lane");

    var server =
        new SailOperations(
            shell(),
            ProjectReader.ofCatalog(new ProjectStore(db)),
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            SyncScheduler.disabled(),
            new FdeStore(db),
            SessionYield.NONE);
    var served = server.runs("acme", null);
    assertTrue(
        served.isSuccess(), "/v1/runs answers from the rows a CLI-lane dispatch just recorded");
  }

  @Test
  void aDraftSpecIsRefusedWithTheCommandThatDispatchesItAgain() throws Exception {
    seed();
    Acting.system(
        () ->
            new SpecStore(db).updateReposAndStatus("auth", List.of("app"), SpecStatus.DRAFT, null));

    var ran = dispatch(shell(), 0, "--background");

    assertEquals(1, ran.exit(), ran.out());
    assertEquals(
        "  ✗ Spec 'auth' is not pending (current status: draft). A spec is dispatched only when"
            + " pending. To dispatch it again, run "
            + OperatorCommand.here().sail("spec dispatch -p acme --spec auth --restart")
            + " (this resets its status to pending and records the restart as a lifecycle"
            + " event).\n",
        ran.err());
  }

  @Test
  void aBranchThatExistsIsRefusedWithTheCommandThatDispatchesOntoIt() throws Exception {
    var ran =
        dispatch(
            shell().on("git -C /home/dev/workspace/app rev-parse --verify --quiet refs/heads/", ""),
            0,
            "--background");

    assertEquals(1, ran.exit(), ran.out());
    assertEquals(
        "  ✗ Branch 'sail/auth' already exists. To dispatch onto it, run "
            + OperatorCommand.here().sail("spec dispatch -p acme --spec auth --restart")
            + ", or delete it first.\n",
        ran.err());
  }

  @Test
  void cliRestartWithoutASpecIsRefusedByTheSharedExecutor() throws Exception {
    var operations = cliOperations(shell(), new ArrayList<>());
    new FdeStore(db).add(HANDLE, null, null, "admin");
    var request = new DispatchOperations.Request(null, "background", false, null, true);

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    Actor.cliOperator(HANDLE),
                    () -> operations.dispatching().dispatch("acme", request, HANDLE)));

    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("spec id"));
  }

  @Test
  void cliRestartRedispatchesAReviewSpecOntoItsPriorBranch() throws Exception {
    var events = new ArrayList<Event>();
    var operations =
        cliOperations(
            shell()
                .on("git -C /home/dev/workspace/app rev-parse --verify --quiet refs/heads/x", "")
                .on("git -C /home/dev/workspace/app checkout -f x", ""),
            events);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    var specStore = new SpecStore(db);
    Acting.system(
        () -> specStore.updateReposAndStatus("auth", List.of("app"), SpecStatus.REVIEW, "x"));
    var request = new DispatchOperations.Request("auth", "background", false, null, true);

    var outcome = DispatchCommand.dispatchAsOperator(operations, "acme", request, HANDLE);

    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    assertTrue(dispatched.restarted());
    assertEquals("x", dispatched.branch(), "a restart lands on the recorded prior branch");
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals(
        List.of(Event.WellKnownTypes.SPEC_RESTARTED, Event.WellKnownTypes.SPEC_DISPATCHED),
        events.stream().map(Event::type).toList());
    assertEquals(Map.of("note", "restarted from review"), events.getFirst().data());
  }

  @Test
  void cliConstructionPathEnforcesTheRosterGuard() throws Exception {
    var operations = cliOperations(shell(), new ArrayList<>());

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    Actor.cliOperator(HANDLE),
                    () -> operations.dispatching().dispatch("acme", request(), HANDLE)));

    assertTrue(ex.getMessage().contains("roster"));
    assertEquals(
        SpecStatus.PENDING,
        new SpecStore(db).findById("auth").orElseThrow().status(),
        "a refused dispatch must not claim the spec");
  }

  static final class StubShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();

    /**
     * Every launch reconciles the in-container sail helpers and installs sail's skill; answer both
     * as already installed, and the skills folder as holding nothing else of sail's.
     */
    StubShell() {
      on("incus config device add", "");
      on("cat " + ContainerSailSetup.STAMP_PATH, ContainerSailSetup.fingerprint());
      on(
          "/spec-board/" + StageSkill.STAMP,
          ProjectSkillInstaller.fingerprint(SpecSkillGenerator.skill()));
      on("for d in", "");
    }

    final List<String> invocations = new ArrayList<>();

    StubShell on(String pattern, String stdout) {
      scripts.put(pattern, new Result(0, stdout, ""));
      return this;
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      invocations.add(joined);
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return ScriptedShellExecutor.reachableContainer(joined)
          .orElseGet(() -> new Result(1, "", "no script for " + joined));
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
