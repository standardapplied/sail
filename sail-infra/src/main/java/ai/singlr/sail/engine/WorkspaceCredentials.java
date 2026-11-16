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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** A template holds no one's credentials (T1); a workspace holds its owner's alone (T2, T3). */
final class WorkspaceCredentials {

  private static final String DEV = ContainerExec.DEV_HOME + "/";
  static final String STORE = DEV + ".git-credentials";
  private static final String NEW = ".sail-new";
  static final String GH_HOSTS = ContainerExec.DEV_HOME + "/.config/gh/hosts.yml";
  static final String KEYS = ContainerExec.DEV_HOME + "/.ssh/authorized_keys";

  /** Every give runs it whole, so nothing of a previous owner survives a re-own or a restart. */
  static final String STRIP =
      """
      set -eu
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      rm -f /home/dev/.git-credentials /home/dev/.git-credentials.sail-new /home/dev/.config/gh/hosts.yml /home/dev/.sail/gh-token
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
        if printf '%s\n' "$tokens" | grep -q -x ''; then echo "an empty token matches every file" >&2; exit 2; fi
        set --
        for d in /* /.[!.]*; do
          [ -e "$d" ] && [ ! -L "$d" ] && ! mountpoint -q "$d" && set -- "$@" "$d"
        done
        found=$(printf '%s\n' "$tokens" | grep -r -l -F --devices=skip -f - -- "$@")
        case $? in
          0) count=$(printf '%s\n' "$found" | wc -l)
             shown=$(printf '%s\n' "$found" | head -n 3 | paste -s -d ' ' -)
             [ "$count" -le 3 ] || shown="$shown and $((count - 3)) more"
             add "a token this build was given in $shown" ;;
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
          template + " was not snapshotted: it still holds " + held.stderr().strip() + ".",
          Remedy.of(
              "Remove what wrote it, a step of "
                  + project
                  + "'s definition or a change made by hand in "
                  + template
                  + ", then build again: ",
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
    var store =
        GitCredentials.buildCredentialStore(owner.tokensFor(config.repos()), config.repos());
    if (!store.isEmpty()) {
      ContainerFilePush.pushAsDev(shell, instance, STORE, store, "0600");
      asDev(shell, instance, "git", "config", "--global", "credential.helper", "store");
    }
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
    logForgeIn(shell, instance, config.repos(), owner.tokensFor(config.repos()));
  }

  private static void logForgeIn(
      ShellExec shell,
      String instance,
      List<ProjectDefinition.Repo> repos,
      Map<String, String> tokens)
      throws IOException, InterruptedException, TimeoutException {
    var hosts = forgeHosts(repos, tokens);
    if (hosts.isEmpty()) {
      return;
    }
    var resolve = new ArrayList<>(List.of("bash", "-c", RESOLVES, "sail-resolve"));
    resolve.addAll(hosts);
    shell.exec(ContainerExec.asRoot(instance, resolve));
    ForgeCliLogin.loginGitHub(shell, instance, ProjectDefinition.USER, tokens, repos);
  }

  /** The hosts gh's login file names: its top-level keys. None when there is no file. */
  private static final String GH_LOGGED_IN =
      "[ -f \"$1\" ] || exit 0; sed -n 's/^\\([^ #][^:]*\\):.*$/\\1/p' \"$1\"";

  /**
   * gh, already logged in to some hosts, left logged in to exactly {@code keep} in place: each
   * other host it names logged out by its own name, and each kept host logged in again only when
   * its token is not the one gh holds, which gh replaces in that host's entry alone. No host that
   * keeps its token is ever without its login, so a live run's {@code gh} never fails for this.
   */
  private static void relogForge(
      ShellExec shell,
      String instance,
      List<ProjectDefinition.Repo> repos,
      Map<String, String> tokens,
      List<String> keep)
      throws IOException, InterruptedException, TimeoutException {
    var named =
        shell.exec(
            ContainerExec.asDevUser(instance, List.of("sh", "-c", GH_LOGGED_IN, "sh", GH_HOSTS)));
    if (!named.ok()) {
      throw new IOException(
          "Could not read gh's logins in " + instance + ": " + named.reason().strip());
    }
    for (var host : named.stdout().lines().map(String::strip).toList()) {
      if (!host.isEmpty() && !keep.contains(host)) {
        asDev(shell, instance, "gh", "auth", "logout", "--hostname", host);
      }
    }
    var changed = new LinkedHashMap<String, String>();
    for (var host : keep) {
      var held =
          shell.exec(
              ContainerExec.asDevUser(
                  instance, List.of("gh", "auth", "token", "--hostname", host)));
      if (!held.ok() || !held.stdout().strip().equals(tokens.get(host))) {
        changed.put(host, tokens.get(host));
      }
    }
    if (!changed.isEmpty()) {
      logForgeIn(shell, instance, repos, changed);
    }
  }

  /** The GitHub hosts of {@code repos} that {@code tokens} holds a token for: gh's logins. */
  private static List<String> forgeHosts(
      List<ProjectDefinition.Repo> repos, Map<String, String> tokens) {
    return GitCredentials.githubHosts(repos).stream().filter(tokens::containsKey).toList();
  }

  /**
   * A token change for the owner a workspace already has ({@link #renew(ShellExec, String, List,
   * Map)}).
   */
  static String renew(
      ShellExec shell, String instance, ProjectDefinition config, WorkspaceOwner owner)
      throws IOException, InterruptedException, TimeoutException {
    return renew(shell, instance, config.repos(), owner.tokensFor(config.repos()));
  }

  /**
   * Gives {@code instance} exactly {@code tokens} for {@code repos}: the store replaced whole by a
   * rename, never stripped first, so a live run's fetch or push never finds the container without
   * one, or removed when there is nothing to write; and gh left logged in to exactly the GitHub
   * hosts with a token, each host kept never logged out on the way. The one step a workspace's
   * token change and a project container's apply both take, so a token removed or no longer needed
   * leaves either. Answers what it removed with nothing written in its place, which may have been
   * made by hand and is said, or null.
   */
  static String renew(
      ShellExec shell,
      String instance,
      List<ProjectDefinition.Repo> repos,
      Map<String, String> tokens)
      throws IOException, InterruptedException, TimeoutException {
    var removed = new ArrayList<String>();
    var store = GitCredentials.buildCredentialStore(tokens, repos);
    if (store.isEmpty()) {
      if (remove(shell, instance, STORE)) {
        removed.add("git credentials");
      }
    } else {
      writeStore(shell, instance, store);
    }
    var keep = forgeHosts(repos, tokens);
    if (keep.isEmpty()) {
      if (remove(shell, instance, GH_HOSTS)) {
        removed.add("gh login");
      }
    } else {
      relogForge(shell, instance, repos, tokens, keep);
    }
    return removed.isEmpty()
        ? null
        : instance
            + "'s "
            + String.join(" and ", removed)
            + " removed: no token is stored for its repos' hosts, and Sail keeps only the tokens its"
            + " git login stores.";
  }

  /** Exits 3 when there is no {@code $1}, so the step knows whether it removed one. */
  private static final String REMOVE = "[ -e \"$1\" ] || exit 3; rm -f \"$1\"";

  private static boolean remove(ShellExec shell, String instance, String file)
      throws IOException, InterruptedException, TimeoutException {
    var ran =
        shell.exec(ContainerExec.asDevUser(instance, List.of("sh", "-c", REMOVE, "sh", file)));
    if (ran.exitCode() == 3) {
      return false;
    }
    if (!ran.ok()) {
      throw new IOException(
          "Could not remove " + file + " in " + instance + ": " + ran.reason().strip());
    }
    return true;
  }

  /** The store written beside the old one and renamed over it, with git told to read it. */
  private static void writeStore(ShellExec shell, String instance, String store)
      throws IOException, InterruptedException, TimeoutException {
    ContainerFilePush.pushAsDev(shell, instance, STORE + NEW, store, "0600");
    asDev(shell, instance, "mv", "-f", STORE + NEW, STORE);
    asDev(shell, instance, "git", "config", "--global", "credential.helper", "store");
  }

  private static void asDev(ShellExec shell, String instance, String... command)
      throws IOException, InterruptedException, TimeoutException {
    var ran = shell.exec(ContainerExec.asDevUser(instance, List.of(command)));
    if (!ran.ok()) {
      throw new IOException(
          String.join(" ", command) + " failed in " + instance + ": " + ran.reason().strip());
    }
  }

  /** Through files alone, so a stopped workspace is reached without starting it. */
  static void takeBack(ShellExec shell, String instance)
      throws IOException, InterruptedException, TimeoutException {
    for (var file : List.of(STORE, STORE + NEW, GH_HOSTS, DEV + ForgeCliLogin.TOKEN_FILE)) {
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
