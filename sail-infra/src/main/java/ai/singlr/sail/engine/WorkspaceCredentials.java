/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * What a template or a workspace holds of a person: the dev user's git token store, {@code gh}'s
 * login, git identity and {@code authorized_keys}. A template is built with its builder's tokens
 * and left holding none of the four (T1); a workspace is given its owner's alone, and a re-own
 * takes the previous owner's away first (T2, T3).
 */
final class WorkspaceCredentials {

  static final String STORE = ContainerExec.DEV_HOME + "/.git-credentials";
  static final String GH_HOSTS = ContainerExec.DEV_HOME + "/.config/gh/hosts.yml";
  static final String KEYS = ContainerExec.DEV_HOME + "/.ssh/authorized_keys";

  /**
   * Run as root: removes the token store and {@code gh}'s login, unsets the dev user's git identity
   * and empties its {@code authorized_keys}, then, given a name ({@code $1}) and an email ({@code
   * $2}), sets them as its identity. Neither is ever part of the text.
   */
  static final String STRIP =
      """
      set -eu
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      rm -f /home/dev/.git-credentials /home/dev/.config/gh/hosts.yml
      for key in user.name user.email; do
        as_dev git config --global --unset-all "$key" || [ "$?" -eq 5 ]
      done
      if [ -e /home/dev/.ssh/authorized_keys ]; then : > /home/dev/.ssh/authorized_keys; fi
      if [ "$#" -eq 2 ]; then
        install -d -m 0700 -o dev -g dev /home/dev/.ssh
        as_dev git config --global user.name "$1"
        as_dev git config --global user.email "$2"
      fi
      """;

  /** Run as root: names each of the four a container still holds, failing when it holds one. */
  static final String HELD =
      """
      held=""
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      [ ! -e /home/dev/.git-credentials ] || held="$held .git-credentials"
      [ ! -e /home/dev/.config/gh/hosts.yml ] || held="$held gh's hosts.yml"
      if as_dev git config --global user.name >/dev/null; then held="$held user.name"; fi
      if as_dev git config --global user.email >/dev/null; then held="$held user.email"; fi
      [ ! -s /home/dev/.ssh/authorized_keys ] || held="$held authorized_keys"
      if [ -n "$held" ]; then echo "it still holds$held" >&2; exit 1; fi
      """;

  private WorkspaceCredentials() {}

  /** Removes from {@code instance}, running, every credential of anyone ({@link #STRIP}). */
  static void strip(ShellExec shell, String instance)
      throws IOException, InterruptedException, TimeoutException {
    run(shell, instance, List.of(), "Could not remove its builder's credentials from " + instance);
  }

  /**
   * Refuses {@code template} when it still holds any of the four ({@link #HELD}): what a build asks
   * before it records or snapshots a version (T1).
   */
  static void requireNone(ShellExec shell, String template)
      throws IOException, InterruptedException, TimeoutException {
    var held = shell.exec(ContainerExec.asRoot(template, List.of("bash", "-c", HELD, "sail-held")));
    if (!held.ok()) {
      throw new IOException(
          template
              + " was not snapshotted, since a template holds no one's credentials and "
              + held.reason().strip());
    }
  }

  /**
   * Gives {@code instance}, running, to {@code owner} before anything of theirs is fetched or
   * staged: every credential it held is removed, their name and email become its git identity,
   * their keys its {@code authorized_keys}, and their tokens for the hosts of {@code config}'s
   * repos its token store. {@code gh} is logged in later ({@link #logForgeIn}), once the network a
   * login asks is up.
   */
  static void give(ShellExec shell, String instance, ProjectDefinition config, WorkspaceOwner owner)
      throws IOException, InterruptedException, TimeoutException {
    run(
        shell,
        instance,
        List.of(owner.name(), owner.email()),
        "Could not make " + instance + " " + owner.handle() + "'s");
    ContainerFilePush.push(
        shell,
        instance,
        KEYS,
        owner.keys().stream().map(key -> key + "\n").reduce("", String::concat),
        List.of("--uid", ContainerExec.DEV_UID, "--gid", ContainerExec.DEV_GID, "--mode", "0600"));
    var repos = owner.tokenRepos(config.repos());
    ProjectApplier.writeCredentialStore(
        shell, instance, ProjectDefinition.USER, owner.tokensFor(repos), repos);
  }

  /**
   * Logs {@code gh} in for {@code owner}'s tokens of the GitHub hosts of {@code config}'s repos.
   */
  static void logForgeIn(
      ShellExec shell, String instance, ProjectDefinition config, WorkspaceOwner owner)
      throws IOException, InterruptedException, TimeoutException {
    var repos = owner.tokenRepos(config.repos());
    ForgeCliLogin.loginGitHub(
        shell, instance, ProjectDefinition.USER, owner.tokensFor(repos), repos);
  }

  /**
   * Takes from {@code instance}, running or stopped, the token store, {@code gh}'s login and the
   * keys of a person no longer on the roster, through its files alone.
   */
  static void takeBack(ShellExec shell, String instance)
      throws IOException, InterruptedException, TimeoutException {
    for (var file : List.of(STORE, GH_HOSTS)) {
      var deleted = shell.exec(List.of("incus", "file", "delete", instance + file));
      if (!deleted.ok() && !absent(deleted.stderr())) {
        throw new IOException(
            "Could not delete " + file + " in " + instance + ": " + deleted.stderr().strip());
      }
    }
    ContainerFilePush.push(
        shell,
        instance,
        KEYS,
        "",
        List.of("--uid", ContainerExec.DEV_UID, "--gid", ContainerExec.DEV_GID, "--mode", "0600"));
  }

  private static void run(ShellExec shell, String instance, List<String> args, String failure)
      throws IOException, InterruptedException, TimeoutException {
    var argv = new ArrayList<>(List.of("bash", "-c", STRIP, "sail-strip"));
    argv.addAll(args);
    var stripped = shell.exec(ContainerExec.asRoot(instance, argv));
    if (!stripped.ok()) {
      throw new IOException(failure + ": " + stripped.reason().strip());
    }
  }

  private static boolean absent(String stderr) {
    var said = stderr.toLowerCase(Locale.ROOT);
    return said.contains("not found") || said.contains("no such file");
  }
}
