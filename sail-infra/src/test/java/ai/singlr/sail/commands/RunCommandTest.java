/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.OperationHooks;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

@Execution(ExecutionMode.SAME_THREAD)
class RunCommandTest {

  private PrintStream originalOut;
  private PrintStream originalErr;
  private ByteArrayOutputStream capturedOut;
  private ByteArrayOutputStream capturedErr;

  @BeforeEach
  void captureStreams() {
    originalOut = System.out;
    originalErr = System.err;
    capturedOut = new ByteArrayOutputStream();
    capturedErr = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capturedOut));
    System.setErr(new PrintStream(capturedErr));
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @TempDir Path tempDir;

  @Test
  void helpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("agent", "run", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("harness"));
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--task"));
    assertTrue(output.contains("--background"));
    assertTrue(output.contains("--json"));
  }

  @Test
  void runRegisteredUnderAgentCommand() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("agent", "--help");

    var output = sw.toString();
    assertTrue(output.contains("run"), "run should appear under agent help");
  }

  @Test
  void aProjectNotInTheCatalogFailsSayingSo() {
    var cmd = command(tempDir.resolve("control-plane.db"));
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("test-project", "--dry-run");

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Project 'test-project' is not in the catalog."), errOutput);
  }

  @Test
  void invalidProjectNameFails() {
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("agent", "run", "INVALID NAME!", "--dry-run");

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Invalid project name"));
  }

  @Test
  void containerNotRunningFails() {
    var dbPath = tempDir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(
          () ->
              new ProjectStore(db)
                  .upsert(
                      TestDefinitions.yaml(
                          """
                      name: test-proj
                      resources:
                        cpu: 2
                        memory: 4GB
                        disk: 50GB
                      agent:
                        type: claude-code
                      """)));
    }
    var cmd = command(dbPath);
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("test-proj", "--dry-run");

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertEquals(
        "  ✗ Project 'test-proj' has no container on this box. Create it: sudo sail project apply"
            + " test-proj\n",
        errOutput);
  }

  /** The command against a host whose {@code incus} has no container of any name. */
  private CommandLine command(Path dbPath) {
    return command(dbPath, new ScriptedShellExecutor().onOk("incus list", "[]"), command -> 0);
  }

  private CommandLine command(
      Path dbPath, ScriptedShellExecutor shell, DispatchOperations.AgentLauncher terminal) {
    return new CommandLine(runCommand(dbPath, shell, terminal));
  }

  private final List<Sqlite> opened = new ArrayList<>();

  @AfterEach
  void closeDatabases() {
    opened.forEach(Sqlite::close);
  }

  /**
   * {@code sail agent run} over the catalog at {@code dbPath}: a task session launches through the
   * command's own hooks, its events dropped and its watcher a pid.
   */
  private RunCommand runCommand(
      Path dbPath, ScriptedShellExecutor shell, DispatchOperations.AgentLauncher terminal) {
    return new RunCommand(
        operator -> OperationsFactory.open(dbPath),
        (host, hooks) -> {
          var db = Sqlite.open(dbPath);
          opened.add(db);
          return OperationsFactory.create(
              db,
              host,
              new OperationHooks(
                  event -> {},
                  new WatcherSpawner(host, (spawned, logPath) -> 4242L),
                  hooks.snapshotter(),
                  hooks.launcher(),
                  hooks.dispatchListener(),
                  hooks.stopListener()),
              SessionYield.NONE);
        },
        dryRun -> shell,
        terminal);
  }

  @Test
  void aTaskSessionWhoseAgentExitsNonZeroFailsSayingWhy() {
    var shell = runningContainer().onOk("tail -n 20", DispatchCommandWiringTest.NOT_LOGGED_IN);

    var ran =
        SailCli.run(
            runCommand(catalogWithASharedSkill(), shell, command -> 1),
            "test-proj",
            "--task",
            "Fix the build",
            "--no-snapshot");

    assertEquals(1, ran.exit(), ran.out());
    assertEquals(
        "  ✗ The agent exited 1: Not logged in · Please run /login\n"
            + "  Full log: "
            + OperatorCommand.here().sail("agent log test-proj")
            + "\n",
        ran.err());
  }

  @Test
  void anInteractiveSessionWhoseAgentExitsNonZeroFailsTheCommand() {
    var ran =
        SailCli.run(
            runCommand(catalogWithASharedSkill(), runningContainer(), command -> 130),
            "test-proj",
            "--no-snapshot");

    assertEquals(1, ran.exit(), ran.out());
    assertEquals("  ✗ The agent exited 130.\n", ran.err());
  }

  private static final String NOCAPTCHA =
      """
      name: test-proj
      agent:
        type: claude-code
        auto_branch: true
      repos:
        - url: https://github.com/acme/nocaptcha.git
          path: nocaptcha
      """;

  @Test
  void aTaskRunCutsItsBranchInTheProjectsRepoAndSaysWhere() {
    var shell = repoContainer();

    var ran =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--no-snapshot");

    assertEquals(0, ran.exit(), ran.err());
    var branch = onlyBranchCutIn(shell, "/home/dev/workspace/nocaptcha");
    assertTrue(
        ran.out()
            .contains(
                "  Creating branch: "
                    + branch
                    + " in nocaptcha\n  ✓ Branch "
                    + branch
                    + " in nocaptcha\n"),
        ran.out());
  }

  @Test
  void aTaskRunInAPathCutsItsBranchInThatRepoAlone() {
    var shell = repoContainer();
    var twoRepos = NOCAPTCHA + "  - url: https://github.com/acme/web.git\n    path: web\n";

    var ran =
        SailCli.run(
            runCommand(catalog(twoRepos), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--path",
            "nocaptcha",
            "--no-snapshot");

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(onlyBranchCutIn(shell, "/home/dev/workspace/nocaptcha").startsWith("sail/snap-"));
  }

  @Test
  void aTaskRunOfAProjectWithNoRepoCutsNoBranchAndSaysSoAsADispatchDoes() {
    var shell = repoContainer();

    var ran =
        SailCli.run(
            runCommand(
                catalog("name: test-proj\nagent:\n  type: claude-code\n  auto_branch: true\n"),
                shell,
                command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--no-snapshot");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(List.of(), checkouts(shell));
    assertTrue(
        Pattern.compile("\n  Branch: sail/snap-\\S+ \\(create manually in repo\\)\n")
            .matcher(ran.out())
            .find(),
        ran.out());
  }

  @Test
  void aBranchGitRefusesFailsTheRunSayingWhatGitSaid() {
    var shell =
        repoContainer().onFail("checkout -b", "fatal: not a git repository: /home/dev/workspace");

    var ran =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--no-snapshot");

    assertEquals(1, ran.exit(), ran.out());
    var branch = onlyBranchCutIn(shell, "/home/dev/workspace/nocaptcha");
    assertEquals(
        "  ✗ Failed to prepare the agent session: Failed to create branch '"
            + branch
            + "' in repo 'nocaptcha': fatal: not a git repository: /home/dev/workspace\n",
        ran.err());
  }

  @Test
  void aForegroundTaskRunPrintsNoLaunchCommandAndItsJsonKeepsIt() {
    var foreground =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), repoContainer(), command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--no-snapshot");
    var json =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), repoContainer(), command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--json");

    assertEquals(0, foreground.exit(), foreground.err());
    assertTrue(foreground.out().contains("  Launching agent with task...\n\n"), foreground.out());
    assertFalse(foreground.out().contains("incus exec"), foreground.out());
    assertEquals(0, json.exit(), json.err());
    assertTrue(
        String.valueOf(YamlUtil.parseMap(json.out()).get("ssh_command")).startsWith("incus exec "),
        json.out());
  }

  @Test
  void aTaskRunInASubdirectoryOfARepoCutsItsBranchInThatRepo() {
    var shell = repoContainer();

    var ran =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--path",
            "nocaptcha/server",
            "--no-snapshot");

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(onlyBranchCutIn(shell, "/home/dev/workspace/nocaptcha").startsWith("sail/snap-"));
  }

  @Test
  void aTaskRunInAPathThroughAnotherRepoCutsItsBranchWhereTheAgentStarts() {
    var shell = repoContainer();
    var twoRepos = NOCAPTCHA + "  - url: https://github.com/acme/web.git\n    path: web\n";

    var ran =
        SailCli.run(
            runCommand(catalog(twoRepos), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--path",
            "nocaptcha/../web",
            "--no-snapshot");

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(onlyBranchCutIn(shell, "/home/dev/workspace/web").startsWith("sail/snap-"));
  }

  @Test
  void aTaskRunInADotPrefixedSubdirectoryCutsItsBranchInTheDeepestRepoHoldingIt() {
    var shell = repoContainer();
    var nested = NOCAPTCHA + "  - url: https://github.com/acme/ui.git\n    path: nocaptcha/ui\n";

    var ran =
        SailCli.run(
            runCommand(catalog(nested), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--path",
            "./nocaptcha/ui/src",
            "--no-snapshot");

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(onlyBranchCutIn(shell, "/home/dev/workspace/nocaptcha/ui").startsWith("sail/snap-"));
  }

  @Test
  void aPathThatLeavesTheWorkspaceIsRefusedBeforeAnythingRuns() {
    var shell = repoContainer();

    var ran =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--path",
            "nocaptcha/../../etc",
            "--no-snapshot");

    assertEquals(1, ran.exit(), ran.out());
    assertTrue(
        ran.err()
            .contains(
                "--path 'nocaptcha/../../etc' leaves the workspace. Name a directory inside it."),
        ran.err());
    assertEquals(List.of(), checkouts(shell));
  }

  @Test
  void aShellThatCannotRunWhilePreparingSaysWhyThroughEveryWrapper() {
    var shell =
        repoContainer()
            .onThrow(
                "rev-parse --is-inside-work-tree",
                new IOException("Cannot run program incus: error=2, No such file or directory"));

    var ran =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--no-snapshot");

    assertEquals(1, ran.exit(), ran.out());
    assertEquals(
        "  ✗ Failed to prepare the agent session: A sail system command failed: Cannot run program"
            + " incus: error=2, No such file or directory\n",
        ran.err());
  }

  @Test
  void aBackgroundRunThatCutNoBranchReportsNone() {
    var ran =
        SailCli.run(
            runCommand(
                catalog("name: test-proj\nagent:\n  type: claude-code\n  auto_branch: true\n"),
                repoContainer(),
                command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--background",
            "--no-snapshot");

    assertEquals(0, ran.exit(), ran.err());
    assertTrue(ran.out().contains("  ✓ Agent launched in background\n    Task:"), ran.out());
    assertFalse(ran.out().contains("    Branch:  "), ran.out());
  }

  @Test
  void aBranchThatAlreadyExistsKeepsItsRemedyThroughThePreparation() {
    var shell = runningContainer();

    var ran =
        SailCli.run(
            runCommand(catalog(NOCAPTCHA), shell, command -> 0),
            "test-proj",
            "--task",
            "Fix the build",
            "--no-snapshot");

    assertEquals(1, ran.exit(), ran.out());
    var probe =
        shell.arguments().stream()
            .filter(command -> command.contains("--verify") && command.contains("rev-parse"))
            .findFirst()
            .orElseThrow();
    var branch = probe.getLast().substring("refs/heads/".length());
    assertEquals(
        "  ✗ Failed to prepare the agent session: Branch '"
            + branch
            + "' already exists.\n  Delete it first.\n",
        ran.err());
  }

  /** A container whose repos exist and hold no branch yet. */
  private static ScriptedShellExecutor repoContainer() {
    return runningContainer().onFail("rev-parse --verify --quiet refs/heads/", "");
  }

  /** The branch {@code shell} cut, after checking it cut one, and only in {@code repoDir}. */
  private static String onlyBranchCutIn(ScriptedShellExecutor shell, String repoDir) {
    var checkouts = checkouts(shell);
    assertEquals(1, checkouts.size(), checkouts.toString());
    var checkout = checkouts.getFirst();
    assertEquals(repoDir, checkout.get(checkout.indexOf("-C") + 1));
    return checkout.get(checkout.indexOf("-b") + 1);
  }

  private static List<List<String>> checkouts(ScriptedShellExecutor shell) {
    return shell.arguments().stream()
        .filter(command -> command.contains("checkout") && command.contains("-b"))
        .toList();
  }

  /** A catalog holding the one project {@code yaml} describes. */
  private Path catalog(String yaml) {
    var dbPath = tempDir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.yaml(yaml)));
    }
    return dbPath;
  }

  private static final String E2E =
      """
      ---
      name: e2e
      description: Runs the e2e suite.
      ---

      Run the suite.
      """;

  /** A catalog holding {@code test-proj}, a claude-code project sharing the skill {@code e2e}. */
  private Path catalogWithASharedSkill() {
    var dbPath = tempDir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(
          () -> {
            new ProjectStore(db)
                .upsert(TestDefinitions.yaml("name: test-proj\nagent:\n  type: claude-code\n"));
            new FileStore(db)
                .put(
                    "test-proj",
                    ".sail/skills/e2e/SKILL.md",
                    new ByteArrayInputStream(E2E.getBytes(StandardCharsets.UTF_8)),
                    0644);
          });
    }
    return dbPath;
  }

  /** A container that is up, whose skills folder holds a stamped {@code old} sail installed. */
  private static ScriptedShellExecutor runningContainer() {
    return new ScriptedShellExecutor(new ScriptedShellExecutor.Result(0, "", ""))
        .onOk("incus list", "[{\"status\": \"Running\"}]")
        .onOk("rev-parse --is-inside-work-tree", "true\n")
        .onOk("basename", "old\n");
  }

  private static boolean placed(ScriptedShellExecutor shell, String folder) {
    return shell.arguments().stream()
        .anyMatch(
            command ->
                command.stream().anyMatch(arg -> arg.contains("mv -T"))
                    && command.contains(folder));
  }

  @Test
  void anInteractiveSessionInstallsTheProjectsSkillsForItsHarnessBeforeTheSsh() {
    var shell = runningContainer();
    var launched = new ArrayList<List<String>>();
    var commandsBeforeTheSession = new AtomicInteger(-1);
    var cmd =
        command(
            catalogWithASharedSkill(),
            shell,
            command -> {
              launched.add(command);
              commandsBeforeTheSession.set(shell.invocations().size());
              return 0;
            });

    var exitCode = cmd.execute("test-proj", "--no-snapshot");

    assertEquals(0, exitCode, capturedErr.toString(StandardCharsets.UTF_8));
    assertTrue(placed(shell, "/home/dev/.claude/skills/e2e"), shell.invocations().toString());
    assertTrue(
        placed(shell, "/home/dev/.claude/skills/spec-board"), shell.invocations().toString());
    assertTrue(
        shell
            .arguments()
            .contains(
                ContainerExec.asDevUser(
                    "test-proj", List.of("rm", "-rf", "/home/dev/.claude/skills/old"))),
        "a stamped folder the project no longer holds is removed: " + shell.invocations());
    assertEquals(1, launched.size());
    assertEquals(List.of("ssh", "-t", "dev@test-proj"), launched.getFirst().subList(0, 3));
    assertEquals(
        shell.invocations().size(),
        commandsBeforeTheSession.get(),
        "every install command ran before the session started");
  }

  @Test
  void aPreviewOfAnInteractiveSessionInstallsNothingAndStartsNoSession() {
    var shell = runningContainer();
    var launched = new ArrayList<List<String>>();
    var dbPath = catalogWithASharedSkill();

    var exitCode =
        command(
                dbPath,
                shell,
                command -> {
                  launched.add(command);
                  return 0;
                })
            .execute("test-proj", "--no-snapshot", "--dry-run");

    assertEquals(0, exitCode, capturedErr.toString(StandardCharsets.UTF_8));
    assertTrue(
        shell.invocations().stream().noneMatch(command -> command.contains("/skills/")),
        shell.invocations().toString());
    assertTrue(launched.isEmpty());
    assertTrue(
        capturedOut.toString(StandardCharsets.UTF_8).contains("[dry-run] ssh -t dev@test-proj"));
  }

  @Test
  void specTaskIncludesSpecDetailsAndDoneInstruction() {
    var spec =
        new Spec(
            "auth",
            "test",
            "Add auth",
            SpecStatus.PENDING,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);

    var task = RunCommand.specTask(spec, "Implement the login flow.");

    assertTrue(task.contains("Add auth"));
    assertTrue(task.contains("(id: auth)"));
    assertTrue(task.contains("Implement the login flow."));
    assertTrue(task.endsWith("\n\nWhen complete, stop."), task);
    assertFalse(task.contains("--status"), "sail manages a spec's status, never the agent");
  }

  @Test
  void rollbackIsReservedForLaunchFailuresThatLeftNoActiveRun() {
    var launchFailed = new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "launch failed");
    var alreadyRunning = new ApiException(ErrorCode.AGENT_ALREADY_RUNNING, "container busy");

    assertTrue(RunCommand.rollbackSafe(launchFailed, false));
    assertFalse(
        RunCommand.rollbackSafe(launchFailed, true),
        "a live run must never have the container restored underneath it");
    assertFalse(
        RunCommand.rollbackSafe(alreadyRunning, false),
        "a reservation refusal must not disturb the existing owner");
  }

  @Test
  void specTaskFallsBackToTitleWhenBodyIsBlank() {
    var spec =
        new Spec(
            "auth",
            "test",
            "Add auth",
            SpecStatus.PENDING,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);

    var task = RunCommand.specTask(spec, "   ");

    assertTrue(task.contains("Add auth"));
    assertTrue(task.endsWith("\n\nWhen complete, stop."), task);
  }

  private static String launchNotes(String harness, boolean interactive) {
    var out = new ByteArrayOutputStream();
    RunCommand.printLaunchNotes(
        Harnesses.of(harness),
        "acme",
        interactive,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        CommandLine.Help.Ansi.OFF);
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void anInteractiveSessionOfAHarnessWithALoginPortAndATipIsToldBoth() {
    var nl = System.lineSeparator();

    assertEquals(
        "    → Agent auth: ssh -N -L 3000:localhost:3000 acme"
            + nl
            + nl
            + "  Tip: Type /rc inside Claude Code to connect from your phone via Remote Control."
            + nl
            + nl,
        launchNotes("claude-code", true));
  }

  @Test
  void aTaskRunIsToldOfTheLoginPortButNotTheInteractiveTip() {
    var nl = System.lineSeparator();

    assertEquals(
        "    → Agent auth: ssh -N -L 3000:localhost:3000 acme" + nl + nl,
        launchNotes("claude-code", false));
  }

  @Test
  void aHarnessWithNeitherALoginPortNorATipIsToldNothing() {
    assertEquals("", launchNotes("codex", true));
    assertEquals("", launchNotes("codex", false));
  }
}
