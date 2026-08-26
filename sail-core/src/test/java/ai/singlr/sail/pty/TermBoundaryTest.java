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

  private static final byte ESC = 0x1b;
  private static final byte BEL = 0x07;

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
    assertFalse(fed(new byte[] {'x', ESC}).atSafeBoundary());
    assertFalse(fed(new byte[] {'x', ESC, '[', '3'}).atSafeBoundary());
    assertFalse(
        fed(new byte[] {'x', ESC, '[', '3', '8', ';', '5', ';', '1', '9'}).atSafeBoundary());
    assertTrue(
        fed(new byte[] {'x', ESC, '[', '3', '8', ';', '5', ';', '1', '9', '6', 'm'})
            .atSafeBoundary());
  }

  @Test
  void oscClosesOnBelOrSt() {
    assertFalse(fed(new byte[] {ESC, ']', '0', ';', 't'}).atSafeBoundary());
    assertTrue(fed(new byte[] {ESC, ']', '0', ';', 't', BEL}).atSafeBoundary());
    assertTrue(fed(new byte[] {ESC, ']', '0', ';', 't', ESC, '\\'}).atSafeBoundary());
  }

  @Test
  void dcsIgnoresBelAndClosesOnStOnly() {
    assertFalse(
        fed(new byte[] {ESC, 'P', 'd', BEL, 'a'}).atSafeBoundary(),
        "BEL is data inside a DCS, not a terminator");
    assertTrue(fed(new byte[] {ESC, 'P', 'd', 'a', ESC, '\\'}).atSafeBoundary());
    for (var opener : new byte[] {'X', '^', '_'}) {
      assertFalse(
          fed(new byte[] {ESC, opener, 'd', BEL, 'a'}).atSafeBoundary(),
          "BEL must not terminate the string opener " + (char) opener);
    }
  }

  @Test
  void aBelThenNewlineInsideADcsIsNeverASafeLineStart() {
    assertFalse(
        fed(new byte[] {ESC, 'P', 'q', BEL, '\n'}).atSafeLineStart(),
        "a newline in DCS payload after an embedded BEL is not a safe replay point");
    assertTrue(fed(new byte[] {ESC, 'P', 'q', ESC, '\\', '\n'}).atSafeLineStart());
  }

  @Test
  void aSplitUtf8CharacterIsNotABoundary() {
    var snowman = "x☃".getBytes(StandardCharsets.UTF_8);
    var partial = new TermBoundary();
    partial.feed(snowman, snowman.length - 1);
    assertFalse(partial.atSafeBoundary());
    partial.feed(new byte[] {snowman[snowman.length - 1]}, 1);
    assertTrue(partial.atSafeBoundary());
  }

  @Test
  void feedingInArbitraryChunksMatchesFeedingWhole() {
    var text = "a[1;31mred]0;t☃\nnext".getBytes(StandardCharsets.UTF_8);
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
