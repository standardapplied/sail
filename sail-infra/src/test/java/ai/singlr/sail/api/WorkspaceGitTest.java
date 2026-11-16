/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceGit#LEFT} run by bash against real repositories cloned from a bare remote, at the
 * paths a project configures, a nested one among them: each thing a repo can hold that no remote
 * has is said, and nothing else is. Linux only, as the script runs only in a workspace.
 */
@EnabledOnOs(OS.LINUX)
class WorkspaceGitTest {

  private static final String BRANCH = "agent/a";
  private static final List<String> REPOS = List.of("app", "org/team/svc");

  @TempDir Path tempDir;
  private Path remote;
  private Path workspace;
  private Path app;
  private Path svc;

  @BeforeEach
  void twoReposClonedFromOneRemoteOneOfThemNested() throws Exception {
    remote = tempDir.resolve("remote.git");
    run(List.of("git", "init", "--quiet", "--bare", "-b", "main", remote.toString()));
    var seed = tempDir.resolve("seed");
    run(List.of("git", "init", "--quiet", "-b", "main", seed.toString()));
    git(seed, "commit", "--quiet", "--allow-empty", "-m", "seed");
    git(seed, "push", "--quiet", remote.toString(), "main");
    workspace = tempDir.resolve("workspace");
    app = workspace.resolve("app");
    svc = workspace.resolve("org/team/svc");
    run(List.of("git", "clone", "--quiet", remote.toString(), app.toString()));
    run(List.of("git", "clone", "--quiet", remote.toString(), svc.toString()));
  }

  @Test
  void cleanReposHoldNothing() throws Exception {
    assertEquals("", left(BRANCH, REPOS));
  }

  /** Blocker 1: a repo three directories deep is read like any other. */
  @Test
  void aCommitOnABranchNotCheckedOutInANestedRepoIsSaid() throws Exception {
    git(svc, "branch", "other");
    git(svc, "switch", "--quiet", "other");
    git(svc, "commit", "--quiet", "--allow-empty", "-m", "by hand");
    git(svc, "switch", "--quiet", "main");

    assertEquals("commits org/team/svc\n", left(BRANCH, REPOS));
  }

  /** Blocker 2: a stash, an untracked file and a commit only a tag holds are each said. */
  @Test
  void aStashAnUntrackedFileAndATagOnlyCommitAreEachSaid() throws Exception {
    Files.writeString(app.resolve("f"), "x\n");
    git(app, "add", "f");
    git(app, "stash", "--quiet");
    Files.writeString(svc.resolve("untracked"), "y\n");
    assertEquals("stash app\nchanges org/team/svc\n", left(BRANCH, REPOS));

    Files.delete(svc.resolve("untracked"));
    git(svc, "switch", "--quiet", "--detach");
    git(svc, "commit", "--quiet", "--allow-empty", "-m", "tagged");
    git(svc, "tag", "keep");
    git(svc, "switch", "--quiet", "main");
    assertEquals("stash app\ncommits org/team/svc\n", left(BRANCH, REPOS));
  }

  /** A commit only the checked-out, detached HEAD holds is said. */
  @Test
  void aDetachedCommitIsSaid() throws Exception {
    git(app, "switch", "--quiet", "--detach");
    git(app, "commit", "--quiet", "--allow-empty", "-m", "detached");

    assertEquals("commits app\n", left(BRANCH, REPOS));
  }

  /** A configured repo that is not there is said, never taken for one that holds nothing. */
  @Test
  void aConfiguredRepoThatIsNotThereIsSaid() throws Exception {
    assertEquals("missing web/site\n", left(BRANCH, List.of("app", "web/site")));
  }

  /**
   * The runs' branch is pushed once when it alone holds what the remote lacks, after which nothing
   * is said; a branch the forge deleted after its merge still counts as pushed (nothing is
   * fetched), and is not pushed again, which would bring it back.
   */
  @Test
  void theRunsBranchIsPushedAndAMergedAndDeletedOneCountsAsPushedAndIsNotPushedAgain()
      throws Exception {
    git(app, "switch", "--quiet", "-c", BRANCH);
    git(app, "commit", "--quiet", "--allow-empty", "-m", "built");

    assertEquals("", left(BRANCH, REPOS));
    assertEquals(
        git(app, "rev-parse", BRANCH),
        run(List.of("git", "-C", remote.toString(), "rev-parse", BRANCH)));

    git(remote, "branch", "--quiet", "-D", BRANCH);
    assertEquals("", left(BRANCH, REPOS));
    assertEquals(
        "",
        run(List.of("git", "-C", remote.toString(), "branch", "--list", BRANCH)),
        "the merged branch is not brought back");
  }

  /**
   * Ruling 6 of round 2: pushed means on {@code origin}, the remote the project clones from; a
   * commit only another remote has, one inside the workspace say, is said.
   */
  @Test
  void aCommitOnlyAnotherRemoteHasIsSaid() throws Exception {
    var backup = tempDir.resolve("backup.git");
    run(List.of("git", "init", "--quiet", "--bare", "-b", "main", backup.toString()));
    git(app, "remote", "add", "backup", backup.toString());
    git(app, "switch", "--quiet", "-c", "kept");
    git(app, "commit", "--quiet", "--allow-empty", "-m", "backed up");
    git(app, "push", "--quiet", "backup", "kept");

    assertEquals("commits app\n", left(BRANCH, REPOS));
  }

  /** A push that cannot reach its remote leaves the commits, which are said. */
  @Test
  void aPushThatCannotReachTheRemoteLeavesTheCommitsSaid() throws Exception {
    git(app, "switch", "--quiet", "-c", BRANCH);
    git(app, "commit", "--quiet", "--allow-empty", "-m", "built");
    git(app, "remote", "set-url", "origin", tempDir.resolve("gone.git").toString());

    assertEquals("commits app\n", left(BRANCH, REPOS));
  }

  /** A repo git cannot read fails the whole read, which keeps the workspace. */
  @Test
  void aRepoGitCannotReadFailsTheRead() throws Exception {
    var scratch = workspace.resolve("scratch");
    run(List.of("git", "init", "--quiet", scratch.toString()));

    var read = bash(List.of(BRANCH, "app", "scratch"));

    assertTrue(read.exit() != 0, read.output());
  }

  private String left(String branch, List<String> repos) throws Exception {
    var args = new ArrayList<String>();
    args.add(branch);
    args.addAll(repos);
    var read = bash(args);
    assertEquals(0, read.exit(), read.output());
    return read.output();
  }

  private record Ran(int exit, String output) {}

  private Ran bash(List<String> args) throws Exception {
    var command =
        new ArrayList<>(
            List.of("bash", "-c", WorkspaceGit.LEFT, "sail-left", workspace.toString()));
    command.addAll(args);
    return exec(command, false);
  }

  private static String git(Path repo, String... args) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    return run(command);
  }

  private static String run(List<String> command) throws Exception {
    var ran = exec(command, true);
    assertEquals(0, ran.exit(), String.join(" ", command) + ": " + ran.output());
    return ran.output();
  }

  /** {@code command} run here, git told who commits and none of this account's configuration. */
  private static Ran exec(List<String> command, boolean withErrors) throws Exception {
    var builder = new ProcessBuilder(command).redirectErrorStream(withErrors);
    if (!withErrors) {
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    }
    builder
        .environment()
        .putAll(
            Map.of(
                "GIT_AUTHOR_NAME", "it",
                "GIT_AUTHOR_EMAIL", "it@sail.invalid",
                "GIT_COMMITTER_NAME", "it",
                "GIT_COMMITTER_EMAIL", "it@sail.invalid",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_CONFIG_NOSYSTEM", "1",
                "LC_ALL", "C"));
    var process = builder.start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(90, TimeUnit.SECONDS), String.join(" ", command));
    return new Ran(process.exitValue(), output);
  }
}
