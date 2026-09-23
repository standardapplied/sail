/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.config;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Main's opt-in retention policy, the {@code retention} block of {@code host.yaml}: how long an
 * archived spec, a room message and a finished run are kept before main's sweeper prunes them.
 * Every age is optional and none has a default — automatic erasure is a decision the fleet makes,
 * so with no block nothing is erased that a person did not ask for. Only main reads it; nodes apply
 * main's erasures.
 */
public record RetentionConfig(
    Duration pruneArchivedAfter, Duration messages, Duration runsAfterFinished) {

  private static final String ARCHIVED = "prune_archived_after";
  private static final String MESSAGES = "messages";
  private static final String RUNS = "runs_after_finished";
  private static final Set<String> KEYS = Set.of(ARCHIVED, MESSAGES, RUNS);

  public RetentionConfig {
    requirePositive(ARCHIVED, pruneArchivedAfter);
    requirePositive(MESSAGES, messages);
    requirePositive(RUNS, runsAfterFinished);
  }

  public static RetentionConfig none() {
    return new RetentionConfig(null, null, null);
  }

  /** The block of this box's {@code host.yaml}; none when the file or the block is absent. */
  public static RetentionConfig load() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) return none();
    try {
      return HostYaml.fromMap(YamlUtil.parseFile(path)).retention();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the retention policy from " + path, e);
    }
  }

  /** Whether no age is set: the sweeper then erases nothing. */
  public boolean isEmpty() {
    return pruneArchivedAfter == null && messages == null && runsAfterFinished == null;
  }

  public static RetentionConfig fromMap(Map<String, Object> map) {
    if (map == null) {
      return none();
    }
    for (var key : map.keySet()) {
      if (!KEYS.contains(key)) {
        throw new IllegalArgumentException(
            "retention."
                + key
                + " is not a retention setting; use prune_archived_after, messages or"
                + " runs_after_finished");
      }
    }
    return new RetentionConfig(age(map, ARCHIVED), age(map, MESSAGES), age(map, RUNS));
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    if (pruneArchivedAfter != null) map.put(ARCHIVED, format(pruneArchivedAfter));
    if (messages != null) map.put(MESSAGES, format(messages));
    if (runsAfterFinished != null) map.put(RUNS, format(runsAfterFinished));
    return map;
  }

  private static Duration age(Map<String, Object> map, String key) {
    var value = map.get(key);
    if (value == null) {
      return null;
    }
    try {
      return DateTimeUtils.parseAge(value.toString());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("retention." + key + ": " + e.getMessage(), e);
    }
  }

  private static void requirePositive(String key, Duration age) {
    if (age != null && (age.isZero() || age.isNegative())) {
      throw new IllegalArgumentException("retention." + key + " must be longer than zero");
    }
  }

  private static String format(Duration age) {
    if (age.toDays() > 0 && age.equals(Duration.ofDays(age.toDays()))) {
      return age.toDays() + "d";
    }
    if (age.toHours() > 0 && age.equals(Duration.ofHours(age.toHours()))) {
      return age.toHours() + "h";
    }
    return age.toMinutes() + "m";
  }
}
