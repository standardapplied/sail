/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReleaseFetcherTest {

  @Test
  void buildDownloadUrlForBinary() {
    assertEquals(
        "https://github.com/standardapplied/sail/releases/download/v0.9.2/sail",
        ReleaseFetcher.buildDownloadUrl("v0.9.2", "sail"));
  }

  @Test
  void buildDownloadUrlForChecksum() {
    assertEquals(
        "https://github.com/standardapplied/sail/releases/download/v0.9.2/sail.sha256",
        ReleaseFetcher.buildDownloadUrl("v0.9.2", "sail.sha256"));
  }

  @Test
  void apiBasePointsToGitHub() {
    assertTrue(ReleaseFetcher.API_BASE.contains("api.github.com"));
    assertTrue(ReleaseFetcher.API_BASE.contains("standardapplied/sail"));
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
    assertEquals("standardapplied/sail", ReleaseFetcher.GITHUB_REPO);
  }

  /** A fetch's client lives for its one request: nothing of it is open once answered. */
  @Test
  void aFetchLeavesNoClientOpen() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var tag = "v1.2.3".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, tag.length);
          exchange.getResponseBody().write(tag);
          exchange.close();
        });
    server.start();
    var before = ClosesWhatItOpens.baseline();
    try {
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest"))
              .GET()
              .build();

      assertEquals(
          "v1.2.3", ReleaseFetcher.send(request, HttpResponse.BodyHandlers.ofString()).body());
    } finally {
      server.stop(0);
    }
    ClosesWhatItOpens.assertClosed("a release fetch", before);
  }
}
