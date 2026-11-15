/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * What a spec's workspace holds that nothing else does, read by git in its configured repos, as
 * {@link WorkBranch} branches in them: what decides whether deleting the workspace would lose
 * anything (R4).
 */
final class WorkspaceGit {

  /**
   * For each repository path after the root ({@code $1}) and the runs' branch ({@code $2}, empty
   * for none): the branch pushed once, within a minute, when it holds commits no remote has; then a
   * line for each thing the repository holds that a delete would lose, {@code missing}, {@code
   * changes} (anything {@code git status} shows, untracked files included), {@code stash} or {@code
   * commits} (on any branch, tag or the checked-out commit, that no remote has), each followed by
   * the path. Nothing is fetched. Any git command that fails fails the script.
   */
  static final String LEFT =
      """
      set -euo pipefail
      root=$1 branch=$2
      shift 2
      for repo in "$@"; do
        dir=$root/$repo
        if [ ! -e "$dir/.git" ]; then
          printf 'missing %s\\n' "$repo"
          continue
        fi
        if [ -n "$branch" ] && git -C "$dir" show-ref --verify --quiet "refs/heads/$branch"; then
          ahead=$(git -C "$dir" log --max-count=1 --format=%H "refs/heads/$branch" --not --remotes)
          if [ -n "$ahead" ]; then
            timeout 60 git -C "$dir" push --quiet origin "$branch" >/dev/null 2>&1 || true
          fi
        fi
        changes=$(git -C "$dir" status --porcelain --untracked-files=all)
        stash=$(git -C "$dir" stash list)
        commits=$(git -C "$dir" log --max-count=1 --format=%H HEAD --branches --tags --not --remotes)
        [ -z "$changes" ] || printf 'changes %s\\n' "$repo"
        [ -z "$stash" ] || printf 'stash %s\\n' "$repo"
        [ -z "$commits" ] || printf 'commits %s\\n' "$repo"
      done
      """;

  /** How long one read waits on its workspace, a push's minute included. */
  static final Duration TIMEOUT = Duration.ofMinutes(3);

  private static final Map<String, String> SAID =
      Map.of(
          "changes", "uncommitted changes",
          "stash", "a stash",
          "commits", "commits no remote has");

  private final ShellExec shell;

  WorkspaceGit(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * What deleting running workspace {@code instance} would lose in {@code repos}, after the runs'
   * {@code branch} is pushed: nothing, or each repo and what it holds. A read that fails, that does
   * not answer in time, or that says what it cannot mean, is itself a loss: the workspace is kept
   * on it, never deleted on a guess.
   */
  Optional<String> left(String instance, List<String> repos, String branch)
      throws InterruptedException {
    var command =
        new ArrayList<>(
            List.of("bash", "-c", LEFT, "sail-left", ContainerExec.DEV_WORKSPACE, branch));
    command.addAll(repos);
    ShellExec.Result read;
    try {
      read =
          shell.exec(
              ContainerExec.asDevUser(instance, null, Map.of("LC_ALL", "C"), command),
              null,
              TIMEOUT);
    } catch (IOException | TimeoutException unanswered) {
      return unread(unanswered.getMessage());
    }
    if (!read.ok()) {
      return unread(read.reason());
    }
    var held = new LinkedHashMap<String, List<String>>();
    for (var line : read.stdout().lines().toList()) {
      var at = line.indexOf(' ');
      var kind = at < 0 ? "" : line.substring(0, at);
      if (!kind.equals("missing") && !SAID.containsKey(kind)) {
        return unread(line);
      }
      held.computeIfAbsent(line.substring(at + 1), repo -> new ArrayList<>()).add(kind);
    }
    if (held.isEmpty()) {
      return Optional.empty();
    }
    var clauses = new ArrayList<String>();
    held.forEach(
        (repo, kinds) ->
            clauses.add(
                kinds.contains("missing")
                    ? repo + " is not in the workspace"
                    : repo
                        + " holds "
                        + String.join(" and ", kinds.stream().map(SAID::get).toList())));
    return Optional.of(String.join("; ", clauses));
  }

  private static Optional<String> unread(String why) {
    return Optional.of(
        "its repos could not be read: " + Strings.oneLine(Objects.toString(why, "")).strip());
  }
}
