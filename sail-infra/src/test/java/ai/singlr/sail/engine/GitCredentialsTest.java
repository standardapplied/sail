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

import ai.singlr.sail.config.SailYaml;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitCredentialsTest {

  @Test
  void resolveHostPathExpandsTildeWithSudoUser() {
    var original = System.getenv("SUDO_USER");
    var path = GitCredentials.resolveHostPath("~/.ssh/id_ed25519");
    if (original != null) {
      assertEquals(Path.of("/home", original, ".ssh/id_ed25519"), path);
    } else {
      assertEquals(
          Path.of(System.getProperty("user.home"), ".ssh/id_ed25519"),
          path,
          "Should fall back to user.home when SUDO_USER is not set");
    }
  }

  @Test
  void resolveHostPathReturnsAbsolutePathUnchanged() {
    var path = GitCredentials.resolveHostPath("/tmp/my-key");
    assertEquals(Path.of("/tmp/my-key"), path);
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
            new SailYaml.Repo("https://gitlab.com/org/repo.git", "repo", null),
            new SailYaml.Repo("https://bitbucket.org/team/app.git", "app", null),
            new SailYaml.Repo("git@github.com:user/other.git", "other", null));
    var creds = GitCredentials.buildCredentialStore(Map.of("*", "tok123"), repos);
    assertTrue(creds.contains("https://oauth2:tok123@gitlab.com\n"));
    assertTrue(creds.contains("https://oauth2:tok123@bitbucket.org\n"));
    assertFalse(creds.contains("git@"), "SSH URLs should not appear in credential store");
  }

  @Test
  void buildCredentialStoreUsesPerHostTokens() {
    var repos =
        List.of(
            new SailYaml.Repo("https://github.com/org/app.git", "app", null),
            new SailYaml.Repo("https://gitlab.com/org/repo.git", "repo", null));
    var tokens = Map.of("github.com", "ghp_xxx", "gitlab.com", "glpat_yyy");
    var creds = GitCredentials.buildCredentialStore(tokens, repos);
    assertTrue(creds.contains("https://oauth2:ghp_xxx@github.com\n"));
    assertTrue(creds.contains("https://oauth2:glpat_yyy@gitlab.com\n"));
  }

  @Test
  void buildCredentialStoreDeduplicatesHosts() {
    var repos =
        List.of(
            new SailYaml.Repo("https://gitlab.com/org/a.git", "a", null),
            new SailYaml.Repo("https://gitlab.com/org/b.git", "b", null));
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
            new SailYaml.Repo("https://gitlab.com/org/repo.git", "repo", null),
            new SailYaml.Repo("https://github.com/org/app.git", "app", null));
    var creds = GitCredentials.buildCredentialStore(null, repos);
    for (var line : creds.lines().toList()) {
      assertFalse(line.contains("null"), "Should not include null token entries");
    }
  }

  @Test
  void buildCredentialStoreWorksWithEmptyMap() {
    var repos = List.of(new SailYaml.Repo("https://github.com/org/app.git", "app", null));
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
            new SailYaml.Repo("https://github.com/org/app.git", "app", null),
            new SailYaml.Repo("https://gitlab.com/org/repo.git", "repo", null),
            new SailYaml.Repo("git@bitbucket.org:team/lib.git", "lib", null));
    var hosts = GitCredentials.extractHttpsHosts(repos);
    assertEquals(List.of("github.com", "gitlab.com"), hosts);
  }

  @Test
  void extractSshHostsIncludesDefaults() {
    var hosts = GitCredentials.extractSshHosts(null);
    assertTrue(hosts.contains("github.com"));
    assertTrue(hosts.contains("gitlab.com"));
  }

  @Test
  void extractSshHostsFromMixedUrls() {
    var repos =
        List.of(
            new SailYaml.Repo("git@bitbucket.org:team/app.git", "app", null),
            new SailYaml.Repo("https://gitlab.example.com/org/repo.git", "repo", null));
    var hosts = GitCredentials.extractSshHosts(repos);
    assertTrue(hosts.contains("github.com"), "Default github.com");
    assertTrue(hosts.contains("gitlab.com"), "Default gitlab.com");
    assertTrue(hosts.contains("bitbucket.org"), "SSH URL host");
    assertTrue(hosts.contains("gitlab.example.com"), "HTTPS URL host");
  }

  @Test
  void extractSshHostsRejectsUnsafeHost() {
    var repos = List.of(new SailYaml.Repo("https://github.com;touch/tmp/pwned", "app", null));

    assertThrows(IllegalArgumentException.class, () -> GitCredentials.extractSshHosts(repos));
  }

  @Test
  void extractHttpsHostsRejectsUnsafeHost() {
    var repos = List.of(new SailYaml.Repo("https://github.com;touch/tmp/pwned", "app", null));

    assertThrows(IllegalArgumentException.class, () -> GitCredentials.extractHttpsHosts(repos));
  }
}
