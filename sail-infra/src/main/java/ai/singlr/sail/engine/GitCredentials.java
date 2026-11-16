/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
   * found in the repo URLs. Looks up each host in the supplied token map, then falls back to
   * provider-specific environment variables ({@code GITHUB_TOKEN}, {@code GITLAB_TOKEN}, {@code
   * BITBUCKET_TOKEN}).
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
            hosts.add(afterScheme.substring(0, slashIdx));
          }
        }
      }
    }
    return hosts.stream().map(GitCredentials::requireSafeHost).toList();
  }

  /**
   * Resolves the git credential token for a specific host. Checks the token map first (exact host,
   * then wildcard "*"), then falls back to provider-specific environment variables.
   */
  public static String resolveTokenForHost(String host, Map<String, String> tokens) {
    if (tokens != null) {
      var explicit = tokens.get(host);
      if (Strings.isNotBlank(explicit)) {
        return explicit;
      }
      var wildcard = tokens.get("*");
      if (Strings.isNotBlank(wildcard)) {
        return wildcard;
      }
    }
    return providerEnvToken(host);
  }

  private static String providerEnvToken(String host) {
    if ("github.com".equals(host)) {
      return System.getenv("GITHUB_TOKEN");
    }
    if ("gitlab.com".equals(host) || host.contains("gitlab")) {
      return System.getenv("GITLAB_TOKEN");
    }
    if (host.contains("bitbucket")) {
      return System.getenv("BITBUCKET_TOKEN");
    }
    return null;
  }

  /**
   * The tokens a run clones {@code repos} with, asking no one: {@code token} for every host when it
   * is given, else each https host's own from its provider's environment variable.
   */
  public static Map<String, String> known(List<ProjectDefinition.Repo> repos, String token) {
    if (Strings.isNotBlank(token)) {
      return singleTokenMap(token);
    }
    var tokens = new LinkedHashMap<String, String>();
    for (var host : extractHttpsHosts(repos)) {
      var found = resolveTokenForHost(host, null);
      if (Strings.isNotBlank(found)) {
        tokens.put(host, found);
      }
    }
    return Map.copyOf(tokens);
  }

  /**
   * The https {@code repos} a run cannot clone with {@code tokens}: those whose host has no token
   * there and whose smart-HTTP refs {@code asks} says ask for credentials ({@link
   * ShellExec#asksForCredentials}). Any other answer, a public repo or a host that cannot be
   * reached, asks for none, and the clone says what is wrong with it. The repos are asked at once,
   * so the whole check takes as long as its slowest answer, which {@link
   * ShellExec#asksForCredentials} bounds.
   */
  public static List<String> askingForAToken(
      List<ProjectDefinition.Repo> repos, Map<String, String> tokens, Predicate<String> asks) {
    return asking(repos, host -> Strings.isNotBlank(resolveTokenForHost(host, tokens)), asks)
        .stream()
        .map(ProjectDefinition.Repo::url)
        .toList();
  }

  /**
   * The https {@code repos} whose host {@code held} says no token is kept for and whose smart-HTTP
   * refs {@code asks} says ask for credentials, as {@link #askingForAToken} asks them: at once,
   * each bounded by {@link ShellExec#asksForCredentials}.
   */
  public static List<ProjectDefinition.Repo> asking(
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

  /** Wraps a single token string into a wildcard token map. Returns empty map if null/blank. */
  public static Map<String, String> singleTokenMap(String token) {
    if (Strings.isBlank(token)) return Map.of();
    return Map.of("*", token);
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
