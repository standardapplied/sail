/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RingJournalTest {

  @TempDir Path dir;

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aRetainedSafeStartReplaysHistoryNotNothing() throws Exception {
    try (var ring = RingJournal.open(dir.resolve("s.ring"), 1024)) {
      ring.append(bytes("first\n"), 6);
      ring.markSafe(0);
      ring.append(bytes("second"), 6);
      ring.markSafe(0);

      var tail = ring.tail(1024);

      assertTrue(tail.safe());
      assertEquals(0, tail.startOffset(), "the oldest in-budget safe start wins — history replays");
      assertArrayEquals(bytes("first\nsecond"), tail.bytes());
    }
  }

  @Test
  void theSafeStartAdvancesOnlyOnceItFallsOutOfTheBudget() throws Exception {
    try (var ring = RingJournal.open(dir.resolve("s.ring"), 1024)) {
      ring.append(bytes("old\n"), 4);
      ring.markSafe(0);
      ring.append(bytes("new\n"), 4);
      ring.markSafe(ring.totalWritten() - 4);

      var tail = ring.tail(4);

      assertTrue(tail.safe());
      assertEquals(8, tail.startOffset(), "the stale mark jumped to the current boundary");
      assertArrayEquals(bytes(""), tail.bytes());
    }
  }

  @Test
  void historySurvivesReopen() throws Exception {
    var path = dir.resolve("s.ring");
    try (var ring = RingJournal.open(path, 64)) {
      ring.append(bytes("persisted\n"), 10);
      ring.markSafe(0);
    }
    try (var ring = RingJournal.open(path, 64)) {
      assertEquals(10, ring.totalWritten());
      ring.append(bytes("more"), 4);
      var tail = ring.tail(64);
      assertTrue(tail.safe());
      assertEquals(0, tail.startOffset(), "the persisted safe start survives reopen");
      assertArrayEquals(bytes("persisted\nmore"), tail.bytes());
    }
  }

  @Test
  void wrappingKeepsTheNewestBytesInOrder() throws Exception {
    try (var ring = RingJournal.open(dir.resolve("s.ring"), 16)) {
      ring.append(bytes("0123456789"), 10);
      ring.append(bytes("abcdefghij"), 10);

      var tail = ring.tail(16);

      assertFalse(tail.safe(), "the watermark fell out of the window");
      assertEquals(4, tail.startOffset());
      assertArrayEquals(bytes("456789abcdefghij"), tail.bytes());
    }
  }

  @Test
  void aForeignFileOrMismatchedCapacityRefusesLoudly() throws Exception {
    var path = dir.resolve("s.ring");
    Files.writeString(path, "not a ring journal at all, definitely");
    assertThrows(IOException.class, () -> RingJournal.open(path, 64));

    var good = dir.resolve("good.ring");
    try (var ring = RingJournal.open(good, 64)) {
      ring.append(bytes("x"), 1);
    }
    assertThrows(IOException.class, () -> RingJournal.open(good, 128));
  }

  @Test
  void tailIsBoundedByMaxBytesWhenNoWatermarkApplies() throws Exception {
    try (var ring = RingJournal.open(dir.resolve("s.ring"), 1024)) {
      ring.append(bytes("aaaaabbbbbccccc"), 15);

      var tail = ring.tail(5);

      assertFalse(tail.safe());
      assertEquals(10, tail.startOffset());
      assertArrayEquals(bytes("ccccc"), tail.bytes());
    }
  }
}
