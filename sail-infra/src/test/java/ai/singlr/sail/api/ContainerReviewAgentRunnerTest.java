/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ShellExec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContainerReviewAgentRunnerTest {

  @Test
  void runsTheAgentCleanInTheWorkspaceAndReturnsStdout() throws Exception {
    var shell =
        new RecordingShell()
            .script("file push", new ShellExec.Result(0, "", ""))
            .script("claude --print", new ShellExec.Result(0, "[]", ""));

    var output = new ContainerReviewAgentRunner(shell).run("acme", "claude-code", "Review it");

    assertEquals("[]", output);
    assertTrue(
        shell.commands().stream()
            .anyMatch(c -> c.contains("file push") && c.contains("review-prompt.txt")),
        "the prompt must be staged to a file, not passed on the command line");
    var agentCommand = shell.commandContaining("claude --print");
    assertFalse(agentCommand.contains("--settings"), "reviewer must not load the sail hooks");
    assertFalse(agentCommand.contains("SAIL_SPEC_ID"), "reviewer must run without a spec id");
    assertTrue(agentCommand.contains("cd /home/dev/workspace"));
  }

  @Test
  void throwsWhenTheReviewAgentFails() {
    var shell =
        new RecordingShell()
            .script("file push", new ShellExec.Result(0, "", ""))
            .script("codex exec", new ShellExec.Result(1, "", "boom"));

    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> new ContainerReviewAgentRunner(shell).run("acme", "codex", "Review it"));
    assertTrue(ex.getMessage().contains("boom"));
  }

  private static final class RecordingShell implements ShellExec {
    private final List<String> commands = new ArrayList<>();
    private final Map<String, Result> scripts = new LinkedHashMap<>();

    RecordingShell script(String substring, Result result) {
      scripts.put(substring, result);
      return this;
    }

    List<String> commands() {
      return commands;
    }

    String commandContaining(String substring) {
      return commands.stream().filter(c -> c.contains(substring)).findFirst().orElseThrow();
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      commands.add(joined);
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
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
