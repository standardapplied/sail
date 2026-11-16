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

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.config.ProjectDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class GitCredentialsTest {

  /**
   * G1, scenario 1: a token goes only to the host it was stored for. One stored for gitlab.com is
   * never sent to a host whose name merely contains "gitlab", nor to any other.
   */
  @Test
  void aTokenStoredForOneHostIsNeverSentToAnother() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://gitlab.attacker.example/x.git", "x", null),
            new ProjectDefinition.Repo("https://bitbucket.attacker.example/y.git", "y", null),
            new ProjectDefinition.Repo("https://GitLab.com/acme/z.git", "z", null));
    var stored = Map.of("gitlab.com", "glpat-stored-for-gitlab");

    var tokens = GitCredentials.forRepos(stored, repos);

    assertEquals(Map.of("GitLab.com", "glpat-stored-for-gitlab"), tokens);
    assertEquals(
        "https://oauth2:glpat-stored-for-gitlab@GitLab.com\n",
        GitCredentials.buildCredentialStore(tokens, repos));
    assertNull(GitCredentials.resolveTokenForHost("gitlab.attacker.example", stored));
    assertNull(GitCredentials.resolveTokenForHost("github.com", Map.of("*", "tok")));
  }

  /**
   * Round 1, item 2 (G1): the token goes to the host it was stored for exactly, case aside, and to
   * no host that contains it, starts or ends with it, or names it with a port.
   */
  @Test
  void aTokenGoesToItsOwnHostAloneAndToNoHostThatMerelyContainsIt() {
    var stored = Map.of("github.com", "ghp_stored_for_github", "gitlab.com", "glpat_stored_for_gl");

    for (var host :
        List.of(
            "github.com.attacker.example",
            "attacker-github.com",
            "github.com:8443",
            "www.github.com",
            "gitlab.attacker.example")) {
      assertEquals(Map.of(), checked(host, stored).byHost(), host);
    }
    assertEquals(
        Map.of("GitHub.com", "ghp_stored_for_github"), checked("GitHub.com", stored).byHost());
  }

  /**
   * Round 1, minor 1: on a box that names no person, the refusal says so, and names the setup that
   * makes one before the login, with the API's code for a login not stored.
   */
  @Test
  void aBoxWithNoPersonIsToldToNameOneBeforeStoringALogin() {
    var refused =
        assertThrows(
            ApiException.class,
            () ->
                CheckedTokens.of(
                    project("github.com"), Optional.empty(), Map.of(), url -> true, Map.of()));

    assertEquals(
        "github.com asks for credentials to fetch acme's repo r, and this box names no person to"
            + " keep a token for it.",
        refused.reason());
    assertEquals(
        "Name one with sail init --as-main --handle <you>, or sail init --main <main>, then store a"
            + " token with sail git login github.com.",
        refused.remedy().plain());
    assertEquals(ErrorCode.LOGIN_NOT_STORED, refused.failure().errorCode());
  }

  /** Round 1, minor 2: a URL that carries a credential is refused naming its host alone. */
  @Test
  void aRepoUrlThatCarriesACredentialIsRefusedWithoutEchoingIt() {
    var secret = "ghp_EMBEDDEDSECRET0123456789";
    var refused =
        assertThrows(
            ApiException.class,
            () ->
                GitCredentials.extractHttpsHosts(
                    List.of(
                        new ProjectDefinition.Repo(
                            "https://x-access-token:" + secret + "@github.com/o/r.git",
                            "r",
                            null))));

    assertEquals(
        "A repository URL on github.com carries a credential, and a URL must not carry one.",
        refused.reason());
    assertEquals(
        "Take it out of the URL and store the token with sail git login github.com.",
        refused.remedy().plain());
    assertFalse(refused.getMessage().contains(secret));
    assertFalse(refused.remedy().plain().contains(secret));
  }

  private static CheckedTokens checked(String host, Map<String, String> stored) {
    return CheckedTokens.of(project(host), Optional.of("eng"), stored, url -> false, Map.of());
  }

  private static ProjectDefinition project(String host) {
    return new ProjectDefinition(
        "acme",
        null,
        null,
        null,
        null,
        null,
        List.of(new ProjectDefinition.Repo("https://" + host + "/acme/r.git", "r", null)),
        null,
        null,
        null,
        null);
  }

  /** Scenario 5: a host with a port is its own host, keyed and refused with its port. */
  @Test
  void aHostWithAPortIsKeyedAndRefusedWithItsPort() {
    var repos =
        List.of(new ProjectDefinition.Repo("https://git.example:8443/acme/app.git", "app", null));

    assertEquals(
        Map.of("git.example:8443", "tok-for-8443"),
        GitCredentials.forRepos(
            Map.of("git.example:8443", "tok-for-8443", "git.example", "tok-without"), repos));
    var missing = GitCredentials.missing(repos, Map.of(), url -> true).orElseThrow();
    assertEquals("git.example:8443", missing.host());
    assertEquals(
        "Store one with sail git login git.example:8443.", missing.remedy(Map.of()).plain());
  }

  /**
   * Item 2: a token variable in the environment is never read for its value; when one is set and no
   * token is stored, the refusal says Sail does not read it and names the command that stores one.
   */
  @Test
  void aTokenVariableSetIsNamedAsUnreadAndItsValueNeverSaid() {
    var repos =
        List.of(new ProjectDefinition.Repo("https://github.com/acme/private.git", "private", null));
    var missing = GitCredentials.missing(repos, Map.of(), url -> true).orElseThrow();

    assertEquals(
        "github.com asks for credentials to fetch acme's repo private, and uday keeps no token for"
            + " it.",
        missing.reason("acme", "uday"));
    assertEquals(
        "GITHUB_TOKEN is set but Sail does not read it. Store it: sail git login github.com.",
        missing.remedy(Map.of("GITHUB_TOKEN", "ghp_secret_value")).plain());
    assertEquals(
        "BITBUCKET_TOKEN is set but Sail does not read it. Store it: sail git login github.com.",
        missing.remedy(Map.of("BITBUCKET_TOKEN", "x", "GITHUB_TOKEN", " ")).plain());
    assertFalse(
        missing.remedy(Map.of("GITHUB_TOKEN", "ghp_secret_value")).plain().contains("ghp_"));
    assertEquals(
        "Store one with sail git login github.com.",
        missing.remedy(Map.of("PATH", "/bin")).plain());
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
    Predicate<String> status =
        url -> {
          asked.add(url);
          return url.contains("private");
        };

    assertEquals(
        "https://github.com/acme/private.git",
        GitCredentials.missing(repos, Map.of(), status).orElseThrow().repo().url());
    assertTrue(
        asked.contains("https://github.com/acme/public.git/info/refs?service=git-upload-pack"),
        String.valueOf(asked));
    assertEquals(
        "git.example.com",
        GitCredentials.missing(repos, Map.of("github.com", "tok"), url -> true)
            .orElseThrow()
            .host());
    assertTrue(GitCredentials.missing(null, Map.of(), url -> true).isEmpty());
  }

  @Test
  void theReposAreAskedAtOnceSoTheCheckTakesAsLongAsOneAnswer() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://github.com/acme/a.git", "a", null),
            new ProjectDefinition.Repo("https://github.com/acme/b.git", "b", null),
            new ProjectDefinition.Repo("https://gitlab.com/acme/c.git", "c", null));
    var inFlight = new CountDownLatch(repos.size());
    Predicate<String> status =
        url -> {
          inFlight.countDown();
          try {
            return inFlight.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            return false;
          }
        };

    assertEquals(
        "https://github.com/acme/a.git",
        GitCredentials.missing(repos, Map.of(), status).orElseThrow().repo().url());
  }

  @Test
  void buildCredentialStoreEmptyWhenNoRepos() {
    var creds = GitCredentials.buildCredentialStore(Map.of("gitlab.com", "tok123"), null);
    assertEquals("", creds);
  }

  @Test
  void buildCredentialStoreIncludesAllHttpsHosts() {
    var repos =
        List.of(
            new ProjectDefinition.Repo("https://gitlab.com/org/repo.git", "repo", null),
            new ProjectDefinition.Repo("https://bitbucket.org/team/app.git", "app", null),
            new ProjectDefinition.Repo("git@github.com:user/other.git", "other", null));
    var creds =
        GitCredentials.buildCredentialStore(
            Map.of("gitlab.com", "tok123", "bitbucket.org", "tok123"), repos);
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
    var creds = GitCredentials.buildCredentialStore(Map.of("gitlab.com", "tok123"), repos);
    var lines = creds.strip().split("\n");
    assertEquals(1, lines.length, "One gitlab.com entry (deduplicated)");
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
