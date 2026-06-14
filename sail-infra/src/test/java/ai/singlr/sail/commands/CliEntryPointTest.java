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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

/**
 * Tests CLI entry points via picocli's {@code CommandLine.execute()}. Must run single-threaded
 * because tests capture and replace the global {@code System.out} and {@code System.err}.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CliEntryPointTest {

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

  @Test
  void sailWithNoArgsShowsUsage() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute();

    assertEquals(0, exitCode);
    var output = capturedOut.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("sail"), "Usage should mention 'sail'");
  }

  @Test
  void versionFlagPrintsVersion() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("-V");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("sail"), "Version should include 'sail'");
  }

  @Test
  void hostWithNoSubcommandShowsUsage() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("host");

    assertEquals(0, exitCode);
    var output = capturedOut.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("host"), "Host usage should be printed");
  }

  @Test
  void sailHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Isolated dev environments for AI agents"));
  }

  @Test
  void hostHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("host", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("bare-metal host"));
  }

  @Test
  void hostInitHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("host", "init", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--disk"));
    assertTrue(output.contains("--pool"));
    assertTrue(output.contains("--yes"));
  }

  @Test
  void unknownCommandReturnsNonZero() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("nonexistent");

    assertNotEquals(0, exitCode);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void hostInitDryRunWithDiskAndYes() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode =
        cmd.execute("host", "init", "--dry-run", "--storage", "zfs", "--disk", "/dev/sdb", "--yes");

    assertEquals(0, exitCode);
    var output = capturedOut.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("███████"), "Should show branding");
    assertTrue(output.contains("Host Detection"), "Should show host detection banner");
    assertTrue(output.contains("[dry-run]"), "Should show dry-run commands");
    assertTrue(output.contains("Server ready"), "Should show server ready");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void hostInitDryRunDirBackendSkipsDiskDetection() {
    var cmd = new CommandLine(new Sail());

    var exitCode = cmd.execute("host", "init", "--dry-run", "--yes");

    assertEquals(0, exitCode);
    var output = capturedOut.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Host Detection"), "Should show host detection");
    assertTrue(output.contains("Server ready"), "Should complete with dir backend");
    assertTrue(output.contains("(dir)"), "Should show dir backend in output");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void hostInitNonDryRunWithoutRootFails() {
    var cmd = new CommandLine(new Sail());

    var exitCode = cmd.execute("host", "init", "--storage", "zfs", "--disk", "/dev/sdb", "--yes");

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Root privileges required"), "Should show root required");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void hostInitDryRunZfsWithCustomPool() {
    var cmd = new CommandLine(new Sail());

    var exitCode =
        cmd.execute(
            "host",
            "init",
            "--dry-run",
            "--storage",
            "zfs",
            "--disk",
            "/dev/nvme1n1",
            "--pool",
            "mypool",
            "--yes");

    assertEquals(0, exitCode);
    var output = capturedOut.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("mypool"), "Should show custom pool name");
    assertTrue(output.contains("/dev/nvme1n1"), "Should show custom disk");
  }

  @TempDir Path tempDir;

  @Test
  void projectWithNoSubcommandShowsUsage() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("project");

    assertEquals(0, exitCode);
    var output = capturedOut.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("project"), "Project usage should be printed");
    assertTrue(output.contains("create"), "Should list 'create' subcommand");
    assertTrue(output.contains("resources"), "Should list 'resources' subcommand");
  }

  @Test
  void projectHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Manage project environments"));
  }

  @Test
  void projectCreateHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "create", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--file"));
    assertTrue(output.contains("--yes"));
  }

  @Test
  void projectInitHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "init", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--json"));
    assertTrue(output.contains("--output"));
    assertTrue(output.contains("sail.yaml"));
  }

  @Test
  void projectDemoHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "demo", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("zero configuration"));
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
  }

  @Test
  void projectResourcesHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "resources", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("Manage project resource allocations"));
    assertTrue(output.contains("set"));
  }

  @Test
  void projectResourcesSetHelpShowsOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("project", "resources", "set", "--help");

    assertEquals(0, exitCode);
    var output = sw.toString();
    assertTrue(output.contains("--cpu"));
    assertTrue(output.contains("--memory"));
    assertTrue(output.contains("--disk"));
    assertTrue(output.contains("--dry-run"));
    assertTrue(output.contains("--json"));
  }

  @Test
  void projectCreateMissingDescriptorFails() {
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode =
        cmd.execute(
            "project",
            "create",
            "--dry-run",
            "--file",
            tempDir.resolve("nonexistent.yaml").toString());

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("not found"), "Should report missing sail.yaml");
  }

  @Test
  void projectCreateInvalidNameFails() throws Exception {
    var yamlFile = tempDir.resolve("sail.yaml");
    Files.writeString(
        yamlFile,
        """
            name: "INVALID NAME!"
            resources:
              cpu: 2
              memory: 4GB
              disk: 50GB
            """);
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("project", "create", "--dry-run", "--file", yamlFile.toString());

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Invalid project name"), "Should report invalid name pattern");
  }

  @Test
  void projectCreateMissingNameFails() throws Exception {
    var yamlFile = tempDir.resolve("sail.yaml");
    Files.writeString(
        yamlFile,
        """
            resources:
              cpu: 2
              memory: 4GB
              disk: 50GB
            """);
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("project", "create", "--dry-run", "--file", yamlFile.toString());

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("name"), "Should report missing name");
  }

  @Test
  void projectCreateMissingResourcesFails() throws Exception {
    var yamlFile = tempDir.resolve("sail.yaml");
    Files.writeString(
        yamlFile,
        """
            name: test-proj
            """);
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, cl, pr) -> 1);

    var exitCode = cmd.execute("project", "create", "--dry-run", "--file", yamlFile.toString());

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("resources"), "Should report missing resources");
  }

  @Test
  void projectCreateNonDryRunWithoutRootFails() throws Exception {
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

    var exitCode = cmd.execute("project", "create", "--file", yamlFile.toString(), "--yes");

    assertNotEquals(0, exitCode);
    var errOutput = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Root privileges required"), "Should require root");
  }
}
