/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class GitCredentialsTest {

  @Test
  void aTokenGivenServesEveryHostAndNoneAsksNoOne() {
    var repos = List.of(new ProjectDefinition.Repo("https://git.example.com/a/b.git", "b", null));

    assertEquals(Map.of("*", "tok"), GitCredentials.known(repos, "tok"));
    assertEquals(Map.of(), GitCredentials.known(repos, null));
    assertEquals(Map.of(), GitCredentials.known(null, " "));
  }

  @Test
  void aRepoWhoseHostHasNoTokenAndThatAsksForCredentialsIsNamed() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://github.com/acme/private.git", "private", null),
            new ProjectDefinition.Repo("https://github.com/acme/public.git/", "public", null),
            new ProjectDefinition.Repo("https://git.example.com/a/b.git", "b", null),
            new ProjectDefinition.Repo("https://git.example.com/a/down.git", "down", null));
    var asked = new CopyOnWriteArrayList<String>();
    ToIntFunction<String> status =
        url -> {
          asked.add(url);
          return url.contains("private") ? 401 : url.contains("down") ? -1 : 200;
        };

    assertEquals(
        List.of("https://github.com/acme/private.git"),
        GitCredentials.askingForAToken(repos, Map.of(), status));
    assertTrue(
        asked.contains("https://github.com/acme/public.git/info/refs?service=git-upload-pack"),
        String.valueOf(asked));
    assertEquals(
        List.of(),
        GitCredentials.askingForAToken(repos, Map.of("github.com", "tok"), url -> 401).stream()
            .filter(url -> url.contains("github.com"))
            .toList());
    assertEquals(List.of(), GitCredentials.askingForAToken(null, Map.of(), url -> 401));
  }

  @Test
  void theReposAreAskedAtOnceSoTheCheckTakesAsLongAsOneAnswer() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://github.com/acme/a.git", "a", null),
            new ProjectDefinition.Repo("https://github.com/acme/b.git", "b", null),
            new ProjectDefinition.Repo("https://gitlab.com/acme/c.git", "c", null));
    var inFlight = new CountDownLatch(repos.size());
    ToIntFunction<String> status =
        url -> {
          inFlight.countDown();
          try {
            return inFlight.await(10, TimeUnit.SECONDS) ? 401 : 200;
          } catch (InterruptedException e) {
            return -1;
          }
        };

    assertEquals(
        List.of(
            "https://github.com/acme/a.git",
            "https://github.com/acme/b.git",
            "https://gitlab.com/acme/c.git"),
        GitCredentials.askingForAToken(repos, Map.of(), status));
  }

  @Test
  void aUrlNoServerAnswersHasNoStatus() {
    assertEquals(-1, GitCredentials.httpStatus("http://127.0.0.1:1/info/refs"));
    assertEquals(-1, GitCredentials.httpStatus("not a url"));
  }

  @Test
  void buildCredentialStoreEmptyWhenNoRepos() {
    var creds = GitCredentials.buildCredentialStore(Map.of("*", "tok123"), null);
    assertEquals("", creds);
  }

  @Test
  void buildCredentialStoreIncludesAllHttpsHosts() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://gitlab.com/org/repo.git", "repo", null),
            new ProjectDefinition.Repo("https://bitbucket.org/team/app.git", "app", null),
            new ProjectDefinition.Repo("git@github.com:user/other.git", "other", null));
    var creds = GitCredentials.buildCredentialStore(Map.of("*", "tok123"), repos);
    assertTrue(creds.contains("https://oauth2:tok123@gitlab.com\n"));
    assertTrue(creds.contains("https://oauth2:tok123@bitbucket.org\n"));
    assertFalse(creds.contains("git@"), "SSH URLs should not appear in credential store");
  }

  @Test
  void buildCredentialStoreUsesPerHostTokens() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://github.com/org/app.git", "app", null),
            new ProjectDefinition.Repo("https://gitlab.com/org/repo.git", "repo", null));
    var tokens = Map.of("github.com", "ghp_xxx", "gitlab.com", "glpat_yyy");
    var creds = GitCredentials.buildCredentialStore(tokens, repos);
    assertTrue(creds.contains("https://oauth2:ghp_xxx@github.com\n"));
    assertTrue(creds.contains("https://oauth2:glpat_yyy@gitlab.com\n"));
  }

  @Test
  void buildCredentialStoreDeduplicatesHosts() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://gitlab.com/org/a.git", "a", null),
            new ProjectDefinition.Repo("https://gitlab.com/org/b.git", "b", null));
    var creds = GitCredentials.buildCredentialStore(Map.of("*", "tok123"), repos);
    var lines = creds.strip().split("\n");
    assertEquals(1, lines.length, "One gitlab.com entry (deduplicated)");
  }

  @Test
  void resolveTokenForHostChecksExplicitFirst() {
    var tokens = Map.of("gitlab.com", "explicit-tok", "*", "wildcard-tok");
    var result = GitCredentials.resolveTokenForHost("gitlab.com", tokens);
    assertEquals("explicit-tok", result);
  }

  @Test
  void resolveTokenForHostFallsBackToWildcard() {
    var tokens = Map.of("*", "wildcard-tok");
    var result = GitCredentials.resolveTokenForHost("gitea.example.com", tokens);
    assertEquals("wildcard-tok", result);
  }

  @Test
  void resolveTokenForHostReturnsNullWhenNoMatch() {
    var result = GitCredentials.resolveTokenForHost("gitea.self-hosted.com", Map.of());
    assertNull(result);
  }

  @Test
  void buildCredentialStoreSkipsHostsWithNoToken() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://gitlab.com/org/repo.git", "repo", null),
            new ProjectDefinition.Repo("https://github.com/org/app.git", "app", null));
    var creds = GitCredentials.buildCredentialStore(null, repos);
    for (var line : creds.lines().toList()) {
      assertFalse(line.contains("null"), "Should not include null token entries");
    }
  }

  @Test
  void buildCredentialStoreWorksWithEmptyMap() {
    var repos = List.of(new ProjectDefinition.Repo("https://github.com/org/app.git", "app", null));
    var creds = GitCredentials.buildCredentialStore(Map.of(), repos);
    assertNotNull(creds);
  }

  @Test
  void singleTokenMapWrapsToken() {
    var result = GitCredentials.singleTokenMap("my-token");
    assertEquals(Map.of("*", "my-token"), result);
  }

  @Test
  void singleTokenMapReturnsEmptyForNull() {
    var result = GitCredentials.singleTokenMap(null);
    assertTrue(result.isEmpty());
  }

  @Test
  void extractHttpsHostsFromMixedRepos() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://github.com/org/app.git", "app", null),
            new ProjectDefinition.Repo("https://gitlab.com/org/repo.git", "repo", null),
            new ProjectDefinition.Repo("git@bitbucket.org:team/lib.git", "lib", null));
    var hosts = GitCredentials.extractHttpsHosts(repos);
    assertEquals(List.of("github.com", "gitlab.com"), hosts);
  }

  @Test
  void extractHttpsHostsRejectsUnsafeHost() {
    var repos =
        List.of(new ProjectDefinition.Repo("https://github.com;touch/tmp/pwned", "app", null));

    assertThrows(IllegalArgumentException.class, () -> GitCredentials.extractHttpsHosts(repos));
  }

  @Test
  void githubHostsAreGithubComAndEveryHostMarkedAsGithub() {
    var hosts =
        GitCredentials.githubHosts(
            List.of(
                new ProjectDefinition.Repo("https://github.com/org/a.git", "a", null),
                new ProjectDefinition.Repo("https://github.com/org/b.git", "b", null),
                new ProjectDefinition.Repo(
                    "https://git.example.com/org/c.git", "c", null, "github"),
                new ProjectDefinition.Repo("https://gitlab.com/org/d.git", "d", null),
                new ProjectDefinition.Repo("git@github.com:org/e.git", "e", null)));

    assertEquals(List.of("github.com", "git.example.com"), hosts);
    assertEquals(List.of(), GitCredentials.githubHosts(null));
  }
}
