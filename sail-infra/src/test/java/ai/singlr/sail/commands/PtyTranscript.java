/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A pty session read the way a client sees it: the attach prologue, then output up to a marker.
 * Every frame is awaited for at most {@link #FRAME_DEADLINE}, so a host that goes quiet fails the
 * test that is waiting on it instead of hanging the lane.
 */
final class PtyTranscript {

  static final Duration FRAME_DEADLINE = Duration.ofSeconds(60);

  private PtyTranscript() {}

  /** The two frames every attach opens with: the geometry, then the start of the ring's replay. */
  static void prologue(SocketChannel channel) {
    assertInstanceOf(PtyMessage.Resized.class, next(channel));
    assertInstanceOf(PtyMessage.ReplayBegin.class, next(channel));
  }

  /** Every byte of output up to {@code marker}; a session that ends before it fails. */
  static String awaitText(SocketChannel channel, String marker) {
    var seen = new StringBuilder();
    while (!seen.toString().contains(marker)) {
      var message = next(channel);
      if (message instanceof PtyMessage.Output(var seq, var bytes)) {
        seen.append(new String(bytes, StandardCharsets.UTF_8));
      }
      if (message instanceof PtyMessage.SessionEnded(var reason)) {
        throw new AssertionError("ended (" + reason + ") before '" + marker + "': " + seen);
      }
    }
    return seen.toString();
  }

  private static PtyMessage next(SocketChannel channel) {
    return assertTimeoutPreemptively(
        FRAME_DEADLINE, () -> PtyWire.read(channel), () -> "no pty frame within " + FRAME_DEADLINE);
  }
}
