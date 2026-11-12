/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.Pty;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command-line skin of the shim, run as the real {@code sail _pty-child} entry point in its own
 * JVM on a real pty: the option parsing, the {@code --} separator, the terminal it claims, and the
 * status that comes back.
 */
class PtyChildCommandTest {

  @TempDir Path dir;

  @Test
  void theCommandRecordsTheStatusAndMirrorsIt() throws Exception {
    var exit = dir.resolve("s.exit");
    try (var pty = Pty.open(80, 24)) {
      var shim =
          pty.spawn(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "--enable-native-access=ALL-UNNAMED",
                  "-cp",
                  System.getProperty("java.class.path"),
                  "ai.singlr.sail.Main",
                  "_pty-child",
                  "--exit",
                  exit.toString(),
                  "--",
                  "sh",
                  "-c",
                  "echo shimmed; exit 7"),
              Map.of("TERM", "dumb"),
              dir);
      var said = new StringBuilder();
      var buf = new byte[4096];
      for (var n = pty.read(buf); n > 0; n = pty.read(buf)) {
        said.append(new String(buf, 0, n, StandardCharsets.UTF_8));
      }

      assertTrue(shim.waitFor(60, TimeUnit.SECONDS));
      assertEquals(7, shim.exitValue());
      assertEquals("7", Files.readString(exit));
      assertEquals("shimmed\r\n", said.toString(), "the command speaks on the claimed terminal");
    }
  }

  @Test
  void theShimCommandIsTheJvmEntryPointOffTheNativeImage() {
    var command = PtyHostCommand.shimCommand(dir.resolve("s.exit"));
    assertTrue(command.contains("ai.singlr.sail.pty.PtyChildShim"), command.toString());
    assertEquals(dir.resolve("s.exit").toString(), command.getLast());
  }
}
