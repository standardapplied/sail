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

/** What a spec's workspace holds that no remote does, as git reads its repos (R4). */
final class WorkspaceGit {

  /**
   * For each repo path after the root ({@code $1}): the runs' branch ({@code $2}) pushed once when
   * {@code origin} lacks its commits ({@code refused} when origin refuses); then, in every worktree
   * git lists and every submodule {@code foreach --recursive} reaches, a line per thing a delete
   * would lose, followed by the place: {@code changes}, {@code untracked}, a {@code stash}, and
   * {@code commits} origin lacks over every ref. A missing repo is {@code missing}. A git command
   * that fails, or a push that does not reach origin, fails the script. Not read: reflog-only
   * commits, ignored files, files outside the repos (W13).
   */
  static final String LEFT =
      """
      set -eEuo pipefail
      trap 'printf "git could not read %s\\n" "${at:-the workspace}" >&2' ERR
      root=$(cd "$1" && pwd -P)
      branch=$2
      shift 2
      read_place() {
        local label=${1#"$root"/} status stash commits changes untracked
        at=$label
        status=$(git -C "$1" status --porcelain --untracked-files=all)
        stash=$(git -C "$1" stash list)
        commits=$(git -C "$1" rev-list --max-count=1 --exclude=refs/stash --all --not "$2")
        changes=$(printf '%s\\n' "$status" | grep -v '^??' || true)
        untracked=$(printf '%s\\n' "$status" | grep '^??' || true)
        [ -z "$changes" ] || printf 'changes %s\\n' "$label"
        [ -z "$untracked" ] || printf 'untracked %s\\n' "$label"
        [ -z "$stash" ] || printf 'stash %s\\n' "$label"
        [ -z "$commits" ] || printf 'commits %s\\n' "$label"
      }
      for repo in "$@"; do
        dir=$root/$repo
        if [ ! -e "$dir/.git" ]; then
          printf 'missing %s\\n' "$repo"
          continue
        fi
        at=$repo
        if [ -n "$branch" ] && git -C "$dir" show-ref --verify --quiet "refs/heads/$branch"; then
          ahead=$(git -C "$dir" log --max-count=1 --format=%H "refs/heads/$branch" --not --remotes=origin)
          pushed=0
          [ -z "$ahead" ] || said=$(timeout 60 git -C "$dir" push --quiet origin "$branch" 2>&1) || pushed=$?
          if [ "$pushed" -eq 1 ]; then
            printf 'refused %s\\n' "$repo"
          elif [ "$pushed" -ne 0 ]; then
            printf '%s\\ncould not reach origin to push %s from %s\\n' "$said" "$branch" "$repo" >&2
            exit 3
          fi
        fi
        trees=$(git -C "$dir" worktree list --porcelain | sed -n 's/^worktree //p')
        while IFS= read -r tree <&3; do
          [ -d "$tree" ] || continue
          read_place "$tree" --remotes=origin
          subs=$(git -C "$tree" submodule --quiet foreach --recursive 'printf "%s/%s\\n" "$toplevel" "$sm_path"')
          while IFS= read -r sub <&4; do
            [ -z "$sub" ] || read_place "$sub" --remotes
          done 4<<< "$subs"
        done 3<<< "$trees"
      done
      """;

  /** A push of the runs' branch that did not reach {@code origin}. */
  static final class Unpushed extends IOException {
    Unpushed(String said) {
      super(said);
    }
  }

  /** How long one read waits on its workspace, a push's minute included. */
  static final Duration TIMEOUT = Duration.ofMinutes(3);

  private static final Map<String, String> SAID =
      Map.of(
          "refused", "a push origin refused",
          "changes", "uncommitted changes",
          "untracked", "untracked files",
          "stash", "a stash",
          "commits", "commits origin lacks");

  private final ShellExec shell;

  WorkspaceGit(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * What deleting running workspace {@code instance} would lose in {@code repos}, after {@code
   * branch} is pushed: nothing, or each place and what it holds; a read that fails throws.
   */
  Optional<String> left(String instance, List<String> repos, String branch)
      throws IOException, TimeoutException, InterruptedException {
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
      throw new IOException("Incus did not answer: " + unanswered.getMessage(), unanswered);
    }
    if (read.exitCode() == 3) {
      throw new Unpushed(unread(read.reason()).getMessage());
    }
    if (!read.ok()) {
      throw unread(read.reason());
    }
    var held = new LinkedHashMap<String, List<String>>();
    for (var line : read.stdout().lines().toList()) {
      var at = line.indexOf(' ');
      var kind = at < 0 ? "" : line.substring(0, at);
      if (!kind.equals("missing") && !SAID.containsKey(kind)) {
        throw unread("git said what Sail cannot read: " + line);
      }
      held.computeIfAbsent(line.substring(at + 1), place -> new ArrayList<>()).add(kind);
    }
    if (held.isEmpty()) {
      return Optional.empty();
    }
    var clauses = new ArrayList<String>();
    held.forEach(
        (place, kinds) ->
            clauses.add(
                kinds.contains("missing")
                    ? place + " is not in the workspace"
                    : place
                        + " holds "
                        + String.join(" and ", kinds.stream().map(SAID::get).toList())));
    return Optional.of(String.join("; ", clauses));
  }

  /** {@code said}'s last line, the cause in Sail's words, then what came before it, git's. */
  private static IOException unread(String said) {
    var lines = said.strip().lines().toList();
    var before = Strings.oneLine(String.join(" ", lines.subList(0, lines.size() - 1))).strip();
    return new IOException(lines.getLast() + (before.isEmpty() ? "" : ": " + before));
  }
}
