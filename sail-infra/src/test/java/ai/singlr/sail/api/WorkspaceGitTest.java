/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.Child;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    assertEquals("stash app\nuntracked org/team/svc\n", left(BRANCH, REPOS));
    Files.writeString(app.resolve("tracked"), "y\n");
    git(app, "add", "tracked");
    assertEquals("changes app\nstash app\nuntracked org/team/svc\n", left(BRANCH, REPOS));
    git(app, "rm", "--quiet", "--cached", "tracked");
    Files.delete(app.resolve("tracked"));

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

  /**
   * The proof's {@code kh2}: a branch pushed to a second remote inside the workspace, the local
   * branch then deleted, leaves the commit under {@code refs/remotes/backup/} alone, which the one
   * read over every ref still finds.
   */
  @Test
  void aCommitOnlyAnotherRemotesRefHoldsIsSaidOnceItsLocalBranchIsGone() throws Exception {
    var backup = tempDir.resolve("backup.git");
    run(List.of("git", "init", "--quiet", "--bare", "-b", "main", backup.toString()));
    git(app, "remote", "add", "backup", backup.toString());
    git(app, "switch", "--quiet", "-c", "side");
    git(app, "commit", "--quiet", "--allow-empty", "-m", "backed up work");
    git(app, "push", "--quiet", "backup", "side");
    git(app, "switch", "--quiet", "-");
    git(app, "branch", "--quiet", "-D", "side");

    assertEquals("commits app\n", left(BRANCH, REPOS));
  }

  /**
   * Rule B of round 4: a push that cannot reach its remote fails the read, saying why, since it
   * says nothing of what the repo holds.
   */
  @Test
  void aPushThatCannotReachTheRemoteFailsTheReadSayingWhy() throws Exception {
    git(app, "switch", "--quiet", "-c", BRANCH);
    git(app, "commit", "--quiet", "--allow-empty", "-m", "built");
    git(app, "remote", "set-url", "origin", tempDir.resolve("gone.git").toString());

    var read = exec(command(List.of(BRANCH, "app")), true);

    assertTrue(read.exit() != 0, read.output());
    assertTrue(
        read.output().contains("fatal:")
            && read.output().strip().endsWith("could not reach origin to push agent/a from app"),
        read.output());
  }

  /** A push origin answers and refuses is what the repo holds: said, with the commits it lacks. */
  @Test
  void aPushOriginRefusesIsSaidWithTheCommitsItLacks() throws Exception {
    git(app, "switch", "--quiet", "-c", BRANCH);
    git(app, "commit", "--quiet", "--allow-empty", "-m", "built");
    git(app, "push", "--quiet", "origin", BRANCH);
    git(svc, "fetch", "--quiet", "origin");
    git(svc, "switch", "--quiet", "-c", BRANCH, "origin/" + BRANCH);
    git(svc, "commit", "--quiet", "--allow-empty", "-m", "theirs");
    git(svc, "push", "--quiet", "origin", BRANCH);
    git(app, "commit", "--quiet", "--allow-empty", "--amend", "-m", "rewritten");

    assertEquals("refused app\ncommits app\n", left(BRANCH, List.of("app")));
  }

  /**
   * S-B1: a linked worktree's edits are read where it lives, under a directory the repo ignores
   * ({@code .claude/worktrees/}, where a harness puts one) or outside the configured path; one
   * whose directory is gone holds nothing left to read.
   */
  @Test
  void aLinkedWorktreesEditsAreSaidWhereverItLives() throws Exception {
    Files.writeString(app.resolve(".git/info/exclude"), ".claude/\n");
    git(app, "worktree", "add", "--quiet", "-b", "wt", ".claude/worktrees/w");
    var inside = app.resolve(".claude/worktrees/w");
    Files.writeString(inside.resolve("f"), "x\n");
    git(inside, "add", "f");
    Files.writeString(inside.resolve("new.java"), "y\n");
    var outside = tempDir.resolve("outside");
    git(svc, "worktree", "add", "--quiet", "-b", "out", outside.toString());
    Files.writeString(outside.resolve("draft"), "z\n");

    assertEquals(
        "changes app/.claude/worktrees/w\n"
            + "untracked app/.claude/worktrees/w\n"
            + "untracked "
            + outside.toRealPath()
            + "\n",
        left(BRANCH, REPOS));

    run(List.of("rm", "-rf", inside.toString(), outside.toString()));
    assertEquals("", left(BRANCH, REPOS));
  }

  /**
   * S-M3: a submodule's commits its own origin lacks are said once the superproject's bump of it is
   * pushed, as are its edits, a submodule's submodule included.
   */
  @Test
  void aSubmodulesUnpushedCommitsAreSaidOnceTheSuperprojectsBumpIsPushed() throws Exception {
    var lib = tempDir.resolve("lib.git");
    run(List.of("git", "init", "--quiet", "--bare", "-b", "main", lib.toString()));
    var seed = tempDir.resolve("lib-seed");
    run(List.of("git", "clone", "--quiet", lib.toString(), seed.toString()));
    git(seed, "commit", "--quiet", "--allow-empty", "-m", "lib");
    git(seed, "push", "--quiet", "origin", "main");
    git(
        seed,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        lib.toString(),
        "inner");
    git(seed, "commit", "--quiet", "-m", "inner");
    git(seed, "push", "--quiet", "origin", "main");
    git(
        app,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        lib.toString(),
        "lib");
    git(
        app,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "update",
        "--quiet",
        "--init",
        "--recursive");
    git(app, "commit", "--quiet", "-m", "lib");
    git(app, "push", "--quiet", "origin", "main");
    assertEquals("", left(BRANCH, REPOS));

    var sub = app.resolve("lib");
    git(sub, "switch", "--quiet", "-c", "work");
    git(sub, "commit", "--quiet", "--allow-empty", "-m", "unpushed");
    git(app, "add", "lib");
    git(app, "commit", "--quiet", "-m", "bump");
    git(app, "push", "--quiet", "origin", "main");
    Files.writeString(sub.resolve("inner/draft"), "x\n");

    assertEquals(
        "changes app\nchanges app/lib\ncommits app/lib\nuntracked app/lib/inner\n",
        left(BRANCH, REPOS));
  }

  /** A worktree, or a submodule, git cannot read fails the whole read, which decides nothing. */
  @Test
  void aWorktreeGitCannotReadFailsTheRead() throws Exception {
    git(app, "worktree", "add", "--quiet", "-b", "wt", tempDir.resolve("tree").toString());
    Files.writeString(tempDir.resolve("tree/.git"), "gitdir: " + tempDir.resolve("nowhere") + "\n");

    var read = exec(command(List.of(BRANCH, "app")), true);

    assertTrue(read.exit() != 0, read.output());
    assertTrue(
        read.output()
            .strip()
            .endsWith("git could not read " + tempDir.resolve("tree").toRealPath()),
        read.output());
  }

  /**
   * Minor F-m3: inside a submodule, pushed means on any of its own remotes, whatever its name; the
   * repo's own commits still count only on {@code origin}.
   */
  @Test
  void aSubmoduleWhoseRemoteIsNotOriginIsPushedOnIt() throws Exception {
    var lib = tempDir.resolve("lib.git");
    run(List.of("git", "init", "--quiet", "--bare", "-b", "main", lib.toString()));
    var seed = tempDir.resolve("lib-seed");
    run(List.of("git", "clone", "--quiet", lib.toString(), seed.toString()));
    git(seed, "commit", "--quiet", "--allow-empty", "-m", "lib");
    git(seed, "push", "--quiet", "origin", "main");
    git(
        app,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        lib.toString(),
        "lib");
    git(app, "commit", "--quiet", "-m", "lib");
    git(app, "push", "--quiet", "origin", "main");
    var sub = app.resolve("lib");
    git(sub, "remote", "rename", "origin", "upstream");
    git(sub, "commit", "--quiet", "--allow-empty", "-m", "pushed upstream");
    git(sub, "push", "--quiet", "upstream", "HEAD:main");
    git(app, "add", "lib");
    git(app, "commit", "--quiet", "-m", "bump");
    git(app, "push", "--quiet", "origin", "main");
    assertEquals("", left(BRANCH, REPOS));

    git(sub, "commit", "--quiet", "--allow-empty", "-m", "nowhere");
    git(app, "add", "lib");
    git(app, "commit", "--quiet", "-m", "bump again");
    git(app, "push", "--quiet", "origin", "main");
    assertEquals("commits app/lib\n", left(BRANCH, REPOS));
  }

  /**
   * A repo git cannot read fails the whole read, which keeps the workspace; one with no commit yet
   * reads as holding what its tree holds.
   */
  @Test
  void aRepoGitCannotReadFailsTheReadAndOneWithNoCommitReadsItsTree() throws Exception {
    var empty = workspace.resolve("empty");
    run(List.of("git", "init", "--quiet", empty.toString()));
    Files.writeString(empty.resolve("draft"), "x\n");
    assertEquals("untracked empty\n", left(BRANCH, List.of("app", "empty")));

    var broken = workspace.resolve("broken");
    Files.createDirectories(broken);
    Files.writeString(broken.resolve(".git"), "not a repository\n");
    var read = bash(List.of(BRANCH, "app", "broken"));

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
    return exec(command(args), false);
  }

  private List<String> command(List<String> args) {
    var command =
        new ArrayList<>(
            List.of("bash", "-c", WorkspaceGit.LEFT, "sail-left", workspace.toString()));
    command.addAll(args);
    return command;
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
    var ran = Child.run(builder, "", Duration.ofSeconds(90));
    return new Ran(ran.exit(), ran.stdout());
  }
}
