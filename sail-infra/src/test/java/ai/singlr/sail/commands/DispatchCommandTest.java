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
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentTaskPrompt;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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
class DispatchCommandTest {

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

    var exitCode = cmd.execute("spec", "dispatch", "--help");

    assertEquals(0, exitCode);
    assertTrue(sw.toString().contains("Dispatch the next ready spec"));
  }

  @Test
  void failsWithMissingSailYaml() {
    var cmd = new CommandLine(new Sail());

    var exitCode =
        cmd.execute(
            "spec", "dispatch", "test-project", "-f", tempDir.resolve("nope.yaml").toString());

    assertNotEquals(0, exitCode);
  }

  @Test
  void failsGracefullyWithoutRunningContainer() throws Exception {
    var yaml =
        """
        name: test-project
        resources:
          cpu: 2
          memory: 4GB
          disk: 20GB
        agent:
          type: claude-code
          specs_dir: specs
        """;
    var yamlPath = tempDir.resolve("sail.yaml");
    Files.writeString(yamlPath, yaml);

    var cmd = new CommandLine(new Sail());

    var exitCode = cmd.execute("spec", "dispatch", "test-project", "-f", yamlPath.toString());

    assertNotEquals(0, exitCode);
  }

  @Test
  void buildTaskPromptIncludesSpecDetails() {
    var spec = new Spec("oauth-flow", "Implement OAuth", SpecStatus.PENDING, null, List.of(), null);
    var description = "Build Google OAuth integration with PKCE flow.";

    var prompt = AgentTaskPrompt.build(spec, description);

    assertTrue(prompt.contains("oauth-flow"));
    assertTrue(prompt.contains("Implement OAuth"));
    assertTrue(prompt.contains("Build Google OAuth integration"));
    assertFalse(prompt.contains("spec.yaml"), "Prompt should not contain lifecycle instructions");
  }

  @Test
  void buildTaskPromptContainsSpecIdAndDescription() {
    var spec = new Spec("auth", "Auth", SpecStatus.PENDING, null, List.of(), null);

    var prompt = AgentTaskPrompt.build(spec, "Details");

    assertTrue(prompt.contains("auth"));
    assertTrue(prompt.contains("Details"));
  }

  @Test
  void buildTaskPromptIncludesFullDescription() {
    var spec = new Spec("setup", "Setup DB", SpecStatus.PENDING, null, List.of(), null);
    var longDescription =
        """
        Create PostgreSQL schema with:
        - users table
        - sessions table
        - migrations
        """;

    var prompt = AgentTaskPrompt.build(spec, longDescription.strip());

    assertTrue(prompt.contains("users table"));
    assertTrue(prompt.contains("sessions table"));
    assertTrue(prompt.contains("migrations"));
  }

  @Test
  void buildTaskPromptIncludesTargetAgent() {
    var spec =
        new Spec(
            "ui",
            "test-project",
            "Polish UI",
            SpecStatus.PENDING,
            null,
            List.of(),
            List.of("chorus"),
            "codex",
            "gpt-5.5",
            "high",
            null);

    var prompt = AgentTaskPrompt.build(spec, "Details");

    assertTrue(prompt.contains("Target repo: chorus"));
    assertTrue(prompt.contains("Target agent: codex"));
    assertTrue(prompt.contains("Target model: gpt-5.5"));
    assertTrue(prompt.contains("Target reasoning effort: high"));
  }

  @Test
  void dispatchCommandRegisteredInSing() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("spec", "--help");

    assertTrue(sw.toString().contains("dispatch"));
  }

  @Test
  void specOptionAccepted() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("spec", "dispatch", "--help");

    assertEquals(0, exitCode);
    var help = sw.toString();
    assertTrue(help.contains("--spec"));
    assertTrue(help.contains("--background"));
    assertTrue(help.contains("--repo"));
    assertTrue(help.contains("--dry-run"));
    assertTrue(help.contains("--json"));
    assertTrue(help.contains("--restart"));
  }

  @Test
  void restartWithoutSpecIsRejected() throws Exception {
    var yaml =
        """
        name: test-project
        resources:
          cpu: 2
          memory: 4GB
          disk: 20GB
        agent:
          type: claude-code
          specs_dir: specs
        """;
    var yamlPath = tempDir.resolve("sail.yaml");
    Files.writeString(yamlPath, yaml);

    var cmd = new CommandLine(new Sail());

    var exitCode =
        cmd.execute("spec", "dispatch", "test-project", "--restart", "-f", yamlPath.toString());

    assertNotEquals(0, exitCode);
    assertTrue(capturedErr.toString().contains("--restart requires --spec"));
  }

  @Test
  void dispatchedEventDataIncludesBranchAndMode() {
    var data = DispatchCommand.dispatchedEventData("sail/oauth-flow", true);

    assertEquals("sail/oauth-flow", data.get("branch"));
    assertEquals("background", data.get("mode"));
    assertEquals(List.of("branch", "mode"), List.copyOf(data.keySet()));
  }

  @Test
  void dispatchedEventDataOmitsBranchWhenNull() {
    var data = DispatchCommand.dispatchedEventData(null, false);

    assertFalse(data.containsKey("branch"));
    assertEquals("foreground", data.get("mode"));
  }

  @Test
  void dispatchedEventDataOmitsBranchWhenBlank() {
    var data = DispatchCommand.dispatchedEventData("   ", true);

    assertFalse(data.containsKey("branch"), "blank branch must not leak into the data payload");
    assertEquals("background", data.get("mode"));
  }

  @Test
  void branchRepoDirUsesSingleTargetWorkDirDirectly() {
    var repo = new SailYaml.Repo("https://github.com/org/chorus.git", "chorus", null);

    var repoDir = DispatchCommand.branchRepoDir("/home/dev/workspace/chorus", List.of(repo), repo);

    assertEquals("/home/dev/workspace/chorus", repoDir);
  }

  @Test
  void branchRepoDirAppendsRepoPathForMultiRepoDispatch() {
    var sing = new SailYaml.Repo("https://github.com/org/sing.git", "sing", null);
    var chorus = new SailYaml.Repo("https://github.com/org/chorus.git", "chorus", null);

    var repoDir =
        DispatchCommand.branchRepoDir("/home/dev/workspace", List.of(sing, chorus), chorus);

    assertEquals("/home/dev/workspace/chorus", repoDir);
  }
}
