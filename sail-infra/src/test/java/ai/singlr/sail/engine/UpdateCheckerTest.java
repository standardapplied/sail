/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckerTest {

  @TempDir Path tempDir;

  @Test
  void recentCacheReturnsCachedVersion() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");

    var map = new LinkedHashMap<String, Object>();
    map.put("last_checked", Instant.now().toEpochMilli());
    map.put("latest_version", "9.9.9");
    YamlUtil.dumpToFile(map, cacheFile);

    var result =
        UpdateChecker.doCheck(
            cacheFile,
            () -> {
              throw new AssertionError("a fresh cache must not fetch");
            });
    assertEquals("9.9.9", result);
  }

  @Test
  void aStaleCacheFetchesAndRefreshesTheCache() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");
    var map = new LinkedHashMap<String, Object>();
    map.put(
        "last_checked",
        Instant.now().minus(UpdateChecker.CHECK_INTERVAL.plusHours(1)).toEpochMilli());
    map.put("latest_version", "1.0.0");
    YamlUtil.dumpToFile(map, cacheFile);

    var result = UpdateChecker.doCheck(cacheFile, () -> "2.0.0");

    assertEquals("2.0.0", result, "a stale cache returns the freshly fetched version");
    var rewritten = (Map<?, ?>) YamlUtil.parseFile(cacheFile);
    assertEquals(
        "2.0.0", rewritten.get("latest_version"), "the fetched version is written back to cache");
  }

  @Test
  void anAbsentCacheFetches() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");

    assertEquals("3.0.0", UpdateChecker.doCheck(cacheFile, () -> "3.0.0"));
  }

  @Test
  void aFetchFailurePropagatesRatherThanCorruptingTheCache() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");
    var map = new LinkedHashMap<String, Object>();
    map.put(
        "last_checked",
        Instant.now().minus(UpdateChecker.CHECK_INTERVAL.plusHours(1)).toEpochMilli());
    map.put("latest_version", "1.0.0");
    YamlUtil.dumpToFile(map, cacheFile);

    assertThrows(
        java.net.ConnectException.class,
        () ->
            UpdateChecker.doCheck(
                cacheFile,
                () -> {
                  throw new java.net.ConnectException("refused");
                }));
    var untouched = (Map<?, ?>) YamlUtil.parseFile(cacheFile);
    assertEquals(
        "1.0.0",
        untouched.get("latest_version"),
        "a failed fetch leaves the prior cache intact — the failure never rewrites it");
  }

  @Test
  void corruptCacheFileIsTreatedAsAbsentAndFetches() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");
    Files.writeString(cacheFile, "not: valid: yaml: [[[");

    assertEquals(
        "4.0.0",
        UpdateChecker.doCheck(cacheFile, () -> "4.0.0"),
        "an unparseable cache reads as absent — the check fetches rather than throwing");
  }

  @Test
  void cacheFileAndItsParentAreWrittenAfterAFreshFetch() throws Exception {
    var cacheFile = tempDir.resolve("subdir/update-check.yaml");
    assertFalse(Files.exists(cacheFile.getParent()));

    UpdateChecker.doCheck(cacheFile, () -> "5.0.0");

    assertTrue(Files.exists(cacheFile), "the fetch creates the cache file and its parent");
    var written = (Map<?, ?>) YamlUtil.parseFile(cacheFile);
    assertEquals("5.0.0", written.get("latest_version"));
  }

  @Test
  void checkIntervalIs24Hours() {
    assertEquals(24, UpdateChecker.CHECK_INTERVAL.toHours());
  }
}
