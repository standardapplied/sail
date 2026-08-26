/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PtyTest {

  private static String readUntil(Pty pty, String marker) throws Exception {
    var out = new StringBuilder();
    var buf = new byte[4096];
    var deadline = System.nanoTime() + 10_000_000_000L;
    while (!out.toString().contains(marker)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("marker '" + marker + "' not seen; got: " + out);
      }
      var n = pty.read(buf);
      if (n < 0) {
        throw new AssertionError("pty closed before marker '" + marker + "'; got: " + out);
      }
      out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
    }
    return out.toString();
  }

  @Test
  void childGetsAControllingTtyWithTheRequestedSizeAndConverses() throws Exception {
    try (var pty = Pty.open(80, 24)) {
      var child =
          pty.spawn(
              List.of(
                  "sh",
                  "-c",
                  "[ -t 0 ] && echo is-a-tty; stty size; read line; echo got:$line; exit 7"),
              Map.of("TERM", "xterm-256color"),
              Path.of("/tmp"));

      var head = readUntil(pty, "24 80");
      assertTrue(head.contains("is-a-tty"), "the child must see a controlling terminal: " + head);

      pty.write("world\n".getBytes(StandardCharsets.UTF_8));
      readUntil(pty, "got:world");

      assertEquals(7, child.waitFor(), "the child exit code rides through");
      var buf = new byte[256];
      int n;
      do {
        n = pty.read(buf);
      } while (n > 0);
      assertEquals(-1, n, "a dead child reads as end of stream, never an exception");
    }
  }

  @Test
  void resizeReachesTheForegroundProcess() throws Exception {
    try (var pty = Pty.open(80, 24)) {
      pty.spawn(
          List.of("sh", "-c", "trap 'stty size' WINCH; echo ready; while read l; do :; done"),
          Map.of("TERM", "xterm-256color"),
          Path.of("/tmp"));
      readUntil(pty, "ready");

      pty.resize(132, 43);
      pty.write("poke\n".getBytes(StandardCharsets.UTF_8));

      readUntil(pty, "43 132");
    }
  }

  @Test
  void writeAfterCloseFailsLoudly() throws Exception {
    var pty = Pty.open(80, 24);
    pty.close();

    org.junit.jupiter.api.Assertions.assertThrows(
        java.io.IOException.class, () -> pty.write("x".getBytes(StandardCharsets.UTF_8)));
  }
}
