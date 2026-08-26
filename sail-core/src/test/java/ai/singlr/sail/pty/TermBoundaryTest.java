/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TermBoundaryTest {

  private static TermBoundary fed(byte[] bytes) {
    var boundary = new TermBoundary();
    boundary.feed(bytes, bytes.length);
    return boundary;
  }

  private static TermBoundary fed(String text) {
    return fed(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void plainTextEndingInNewlineIsThePreferredReplayPoint() {
    assertTrue(fed("hello\n").atSafeLineStart());
    assertTrue(fed("hello").atSafeBoundary());
    assertFalse(fed("hello").atSafeLineStart());
  }

  @Test
  void aStreamInsideAnEscapeSequenceIsNeverSafe() {
    assertFalse(fed("x\u001b").atSafeBoundary());
    assertFalse(fed("x\u001b[3").atSafeBoundary());
    assertFalse(fed("x\u001b[38;5;19").atSafeBoundary());
    assertTrue(fed("x\u001b[38;5;196m").atSafeBoundary());
  }

  @Test
  void oscAndDcsStringsCloseOnBelOrStOnly() {
    assertFalse(fed("\u001b]0;title").atSafeBoundary());
    assertTrue(fed("\u001b]0;title\u0007").atSafeBoundary());
    assertTrue(fed("\u001b]0;title\u001b\\").atSafeBoundary());
    assertFalse(fed("\u001bPdata").atSafeBoundary());
    assertTrue(fed("\u001bPdata\u001b\\").atSafeBoundary());
  }

  @Test
  void aSplitUtf8CharacterIsNotABoundary() {
    var snowman = "x\u2603".getBytes(StandardCharsets.UTF_8);
    var partial = new TermBoundary();
    partial.feed(snowman, snowman.length - 1);
    assertFalse(partial.atSafeBoundary());
    partial.feed(new byte[] {snowman[snowman.length - 1]}, 1);
    assertTrue(partial.atSafeBoundary());
  }

  @Test
  void feedingInArbitraryChunksMatchesFeedingWhole() {
    var text = "a\u001b[1;31mred\u001b]0;t\u0007\u2603\nnext".getBytes(StandardCharsets.UTF_8);
    for (var chunk = 1; chunk <= text.length; chunk++) {
      var boundary = new TermBoundary();
      for (var i = 0; i < text.length; i += chunk) {
        var len = Math.min(chunk, text.length - i);
        var slice = new byte[len];
        System.arraycopy(text, i, slice, 0, len);
        boundary.feed(slice, len);
      }
      assertTrue(boundary.atSafeBoundary(), "chunk size " + chunk);
    }
  }
}
