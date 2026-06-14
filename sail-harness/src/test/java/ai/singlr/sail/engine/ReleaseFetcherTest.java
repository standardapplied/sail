/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReleaseFetcherTest {

  @Test
  void buildDownloadUrlForBinary() {
    assertEquals(
        "https://github.com/singlr-ai/sing/releases/download/v0.9.2/sail",
        ReleaseFetcher.buildDownloadUrl("v0.9.2", "sail"));
  }

  @Test
  void buildDownloadUrlForChecksum() {
    assertEquals(
        "https://github.com/singlr-ai/sing/releases/download/v0.9.2/sail.sha256",
        ReleaseFetcher.buildDownloadUrl("v0.9.2", "sail.sha256"));
  }

  @Test
  void apiBasePointsToGitHub() {
    assertTrue(ReleaseFetcher.API_BASE.contains("api.github.com"));
    assertTrue(ReleaseFetcher.API_BASE.contains("singlr-ai/sing"));
  }

  @Test
  void downloadBasePointsToGitHubReleases() {
    assertTrue(ReleaseFetcher.DOWNLOAD_BASE.contains("github.com"));
    assertTrue(ReleaseFetcher.DOWNLOAD_BASE.contains("releases/download"));
  }

  @Test
  void binaryAssetNameUsesSailPrefix() {
    assertTrue(ReleaseFetcher.binaryAssetName().startsWith("sail-"));
  }

  @Test
  void checksumAssetNameMatchesBinaryAssetName() {
    assertEquals(ReleaseFetcher.binaryAssetName() + ".sha256", ReleaseFetcher.checksumAssetName());
  }

  @Test
  void githubRepoIsCorrect() {
    assertEquals("singlr-ai/sing", ReleaseFetcher.GITHUB_REPO);
  }
}
