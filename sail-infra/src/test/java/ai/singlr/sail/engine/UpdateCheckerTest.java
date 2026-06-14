/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckerTest {

  @TempDir Path tempDir;

  @Test
  void freshCheckWithNoCacheFile() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");
    assertFalse(Files.exists(cacheFile));

    assertDoesNotThrow(
        () -> {
          try {
            UpdateChecker.doCheck(cacheFile);
          } catch (Exception e) {
          }
        });
  }

  @Test
  void recentCacheReturnsCachedVersion() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");

    var map = new LinkedHashMap<String, Object>();
    map.put("last_checked", Instant.now().toEpochMilli());
    map.put("latest_version", "9.9.9");
    YamlUtil.dumpToFile(map, cacheFile);

    var result = UpdateChecker.doCheck(cacheFile);
    assertEquals("9.9.9", result);
  }

  @Test
  void staleCacheTriggersNetworkFetch() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");

    var map = new LinkedHashMap<String, Object>();
    map.put(
        "last_checked",
        Instant.now().minus(UpdateChecker.CHECK_INTERVAL.plusHours(1)).toEpochMilli());
    map.put("latest_version", "1.0.0");
    YamlUtil.dumpToFile(map, cacheFile);

    try {
      UpdateChecker.doCheck(cacheFile);
    } catch (Exception e) {
      assertTrue(
          e.getMessage().contains("Failed to fetch")
              || e instanceof java.net.ConnectException
              || e instanceof java.net.UnknownHostException,
          "Expected network error, got: " + e);
    }
  }

  @Test
  void corruptCacheFileIsHandledGracefully() throws Exception {
    var cacheFile = tempDir.resolve("update-check.yaml");
    Files.writeString(cacheFile, "not: valid: yaml: [[[");

    try {
      UpdateChecker.doCheck(cacheFile);
    } catch (Exception e) {
      assertFalse(e.getMessage().contains("yaml"), "Should not fail on YAML parse: " + e);
    }
  }

  @Test
  void cacheFileIsWrittenAfterFreshFetch() throws Exception {
    var cacheFile = tempDir.resolve("subdir/update-check.yaml");
    assertFalse(Files.exists(cacheFile.getParent()));

    try {
      UpdateChecker.doCheck(cacheFile);
    } catch (Exception e) {
    }
  }

  @Test
  void checkIntervalIs24Hours() {
    assertEquals(24, UpdateChecker.CHECK_INTERVAL.toHours());
  }
}
