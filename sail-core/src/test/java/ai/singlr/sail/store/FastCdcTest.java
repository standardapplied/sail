/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FastCdcTest {
  @Test
  void insertionOnlyChangesTheFirstChunk() {
    var bytes = new byte[12 * FastCdc.MAX];
    new Random(42).nextBytes(bytes);
    var inserted = new byte[bytes.length + 1];
    System.arraycopy(bytes, 0, inserted, 1, bytes.length);
    var original = chunks(bytes);
    var edited = chunks(inserted);
    assertEquals(original.size(), edited.size());
    for (var i = 1; i < original.size(); i++) {
      assertArrayEquals(original.get(i), edited.get(i));
    }
    assertEquals(original.getFirst().length + 1, edited.getFirst().length);
  }

  @Test
  void boundsHoldForRandomAndZeroInput() {
    for (var random : List.of(false, true)) {
      var bytes = new byte[32 * FastCdc.MAX];
      if (random) new Random(7).nextBytes(bytes);
      var chunks = chunks(bytes);
      for (var i = 0; i < chunks.size(); i++) {
        assertTrue(chunks.get(i).length <= FastCdc.MAX);
        if (i < chunks.size() - 1) assertTrue(chunks.get(i).length >= FastCdc.MIN);
      }
      if (random) {
        var average = bytes.length / chunks.size();
        assertTrue(average > FastCdc.TARGET / 2 && average < FastCdc.TARGET * 2);
      }
    }
    assertEquals(0, FastCdc.cut(new byte[0], 0));
    assertEquals(5, FastCdc.cut(new byte[5], 5));
  }

  private static List<byte[]> chunks(byte[] bytes) {
    var chunks = new ArrayList<byte[]>();
    for (var offset = 0; offset < bytes.length; ) {
      var window = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + FastCdc.MAX));
      var size = FastCdc.cut(window, window.length);
      chunks.add(Arrays.copyOf(window, size));
      offset += size;
    }
    return chunks;
  }
}
