/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  void missingSailYamlFails() {
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode =
        cmd.execute(
            "agent",
            "run",
            "test-project",
            "--dry-run",
            "--file",
            tempDir.resolve("nonexistent.yaml").toString());

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("not found"));
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
  void containerNotRunningFails() throws Exception {
    var yamlFile = tempDir.resolve("sail.yaml");
    Files.writeString(
        yamlFile,
        """
            name: test-proj
            resources:
              cpu: 2
              memory: 4GB
              disk: 50GB
            agent:
              type: claude-code
            """);
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode =
        cmd.execute("agent", "run", "test-proj", "--dry-run", "--file", yamlFile.toString());

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("does not exist"));
  }
}
