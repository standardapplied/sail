/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetentionConfigTest {

  @Test
  void noBlockMeansNoRetentionAndNothingIsWrittenBack() {
    var host = HostYaml.fromMap(YamlUtil.parseMap("storage_backend: dir\n"));

    assertTrue(host.retention().isEmpty());
    assertFalse(host.toMap().containsKey("retention"));
    assertEquals(RetentionConfig.none(), RetentionConfig.fromMap(null));
  }

  @Test
  void everyAgeParsesAndSurvivesARewriteOfHostYaml() {
    var host =
        HostYaml.fromMap(
            YamlUtil.parseMap(
                """
                storage_backend: dir
                retention:
                  prune_archived_after: 90d
                  messages: 36h
                  runs_after_finished: 45m
                """));

    assertEquals(
        new RetentionConfig(Duration.ofDays(90), Duration.ofHours(36), Duration.ofMinutes(45)),
        host.retention());
    var rewritten =
        host.withServerIp("192.0.2.9").withIncusVersion("6.9").withSync(SyncConfig.unset());
    assertEquals(host.retention(), rewritten.retention(), "no rewrite drops the policy");
    assertEquals(
        Map.of("prune_archived_after", "90d", "messages", "36h", "runs_after_finished", "45m"),
        rewritten.toMap().get("retention"));
    assertEquals(host.retention(), HostYaml.fromMap(rewritten.toMap()).retention());
  }

  @Test
  void aPartialBlockSetsOnlyWhatItNames() {
    var retention = RetentionConfig.fromMap(Map.of("messages", "365d"));

    assertEquals(new RetentionConfig(null, Duration.ofDays(365), null), retention);
    assertFalse(retention.isEmpty());
    assertEquals(Map.of("messages", "365d"), retention.toMap());
  }

  @Test
  void anUnknownSettingAMalformedAgeOrAZeroAgeIsRefusedNamingTheKey() {
    var unknown =
        assertThrows(
            IllegalArgumentException.class, () -> RetentionConfig.fromMap(Map.of("specs", "9d")));
    assertTrue(unknown.getMessage().contains("retention.specs"), unknown.getMessage());
    var malformed =
        assertThrows(
            IllegalArgumentException.class,
            () -> RetentionConfig.fromMap(Map.of("messages", "a year")));
    assertTrue(malformed.getMessage().startsWith("retention.messages:"), malformed.getMessage());
    var zero =
        assertThrows(
            IllegalArgumentException.class,
            () -> RetentionConfig.fromMap(Map.of("runs_after_finished", "0d")));
    assertTrue(zero.getMessage().contains("longer than zero"), zero.getMessage());
  }
}
