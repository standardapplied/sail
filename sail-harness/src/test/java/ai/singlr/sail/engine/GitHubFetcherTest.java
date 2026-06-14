/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GitHubFetcherTest {

  @Test
  void buildUrlConstructsCorrectPath() {
    var url = GitHubFetcher.buildUrl("acme-org/projects", "acme-health/sail.yaml", "main");

    assertEquals(
        "https://raw.githubusercontent.com/acme-org/projects/main/acme-health/sail.yaml", url);
  }

  @Test
  void buildUrlWithCustomRef() {
    var url = GitHubFetcher.buildUrl("org/repo", "global.yaml", "v2.0");

    assertEquals("https://raw.githubusercontent.com/org/repo/v2.0/global.yaml", url);
  }

  @Test
  void buildUrlWithNestedPath() {
    var url = GitHubFetcher.buildUrl("org/repo", "deep/nested/path/file.yaml", "develop");

    assertEquals(
        "https://raw.githubusercontent.com/org/repo/develop/deep/nested/path/file.yaml", url);
  }

  @Test
  void buildUrlRejectsInvalidRepo() {
    assertThrows(
        IllegalArgumentException.class,
        () -> GitHubFetcher.buildUrl("not-a-valid-repo", "file.yaml", "main"));
  }

  @Test
  void buildUrlEncodesRefWithSpecialChars() {
    var url = GitHubFetcher.buildUrl("org/repo", "file.yaml", "feature/my branch");

    assertTrue(url.contains("feature%2Fmy+branch") || url.contains("feature%2Fmy%20branch"));
    assertFalse(url.contains("feature/my branch"), "Ref must be URL-encoded");
  }
}
