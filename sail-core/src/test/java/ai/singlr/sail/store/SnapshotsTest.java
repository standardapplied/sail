/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The snapshot-coercion helpers every synced store reads its rows back through: JSON snapshots
 * decode to {@code Map<String, Object>} with loose types (numbers as {@code Long}/{@code Integer},
 * lists as {@code List<?>}), and these turn one key into the typed value a row field wants — null
 * for an absent or wrong-typed value, never a class-cast.
 */
class SnapshotsTest {

  @Test
  void textReturnsTheStringFormOfAnyPresentValue() {
    assertEquals("hello", Snapshots.text(Map.of("k", "hello"), "k"));
    assertEquals("42", Snapshots.text(Map.of("k", 42), "k"));
    assertEquals("true", Snapshots.text(Map.of("k", true), "k"));
  }

  @Test
  void textIsNullForAnAbsentKey() {
    assertNull(Snapshots.text(Map.of(), "missing"));
  }

  @Test
  void integerCoercesAnyNumberToItsIntValue() {
    assertEquals(7, Snapshots.integer(Map.of("k", 7L), "k"));
    assertEquals(3, Snapshots.integer(Map.of("k", 3.9), "k"));
  }

  @Test
  void integerIsNullForAbsentOrNonNumeric() {
    assertNull(Snapshots.integer(Map.of(), "k"));
    assertNull(Snapshots.integer(Map.of("k", "notanumber"), "k"));
  }

  @Test
  void longValueCoercesAnyNumberToItsLongValue() {
    assertEquals(9_000_000_000L, Snapshots.longValue(Map.of("k", 9_000_000_000L), "k"));
    assertEquals(4L, Snapshots.longValue(Map.of("k", 4), "k"));
  }

  @Test
  void longValueIsNullForAbsentOrNonNumeric() {
    assertNull(Snapshots.longValue(Map.of(), "k"));
    assertNull(Snapshots.longValue(Map.of("k", "x"), "k"));
  }

  @Test
  void stringListMapsEveryElementToItsStringForm() {
    assertEquals(List.of("a", "b"), Snapshots.stringList(Map.of("k", List.of("a", "b")), "k"));
    assertEquals(List.of("1", "2"), Snapshots.stringList(Map.of("k", List.of(1, 2)), "k"));
  }

  @Test
  void stringListIsEmptyForAbsentOrNonList() {
    assertTrue(Snapshots.stringList(Map.of(), "k").isEmpty());
    assertTrue(Snapshots.stringList(Map.of("k", "notalist"), "k").isEmpty());
  }

  @Test
  void actorReadsTheReservedKeyAndDefaultsToSync() {
    assertEquals("uday", Snapshots.actor(Map.of("_actor", "uday")));
    assertEquals("sync", Snapshots.actor(Map.of("title", "no author here")));
    assertEquals("sync", Snapshots.actor(null));
  }
}
