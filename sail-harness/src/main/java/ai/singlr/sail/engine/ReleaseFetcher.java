/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Fetches release artifacts from GitHub Releases for {@code sail upgrade}. No authentication
 * required — releases are public. This is the one remaining GitHub touch and is orthogonal to
 * project coordination: it downloads the signed binary, nothing else. Uses the JDK {@link
 * HttpClient}.
 */
public final class ReleaseFetcher {

  static final String GITHUB_REPO = "standardapplied/sail";
  static final String API_BASE = "https://api.github.com/repos/" + GITHUB_REPO;
  static final String DOWNLOAD_BASE = "https://github.com/" + GITHUB_REPO + "/releases/download";
  private static final Duration META_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration BINARY_TIMEOUT = Duration.ofMinutes(5);

  private ReleaseFetcher() {}

  /** Sends {@code request} on a client of its own, closed once the answer is read. */
  static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body)
      throws IOException, InterruptedException {
    try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
      return client.send(request, body);
    }
  }

  /** Fetches the latest version string (e.g. {@code "0.9.2"}) from GitHub Releases. */
  @SuppressWarnings("unchecked")
  public static String fetchLatestVersion() throws IOException, InterruptedException {
    var url = API_BASE + "/releases/latest";
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .timeout(META_TIMEOUT)
            .GET()
            .build();
    var response = send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Failed to fetch latest release (HTTP " + response.statusCode() + ").");
    }
    var map = (Map<String, Object>) YamlUtil.parseMap(response.body());
    var tagName = (String) map.get("tag_name");
    if (tagName == null) {
      throw new IOException("No tag_name in GitHub release response.");
    }
    return tagName.startsWith("v") ? tagName.substring(1) : tagName;
  }

  /** Downloads the platform-specific binary for the given version tag (e.g. {@code "v0.9.2"}). */
  public static byte[] downloadBinary(String versionTag) throws IOException, InterruptedException {
    return fetchReleaseAsset(versionTag, binaryAssetName(), BINARY_TIMEOUT);
  }

  /** Downloads the latest binary. */
  public static byte[] downloadLatestBinary() throws IOException, InterruptedException {
    var latest = fetchLatestVersion();
    return downloadBinary("v" + latest);
  }

  /** Fetches the SHA-256 checksum for the platform-specific binary (hex string). */
  public static String fetchChecksum(String versionTag) throws IOException, InterruptedException {
    var content = fetchReleaseAssetText(versionTag, checksumAssetName(), META_TIMEOUT).strip();
    return content.split("\\s+")[0];
  }

  /** Fetches the SHA-256 checksum for the latest binary (hex string). */
  public static String fetchLatestChecksum() throws IOException, InterruptedException {
    var latest = fetchLatestVersion();
    return fetchChecksum("v" + latest);
  }

  /** Builds a full download URL for the given version tag and asset. */
  public static String buildDownloadUrl(String versionTag, String asset) {
    return DOWNLOAD_BASE + "/" + versionTag + "/" + asset;
  }

  static String binaryAssetName() {
    return "sail-" + PlatformDetector.platformSuffix();
  }

  static String checksumAssetName() {
    return binaryAssetName() + ".sha256";
  }

  private static byte[] fetchReleaseAsset(String versionTag, String asset, Duration timeout)
      throws IOException, InterruptedException {
    return fetchBytes(buildDownloadUrl(versionTag, asset), timeout);
  }

  private static String fetchReleaseAssetText(String versionTag, String asset, Duration timeout)
      throws IOException, InterruptedException {
    return fetchText(buildDownloadUrl(versionTag, asset), timeout);
  }

  private static String fetchText(String url, Duration timeout)
      throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout).GET().build();
    var response = send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Failed to fetch " + url + " (HTTP " + response.statusCode() + ").");
    }
    return response.body();
  }

  private static byte[] fetchBytes(String url, Duration timeout)
      throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout).GET().build();
    var response = send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException(
          "Failed to download from " + url + " (HTTP " + response.statusCode() + ").");
    }
    return response.body();
  }
}
