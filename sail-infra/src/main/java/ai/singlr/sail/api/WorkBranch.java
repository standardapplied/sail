/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Cuts a run's work branch in the repos it works in, and reports each to the listener: the one
 * place a dispatch and an ad-hoc {@code agent run} branch, so both branch in the same repos, off
 * the same base, and say so in the same words.
 */
public final class WorkBranch {

  private final ShellExec shell;
  private final DispatchOperations.Listener listener;

  public WorkBranch(ShellExec shell, DispatchOperations.Listener listener) {
    this.shell = shell;
    this.listener = listener;
  }

  /**
   * The repos a run started in {@code path} branches in: the deepest of the project's repos that
   * holds {@code path}, or {@code path} itself when no configured repo does, or every repo of the
   * project when there is no path. Paths compare as the filesystem resolves them, so {@code
   * web/../api} and {@code ./api/src} branch in {@code api}, where the agent starts.
   */
  public static List<ProjectDefinition.Repo> repos(ProjectDefinition config, String path) {
    var repos = Objects.requireNonNullElse(config.repos(), List.<ProjectDefinition.Repo>of());
    var requested = Path.of(workspacePath(path));
    if (requested.toString().isEmpty()) {
      return repos;
    }
    return List.of(
        repos.stream()
            .filter(repo -> requested.startsWith(Path.of(repo.path()).normalize()))
            .max(Comparator.comparingInt(repo -> Path.of(repo.path()).normalize().getNameCount()))
            .orElse(new ProjectDefinition.Repo(null, requested.toString(), null)));
  }

  /**
   * {@code path} as a normalized path relative to the workspace, empty for the workspace itself.
   *
   * @throws IllegalArgumentException when {@code path} leaves the workspace
   */
  public static String workspacePath(String path) {
    if (Strings.isBlank(path)) {
      return "";
    }
    var normalized = Path.of(path).normalize();
    if (normalized.isAbsolute() || normalized.startsWith("..")) {
      throw new IllegalArgumentException(
          "--path '" + path + "' leaves the workspace. Name a directory inside it.");
    }
    return normalized.toString();
  }

  /**
   * Checks out the work branch in every target repo that exists in the container: a fresh branch
   * with {@code checkout -b}, or — on a restart — a forced {@code checkout -f} onto the existing
   * branch so re-dispatch lands on the prior work even over a dirty tree the previous run left. A
   * collision on a non-restart dispatch fails loud, naming {@code redispatch}, the command that
   * dispatches the spec onto the branch; a run with no spec to re-dispatch is told to delete it.
   */
  public boolean checkout(
      String project,
      ProjectDefinition config,
      List<ProjectDefinition.Repo> targetRepos,
      String branch,
      Optional<Remedy.Command> redispatch,
      boolean restart) {
    if (Strings.isBlank(branch)) {
      return false;
    }
    var created = false;
    for (var repo : targetRepos) {
      var repoDir = "/home/" + ProjectDefinition.USER + "/workspace/" + repo.path();
      if (!insideWorkTree(project, repoDir)) {
        continue;
      }
      var branchExists =
          exec(ContainerExec.asDevUser(
                  project,
                  List.of(
                      "git",
                      "-C",
                      repoDir,
                      "rev-parse",
                      "--verify",
                      "--quiet",
                      "refs/heads/" + branch)))
              .ok();
      List<String> checkoutArgs;
      if (branchExists) {
        checkoutArgs =
            RestartResolution.branchCheckoutArgs(repoDir, branch, true, restart, redispatch);
      } else {
        var base = baseBranch(project, repoDir, repo);
        checkoutArgs =
            RestartResolution.freshBranchArgs(
                repoDir, branch, base, fetchLatestBase(project, repoDir, base));
      }
      var result = exec(ContainerExec.asDevUser(project, checkoutArgs));
      if (!result.ok()) {
        throw new ApiException(
            ErrorCode.BRANCH_CREATE_FAILED,
            "Failed to "
                + (branchExists ? "check out" : "create")
                + " branch '"
                + branch
                + "' in repo '"
                + repo.path()
                + "': "
                + result.reason());
      }
      listener.branchReady(branch, repo.path(), branchExists);
      created = true;
    }
    if (!created) {
      listener.branchUnavailable(branch);
    }
    return created;
  }

  /**
   * Whether {@link #checkout} would create {@code branch}: a target repo in the container is a work
   * tree that does not have it yet. Read only, on the shell's observer, so a dry run can say it.
   */
  public boolean wouldCreate(
      String project, List<ProjectDefinition.Repo> targetRepos, String branch) {
    if (Strings.isBlank(branch)) {
      return false;
    }
    var reads = new WorkBranch(shell.observer(), listener);
    for (var repo : targetRepos) {
      var repoDir = "/home/" + ProjectDefinition.USER + "/workspace/" + repo.path();
      if (reads.insideWorkTree(project, repoDir)
          && !reads
              .exec(
                  ContainerExec.asDevUser(
                      project,
                      List.of(
                          "git",
                          "-C",
                          repoDir,
                          "rev-parse",
                          "--verify",
                          "--quiet",
                          "refs/heads/" + branch)))
              .ok()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Puts every target repo that exists in the container back on {@code branch}, which a build
   * already made, as a build that goes on after its question needs. A repo still on the branch is
   * left as that build left it, uncommitted work and all. A repo another run moved is checked out
   * only when its tree is clean: what is uncommitted there is that run's, and is never carried onto
   * this branch or discarded, so the resume fails loud instead.
   */
  public void resume(String project, List<ProjectDefinition.Repo> targetRepos, String branch) {
    if (Strings.isBlank(branch)) {
      return;
    }
    for (var repo : targetRepos) {
      var repoDir = "/home/" + ProjectDefinition.USER + "/workspace/" + repo.path();
      if (!insideWorkTree(project, repoDir)) {
        continue;
      }
      var on = git(project, repoDir, "rev-parse", "--abbrev-ref", "HEAD");
      if (on.ok() && branch.equals(on.stdout().strip())) {
        continue;
      }
      var dirty = git(project, repoDir, "status", "--porcelain");
      if (!dirty.ok()) {
        throw resumeRefused(branch, repo, dirty.reason());
      }
      if (!dirty.stdout().isBlank()) {
        throw resumeRefused(
            branch,
            repo,
            "it holds uncommitted work of "
                + on.stdout().strip()
                + ", which is never carried onto "
                + branch);
      }
      var checkedOut = git(project, repoDir, "checkout", branch, "--");
      if (!checkedOut.ok()) {
        throw resumeRefused(branch, repo, checkedOut.reason());
      }
    }
  }

  private static ApiException resumeRefused(
      String branch, ProjectDefinition.Repo repo, String why) {
    return new ApiException(
        ErrorCode.BRANCH_CREATE_FAILED,
        "Failed to check out branch '"
            + branch
            + "' in repo '"
            + repo.path()
            + "' to resume its build: "
            + why);
  }

  private ShellExec.Result git(String project, String repoDir, String... args) {
    var command = new ArrayList<>(List.of("git", "-C", repoDir));
    command.addAll(List.of(args));
    return exec(ContainerExec.asDevUser(project, command));
  }

  /**
   * Whether {@code repoDir} lies in a git work tree. Git answers rather than a {@code .git}
   * directory, which a linked worktree or a submodule holds as a file.
   */
  private boolean insideWorkTree(String project, String repoDir) {
    var probe =
        exec(
            ContainerExec.asDevUser(
                project, List.of("git", "-C", repoDir, "rev-parse", "--is-inside-work-tree")));
    return probe.ok() && "true".equals(probe.stdout().strip());
  }

  /**
   * The mainline a fresh work branch forks from: the repo's configured branch when {@code
   * definition} pins one, else origin's default branch (the clone's {@code origin/HEAD}), else
   * blank. Deriving it from the repo config or the remote default — never the current {@code HEAD}
   * — keeps a new branch off the mainline even when a prior dispatch left the checkout on an
   * earlier work branch.
   */
  private String baseBranch(String project, String repoDir, ProjectDefinition.Repo repo) {
    if (Strings.isNotBlank(repo.branch())) {
      return repo.branch();
    }
    var result =
        exec(
            ContainerExec.asDevUser(
                project,
                List.of("git", "-C", repoDir, "rev-parse", "--abbrev-ref", "origin/HEAD")));
    if (!result.ok()) {
      return "";
    }
    var ref = result.stdout().trim();
    return ref.startsWith("origin/") ? ref.substring("origin/".length()) : ref;
  }

  /**
   * Fetches {@code base} from origin so a fresh work branch forks from the current upstream tip
   * rather than a stale local checkout. Best-effort: returns true only when {@code origin/<base>}
   * is available to branch from afterwards, so an offline box, a repo with no {@code origin}, or a
   * detached base falls back to the local {@code HEAD} instead of failing the dispatch.
   */
  private boolean fetchLatestBase(String project, String repoDir, String base) {
    if (Strings.isBlank(base) || "HEAD".equals(base)) {
      return false;
    }
    exec(
        ContainerExec.asDevUser(
            project, List.of("git", "-C", repoDir, "fetch", "--quiet", "origin", base)));
    return exec(ContainerExec.asDevUser(
            project,
            List.of(
                "git",
                "-C",
                repoDir,
                "rev-parse",
                "--verify",
                "--quiet",
                "refs/remotes/origin/" + base)))
        .ok();
  }

  private ShellExec.Result exec(List<String> command) {
    try {
      return shell.exec(command);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed.", e);
    }
  }
}
