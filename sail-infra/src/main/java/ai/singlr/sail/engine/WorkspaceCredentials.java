/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** A template holds no one's credentials (T1); a workspace holds its owner's alone (T2, T3). */
final class WorkspaceCredentials {

  static final String STORE = ContainerExec.DEV_HOME + "/.git-credentials";
  static final String GH_HOSTS = ContainerExec.DEV_HOME + "/.config/gh/hosts.yml";
  static final String KEYS = ContainerExec.DEV_HOME + "/.ssh/authorized_keys";

  /** Every give runs it whole, so nothing of a previous owner survives a re-own or a restart. */
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
   * Searches by content, not by a list of paths, because a token can hide where no list names it;
   * exits 2, never 0, when a search cannot tell. The tokens arrive on stdin so no argv holds one.
   */
  static final String HELD =
      """
      set -u
      held=""
      add() { held="${held:+$held, }$1"; }
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      tokens=$(cat) || exit 2
      if [ -n "$tokens" ]; then
        set --
        for d in /* /.[!.]*; do
          [ -e "$d" ] && [ ! -L "$d" ] && ! mountpoint -q "$d" && set -- "$@" "$d"
        done
        found=$(printf '%s\n' "$tokens" | grep -r -l -F --devices=skip -f - -- "$@")
        case $? in
          0) add "a token in $(echo $found)" ;;
          1) ;;
          *) echo "the search for the build's tokens failed" >&2; exit 2 ;;
        esac
      fi
      for key in user.name user.email; do
        for scope in system global; do
          if [ "$scope" = system ]; then git config --system --get "$key" >/dev/null
          else as_dev git config --global --get "$key" >/dev/null; fi
          case $? in
            0) add "a $scope git $key" ;;
            1) ;;
            *) echo "git could not read the $scope $key" >&2; exit 2 ;;
          esac
        done
      done
      for keys in /root/.ssh/authorized_keys /home/*/.ssh/authorized_keys; do
        [ -e "$keys" ] || continue
        grep -q '[^[:space:]]' "$keys"
        case $? in
          0) add "a key in $keys" ;;
          1) ;;
          *) echo "could not read $keys" >&2; exit 2 ;;
        esac
      done
      if said=$(as_dev gh auth status 2>&1); then
        add "a gh login"
      else
        case $said in
          *"not logged in"*) ;;
          *) printf 'gh could not say whether it is logged in: %s\n' "$said" >&2; exit 2 ;;
        esac
      fi
      if [ -n "$held" ]; then echo "$held" >&2; exit 1; fi
      """;

  private static final Duration HELD_TIMEOUT = Duration.ofMinutes(10);

  private WorkspaceCredentials() {}

  static void strip(ShellExec shell, String instance)
      throws IOException, InterruptedException, TimeoutException {
    run(shell, instance, List.of(), "Could not remove its builder's credentials from " + instance);
  }

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
    if (held.exitCode() == 1) {
      var project =
          template.substring(0, template.length() - BoxInstances.TEMPLATE_SUFFIX.length());
      throw Remedied.io(
          template
              + " was not snapshotted: it still holds "
              + held.stderr().strip()
              + ", which "
              + project
              + "'s own provisioning wrote and the strip does not take out, so it is left where it"
              + " is.",
          Remedy.of(
              "Take the step that writes it out of "
                  + project
                  + "'s definition, then build again: ",
              Remedy.sail("project template build", project),
              "."));
    }
    if (!held.ok()) {
      throw new IOException(
          template
              + " was not snapshotted, since Sail could not tell what it holds: "
              + held.reason().strip());
    }
  }

  /** gh is logged in separately ({@link #logForgeIn}): its login needs the network up. */
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

  /** A container just started has no resolver yet, and gh's login asks its forge. */
  static final String RESOLVES =
      """
      for host in "$@"; do
        for _ in $(seq 1 300); do getent hosts "$host" >/dev/null && break; sleep 0.1; done
      done
      """;

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

  /** Through files alone, so a stopped workspace is reached without starting it. */
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
