/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the git credential material pushed into a project container: HTTPS token entries for a
 * {@code .git-credentials} store, SSH host lists for {@code ssh-keyscan}, and the host-side path of
 * a configured SSH key. Repo hosts are validated against {@link #HOST_PATTERN} to keep
 * attacker-influenced URLs out of generated files and shell arguments.
 */
public final class GitCredentials {

  private static final Pattern HOST_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]{1,5})?$");

  private GitCredentials() {}

  /**
   * Builds the contents of a {@code .git-credentials} file with one entry per unique HTTPS host
   * found in the repo URLs. Looks up each host in the supplied token map, then falls back to
   * provider-specific environment variables ({@code GITHUB_TOKEN}, {@code GITLAB_TOKEN}, {@code
   * BITBUCKET_TOKEN}).
   */
  public static String buildCredentialStore(Map<String, String> tokens, List<SailYaml.Repo> repos) {
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
   * Extracts unique HTTPS hostnames from repo URLs. Only includes hosts from repos that use HTTPS.
   */
  public static List<String> extractHttpsHosts(List<SailYaml.Repo> repos) {
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

  /** Wraps a single token string into a wildcard token map. Returns empty map if null/blank. */
  public static Map<String, String> singleTokenMap(String token) {
    if (Strings.isBlank(token)) return Map.of();
    return Map.of("*", token);
  }

  /**
   * Extracts unique SSH hostnames from repo URLs. Handles both SSH-style ({@code git@host:path})
   * and HTTPS-style URLs. Always includes {@code github.com} and {@code gitlab.com} as common
   * defaults for {@code ssh-keyscan}.
   */
  public static List<String> extractSshHosts(List<SailYaml.Repo> repos) {
    var hosts = new LinkedHashSet<String>();
    hosts.add("github.com");
    hosts.add("gitlab.com");
    if (repos != null) {
      for (var repo : repos) {
        var url = repo.url();
        if (url.startsWith("git@")) {
          var colonIdx = url.indexOf(':');
          if (colonIdx > 4) {
            hosts.add(url.substring(4, colonIdx));
          }
        } else if (url.startsWith("https://")) {
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

  /**
   * Resolves a host-side file path, expanding {@code ~} to the invoking user's home directory. When
   * running under {@code sudo}, uses {@code SUDO_USER} to find the real user's home instead of
   * root's.
   */
  public static Path resolveHostPath(String path) {
    if (path.startsWith("~/")) {
      var sudoUser = System.getenv("SUDO_USER");
      if (Strings.isNotBlank(sudoUser)) {
        return Path.of("/home", sudoUser, path.substring(2));
      }
      return Path.of(System.getProperty("user.home"), path.substring(2));
    }
    return Path.of(path);
  }
}
