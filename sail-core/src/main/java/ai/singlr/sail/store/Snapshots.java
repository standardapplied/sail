/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.List;
import java.util.Map;

/**
 * Coerces one key of a decoded JSON snapshot into the typed value a synced row's field wants.
 * Snapshots round-trip through {@code YamlUtil} as {@code Map<String, Object>} with loose types
 * (numbers as {@code Number}, lists as {@code List<?>}), so every store reads its rows back through
 * the same handful of null-tolerant coercions. Shared here so the revision journal and each store's
 * {@code fromSnapshot} decode identically rather than each hand-rolling the idiom.
 */
public final class Snapshots {

  /** Reserved metadata key carrying the author of a revision through the sync protocol. */
  public static final String ACTOR = "_actor";

  private Snapshots() {}

  /** The string form of a present value, or null when the key is absent. */
  public static String text(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  /** A numeric value narrowed to {@code int}, or null when absent or not a number. */
  public static Integer integer(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.intValue() : null;
  }

  /** A numeric value widened to {@code long}, or null when absent or not a number. */
  public static Long longValue(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.longValue() : null;
  }

  /** Each element of a list value in string form, or an empty list when absent or not a list. */
  public static List<String> stringList(Map<String, Object> map, String key) {
    return map.get(key) instanceof List<?> list
        ? list.stream().map(String::valueOf).toList()
        : List.of();
  }

  /**
   * The author a synced snapshot attributes its revision to — the reserved {@link #ACTOR} key — or
   * {@code sync} when absent (or the snapshot itself is null), so a row that arrived with no
   * attribution is still recorded as a sync write rather than crashing.
   */
  public static String actor(Map<String, Object> snapshot) {
    var actor = snapshot == null ? null : snapshot.get(ACTOR);
    return actor == null ? "sync" : actor.toString();
  }
}
