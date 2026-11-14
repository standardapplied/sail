/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.RunPlace;
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
   * Enters a run into its spec's workspace {@code instance}: the one branch rule for a workspace
   * made for the run and one reused (R11). For each target repo that is a work tree, with B the
   * run's {@code branch}: a non-restart build whose B is already there, locally or on the remote,
   * is refused naming {@code redispatch}; a repo on B is left as it is, uncommitted work and all; a
   * repo elsewhere with uncommitted work is refused naming where it is, since in a spec's own
   * workspace that work is the spec's, never carried onto B or discarded; a clean one is put on B,
   * the local one, else the remote's, else, for a build only, a new one from the latest base. A
   * lane other than a build with no B anywhere stays where it is, and so does every repo of a lane
   * with no B but a build, which with no B ({@code agent.auto_branch: false}) puts each repo on its
   * base branch, tracking the remote. Answers whether a repo is on B now.
   */
  public boolean enter(
      RunPlace.SpecWorkspace place,
      List<ProjectDefinition.Repo> targetRepos,
      String branch,
      boolean build,
      boolean restart,
      Optional<Remedy.Command> redispatch) {
    var instance = place.instance();
    var entered = false;
    for (var repo : targetRepos) {
      var repoDir = "/home/" + ProjectDefinition.USER + "/workspace/" + repo.path();
      if (!insideWorkTree(instance, repoDir)) {
        continue;
      }
      var named = Strings.isNotBlank(branch);
      var local = named && hasRef(instance, repoDir, "refs/heads/" + branch);
      var remote = named && !local && onRemote(instance, repoDir, branch);
      if (build && !restart && (local || remote)) {
        RestartResolution.branchCheckoutArgs(repoDir, branch, true, false, redispatch);
      }
      var base = build ? baseBranch(instance, repoDir, repo) : "";
      var target = named ? branch : base;
      if (Strings.isBlank(target) || (named && !local && !remote && !build)) {
        continue;
      }
      var on = git(instance, repoDir, "rev-parse", "--abbrev-ref", "HEAD");
      if (on.ok() && target.equals(on.stdout().strip())) {
        listener.branchReady(target, repo.path(), true);
        entered = entered || named;
        continue;
      }
      requireClean(place, repoDir, repo, target, on);
      var existing = !named || local || remote;
      var moved =
          existing
              ? git(instance, repoDir, "switch", "--quiet", target)
              : exec(
                  ContainerExec.asDevUser(
                      instance,
                      RestartResolution.freshBranchArgs(
                          repoDir, branch, base, fetchLatestBase(instance, repoDir, base))));
      if (!moved.ok()) {
        throw new ApiException(
            ErrorCode.BRANCH_CREATE_FAILED,
            "Failed to put repo '"
                + repo.path()
                + "' on branch '"
                + target
                + "' in "
                + instance
                + ": "
                + moved.reason());
      }
      listener.branchReady(target, repo.path(), existing);
      entered = entered || named;
    }
    if (Strings.isNotBlank(branch) && build && !entered) {
      listener.branchUnavailable(branch);
    }
    return entered;
  }

  /**
   * Refuses to move a repo off where it is, {@code on}, while it holds uncommitted work: in a
   * spec's own workspace that work is the spec's, so it is never carried onto {@code target} or
   * discarded.
   */
  private void requireClean(
      RunPlace.SpecWorkspace place,
      String repoDir,
      ProjectDefinition.Repo repo,
      String target,
      ShellExec.Result on) {
    var instance = place.instance();
    var dirty = git(instance, repoDir, "status", "--porcelain");
    if (dirty.ok() && dirty.stdout().isBlank()) {
      return;
    }
    var where = on.ok() ? on.stdout().strip() : "an unknown branch";
    throw new ApiException(
        ErrorCode.BRANCH_CREATE_FAILED,
        "Repo '"
            + repo.path()
            + "' in "
            + instance
            + " is on "
            + ("HEAD".equals(where) ? "a detached HEAD" : "branch '" + where + "'")
            + (dirty.ok()
                ? " with uncommitted work, which is never carried onto '"
                    + target
                    + "' or discarded."
                : ", and whether it holds uncommitted work could not be read: " + dirty.reason()),
        Remedy.of(
            "Commit or stash it in the workspace (",
            Remedy.sail("project workspace shell", place.project(), place.workspace()),
            "), then run it again."));
  }

  /** Whether {@code ref} names a commit in {@code repoDir}. */
  private boolean hasRef(String instance, String repoDir, String ref) {
    return git(instance, repoDir, "rev-parse", "--verify", "--quiet", ref).ok();
  }

  /**
   * Whether origin has {@code branch}, asked of origin itself (best-effort: a repo that cannot
   * reach it answers from what it last fetched).
   */
  private boolean onRemote(String instance, String repoDir, String branch) {
    git(instance, repoDir, "fetch", "--quiet", "origin", branch);
    return hasRef(instance, repoDir, "refs/remotes/origin/" + branch);
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
