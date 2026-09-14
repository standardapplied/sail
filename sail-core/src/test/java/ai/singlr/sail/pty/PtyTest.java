/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
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
  void anAdoptedMasterIsTheSameTerminalResizeReachesTheChildAndCloseHangsItUp() throws Exception {
    var original = Pty.open(80, 24);
    var child =
        original.spawn(
            List.of("sh", "-c", "trap 'stty size' WINCH; echo ready; while read l; do :; done"),
            Map.of("TERM", "xterm-256color"),
            Path.of("/tmp"));
    readUntil(original, "ready");
    original.release();
    assertEquals(-1, original.read(new byte[16]), "a released master reads as end of stream");
    assertTrue(child.isAlive(), "releasing the master does not hang the child up");

    try (var adopted = Pty.adopt(original.fd())) {
      assertEquals(original.slavePath(), adopted.slavePath(), "ptsname resolves on the adoptee");
      adopted.resize(132, 43);
      adopted.write("poke\n".getBytes(StandardCharsets.UTF_8));
      readUntil(adopted, "43 132");
    }
    assertTrue(
        child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "close hangs the child up");
  }

  @Test
  void adoptingSomethingThatIsNotAPtyMasterNamesTheDescriptor() throws Exception {
    var file = Files.createTempFile("not-a-pty", ".txt");
    try (var channel = java.nio.channels.FileChannel.open(file)) {
      var fd = fdOf(file);
      var refused =
          org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Pty.adopt(fd));
      assertTrue(refused.getMessage().contains("File descriptor " + fd), refused.getMessage());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /** The descriptor behind {@code file}'s channel, found through {@code /proc/self/fd}. */
  private static int fdOf(Path file) throws IOException {
    var real = file.toRealPath();
    try (var fds = Files.list(Path.of("/proc/self/fd"))) {
      for (var fd : fds.toList()) {
        try {
          if (Files.readSymbolicLink(fd).equals(real)) {
            return Integer.parseInt(fd.getFileName().toString());
          }
        } catch (IOException gone) {
          var unused = gone;
        }
      }
    }
    throw new AssertionError("no open descriptor for " + file);
  }

  @Test
  void writeAfterCloseFailsLoudly() throws Exception {
    var pty = Pty.open(80, 24);
    pty.close();

    org.junit.jupiter.api.Assertions.assertThrows(
        java.io.IOException.class, () -> pty.write("x".getBytes(StandardCharsets.UTF_8)));
  }
}
