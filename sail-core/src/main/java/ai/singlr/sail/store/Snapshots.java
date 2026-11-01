/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
import java.time.Instant;
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

  /** Reserved metadata key carrying when an entity was created, on its creator's clock. */
  public static final String CREATED_AT = "_created_at";

  /** Reserved metadata key carrying when a revision was made, on its author's clock. */
  public static final String UPDATED_AT = "_updated_at";

  /**
   * Reserved metadata key carrying when a spec entered {@code archived} or {@code cancelled}, on
   * the clock of the revision that made the transition: a later revision of the same status is not
   * that revision, so its own time cannot stand in. Absent in any other status.
   */
  public static final String STATUS_SINCE = "_status_since";

  private Snapshots() {}

  /**
   * Whether {@code snapshot}, a version a replica reports, stands for a marked deletion: it carries
   * nothing but reserved keys, the marks a tombstone crossed with and the author it names, where a
   * live version always carries the entity's own fields. Null is a plain deletion, not a marked
   * one.
   */
  public static boolean isDeletionMark(Map<String, Object> snapshot) {
    return snapshot != null && snapshot.keySet().stream().allMatch(ConflictDetector::isMetadata);
  }

  /**
   * Whether {@code snapshot}, a version a replica reports, stands for a deletion: null always does,
   * and so does a marked one ({@link #isDeletionMark}), which the journal adopts as a tombstone
   * recording its marks.
   */
  public static boolean isDeletion(Map<String, Object> snapshot) {
    return snapshot == null || isDeletionMark(snapshot);
  }

  /** The string form of a present value, or null when the key is absent. */
  public static String text(Map<String, Object> map, String key) {
    var value = map == null ? null : map.get(key);
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

  /**
   * The instant a snapshot carries under {@code key}, as the integer millisecond every snapshot
   * carries an instant as; null when absent. Anything else is refused, naming the key.
   */
  public static Instant instant(Map<String, Object> map, String key) {
    var value = map == null ? null : map.get(key);
    return switch (value) {
      case null -> null;
      case Number millis -> DateTimeUtils.ofMillis(millis.longValue());
      default ->
          throw new IllegalArgumentException(
              key + " must be an integer millisecond, not " + value.getClass().getSimpleName());
    };
  }

  /** {@code instant} as a snapshot carries it: its integer millisecond, or null. */
  public static Long millis(Instant instant) {
    return instant == null ? null : DateTimeUtils.toMillis(instant);
  }

  /** Each element of a list value in string form, or an empty list when absent or not a list. */
  public static List<String> stringList(Map<String, Object> map, String key) {
    return map.get(key) instanceof List<?> list
        ? list.stream().map(String::valueOf).toList()
        : List.of();
  }

  /**
   * {@code comparable} carrying when the entity {@code full} holds was created and last revised, as
   * {@link #CREATED_AT} and {@link #UPDATED_AT}: metadata, so two boxes never conflict on a clock,
   * yet every box applying the revision writes the author's times verbatim.
   */
  public static Map<String, Object> timed(
      Map<String, Object> comparable, Map<String, Object> full) {
    var created = full.get("created_at");
    if (created != null) {
      comparable.put(CREATED_AT, created);
    }
    var updated = full.get("updated_at");
    if (updated != null) {
      comparable.put(UPDATED_AT, updated);
    }
    return comparable;
  }

  /**
   * The time {@code snapshot} names under {@code key}, or {@code now} when it names none, as a
   * restore's state does.
   */
  public static Instant timeOr(Map<String, Object> snapshot, String key, Instant now) {
    var time = instant(snapshot, key);
    return time == null ? now : time;
  }

  /**
   * {@code state} as this box's own new revision of it, made now: a rebase, a resolve, a restore or
   * a merge offered to main carries this box's clock, not the time of the revision it was built
   * from, and every box applying it writes that one time. Null stays null.
   */
  public static Map<String, Object> revisedHere(Map<String, Object> state) {
    if (state == null) {
      return null;
    }
    var revised = new LinkedHashMap<>(state);
    revised.put(UPDATED_AT, millis(DateTimeUtils.now()));
    return revised;
  }

  /**
   * {@code snapshot} naming no creation time, so the row it is applied over keeps its own: main
   * holds a row's creation time once, whatever a later offer of the row carries, and its answer
   * ({@link #withCreation}) carries it back.
   */
  public static Map<String, Object> keepingCreation(Map<String, Object> snapshot) {
    if (!snapshot.containsKey(CREATED_AT)) {
      return snapshot;
    }
    var kept = new LinkedHashMap<>(snapshot);
    kept.remove(CREATED_AT);
    return kept;
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
   * The creator a row that holds {@code held} records when written from {@code snapshot}. A creator
   * is written once: main keeps the one it holds whatever a later offer names, and a node adopting
   * main's revision takes the creator main holds, none included; a snapshot without the {@link
   * #CREATOR} key keeps {@code held}. A creator main never recorded is taken from a push by that
   * creator, naming itself — a push never names anyone else as a creator.
   */
  public static String adoptedCreator(Map<String, Object> snapshot, String held) {
    var actor = Actor.current();
    if (actor.lane() == Actor.Lane.MAIN) {
      return snapshot.containsKey(CREATOR) ? text(snapshot, CREATOR) : held;
    }
    return actor.lane() == Actor.Lane.SYNC
            && Strings.isBlank(held)
            && Ownership.owns(actor.handle(), text(snapshot, CREATOR))
        ? actor.handle()
        : held;
  }

  /**
   * The creator main holds for an entity; {@code handle} is null when it holds none. A null {@code
   * Creator} is a main that did not say.
   */
  public record Creator(String handle) {}

  /**
   * {@code snapshot} naming {@code creator} as its {@link #CREATOR} and {@code createdAt} as its
   * {@link #CREATED_AT}: an offer main accepted, as main committed it, since main keeps the creator
   * and the creation time it holds whatever the offer named. Either main did not say is left as the
   * offer named it.
   */
  public static Map<String, Object> withCreation(
      Map<String, Object> snapshot, Creator creator, Instant createdAt) {
    if (snapshot == null || (creator == null && createdAt == null)) {
      return snapshot;
    }
    var committed = new LinkedHashMap<>(snapshot);
    if (creator != null) {
      committed.put(CREATOR, creator.handle());
    }
    if (createdAt != null) {
      committed.put(CREATED_AT, millis(createdAt));
    }
    return committed;
  }
}
