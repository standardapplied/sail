/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Whether a checkout already in a container is the repository its definition names. Apply never
 * deletes or re-clones a checkout, which may hold work, so one cloned from another repository, or
 * no checkout at all, is refused before apply reports the project converged.
 */
final class RepoOrigin {

  private static final int NO_SUCH_REMOTE = 2;

  private RepoOrigin() {}

  /**
   * Refuses the checkout at {@code dir} unless its {@code origin} is {@code repo}'s URL. A dry run
   * observes no checkout, so it has nothing to refuse.
   */
  static void require(ShellExec probes, String container, String dir, SailYaml.Repo repo)
      throws IOException, InterruptedException, TimeoutException {
    if (probes.isDryRun()) {
      return;
    }
    var origin =
        probes.exec(
            ContainerExec.asDevUser(
                container, List.of("git", "-C", dir, "remote", "get-url", "origin")));
    var actual = origin.stdout().strip();
    if (origin.ok() && sameRepository(actual, repo.url())) {
      return;
    }
    var found =
        origin.ok()
            ? "is a clone of " + shown(actual)
            : origin.exitCode() == NO_SUCH_REMOTE
                ? "is a clone with no origin"
                : "is not a git checkout";
    throw new IllegalStateException(
        "Repo '"
            + repo.path()
            + "' "
            + found
            + ", but the definition says "
            + repo.url()
            + ". Move it aside (it may hold work) or fix the URL.");
  }

  /**
   * Whether two clone URLs name one repository: the same host, case-insensitively, and the same
   * path, whatever the scheme, user, port, {@code :} or {@code /} after the host, trailing {@code
   * .git} or {@code /}.
   */
  static boolean sameRepository(String one, String other) {
    return identity(one).equals(identity(other));
  }

  private static String identity(String url) {
    var trimmed = url.strip();
    var scheme = trimmed.indexOf("://");
    if (scheme >= 0) {
      var rest = trimmed.substring(scheme + 3);
      var slash = rest.indexOf('/');
      var authority = slash >= 0 ? rest.substring(0, slash) : rest;
      return hostOf(authority.substring(authority.lastIndexOf('@') + 1))
          + "/"
          + pathOf(slash >= 0 ? rest.substring(slash) : "");
    }
    var colon = trimmed.indexOf(':');
    var slash = trimmed.indexOf('/');
    if (colon > 0 && (slash < 0 || colon < slash)) {
      return hostOf(trimmed.substring(trimmed.lastIndexOf('@', colon) + 1, colon))
          + "/"
          + pathOf(trimmed.substring(colon + 1));
    }
    return "/" + pathOf(trimmed);
  }

  private static String hostOf(String hostAndPort) {
    var colon = hostAndPort.lastIndexOf(':');
    var host =
        colon >= 0 && !hostAndPort.endsWith("]") ? hostAndPort.substring(0, colon) : hostAndPort;
    return host.toLowerCase(Locale.ROOT);
  }

  private static String pathOf(String path) {
    var stripped = trimSlashes(path);
    return trimSlashes(
        stripped.endsWith(".git") ? stripped.substring(0, stripped.length() - 4) : stripped);
  }

  private static String trimSlashes(String path) {
    var start = 0;
    var end = path.length();
    while (start < end && path.charAt(start) == '/') {
      start++;
    }
    while (end > start && path.charAt(end - 1) == '/') {
      end--;
    }
    return path.substring(start, end);
  }

  /** A URL as a refusal may print it: its user info, which may be a token, is never echoed. */
  private static String shown(String url) {
    var scheme = url.indexOf("://");
    if (scheme < 0) {
      return url;
    }
    var rest = url.substring(scheme + 3);
    var slash = rest.indexOf('/');
    var at = (slash >= 0 ? rest.substring(0, slash) : rest).lastIndexOf('@');
    return at < 0 ? url : url.substring(0, scheme + 3) + rest.substring(at + 1);
  }
}
