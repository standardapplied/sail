/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A file's name is bytes: whatever a workspace's filesystem holds, UTF-8 or not, reads back as the
 * same bytes, so a name a listing carried addresses that file and no other.
 */
class FileNamesTest {

  @Test
  void everyByteStringRoundTrips() {
    for (var b = 0; b < 256; b++) {
      var one = new byte[] {'a', (byte) b, 'z'};
      assertArrayEquals(one, FileNames.encode(FileNames.decode(one)), "byte " + b);
    }
    var random = new Random(504);
    for (var i = 0; i < 10_000; i++) {
      var bytes = new byte[random.nextInt(64)];
      random.nextBytes(bytes);
      assertArrayEquals(bytes, FileNames.encode(FileNames.decode(bytes)));
    }
  }

  @Test
  void aValidNameIsItsOwnTextAndTwoInvalidNamesStayApart() {
    var valid = "src/App é ✅.java";
    assertEquals(valid, FileNames.decode(valid.getBytes(StandardCharsets.UTF_8)));
    assertArrayEquals(valid.getBytes(StandardCharsets.UTF_8), FileNames.encode(valid));
    var ff = FileNames.decode(new byte[] {'b', (byte) 0xff});
    var fe = FileNames.decode(new byte[] {'b', (byte) 0xfe});
    assertNotEquals(ff, fe, "two names a lossy decoding would merge stay two");
    assertEquals("b�", FileNames.shown(ff), "what a person reads, replaced");
    assertEquals(valid, FileNames.shown(valid));
  }
}
