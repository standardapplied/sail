/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache-aware version checker. Rate-limited to one network call per 24 hours — cached result stored
 * in {@code /etc/sail/update-check.yaml}. Used by {@link AutoUpgrader} to decide whether an update
 * is available without hitting the network on every invocation.
 */
public final class UpdateChecker {

  static final Duration CHECK_INTERVAL = Duration.ofHours(24);

  private UpdateChecker() {}

  /**
   * Performs the actual check: reads cache, fetches if stale, writes cache. Returns the latest
   * version string, or null on error.
   */
  static String doCheck() throws Exception {
    var cacheFile = SailPaths.updateCheckFile();
    return doCheck(cacheFile);
  }

  /** The stale-cache fetch, injectable so tests exercise the cache logic without a network call. */
  @FunctionalInterface
  interface VersionFetcher {
    String fetch() throws Exception;
  }

  /** Package-private overload for testing with a custom cache file path. */
  static String doCheck(Path cacheFile) throws Exception {
    return doCheck(cacheFile, ReleaseFetcher::fetchLatestVersion);
  }

  /**
   * The cache decision with the network fetch behind a seam: a fresh cache returns without
   * fetching; a stale or absent cache fetches and rewrites. Tests inject {@code fetcher} so the
   * branch under test is deterministic — the real network's failure mode (connection refused on one
   * host, an SSL handshake on another) is not a contract this code owns and must never decide
   * whether a test passes.
   */
  static String doCheck(Path cacheFile, VersionFetcher fetcher) throws Exception {
    var cached = readCache(cacheFile);
    if (cached != null) {
      var lastChecked = Instant.ofEpochMilli((Long) cached.get("last_checked"));
      if (DateTimeUtils.now().minus(CHECK_INTERVAL).isBefore(lastChecked)) {
        return (String) cached.get("latest_version");
      }
    }

    var latestVersion = fetcher.fetch();

    writeCache(cacheFile, latestVersion);

    return latestVersion;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readCache(Path cacheFile) {
    try {
      if (Files.exists(cacheFile)) {
        return (Map<String, Object>) YamlUtil.parseFile(cacheFile);
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static void writeCache(Path cacheFile, String latestVersion) {
    try {
      Files.createDirectories(cacheFile.getParent());
      var map = new LinkedHashMap<String, Object>();
      map.put("last_checked", DateTimeUtils.now().toEpochMilli());
      map.put("latest_version", latestVersion);
      var tmpFile = cacheFile.resolveSibling("update-check.yaml.tmp");
      YamlUtil.dumpToFile(map, tmpFile);
      Files.move(
          tmpFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception ignored) {
    }
  }
}
