/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkBranchTest {

  private static final String WORKSPACE = "/home/dev/workspace";

  @TempDir Path tempDir;

  @Test
  void aLinkedWorktreeGetsTheWorkBranch() throws Exception {
    var main = Files.createDirectories(tempDir.resolve("app"));
    git(main, "init", "-q", "-b", "main");
    git(
        main,
        "-c",
        "user.email=a@b",
        "-c",
        "user.name=a",
        "-c",
        "commit.gpgsign=false",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "x");
    git(main, "worktree", "add", "-q", tempDir.resolve("app-wt").toString());
    var reported = new ArrayList<String>();
    var listener =
        new DispatchOperations.Listener() {
          @Override
          public void branchReady(String branch, String repoPath, boolean reused) {
            reported.add(branch + " in " + repoPath);
          }
        };
    var config = TestDefinitions.map(Map.of("name", "acme"));

    var cut =
        new WorkBranch(new LocalGit(tempDir), listener)
            .checkout(
                "acme",
                config,
                WorkBranch.repos(config, "app-wt"),
                "sail/run",
                Optional.empty(),
                false);

    assertTrue(cut);
    assertEquals(List.of("sail/run in app-wt"), reported);
    assertEquals("sail/run", git(tempDir.resolve("app-wt"), "branch", "--show-current"));
  }

  @Test
  void aDirectoryGitSaysIsNoWorkTreeGetsNoBranch() throws Exception {
    var main = Files.createDirectories(tempDir.resolve("app"));
    git(main, "init", "-q", "-b", "main");
    var unavailable = new ArrayList<String>();
    var listener =
        new DispatchOperations.Listener() {
          @Override
          public void branchUnavailable(String branch) {
            unavailable.add(branch);
          }
        };
    var config = TestDefinitions.map(Map.of("name", "acme"));

    var cut =
        new WorkBranch(new LocalGit(tempDir), listener)
            .checkout(
                "acme",
                config,
                WorkBranch.repos(config, "app/.git"),
                "sail/run",
                Optional.empty(),
                false);

    assertFalse(cut);
    assertEquals(List.of("sail/run"), unavailable);
  }

  @Test
  void aPathBranchesInTheDeepestRepoHoldingItAsTheFilesystemResolvesIt() {
    var config =
        TestDefinitions.map(
            Map.of(
                "name",
                "acme",
                "repos",
                List.of(
                    Map.of("url", "https://github.com/acme/web.git", "path", "web"),
                    Map.of("url", "https://github.com/acme/api.git", "path", "api"),
                    Map.of("url", "https://github.com/acme/ui.git", "path", "web/./ui"))));

    assertEquals("api", only(WorkBranch.repos(config, "web/../api")));
    assertEquals("web/./ui", only(WorkBranch.repos(config, "./web/ui/src")));
    assertEquals("docs", only(WorkBranch.repos(config, "web/../docs/")));
    assertEquals(3, WorkBranch.repos(config, "web/..").size());
  }

  private static String only(List<ProjectDefinition.Repo> repos) {
    assertEquals(1, repos.size(), repos.toString());
    return repos.getFirst().path();
  }

  /**
   * Runs the git a container would on this machine, with the container's workspace at {@code root}.
   */
  private record LocalGit(Path root) implements ShellExec {

    @Override
    public Result exec(List<String> command) throws IOException {
      return exec(command, null, null);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      var args =
          command.subList(command.indexOf("--") + 1, command.size()).stream()
              .map(
                  arg -> arg.startsWith(WORKSPACE) ? root + arg.substring(WORKSPACE.length()) : arg)
              .toList();
      try {
        return new ShellExecutor(false).exec(args);
      } catch (InterruptedException | TimeoutException e) {
        throw new IOException(e);
      }
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private static String git(Path dir, String... args) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var result = new ShellExecutor(false).exec(command);
    assertTrue(result.ok(), String.join(" ", command) + ": " + result.stderr());
    return result.stdout().strip();
  }
}
