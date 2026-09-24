/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/** A pty session read the way a client sees it: the attach prologue, then output up to a marker. */
final class PtyTranscript {

  private PtyTranscript() {}

  /** The two frames every attach opens with: the geometry, then the start of the ring's replay. */
  static void prologue(SocketChannel channel) throws IOException {
    assertInstanceOf(PtyMessage.Resized.class, PtyWire.read(channel));
    assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
  }

  /** Every byte of output up to {@code marker}; a session that ends before it fails. */
  static String awaitText(SocketChannel channel, String marker) throws IOException {
    var seen = new StringBuilder();
    while (!seen.toString().contains(marker)) {
      var message = PtyWire.read(channel);
      if (message instanceof PtyMessage.Output(var seq, var bytes)) {
        seen.append(new String(bytes, StandardCharsets.UTF_8));
      }
      if (message instanceof PtyMessage.SessionEnded(var reason)) {
        throw new AssertionError("ended (" + reason + ") before '" + marker + "': " + seen);
      }
    }
    return seen.toString();
  }
}
