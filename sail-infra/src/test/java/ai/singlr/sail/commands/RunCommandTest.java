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
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    assertTrue(output.contains("--no-regen"));
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
  void aProjectNotInTheCatalogFailsSayingSoWhateverFileIsNamed() {
    var cmd = command(tempDir.resolve("control-plane.db"));
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode =
        cmd.execute(
            "test-project", "--dry-run", "--file", tempDir.resolve("nonexistent.yaml").toString());

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Project 'test-project' is not in the catalog."), errOutput);
  }

  @Test
  void invalidProjectNameFails() throws Exception {
    var yamlFile = tempDir.resolve("sail.yaml");
    Files.writeString(
        yamlFile,
        """
            name: test-proj
            resources:
              cpu: 2
              memory: 4GB
              disk: 50GB
            """);
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode =
        cmd.execute("agent", "run", "INVALID NAME!", "--dry-run", "--file", yamlFile.toString());

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
                      "test-proj",
                      """
                      name: test-proj
                      resources:
                        cpu: 2
                        memory: 4GB
                        disk: 50GB
                      agent:
                        type: claude-code
                      """));
    }
    var cmd = command(dbPath);
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("test-proj", "--dry-run", "--file", "ignored.yaml");

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("does not exist"), errOutput);
  }

  private static CommandLine command(Path dbPath) {
    var noGit = new ScriptedShellExecutor();
    return new CommandLine(
        new RunCommand(
            () -> OperationsFactory.open(dbPath),
            new LocalIdentity(noGit, dbPath.resolveSibling("none.pub"))));
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

    var task = RunCommand.specTask("acme", spec, "Implement the login flow.");

    assertTrue(task.contains("Add auth"));
    assertTrue(task.contains("(id: auth)"));
    assertTrue(task.contains("Implement the login flow."));
    assertTrue(task.contains("sail spec status acme auth done"));
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

    var task = RunCommand.specTask("acme", spec, "   ");

    assertTrue(task.contains("Add auth"));
    assertTrue(task.contains("sail spec status acme auth done"));
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
