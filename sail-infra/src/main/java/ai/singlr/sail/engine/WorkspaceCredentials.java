/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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

  /**
   * Run as root on a quiet container, with the tokens its build was given on stdin, one per line:
   * names what it still holds and exits 1, or exits 2 when it cannot tell, since an error is never
   * "nothing found". It holds (a) a token, when the bytes of any appear in any file of its root
   * filesystem (every top-level directory that is no mount point), grep reading the tokens as its
   * fixed-string patterns from stdin, never as an argument; (b) a git identity, when git reports a
   * user name or email at the system or the dev user's global level; (c) a key, when any {@code
   * authorized_keys} under a home has a line; (d) a gh login, when gh reports one.
   */
  static final String HELD =
      """
      set -u
      held=""
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      tokens=$(cat) || exit 2
      if [ -n "$tokens" ]; then
        set --
        for d in /* /.[!.]*; do
          [ -e "$d" ] && [ ! -L "$d" ] && ! mountpoint -q "$d" && set -- "$@" "$d"
        done
        found=$(printf '%s\n' "$tokens" | grep -r -l -F --devices=skip -f - -- "$@")
        case $? in
          0) held="$held a token in $(printf '%s ' $found)" ;;
          1) ;;
          *) echo "the search for the build's tokens failed" >&2; exit 2 ;;
        esac
      fi
      for key in user.name user.email; do
        for scope in system global; do
          if [ "$scope" = system ]; then git config --system --get "$key" >/dev/null
          else as_dev git config --global --get "$key" >/dev/null; fi
          case $? in
            0) held="$held $scope $key" ;;
            1) ;;
            *) echo "git could not read the $scope $key" >&2; exit 2 ;;
          esac
        done
      done
      for keys in /root/.ssh/authorized_keys /home/*/.ssh/authorized_keys; do
        [ -e "$keys" ] || continue
        grep -q '[^[:space:]]' "$keys"
        case $? in
          0) held="$held $keys" ;;
          1) ;;
          *) echo "could not read $keys" >&2; exit 2 ;;
        esac
      done
      if said=$(as_dev gh auth status 2>&1); then
        held="$held a gh login"
      else
        case $said in
          *"not logged in"*) ;;
          *) printf 'gh could not say whether it is logged in: %s\n' "$said" >&2; exit 2 ;;
        esac
      fi
      if [ -n "$held" ]; then echo "it still holds$held" >&2; exit 1; fi
      """;

  private static final Duration HELD_TIMEOUT = Duration.ofMinutes(10);

  private WorkspaceCredentials() {}

  /** Removes from {@code instance}, running, every credential of anyone ({@link #STRIP}). */
  static void strip(ShellExec shell, String instance)
      throws IOException, InterruptedException, TimeoutException {
    run(shell, instance, List.of(), "Could not remove its builder's credentials from " + instance);
  }

  /**
   * Refuses {@code template} when it still holds a credential ({@link #HELD}), the build's {@code
   * tokens} given to the check on stdin: what a build asks before it records or snapshots a version
   * (T1).
   */
  static void requireNone(ShellExec shell, String template, Collection<String> tokens)
      throws IOException, InterruptedException, TimeoutException {
    var held =
        shell.execWithInput(
            ContainerExec.asRoot(template, List.of("bash", "-c", HELD, "sail-held")),
            tokens.stream()
                .filter(token -> !token.isBlank())
                .map(token -> token + "\n")
                .reduce("", String::concat),
            HELD_TIMEOUT);
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
   * Run as root: waits until each host named after it resolves, for at most 30 s, so a {@code gh}
   * login, which asks its forge, does not run before a container just started has a resolver.
   */
  static final String RESOLVES =
      """
      for host in "$@"; do
        for _ in $(seq 1 300); do getent hosts "$host" >/dev/null && break; sleep 0.1; done
      done
      """;

  /**
   * Logs {@code gh} in for {@code owner}'s tokens of the GitHub hosts of {@code config}'s repos,
   * once each resolves ({@link #RESOLVES}).
   */
  static void logForgeIn(
      ShellExec shell, String instance, ProjectDefinition config, WorkspaceOwner owner)
      throws IOException, InterruptedException, TimeoutException {
    var repos = owner.tokenRepos(config.repos());
    var hosts = GitCredentials.githubHosts(repos);
    if (hosts.isEmpty()) {
      return;
    }
    var resolve = new ArrayList<>(List.of("bash", "-c", RESOLVES, "sail-resolve"));
    resolve.addAll(hosts);
    shell.exec(ContainerExec.asRoot(instance, resolve));
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
