/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.identity.Actor;
import java.util.LinkedHashMap;
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

  /** Reserved metadata key carrying the creator of an entity through the sync protocol. */
  public static final String CREATOR = "_created_by";

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
   * The author a row written from {@code snapshot} records, as the change log records it for the
   * same revision: the snapshot's reserved {@link #ACTOR} key where the bound {@link Actor} honours
   * an offered author, otherwise the actor itself (see {@link Actor#authorOf}).
   */
  public static String actor(Map<String, Object> snapshot) {
    return Actor.current().authorOf(snapshot == null ? null : text(snapshot, ACTOR));
  }

  /**
   * The creator a row born from {@code snapshot} records: the {@link #CREATOR} it offers, or else
   * the FDE the bound {@link Actor} acts as ({@link Actor#actingFde}) — the pusher, when main
   * commits a node's create that names none. A node adopting main's revision records only the
   * creator main names.
   */
  public static String creator(Map<String, Object> snapshot) {
    var offered = text(snapshot, CREATOR);
    var actor = Actor.current();
    return offered != null || actor.lane() == Actor.Lane.MAIN ? offered : actor.actingFde();
  }

  /**
   * The creator {@code snapshot} writes over the one a row already holds, or null to keep the row's
   * own. A creator is written once, at create: main keeps the one it holds whatever a later offer
   * names, and only a node adopting main's revision takes the creator main holds.
   */
  public static String adoptedCreator(Map<String, Object> snapshot) {
    return Actor.current().lane() == Actor.Lane.MAIN ? text(snapshot, CREATOR) : null;
  }

  /**
   * {@code snapshot} naming {@code creator} as its {@link #CREATOR}: an offer main accepted, as
   * main committed it, since main keeps the creator it holds whatever the offer named. The snapshot
   * itself when main names none.
   */
  public static Map<String, Object> withCreator(Map<String, Object> snapshot, String creator) {
    if (snapshot == null || creator == null) {
      return snapshot;
    }
    var committed = new LinkedHashMap<>(snapshot);
    committed.put(CREATOR, creator);
    return committed;
  }
}
