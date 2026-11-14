/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.RunPlace;
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
  void aBranchIsSaidToBeCreatedOnlyWhereARepoLacksItAndNeverWithNoBranch() throws Exception {
    var main = Files.createDirectories(tempDir.resolve("app"));
    git(main, "init", "-q", "-b", "main");
    git(
        main,
        "-c",
        "user.email=t@t",
        "-c",
        "user.name=t",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "c");
    var config = TestDefinitions.map(Map.of("name", "acme"));
    var reads = new WorkBranch(new LocalGit(tempDir), DispatchOperations.Listener.NONE);
    var repos = WorkBranch.repos(config, "app");

    assertTrue(reads.wouldCreate("acme", repos, "sail/new"));
    assertFalse(reads.wouldCreate("acme", repos, "main"), "the repo has it");
    assertFalse(reads.wouldCreate("acme", repos, null), "no work branch is none to create");
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

  private static final RunPlace.SpecWorkspace PLACE =
      (RunPlace.SpecWorkspace) RunPlace.of("acme", "a");

  /**
   * A spec's fresh workspace as PREPARE leaves it: repo {@code app} cloned from a bare origin
   * holding {@code main} and every branch of {@code remote}, fetched, and detached at {@code
   * origin/main}.
   */
  private List<ProjectDefinition.Repo> freshWorkspace(String... remote) throws Exception {
    var origin = Files.createDirectories(tempDir.resolve("origin.git"));
    git(origin, "init", "-q", "--bare", "-b", "main");
    var seed = Files.createDirectories(tempDir.resolve("seed"));
    git(seed, "init", "-q", "-b", "main");
    commit(seed, "first");
    git(seed, "remote", "add", "origin", origin.toString());
    git(seed, "push", "-q", "origin", "main");
    for (var branch : remote) {
      git(seed, "switch", "-q", "-c", branch);
      commit(seed, "on " + branch);
      git(seed, "push", "-q", "origin", branch);
      git(seed, "switch", "-q", "main");
    }
    git(tempDir, "clone", "-q", origin.toString(), tempDir.resolve("app").toString());
    git(tempDir.resolve("app"), "switch", "-q", "--detach", "origin/main");
    return List.of(new ProjectDefinition.Repo(origin.toString(), "app", null));
  }

  private static void commit(Path repo, String message) throws Exception {
    git(
        repo,
        "-c",
        "user.email=t@t",
        "-c",
        "user.name=t",
        "-c",
        "commit.gpgsign=false",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        message);
  }

  private WorkBranch enter() {
    return new WorkBranch(new LocalGit(tempDir), DispatchOperations.Listener.NONE);
  }

  private String head() throws Exception {
    return git(tempDir.resolve("app"), "rev-parse", "--abbrev-ref", "HEAD");
  }

  @Test
  void aBuildEntersAFreshWorkspaceOnANewBranchFromTheLatestBase() throws Exception {
    var repos = freshWorkspace();

    assertTrue(enter().enter(PLACE, repos, "agent/a", true, false, Optional.empty()));

    assertEquals("agent/a", head());
    assertEquals(
        git(tempDir.resolve("app"), "rev-parse", "origin/main"),
        git(tempDir.resolve("app"), "rev-parse", "HEAD"));
  }

  @Test
  void aRoomTurnOnASpecNeverDispatchedLeavesTheReposDetachedAndMakesNoBranch() throws Exception {
    var repos = freshWorkspace();

    assertFalse(enter().enter(PLACE, repos, "", false, true, Optional.empty()));
    assertFalse(enter().enter(PLACE, repos, "agent/a", false, true, Optional.empty()));

    assertEquals("HEAD", head(), "a non-build lane with no branch anywhere stays where it is");
    assertEquals("", git(tempDir.resolve("app"), "branch", "--list", "agent/a"));
  }

  @Test
  void aBuildThatIsNoRestartIsRefusedWhenTheRemoteHasItsBranchAndNothingIsCheckedOut()
      throws Exception {
    var repos = freshWorkspace("agent/a");
    var redispatch = Optional.of(RestartResolution.redispatch("acme", "a"));

    var refused =
        assertThrows(
            ApiException.class,
            () -> enter().enter(PLACE, repos, "agent/a", true, false, redispatch));

    assertEquals("Branch 'agent/a' already exists.", refused.getMessage());
    assertEquals(
        "To dispatch onto it, run sail spec dispatch -p acme --spec a --restart, or delete it"
            + " first.",
        refused.remedy().plain());
    assertEquals("HEAD", head());
  }

  @Test
  void aRestartOntoABranchOnlyTheRemoteHasTracksIt() throws Exception {
    var repos = freshWorkspace("agent/a");

    assertTrue(enter().enter(PLACE, repos, "agent/a", true, true, Optional.empty()));

    assertEquals("agent/a", head());
    assertEquals(
        "origin/agent/a",
        git(tempDir.resolve("app"), "rev-parse", "--abbrev-ref", "agent/a@{upstream}"));
  }

  @Test
  void aRestartFindsItsUncommittedWorkOnTheBranchAndKeepsIt() throws Exception {
    var repos = freshWorkspace();
    var app = tempDir.resolve("app");
    git(app, "switch", "-q", "-c", "agent/a");
    Files.writeString(app.resolve("work.txt"), "half done");

    assertTrue(enter().enter(PLACE, repos, "agent/a", true, true, Optional.empty()));

    assertEquals("agent/a", head());
    assertEquals("half done", Files.readString(app.resolve("work.txt")));
    assertEquals("?? work.txt", git(app, "status", "--porcelain"));
  }

  @Test
  void aRepoElsewhereWithUncommittedWorkIsRefusedAndKeepsIt() throws Exception {
    var repos = freshWorkspace("agent/a");
    var app = tempDir.resolve("app");
    git(app, "switch", "-q", "-c", "other");
    Files.writeString(app.resolve("work.txt"), "the spec's");

    var refused =
        assertThrows(
            ApiException.class,
            () -> enter().enter(PLACE, repos, "agent/a", true, true, Optional.empty()));

    assertEquals(
        "Repo 'app' in acme-a is on branch 'other' with uncommitted work, which is never carried"
            + " onto 'agent/a' or discarded.",
        refused.getMessage());
    assertEquals(
        "Commit or stash it in the workspace (sail project workspace shell acme a), then run it"
            + " again.",
        refused.remedy().plain());
    assertEquals("other", head());
    assertEquals("the spec's", Files.readString(app.resolve("work.txt")));
  }

  @Test
  void aBuildWithNoBranchPutsEachRepoOnItsBaseTrackingTheRemote() throws Exception {
    var repos = freshWorkspace();

    assertFalse(enter().enter(PLACE, repos, null, true, false, Optional.empty()));

    assertEquals("main", head());
    assertEquals(
        "origin/main", git(tempDir.resolve("app"), "rev-parse", "--abbrev-ref", "main@{upstream}"));
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
