/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Resolves the git credential material pushed into a project container: HTTPS token entries for a
 * {@code .git-credentials} store, SSH host lists for {@code ssh-keyscan}, and the host-side path of
 * a configured SSH key. Repo hosts are validated against {@link #HOST_PATTERN} to keep
 * attacker-influenced URLs out of generated files and shell arguments.
 */
public final class GitCredentials {

  private static final String REFS = "/info/refs?service=git-upload-pack";

  private static final Pattern HOST_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]{1,5})?$");

  private GitCredentials() {}

  /**
   * Builds the contents of a {@code .git-credentials} file with one entry per unique HTTPS host
   * found in the repo URLs that {@code tokens} holds a token for under that exact host.
   */
  public static String buildCredentialStore(
      Map<String, String> tokens, List<ProjectDefinition.Repo> repos) {
    var hosts = extractHttpsHosts(repos);
    var sb = new StringBuilder();
    for (var host : hosts) {
      var resolved = resolveTokenForHost(host, tokens);
      if (Strings.isNotBlank(resolved)) {
        sb.append("https://oauth2:").append(resolved).append('@').append(host).append('\n');
      }
    }
    return sb.toString();
  }

  /**
   * The unique HTTPS hosts of {@code repos} that are GitHub: {@code github.com}, or a host whose
   * repo is marked {@code forge: github}.
   */
  public static List<String> githubHosts(List<ProjectDefinition.Repo> repos) {
    if (repos == null) {
      return List.of();
    }
    return repos.stream()
        .filter(repo -> ProjectDefinition.Repo.GITHUB.equals(repo.forge()) || isGithubCom(repo))
        .flatMap(repo -> extractHttpsHosts(List.of(repo)).stream())
        .distinct()
        .toList();
  }

  private static boolean isGithubCom(ProjectDefinition.Repo repo) {
    return extractHttpsHosts(List.of(repo)).contains("github.com");
  }

  /** The unique hosts of {@code repos}, each an https URL a definition holds. */
  public static List<String> extractHttpsHosts(List<ProjectDefinition.Repo> repos) {
    var hosts = new LinkedHashSet<String>();
    if (repos != null) {
      for (var repo : repos) {
        var url = repo.url();
        if (url.startsWith("https://")) {
          var afterScheme = url.substring(8);
          var slashIdx = afterScheme.indexOf('/');
          if (slashIdx > 0) {
            hosts.add(withoutCredential(afterScheme.substring(0, slashIdx)));
          }
        }
      }
    }
    return hosts.stream().map(GitCredentials::requireSafeHost).toList();
  }

  /**
   * The token {@code tokens} holds for {@code host} exactly, as its repo URL spells it, or null:
   * never one stored for another host, and never one from the environment (G1, G2).
   */
  public static String resolveTokenForHost(String host, Map<String, String> tokens) {
    return tokens == null ? null : tokens.get(host);
  }

  /**
   * The tokens of {@code stored}, keyed by lower-case host as {@code sail git login} keeps them,
   * for the hosts of {@code repos}, keyed as each repo URL spells its host: the key the credential
   * store matches.
   */
  public static Map<String, String> forRepos(
      Map<String, String> stored, List<ProjectDefinition.Repo> repos) {
    var spelled = new LinkedHashMap<String, String>();
    for (var host : extractHttpsHosts(repos)) {
      var token = stored.get(host.toLowerCase(Locale.ROOT));
      if (token != null) {
        spelled.put(host, token);
      }
    }
    return spelled;
  }

  /**
   * The first of {@code repos} whose host asks for credentials ({@code asks}) and {@code tokens}
   * holds no token for, keyed as {@link #forRepos} keys them.
   */
  public static Optional<Missing> missing(
      List<ProjectDefinition.Repo> repos, Map<String, String> tokens, Predicate<String> asks) {
    return asking(repos, host -> Strings.isNotBlank(resolveTokenForHost(host, tokens)), asks)
        .stream()
        .findFirst()
        .map(repo -> new Missing(extractHttpsHosts(List.of(repo)).getFirst(), repo));
  }

  /** A repo whose host asks for credentials that no stored token answers: what refuses a fetch. */
  public record Missing(String host, ProjectDefinition.Repo repo) {

    /** What people set and Sail never reads: a refusal names it when it is set (G2). */
    static final List<String> IGNORED = List.of("GITHUB_TOKEN", "GITLAB_TOKEN", "BITBUCKET_TOKEN");

    public String reason(String project, String who) {
      return host
          + " asks for credentials to fetch "
          + project
          + "'s repo "
          + repo.path()
          + ", and "
          + who
          + " keeps no token for it.";
    }

    /**
     * The command that stores one for this host; when {@code env} sets a variable of {@link
     * #IGNORED}, that Sail does not read it. Whether it is set is all that is read of it.
     */
    public Remedy remedy(Map<String, String> env) {
      var store = Remedy.sail("git login", host);
      return IGNORED.stream()
          .filter(name -> Strings.isNotBlank(env.get(name)))
          .findFirst()
          .map(
              name -> Remedy.of(name + " is set but Sail does not read it. Store it: ", store, "."))
          .orElseGet(() -> Remedy.of("Store one with ", store, "."));
    }
  }

  /**
   * The https {@code repos} whose host {@code held} says no token is kept for and whose smart-HTTP
   * refs {@code asks} says ask for credentials: at once, each bounded by {@link
   * ShellExec#asksForCredentials}, so the whole check takes as long as its slowest answer. Any
   * other answer, a public repo or a host that cannot be reached, asks for none, and the clone says
   * what is wrong with it.
   */
  private static List<ProjectDefinition.Repo> asking(
      List<ProjectDefinition.Repo> repos, Predicate<String> held, Predicate<String> asks) {
    var asked = new ArrayList<ProjectDefinition.Repo>();
    for (var repo : repos == null ? List.<ProjectDefinition.Repo>of() : repos) {
      var hosts = extractHttpsHosts(List.of(repo));
      if (!hosts.isEmpty() && !held.test(hosts.getFirst())) {
        asked.add(repo);
      }
    }
    try (var probes = Executors.newVirtualThreadPerTaskExecutor()) {
      var answers =
          asked.stream()
              .map(repo -> probes.submit(() -> asks.test(repo.url().replaceAll("/+$", "") + REFS)))
              .toList();
      var asking = new ArrayList<ProjectDefinition.Repo>();
      for (var i = 0; i < asked.size(); i++) {
        if (answers.get(i).get()) {
          asking.add(asked.get(i));
        }
      }
      return List.copyOf(asking);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not ask whether a repo needs a token.", e.getCause());
    }
  }

  /**
   * Refuses a URL's authority that carries a credential before its host, naming the host alone: a
   * token in a definition would be echoed, logged and sent, and a token is stored with {@code sail
   * git login} instead.
   */
  private static String withoutCredential(String authority) {
    var at = authority.lastIndexOf('@');
    if (at < 0) {
      return authority;
    }
    var host = requireSafeHost(authority.substring(at + 1));
    throw new ApiException(
        ErrorCode.INVALID_REQUEST,
        "A repository URL on " + host + " carries a credential, and a URL must not carry one.",
        Remedy.of(
            "Take it out of the URL and store the token with ",
            Remedy.sail("git login", host),
            "."));
  }

  private static String requireSafeHost(String host) {
    if (host == null
        || host.length() > 253
        || !HOST_PATTERN.matcher(host).matches()
        || host.contains("..")
        || host.endsWith(".")
        || invalidPort(host)) {
      throw new IllegalArgumentException("Invalid repository host: '" + host + "'.");
    }
    return host;
  }

  private static boolean invalidPort(String host) {
    var colon = host.lastIndexOf(':');
    if (colon < 0) {
      return false;
    }
    try {
      var port = Integer.parseInt(host.substring(colon + 1));
      return port < 1 || port > 65535;
    } catch (NumberFormatException e) {
      return true;
    }
  }
}
